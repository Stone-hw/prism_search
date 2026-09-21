package com.prismsearch.service.impl;

import com.prismsearch.cache.SearchCacheService;
import com.prismsearch.common.BizException;
import com.prismsearch.common.ErrorCode;
import com.prismsearch.config.PrismsearchProperties;
import com.prismsearch.model.NormalizedResult;
import com.prismsearch.model.RawSearchResult;
import com.prismsearch.model.SearchRequest;
import com.prismsearch.model.SearchResponse;
import com.prismsearch.provider.SearchProvider;
import com.prismsearch.rank.Deduplicator;
import com.prismsearch.rank.RRFRanker;
import com.prismsearch.rank.ResultProcessor;
import com.prismsearch.rank.SimHasher;
import com.prismsearch.rank.TextCleaner;
import com.prismsearch.rank.Tokenizer;
import com.prismsearch.rank.UrlNormalizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchOrchestratorImplTest {

    private ExecutorService executor;
    private MeterRegistry meters;
    private PrismsearchProperties props;
    private SearchCacheService cacheService;
    private ResultProcessor processor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        meters = new SimpleMeterRegistry();
        props = new PrismsearchProperties();

        // Cache service with mocked Redis that always misses.
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        cacheService = new SearchCacheService(redis, props, meters, executor,
                new com.fasterxml.jackson.databind.ObjectMapper());

        Tokenizer tokenizer = new Tokenizer();
        SimHasher simHasher = new SimHasher(tokenizer, redis, props);
        UrlNormalizer urlNormalizer = new UrlNormalizer();
        TextCleaner textCleaner = new TextCleaner();
        Deduplicator deduplicator = new Deduplicator(simHasher, props);
        RRFRanker ranker = new RRFRanker(props);
        processor = new ResultProcessor(urlNormalizer, textCleaner, deduplicator, ranker);
    }

    @Test
    void mergesResultsFromMultipleProvidersAndRanksByRrf() {
        SearchProvider p1 = fakeProvider("google", 1.0, List.of(
                raw("google", 1, "A", "https://example.com/a"),
                raw("google", 2, "B", "https://example.com/b")
        ));
        SearchProvider p2 = fakeProvider("bing", 0.9, List.of(
                raw("bing", 1, "A", "https://example.com/a"),
                raw("bing", 2, "C", "https://example.com/c")
        ));

        SearchOrchestratorImpl orch = new SearchOrchestratorImpl(
                List.of(p1, p2), processor, cacheService, executor, meters);

        SearchRequest req = new SearchRequest();
        req.setQ("test");
        req.setPage(1);
        req.setSize(10);

        SearchResponse resp = orch.search(req);
        assertNotNull(resp);
        assertEquals("test", resp.getQuery());
        assertFalse(resp.isCached());
        assertEquals(3, resp.getTotal(), "should have 3 unique URLs after dedup");

        // URL "a" hits both providers -> ranked first.
        NormalizedResult first = resp.getResults().get(0);
        assertEquals("https://example.com/a", first.getUrl());
        assertEquals(2, first.getSources().size());
        assertTrue(first.getSources().containsAll(List.of("google", "bing")));
        assertTrue(first.getScore() > resp.getResults().get(1).getScore());

        // Provider status should reflect both engines.
        assertEquals("ok", resp.getProviders().get("google").getStatus());
        assertEquals("ok", resp.getProviders().get("bing").getStatus());
        assertEquals(2, resp.getProviders().get("google").getCount());
    }

    @Test
    void singleProviderFailureIsDegraded() {
        SearchProvider ok = fakeProvider("google", 1.0, List.of(
                raw("google", 1, "A", "https://example.com/a")
        ));
        SearchProvider failing = new SearchProvider() {
            @Override public String name() { return "bing"; }
            @Override public boolean enabled() { return true; }
            @Override public double weight() { return 0.9; }
            @Override public long timeoutMs() { return 500; }
            @Override public List<RawSearchResult> search(SearchRequest request) {
                throw new RuntimeException("upstream 500");
            }
        };

        SearchOrchestratorImpl orch = new SearchOrchestratorImpl(
                List.of(ok, failing), processor, cacheService, executor, meters);

        SearchRequest req = new SearchRequest();
        req.setQ("test");
        SearchResponse resp = orch.search(req);

        assertEquals(1, resp.getTotal());
        assertEquals("ok", resp.getProviders().get("google").getStatus());
        assertEquals("error", resp.getProviders().get("bing").getStatus());
    }

    @Test
    void allProvidersFailedThrowsBizException() {
        SearchProvider failing1 = failingProvider("google");
        SearchProvider failing2 = failingProvider("bing");

        SearchOrchestratorImpl orch = new SearchOrchestratorImpl(
                List.of(failing1, failing2), processor, cacheService, executor, meters);

        SearchRequest req = new SearchRequest();
        req.setQ("test");

        BizException ex = assertThrows(BizException.class, () -> orch.search(req));
        assertEquals(ErrorCode.ALL_PROVIDERS_FAILED, ex.getErrorCode());
    }

    @Test
    void providerFilterRestrictsFanOut() {
        SearchProvider google = fakeProvider("google", 1.0, List.of(
                raw("google", 1, "A", "https://example.com/a")
        ));
        SearchProvider bing = fakeProvider("bing", 0.9, List.of(
                raw("bing", 1, "B", "https://example.com/b")
        ));

        SearchOrchestratorImpl orch = new SearchOrchestratorImpl(
                List.of(google, bing), processor, cacheService, executor, meters);

        SearchRequest req = new SearchRequest();
        req.setQ("test");
        req.setProviders("google");
        SearchResponse resp = orch.search(req);

        assertEquals(1, resp.getTotal());
        assertEquals("https://example.com/a", resp.getResults().get(0).getUrl());
        assertEquals("ok", resp.getProviders().get("google").getStatus());
        assertEquals("skipped", resp.getProviders().get("bing").getStatus());
    }

    @Test
    void urlNormalizationMergesAcrossProviders() {
        // Same content but different tracking params -> should merge.
        SearchProvider p1 = fakeProvider("google", 1.0, List.of(
                raw("google", 1, "A", "https://example.com/a?utm_source=news")
        ));
        SearchProvider p2 = fakeProvider("bing", 0.9, List.of(
                raw("bing", 1, "A", "https://example.com/a?fbclid=xyz")
        ));

        SearchOrchestratorImpl orch = new SearchOrchestratorImpl(
                List.of(p1, p2), processor, cacheService, executor, meters);

        SearchRequest req = new SearchRequest();
        req.setQ("test");
        SearchResponse resp = orch.search(req);

        assertEquals(1, resp.getTotal(), "tracking-param variants should merge to one URL");
        NormalizedResult r = resp.getResults().get(0);
        assertEquals("https://example.com/a", r.getUrl());
        assertEquals(2, r.getSources().size());
    }

    @Test
    void paginationSlicesResults() {
        List<RawSearchResult> raws = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            raws.add(raw("google", i, "T" + i, "https://example.com/p" + i));
        }
        SearchProvider google = fakeProvider("google", 1.0, raws);

        SearchOrchestratorImpl orch = new SearchOrchestratorImpl(
                List.of(google), processor, cacheService, executor, meters);

        SearchRequest req = new SearchRequest();
        req.setQ("test");
        req.setPage(2);
        req.setSize(10);
        SearchResponse resp = orch.search(req);

        assertEquals(25, resp.getTotal());
        assertEquals(10, resp.getResults().size());
        // Page 2 should NOT contain the very first URL (rank 1 lands on page 1).
        boolean containsFirstPageUrl = resp.getResults().stream()
                .anyMatch(r -> r.getUrl().equals("https://example.com/p1"));
        assertFalse(containsFirstPageUrl);
    }

    private static RawSearchResult raw(String provider, int rank, String title, String url) {
        RawSearchResult r = RawSearchResult.of(provider, rank, title, url, "snippet for " + title);
        return r;
    }

    private static SearchProvider fakeProvider(String name, double weight, List<RawSearchResult> results) {
        return new SearchProvider() {
            @Override public String name() { return name; }
            @Override public boolean enabled() { return true; }
            @Override public double weight() { return weight; }
            @Override public long timeoutMs() { return 2000; }
            @Override public List<RawSearchResult> search(SearchRequest request) { return results; }
        };
    }

    private static SearchProvider failingProvider(String name) {
        return new SearchProvider() {
            @Override public String name() { return name; }
            @Override public boolean enabled() { return true; }
            @Override public double weight() { return 1.0; }
            @Override public long timeoutMs() { return 500; }
            @Override public List<RawSearchResult> search(SearchRequest request) {
                throw new RuntimeException("simulated outage");
            }
        };
    }
}

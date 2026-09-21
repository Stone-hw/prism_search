package com.mysearch.rank;

import com.mysearch.config.MysearchProperties;
import com.mysearch.model.NormalizedResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeduplicatorTest {

    private Deduplicator deduplicator;
    private SimHasher simHasher;

    @BeforeEach
    void setUp() {
        Tokenizer tokenizer = new Tokenizer();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        MysearchProperties props = new MysearchProperties();
        simHasher = new SimHasher(tokenizer, redis, props);
        deduplicator = new Deduplicator(simHasher, props);
    }

    @Test
    void mergesSameUrlAndAccumulatesSources() {
        List<NormalizedResult> in = new ArrayList<>();
        in.add(result("https://a", "google", 2, "Title A", "Snippet A"));
        in.add(result("https://a", "bing", 1, "Title A (bing)", "Snippet A longer from bing"));

        List<NormalizedResult> out = deduplicator.dedupe(in);

        assertEquals(1, out.size());
        NormalizedResult merged = out.get(0);
        assertEquals(2, merged.getSources().size());
        assertTrue(merged.getSources().containsAll(List.of("google", "bing")));
        assertEquals(1, merged.getBestRank(), "should keep smallest rank");
        // Longer snippet wins.
        assertEquals("Snippet A longer from bing", merged.getSnippet());
    }

    @Test
    void mergesNearDuplicatesViaSimHash() {
        List<NormalizedResult> in = new ArrayList<>();
        // Nearly identical content but different URLs.
        in.add(result("https://a", "google", 1,
                "Spring Boot makes it easy to create stand-alone production-grade applications.",
                "Spring Boot makes it easy to create stand-alone production-grade applications."));
        in.add(result("https://b", "bing", 3,
                "Spring Boot makes it easy to create stand-alone production-grade applications.",
                "Spring Boot makes it easy to create stand-alone production-grade applications."));

        List<NormalizedResult> out = deduplicator.dedupe(in);

        assertEquals(1, out.size(), "SimHash should merge identical texts across URLs");
        assertEquals("https://a", out.get(0).getUrl(), "keeps the smaller-rank record");
        assertEquals(2, out.get(0).getSources().size());
    }

    @Test
    void keepsUnrelatedResults() {
        List<NormalizedResult> in = new ArrayList<>();
        in.add(result("https://a", "google", 1, "Spring Boot guide", "Learn Spring Boot concurrency."));
        in.add(result("https://b", "bing", 2, "Kubernetes handbook", "Deploy containers at scale."));

        List<NormalizedResult> out = deduplicator.dedupe(in);

        assertEquals(2, out.size());
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertTrue(deduplicator.dedupe(List.of()).isEmpty());
        assertTrue(deduplicator.dedupe(null).isEmpty());
    }

    private static NormalizedResult result(String url, String source, int rank, String title, String snippet) {
        NormalizedResult r = new NormalizedResult();
        r.setUrl(url);
        r.addSource(source);
        r.setSource(source);
        r.setBestRank(rank);
        r.setTitle(title);
        r.setSnippet(snippet);
        return r;
    }
}

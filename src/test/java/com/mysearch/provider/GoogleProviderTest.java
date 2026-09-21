package com.mysearch.provider;

import com.mysearch.model.RawSearchResult;
import com.mysearch.model.SearchRequest;
import com.mysearch.provider.config.GoogleProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleProviderTest {

    private MockWebServer server;
    private GoogleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        GoogleProperties props = new GoogleProperties();
        props.setEnabled(true);
        props.setApiKey("test-key");
        props.setCx("test-cx");
        props.setEndpoint(server.url("/customsearch/v1").toString());
        props.setTimeoutMs(2000);
        props.setPageSize(10);

        MeterRegistry meters = new SimpleMeterRegistry();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(redis);

        WebClient webClient = WebClient.builder().build();
        provider = new GoogleProvider(webClient, meters, breaker, props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesItemsArray() throws Exception {
        String body = """
                {
                  "kind": "customsearch#search",
                  "items": [
                    {"title": "A", "link": "https://example.com/a", "snippet": "Snippet A"},
                    {"title": "B", "link": "https://example.com/b", "snippet": "Snippet B",
                     "pagemap": {"metatags": [{"article:published_time": "2025-06-01T10:00:00Z"}]}}
                  ]
                }
                """;
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));

        SearchRequest req = new SearchRequest();
        req.setQ("spring boot");
        req.setPage(1);

        List<RawSearchResult> results = provider.search(req);
        assertEquals(2, results.size());
        assertEquals("A", results.get(0).getTitle());
        assertEquals("https://example.com/a", results.get(0).getUrl());
        assertEquals("Snippet A", results.get(0).getSnippet());
        assertEquals(1, results.get(0).getRank());
        assertNotNull(results.get(1).getPublishedAt(), "publishedAt should be parsed from metatags");

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String path = recorded.getPath();
        assertTrue(path.contains("key=test-key"), "path should carry api key: " + path);
        assertTrue(path.contains("cx=test-cx"), "path should carry cx: " + path);
        assertTrue(path.contains("num=10"), "path should carry num: " + path);
        assertTrue(path.contains("start=1"), "path should carry start: " + path);
    }

    @Test
    void missingItemsYieldsEmpty() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
        SearchRequest req = new SearchRequest();
        req.setQ("nothing");
        List<RawSearchResult> results = provider.search(req);
        assertTrue(results.isEmpty());
    }

    @Test
    void paginationAdvancesStart() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"items\": []}"));
        SearchRequest req = new SearchRequest();
        req.setQ("q");
        req.setPage(3);
        provider.search(req);

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recorded);
        // page 3, pageSize 10 -> start = 21
        assertTrue(recorded.getPath().contains("start=21"),
                "start should advance with page: " + recorded.getPath());
    }

    @Test
    void providerDisabledWithoutKey() {
        GoogleProperties props = new GoogleProperties();
        props.setEnabled(true);
        // no api key
        assertTrue(!props.isEnabled(), "should auto-disable without key material");
    }
}

package com.mysearch.provider;

import com.mysearch.model.RawSearchResult;
import com.mysearch.model.SearchRequest;
import com.mysearch.provider.config.SearxngProperties;
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

class SearxngProviderTest {

    private MockWebServer server;
    private SearxngProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        SearxngProperties props = new SearxngProperties();
        props.setEnabled(true);
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
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
        provider = new SearxngProvider(webClient, meters, breaker, props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesJsonResults() throws Exception {
        String body = """
                {
                  "query": "spring boot",
                  "results": [
                    {"title": "Spring Boot Guide", "url": "https://example.com/a", "content": "Learn Spring Boot."},
                    {"title": "Spring Boot Ref",   "url": "https://example.com/b", "content": "Reference docs.", "publishedDate": "2025-01-02T03:04:05Z"}
                  ]
                }
                """;
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));

        SearchRequest req = new SearchRequest();
        req.setQ("spring boot");
        req.setPage(1);

        List<RawSearchResult> results = provider.search(req);

        assertEquals(2, results.size());
        assertEquals("Spring Boot Guide", results.get(0).getTitle());
        assertEquals("https://example.com/a", results.get(0).getUrl());
        assertEquals("Learn Spring Boot.", results.get(0).getSnippet());
        assertEquals(1, results.get(0).getRank());
        assertEquals(2, results.get(1).getRank());
        assertNotNull(results.get(1).getPublishedAt());

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recorded);
        String path = recorded.getPath();
        assertTrue(path.contains("q=spring"), "path should carry the query: " + path);
        assertTrue(path.contains("format=json"), "path should request json: " + path);
    }

    @Test
    void emptyResultsArrayYieldsEmptyList() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"results\": []}"));
        SearchRequest req = new SearchRequest();
        req.setQ("nothing");
        List<RawSearchResult> results = provider.search(req);
        assertTrue(results.isEmpty());
    }

    @Test
    void missingTitleSkipsEntry() {
        String body = """
                {"results": [
                  {"url": "https://example.com/no-title"},
                  {"title": "OK", "url": "https://example.com/ok"}
                ]}
                """;
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
        SearchRequest req = new SearchRequest();
        req.setQ("q");
        List<RawSearchResult> results = provider.search(req);
        assertEquals(1, results.size());
        assertEquals("OK", results.get(0).getTitle());
    }

    @Test
    void honorsPageSizeCap() {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < 15; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"title\":\"t").append(i).append("\",\"url\":\"https://example.com/").append(i).append("\"}");
        }
        sb.append("]}");
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(sb.toString()));

        SearchRequest req = new SearchRequest();
        req.setQ("q");
        List<RawSearchResult> results = provider.search(req);
        assertEquals(10, results.size(), "page-size cap should apply");
    }
}

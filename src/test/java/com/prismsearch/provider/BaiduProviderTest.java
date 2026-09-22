package com.prismsearch.provider;

import com.prismsearch.model.RawSearchResult;
import com.prismsearch.model.SearchRequest;
import com.prismsearch.provider.config.BaiduProperties;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaiduProviderTest {

    private MockWebServer server;
    private BaiduProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        BaiduProperties props = new BaiduProperties();
        props.setEnabled(true);
        props.setApiKey("bce-v3/test-key");
        props.setEndpoint(server.url("/v2/ai_search/web_search").toString());
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
        provider = new BaiduProvider(webClient, meters, breaker, props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesReferencesAndSendsPostBody() throws Exception {
        String body = """
                {
                  "references": [
                    {
                      "id": 1,
                      "title": "Result A",
                      "url": "https://example.com/a",
                      "snippet": "Snippet A",
                      "content": "Content A",
                      "type": "web",
                      "date": "2025-04-27 18:02:00",
                      "web_anchor": "Anchor A"
                    },
                    {
                      "id": 2,
                      "title": "Result B",
                      "url": "https://example.com/b",
                      "snippet": "Snippet B",
                      "type": "web",
                      "date": "2025-05-20 11:58:00"
                    }
                  ],
                  "request_id": "test-req-id"
                }
                """;
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));

        SearchRequest req = new SearchRequest();
        req.setQ("spring boot");
        req.setPage(1);

        List<RawSearchResult> results = provider.search(req);
        assertEquals(2, results.size());
        assertEquals("Result A", results.get(0).getTitle());
        assertEquals("https://example.com/a", results.get(0).getUrl());
        assertEquals("Snippet A", results.get(0).getSnippet());
        assertNotNull(results.get(0).getPublishedAt());

        // Verify POST request
        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertEquals("POST", recorded.getMethod());
        assertEquals("Bearer bce-v3/test-key", recorded.getHeader("Authorization"));
        assertEquals("application/json", recorded.getHeader("Content-Type"));

        // Verify request body
        String requestBody = recorded.getBody().readString(StandardCharsets.UTF_8);
        assertTrue(requestBody.contains("\"content\":\"spring boot\""), "body should carry query: " + requestBody);
        assertTrue(requestBody.contains("\"role\":\"user\""), "body should carry role: " + requestBody);
        assertTrue(requestBody.contains("\"search_source\":\"baidu_search_v2\""), "body should carry search_source: " + requestBody);
        assertTrue(requestBody.contains("\"type\":\"web\""), "body should carry resource filter: " + requestBody);
        assertTrue(requestBody.contains("\"top_k\":10"), "body should carry top_k: " + requestBody);
    }

    @Test
    void filtersNonWebReferences() {
        String body = """
                {
                  "references": [
                    {"id": 1, "title": "Web Result", "url": "https://example.com/web", "type": "web", "snippet": "s1"},
                    {"id": 2, "title": "Video Result", "url": "https://example.com/video", "type": "video"},
                    {"id": 3, "title": "Image Result", "url": "https://example.com/img", "type": "image"}
                  ]
                }
                """;
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));

        SearchRequest req = new SearchRequest();
        req.setQ("test");
        List<RawSearchResult> results = provider.search(req);
        assertEquals(1, results.size());
        assertEquals("Web Result", results.get(0).getTitle());
    }

    @Test
    void missingReferencesYieldsEmpty() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
        SearchRequest req = new SearchRequest();
        req.setQ("nothing");
        List<RawSearchResult> results = provider.search(req);
        assertTrue(results.isEmpty());
    }

    @Test
    void providerDisabledWithoutKey() {
        BaiduProperties props = new BaiduProperties();
        props.setEnabled(true);
        assertTrue(!props.isEnabled(), "should auto-disable without api key");
    }

    @Test
    void fallsBackToContentWhenSnippetMissing() {
        String body = """
                {
                  "references": [
                    {"id": 1, "title": "No Snippet", "url": "https://example.com/ns", "type": "web", "content": "Content fallback"}
                  ]
                }
                """;
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));

        SearchRequest req = new SearchRequest();
        req.setQ("test");
        List<RawSearchResult> results = provider.search(req);
        assertEquals(1, results.size());
        assertEquals("Content fallback", results.get(0).getSnippet());
    }
}

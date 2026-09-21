package com.mysearch.provider;

import com.mysearch.model.RawSearchResult;
import com.mysearch.model.SearchRequest;
import com.mysearch.provider.config.BingProperties;
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

class BingProviderTest {

    private MockWebServer server;
    private BingProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        BingProperties props = new BingProperties();
        props.setEnabled(true);
        props.setApiKey("bing-test-key");
        props.setEndpoint(server.url("/v7.0/search").toString());
        props.setTimeoutMs(2000);
        props.setPageSize(10);
        props.setMkt("zh-CN");

        MeterRegistry meters = new SimpleMeterRegistry();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(redis);

        WebClient webClient = WebClient.builder().build();
        provider = new BingProvider(webClient, meters, breaker, props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesWebPagesValues() throws Exception {
        String body = """
                {
                  "webPages": {
                    "value": [
                      {"name": "Result A", "url": "https://example.com/a", "snippet": "Snippet A",
                       "dateLastCrawled": "2025-05-01T12:00:00Z"},
                      {"name": "Result B", "url": "https://example.com/b", "snippet": "Snippet B"}
                    ]
                  }
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

        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recorded);
        assertEquals("bing-test-key", recorded.getHeader("Ocp-Apim-Subscription-Key"));
        String path = recorded.getPath();
        assertTrue(path.contains("q=spring"), "path should carry q: " + path);
        assertTrue(path.contains("count=10"), "path should carry count: " + path);
        assertTrue(path.contains("offset=0"), "path should carry offset: " + path);
        assertTrue(path.contains("mkt="), "path should carry mkt: " + path);
    }

    @Test
    void missingWebPagesYieldsEmpty() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
        SearchRequest req = new SearchRequest();
        req.setQ("nothing");
        List<RawSearchResult> results = provider.search(req);
        assertTrue(results.isEmpty());
    }

    @Test
    void providerDisabledWithoutKey() {
        BingProperties props = new BingProperties();
        props.setEnabled(true);
        assertTrue(!props.isEnabled(), "should auto-disable without api key");
    }
}

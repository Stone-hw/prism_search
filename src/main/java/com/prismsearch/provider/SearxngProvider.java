package com.prismsearch.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.prismsearch.common.Constants;
import com.prismsearch.model.RawSearchResult;
import com.prismsearch.model.SearchRequest;
import com.prismsearch.provider.config.SearxngProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * SearXNG adapter: GET {base-url}/search?format=json&q=...
 */
@Component
public class SearxngProvider extends AbstractSearchProvider {

    private final SearxngProperties props;

    public SearxngProvider(WebClient providerWebClient,
                           MeterRegistry meters,
                           ProviderCircuitBreaker circuitBreaker,
                           SearxngProperties props) {
        super(providerWebClient, meters, circuitBreaker);
        this.props = props;
    }

    @Override
    public String name() {
        return Constants.PROVIDER_SEARXNG;
    }

    @Override
    public boolean enabled() {
        return props.isEnabled() && props.getBaseUrl() != null && !props.getBaseUrl().isBlank();
    }

    @Override
    public double weight() {
        return props.getWeight();
    }

    @Override
    public long timeoutMs() {
        return props.getTimeoutMs();
    }

    @Override
    protected Mono<List<RawSearchResult>> doSearch(SearchRequest request) {
        URI uri = UriComponentsBuilder.fromHttpUrl(props.getBaseUrl())
                .path("/search")
                .queryParam("q", request.getQ())
                .queryParam("format", "json")
                .queryParam("pageno", request.getPage())
                .queryParam("language", request.getLang() == null ? "auto" : request.getLang())
                .queryParam("safesearch", request.getSafesearch())
                .build()
                .encode()
                .toUri();

        return webClient.get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.createException())
                .bodyToMono(JsonNode.class)
                .map(this::parse);
    }

    private List<RawSearchResult> parse(JsonNode root) {
        List<RawSearchResult> out = new ArrayList<>();
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return out;
        }
        int rank = 1;
        for (JsonNode n : results) {
            String url = textOrNull(n, "url");
            String title = textOrNull(n, "title");
            if (url == null || title == null) {
                continue;
            }
            String snippet = firstNonBlank(textOrNull(n, "content"), textOrNull(n, "snippet"));
            RawSearchResult r = RawSearchResult.of(name(), rank++, title, url, snippet);
            r.setPublishedAt(com.prismsearch.util.TimeUtil.parseIso(textOrNull(n, "publishedDate")));
            JsonNode score = n.get("score");
            if (score != null && score.isNumber()) {
                r.setEngineScore(score.asDouble());
            }
            out.add(r);
            if (out.size() >= props.getPageSize()) {
                break;
            }
        }
        return out;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}

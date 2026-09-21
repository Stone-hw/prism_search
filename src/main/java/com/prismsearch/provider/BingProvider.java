package com.prismsearch.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.prismsearch.common.Constants;
import com.prismsearch.model.RawSearchResult;
import com.prismsearch.model.SearchRequest;
import com.prismsearch.provider.config.BingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Bing Web Search API v7 adapter.
 * Docs: https://learn.microsoft.com/en-us/bing/search-apis/bing-web-search/reference/query-parameters
 */
@Component
public class BingProvider extends AbstractSearchProvider {

    private static final String SUBSCRIPTION_HEADER = "Ocp-Apim-Subscription-Key";

    private final BingProperties props;

    public BingProvider(WebClient providerWebClient,
                        MeterRegistry meters,
                        ProviderCircuitBreaker circuitBreaker,
                        BingProperties props) {
        super(providerWebClient, meters, circuitBreaker);
        this.props = props;
    }

    @Override
    public String name() {
        return Constants.PROVIDER_BING;
    }

    @Override
    public boolean enabled() {
        return props.isEnabled();
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
        int count = Math.min(props.getPageSize(), 50);
        int offset = Math.max(0, (request.getPage() - 1) * count);

        URI uri = UriComponentsBuilder.fromHttpUrl(props.getEndpoint())
                .queryParam("q", request.getQ())
                .queryParam("count", count)
                .queryParam("offset", offset)
                .queryParam("mkt", request.getLang() != null && !request.getLang().isBlank()
                        ? request.getLang() : props.getMkt())
                .queryParam("safeSearch", mapSafe(request.getSafesearch()))
                .queryParam("responseFilter", "Webpages")
                .build()
                .encode()
                .toUri();

        return webClient.get()
                .uri(uri)
                .header(SUBSCRIPTION_HEADER, props.getApiKey())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.createException())
                .bodyToMono(JsonNode.class)
                .map(this::parse);
    }

    private List<RawSearchResult> parse(JsonNode root) {
        List<RawSearchResult> out = new ArrayList<>();
        JsonNode values = root.path("webPages").path("value");
        if (!values.isArray()) {
            return out;
        }
        int rank = 1;
        for (JsonNode n : values) {
            String url = textOrNull(n, "url");
            String title = textOrNull(n, "name");
            if (url == null || title == null) {
                continue;
            }
            String snippet = textOrNull(n, "snippet");
            RawSearchResult r = RawSearchResult.of(name(), rank++, title, url, snippet);
            r.setPublishedAt(com.prismsearch.util.TimeUtil.parseIso(textOrNull(n, "dateLastCrawled")));
            out.add(r);
        }
        return out;
    }

    private static String mapSafe(int safeSearch) {
        return switch (safeSearch) {
            case 2 -> "Strict";
            case 0 -> "Off";
            default -> "Moderate";
        };
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }
}

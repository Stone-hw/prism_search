package com.mysearch.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysearch.common.Constants;
import com.mysearch.model.RawSearchResult;
import com.mysearch.model.SearchRequest;
import com.mysearch.provider.config.GoogleProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Google Custom Search JSON API adapter.
 * Docs: https://developers.google.com/custom-search/v1/reference/rest/v1/cse/list
 */
@Component
public class GoogleProvider extends AbstractSearchProvider {

    private final GoogleProperties props;

    public GoogleProvider(WebClient providerWebClient,
                          MeterRegistry meters,
                          ProviderCircuitBreaker circuitBreaker,
                          GoogleProperties props) {
        super(providerWebClient, meters, circuitBreaker);
        this.props = props;
    }

    @Override
    public String name() {
        return Constants.PROVIDER_GOOGLE;
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
        // Google caps num at 10 and start at 91.
        int num = Math.min(props.getPageSize(), 10);
        int start = Math.max(1, (request.getPage() - 1) * num + 1);

        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(props.getEndpoint())
                .queryParam("key", props.getApiKey())
                .queryParam("cx", props.getCx())
                .queryParam("q", request.getQ())
                .queryParam("num", num)
                .queryParam("start", start)
                .queryParam("safe", mapSafe(request.getSafesearch()));
        if (request.getLang() != null && !request.getLang().isBlank()) {
            b.queryParam("lr", "lang_" + primaryLang(request.getLang()));
        }
        URI uri = b.build().encode().toUri();

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
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            return out;
        }
        int rank = 1;
        for (JsonNode n : items) {
            String url = textOrNull(n, "link");
            String title = textOrNull(n, "title");
            if (url == null || title == null) {
                continue;
            }
            String snippet = textOrNull(n, "snippet");
            RawSearchResult r = RawSearchResult.of(name(), rank++, title, url, snippet);
            // pagemap.metatags may contain article:published_time
            JsonNode metatags = n.path("pagemap").path("metatags");
            if (metatags.isArray() && !metatags.isEmpty()) {
                JsonNode first = metatags.get(0);
                r.setPublishedAt(com.mysearch.util.TimeUtil.parseIso(
                        firstNonNull(first, "article:published_time", "og:updated_time", "date")));
            }
            out.add(r);
        }
        return out;
    }

    private static String mapSafe(int safeSearch) {
        return switch (safeSearch) {
            case 2 -> "active";
            case 0 -> "off";
            default -> "medium";
        };
    }

    private static String primaryLang(String lang) {
        int idx = lang.indexOf('-');
        return (idx > 0 ? lang.substring(0, idx) : lang).toLowerCase(Locale.ROOT);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static String firstNonNull(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = textOrNull(node, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}

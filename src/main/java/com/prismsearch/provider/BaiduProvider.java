package com.prismsearch.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prismsearch.common.Constants;
import com.prismsearch.model.RawSearchResult;
import com.prismsearch.model.SearchRequest;
import com.prismsearch.provider.config.BaiduProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Baidu AI Search (千帆) web_search adapter.
 * Docs: https://cloud.baidu.com/doc/qianfan/s/Pmh4sv5qa
 * <p>
 * POST JSON body with {@code messages[{role:"user", content:<query>}]} and
 * {@code resource_type_filter[{type:"web", top_k:N}]}.
 * Response carries results in {@code references[]}.
 */
@Component
public class BaiduProvider extends AbstractSearchProvider {

    private final BaiduProperties props;
    private final ObjectMapper mapper;

    public BaiduProvider(WebClient providerWebClient,
                         MeterRegistry meters,
                         ProviderCircuitBreaker circuitBreaker,
                         BaiduProperties props) {
        super(providerWebClient, meters, circuitBreaker);
        this.props = props;
        this.mapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return Constants.PROVIDER_BAIDU;
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
        // Baidu API truncates query to 72 chars internally; we still send the full query.
        int topK = Math.min(props.getPageSize(), 50);

        ObjectNode body = mapper.createObjectNode();

        // messages
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", request.getQ());

        // search_source
        body.put("search_source", "baidu_search_v2");

        // resource_type_filter — web only
        ArrayNode filters = body.putArray("resource_type_filter");
        ObjectNode webFilter = filters.addObject();
        webFilter.put("type", "web");
        webFilter.put("top_k", topK);

        return webClient.post()
                .uri(props.getEndpoint())
                .header("Authorization", "Bearer " + props.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.createException())
                .bodyToMono(JsonNode.class)
                .map(this::parse);
    }

    private List<RawSearchResult> parse(JsonNode root) {
        List<RawSearchResult> out = new ArrayList<>();
        JsonNode references = root.path("references");
        if (!references.isArray()) {
            return out;
        }
        int rank = 1;
        for (JsonNode n : references) {
            // Only include web-type results
            String type = textOrNull(n, "type");
            if (type != null && !"web".equals(type)) {
                continue;
            }
            String url = textOrNull(n, "url");
            String title = textOrNull(n, "title");
            if (url == null || title == null) {
                continue;
            }
            String snippet = textOrNull(n, "snippet");
            if (snippet == null) {
                snippet = textOrNull(n, "content");
            }
            RawSearchResult r = RawSearchResult.of(name(), rank++, title, url, snippet);
            r.setPublishedAt(com.prismsearch.util.TimeUtil.parseBaiduDate(textOrNull(n, "date")));
            out.add(r);
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
}

package com.mysearch.web;

import com.mysearch.common.ApiResponse;
import com.mysearch.config.MysearchProperties;
import com.mysearch.model.SearchRequest;
import com.mysearch.model.SearchResponse;
import com.mysearch.service.SearchOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Public search API.
 */
@Validated
@RestController
@RequestMapping("/api")
@Tag(name = "search", description = "Multi-provider meta search API")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final SearchOrchestrator orchestrator;
    private final MysearchProperties props;

    public SearchController(SearchOrchestrator orchestrator, MysearchProperties props) {
        this.orchestrator = orchestrator;
        this.props = props;
    }

    @GetMapping("/search")
    @Operation(summary = "Aggregate search across SearXNG/Google/Bing")
    public ApiResponse<SearchResponse> search(@Valid SearchRequest request) {
        log.info("GET /api/search {}", request);
        SearchResponse resp = orchestrator.search(request);
        return ApiResponse.ok(resp);
    }

    @GetMapping("/suggest")
    @Operation(summary = "Query suggestion based on a local hot-word list")
    public ApiResponse<List<String>> suggest(
            @RequestParam(name = "q", required = false) @Size(max = 64) String q,
            @RequestParam(name = "limit", defaultValue = "8") @Min(1) @Max(20) int limit) {
        List<String> dict = props.getSuggest().getHotWords();
        int max = Math.min(limit, props.getSuggest().getLimit());
        List<String> out = new ArrayList<>();
        if (dict == null || dict.isEmpty()) {
            return ApiResponse.ok(out);
        }
        String prefix = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        for (String w : dict) {
            if (w == null || w.isBlank()) {
                continue;
            }
            if (prefix.isEmpty() || w.toLowerCase(Locale.ROOT).startsWith(prefix)
                    || w.toLowerCase(Locale.ROOT).contains(prefix)) {
                out.add(w);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return ApiResponse.ok(out);
    }
}

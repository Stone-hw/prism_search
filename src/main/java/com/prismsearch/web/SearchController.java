package com.prismsearch.web;

import com.prismsearch.cache.HotWordService;
import com.prismsearch.common.ApiResponse;
import com.prismsearch.config.PrismsearchProperties;
import com.prismsearch.model.SearchRequest;
import com.prismsearch.model.SearchResponse;
import com.prismsearch.service.SearchOrchestrator;
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

import java.util.List;

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
    private final PrismsearchProperties props;
    private final HotWordService hotWordService;

    public SearchController(SearchOrchestrator orchestrator,
                            PrismsearchProperties props,
                            HotWordService hotWordService) {
        this.orchestrator = orchestrator;
        this.props = props;
        this.hotWordService = hotWordService;
    }

    @GetMapping("/search")
    @Operation(summary = "Aggregate search across SearXNG/Google/Bing")
    public ApiResponse<SearchResponse> search(@Valid SearchRequest request) {
        log.info("GET /api/search {}", request);
        SearchResponse resp = orchestrator.search(request);
        return ApiResponse.ok(resp);
    }

    @GetMapping("/suggest")
    @Operation(summary = "Query suggestion from Redis hot-words with static fallback")
    public ApiResponse<List<String>> suggest(
            @RequestParam(name = "q", required = false) @Size(max = 64) String q,
            @RequestParam(name = "limit", defaultValue = "8") @Min(1) @Max(20) int limit) {
        int max = Math.min(limit, props.getSuggest().getLimit());
        List<String> suggestions = hotWordService.suggest(q, max);
        return ApiResponse.ok(suggestions);
    }
}

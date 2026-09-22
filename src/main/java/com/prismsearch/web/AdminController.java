package com.prismsearch.web;

import com.prismsearch.cache.HotWordService;
import com.prismsearch.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin API for hot-word management.
 */
@Validated
@RestController
@RequestMapping("/api/admin")
@Tag(name = "admin", description = "Hot-word management API")
public class AdminController {

    private static final double DEFAULT_WEIGHT = 1000.0;

    private final HotWordService hotWordService;

    public AdminController(HotWordService hotWordService) {
        this.hotWordService = hotWordService;
    }

    @GetMapping("/hotwords")
    @Operation(summary = "List all hot words (manual + auto merged)")
    public ApiResponse<Map<String, Double>> listHotWords() {
        return ApiResponse.ok(hotWordService.listAll());
    }

    @PostMapping("/hotwords")
    @Operation(summary = "Add a manual hot word with optional weight")
    public ApiResponse<String> addHotWord(@RequestBody HotWordRequest req) {
        double weight = req.weight() != null ? req.weight() : DEFAULT_WEIGHT;
        hotWordService.addManual(req.word(), weight);
        return ApiResponse.ok("added");
    }

    @DeleteMapping("/hotwords")
    @Operation(summary = "Remove a manual hot word")
    public ApiResponse<String> removeHotWord(
            @RequestParam @NotBlank @Size(max = 128) String word) {
        hotWordService.removeManual(word);
        return ApiResponse.ok("removed");
    }

    @PostMapping("/hotwords/aggregate")
    @Operation(summary = "Trigger hot-word aggregation manually")
    public ApiResponse<String> triggerAggregate() {
        hotWordService.aggregate();
        return ApiResponse.ok("aggregated");
    }

    record HotWordRequest(@NotBlank @Size(max = 128) String word, Double weight) { }
}

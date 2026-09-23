package com.prismsearch.service.impl;

import com.prismsearch.cache.HotWordService;
import com.prismsearch.cache.SearchCacheService;
import com.prismsearch.common.BizException;
import com.prismsearch.common.ErrorCode;
import com.prismsearch.model.NormalizedResult;
import com.prismsearch.model.ProviderStatus;
import com.prismsearch.model.RawSearchResult;
import com.prismsearch.model.SearchRequest;
import com.prismsearch.model.SearchResponse;
import com.prismsearch.provider.SearchProvider;
import com.prismsearch.rank.ResultProcessor;
import com.prismsearch.service.SearchOrchestrator;
import com.prismsearch.util.MdcUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Default {@link SearchOrchestrator} implementation. Follows TECH_DESIGN.md section 2.9.3.
 */
@Service
public class SearchOrchestratorImpl implements SearchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SearchOrchestratorImpl.class);

    private final List<SearchProvider> providers;
    private final ResultProcessor resultProcessor;
    private final SearchCacheService cacheService;
    private final HotWordService hotWordService;
    private final ExecutorService providerExecutor;
    private final MeterRegistry meters;

    public SearchOrchestratorImpl(List<SearchProvider> providers,
                                  ResultProcessor resultProcessor,
                                  SearchCacheService cacheService,
                                  HotWordService hotWordService,
                                  ExecutorService providerExecutor,
                                  MeterRegistry meters) {
        this.providers = providers;
        this.resultProcessor = resultProcessor;
        this.cacheService = cacheService;
        this.hotWordService = hotWordService;
        this.providerExecutor = providerExecutor;
        this.meters = meters;
        log.info("SearchOrchestrator initialized with providers={}",
                providers.stream().map(SearchProvider::name).toList());
    }

    @Override
    public SearchResponse search(SearchRequest req) {
        long start = System.nanoTime();
        Timer.Sample sample = Timer.start(meters);
        String cacheKey = cacheService.buildKey(req);

        // 1. Cache lookup
        if (!req.isNocache()) {
            Optional<SearchResponse> cached = cacheService.get(cacheKey);
            if (cached.isPresent()) {
                SearchResponse resp = cached.get();
                resp.setCached(true);
                // Refresh elapsedMs so clients see a fast response.
                resp.setElapsedMs(elapsedMs(start));
                meters.counter("prismsearch.request.total",
                        "cached", "true", "success", "true").increment();
                sample.stop(Timer.builder("prismsearch.request.latency")
                        .tag("cached", "true")
                        .publishPercentileHistogram()
                        .register(meters));
                return resp;
            }
        }

        // 2. Select providers (enabled + optional filter).
        Set<String> filter = parseProviders(req.getProviders());
        List<SearchProvider> selected = providers.stream()
                .filter(SearchProvider::enabled)
                .filter(p -> filter.isEmpty() || filter.contains(p.name()))
                .toList();

        Map<String, ProviderStatus> statusMap = new LinkedHashMap<>();
        Map<String, List<RawSearchResult>> byProvider = new LinkedHashMap<>();

        if (selected.isEmpty()) {
            // No provider available at all - treat as full outage.
            meters.counter("prismsearch.request.total", "cached", "false", "success", "false").increment();
            sample.stop(Timer.builder("prismsearch.request.latency")
                    .tag("cached", "false")
                    .publishPercentileHistogram()
                    .register(meters));
            throw new BizException(ErrorCode.ALL_PROVIDERS_FAILED, "no enabled provider");
        }

        // Record skipped providers so the response reflects the full roster.
        for (SearchProvider p : providers) {
            if (!selected.contains(p)) {
                String reason = !p.enabled() ? "disabled" : "filtered";
                statusMap.put(p.name(), ProviderStatus.skipped(reason));
            }
        }

        // Capture MDC context so virtual threads inherit traceId.
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        // 3. Concurrent fan-out on virtual threads.
        List<CompletableFuture<ProviderOutcome>> futures = new ArrayList<>(selected.size());
        for (SearchProvider p : selected) {
            CompletableFuture<ProviderOutcome> f = CompletableFuture
                    .supplyAsync(MdcUtil.wrap(() -> callProvider(p, req), mdcContext), providerExecutor)
                    .orTimeout(p.timeoutMs(), TimeUnit.MILLISECONDS)
                    .handle((outcome, ex) -> outcome != null
                            ? outcome
                            : ProviderOutcome.failed(p.name(), ex, p.timeoutMs()));
            futures.add(f);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        for (CompletableFuture<ProviderOutcome> f : futures) {
            ProviderOutcome out = f.join();
            byProvider.put(out.name(), out.results());
            statusMap.put(out.name(), out.toStatus());
        }

        // 4. All failed -> business exception.
        boolean allEmpty = byProvider.values().stream().allMatch(List::isEmpty);
        boolean anySucceeded = statusMap.values().stream().anyMatch(s -> "ok".equals(s.getStatus()));
        if (allEmpty && !anySucceeded) {
            meters.counter("prismsearch.request.total", "cached", "false", "success", "false").increment();
            sample.stop(Timer.builder("prismsearch.request.latency")
                    .tag("cached", "false")
                    .publishPercentileHistogram()
                    .register(meters));
            throw new BizException(ErrorCode.ALL_PROVIDERS_FAILED);
        }

        // 5. Normalize -> dedup -> rank.
        List<NormalizedResult> processed = resultProcessor.process(byProvider, selected, req.getLang());

        // 6. Paginate.
        int total = processed.size();
        int from = Math.min((req.getPage() - 1) * req.getSize(), total);
        int to = Math.min(from + req.getSize(), total);
        List<NormalizedResult> pageData = from >= to ? Collections.emptyList() : processed.subList(from, to);

        // 7. Build response.
        SearchResponse resp = new SearchResponse();
        resp.setQuery(req.getQ());
        resp.setTotal(total);
        resp.setPage(req.getPage());
        resp.setSize(req.getSize());
        resp.setElapsedMs(elapsedMs(start));
        resp.setCached(false);
        resp.setProviders(statusMap);
        resp.setResults(new ArrayList<>(pageData));

        meters.counter("prismsearch.request.total", "cached", "false", "success", "true").increment();
        meters.summary("prismsearch.result.count").record(total);
        sample.stop(Timer.builder("prismsearch.request.latency")
                .tag("cached", "false")
                .publishPercentileHistogram()
                .register(meters));

        // 8. Async cache write.
        cacheService.putAsync(cacheKey, resp);

        // 9. Async record search query for hot-word aggregation.
        CompletableFuture.runAsync(MdcUtil.wrap(() -> hotWordService.recordSearch(req.getQ()), mdcContext), providerExecutor)
                .exceptionally(ex -> { log.debug("recordSearch async failed: {}", ex.toString()); return null; });

        return resp;
    }

    private ProviderOutcome callProvider(SearchProvider p, SearchRequest req) {
        long t0 = System.nanoTime();
        try {
            List<RawSearchResult> results = p.search(req);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            return ProviderOutcome.ok(p.name(), results == null ? List.of() : results, elapsed);
        } catch (Exception ex) {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            return ProviderOutcome.failed(p.name(), ex, elapsed);
        }
    }

    private static Set<String> parseProviders(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .forEach(set::add);
        return set;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Internal result of one provider call, including timing and status classification. */
    record ProviderOutcome(String name,
                           List<RawSearchResult> results,
                           long elapsedMs,
                           String status,
                           String message) {

        static ProviderOutcome ok(String name, List<RawSearchResult> results, long elapsedMs) {
            return new ProviderOutcome(name, results, elapsedMs, "ok", null);
        }

        static ProviderOutcome failed(String name, Throwable ex, long elapsedMs) {
            boolean timeout = ex instanceof TimeoutException
                    || (ex != null && ex.getClass().getSimpleName().contains("Timeout"))
                    || (ex != null && ex.getCause() instanceof TimeoutException);
            String status = timeout ? "timeout" : "error";
            String msg = ex == null ? status : (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return new ProviderOutcome(name, Collections.emptyList(), elapsedMs, status, msg);
        }

        ProviderStatus toStatus() {
            return switch (status) {
                case "ok" -> ProviderStatus.ok(elapsedMs, results.size());
                case "timeout" -> ProviderStatus.timeout(elapsedMs);
                default -> ProviderStatus.error(elapsedMs, message);
            };
        }
    }
}

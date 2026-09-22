package com.prismsearch.provider;

import com.prismsearch.model.RawSearchResult;
import com.prismsearch.model.SearchRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Base class handling common concerns: metrics, retries on 5xx, circuit-breaker hooks
 * and per-call error normalization. Subclasses implement the actual HTTP request.
 */
public abstract class AbstractSearchProvider implements SearchProvider {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final WebClient webClient;
    protected final MeterRegistry meters;
    protected final ProviderCircuitBreaker circuitBreaker;

    protected AbstractSearchProvider(WebClient webClient,
                                     MeterRegistry meters,
                                     ProviderCircuitBreaker circuitBreaker) {
        this.webClient = webClient;
        this.meters = meters;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Perform the HTTP call and parse the JSON body into a {@link Mono} of results.
     */
    protected abstract Mono<List<RawSearchResult>> doSearch(SearchRequest request);

    @Override
    public final List<RawSearchResult> search(SearchRequest request) {
        if (!enabled()) {
            return Collections.emptyList();
        }
        if (circuitBreaker.isOpen(name())) {
            log.warn("[provider={}] circuit breaker OPEN, skipping", name());
            meters.counter("prismsearch.provider.error", "name", name(), "type", "circuit_open").increment();
            return Collections.emptyList();
        }

        long start = System.nanoTime();
        Timer.Sample sample = Timer.start(meters);
        try {
            // .timeout() before .block() so we get a proper TimeoutException (classified correctly)
            // and so the total time including retries stays within timeoutMs.
            List<RawSearchResult> results = doSearch(request)
                    .retryWhen(Retry.backoff(1, Duration.ofMillis(200))
                            .filter(this::retryable)
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .timeout(Duration.ofMillis(timeoutMs()))
                    .block();

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            int count = results == null ? 0 : results.size();
            sample.stop(Timer.builder("prismsearch.provider.latency")
                    .tag("name", name())
                    .publishPercentileHistogram()
                    .register(meters));
            meters.summary("prismsearch.provider.result.count", "name", name()).record(count);
            circuitBreaker.recordSuccess(name());
            log.info("[provider={}] [q={}] [elapsed={}ms] [count={}] success",
                    name(), abbreviate(request.getQ()), elapsedMs, count);
            return results == null ? Collections.emptyList() : results;
        } catch (Exception ex) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            sample.stop(Timer.builder("prismsearch.provider.latency")
                    .tag("name", name())
                    .publishPercentileHistogram()
                    .register(meters));
            String type = classify(ex);
            meters.counter("prismsearch.provider.error", "name", name(), "type", type).increment();
            if (!"timeout".equals(type)) {
                // Do not open the breaker for transient timeouts.
                circuitBreaker.recordFailure(name());
            }
            log.warn("[provider={}] [q={}] [elapsed={}ms] failed type={} msg={}",
                    name(), abbreviate(request.getQ()), elapsedMs, type, ex.toString());
            throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
        }
    }

    /** Only retry on 5xx and I/O level errors, not on 4xx (bad key, bad cx, ...). */
    private boolean retryable(Throwable t) {
        if (t instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().is5xxServerError();
        }
        return !(t instanceof java.util.concurrent.TimeoutException);
    }

    private String classify(Throwable t) {
        if (t == null) {
            return "error";
        }
        if (t instanceof java.util.concurrent.TimeoutException
                || t instanceof io.netty.handler.timeout.TimeoutException
                || t.getClass().getSimpleName().contains("Timeout")) {
            return "timeout";
        }
        // Mono.block(Duration) wraps expiry in IllegalStateException("Timeout on blocking read").
        // Reactor's .timeout() emits java.util.concurrent.TimeoutException directly (handled above).
        String msg = t.getMessage();
        if (msg != null && msg.contains("Timeout")) {
            return "timeout";
        }
        if (t instanceof WebClientResponseException wcre) {
            if (wcre.getStatusCode().is4xxClientError()) {
                return "http_4xx";
            }
            if (wcre.getStatusCode().is5xxServerError()) {
                return "http_5xx";
            }
        }
        // Also unwrap cause (e.g. Reactor's ReactiveException around TimeoutException).
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            String inner = classify(cause);
            if (!"error".equals(inner)) {
                return inner;
            }
        }
        return "error";
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 32 ? s : s.substring(0, 32) + "...";
    }
}

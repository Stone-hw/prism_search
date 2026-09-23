package com.prismsearch.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prismsearch.common.Constants;
import com.prismsearch.config.PrismsearchProperties;
import com.prismsearch.model.SearchRequest;
import com.prismsearch.model.SearchResponse;
import com.prismsearch.util.Md5Util;
import com.prismsearch.util.MdcUtil;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis-backed result cache. All Redis failures are swallowed and reported via
 * {@code prismsearch.cache.error} counter so a Redis outage degrades performance but
 * does not break the search flow.
 */
@Service
public class SearchCacheService {

    private static final Logger log = LoggerFactory.getLogger(SearchCacheService.class);

    private final StringRedisTemplate redis;
    private final PrismsearchProperties props;
    private final MeterRegistry meters;
    private final ExecutorService providerExecutor;
    private final ObjectMapper mapper;

    public SearchCacheService(StringRedisTemplate redis,
                              PrismsearchProperties props,
                              MeterRegistry meters,
                              ExecutorService providerExecutor,
                              ObjectMapper mapper) {
        this.redis = redis;
        this.props = props;
        this.meters = meters;
        this.providerExecutor = providerExecutor;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        log.info("SearchCacheService initialized (enabled={}, ttl={}s, emptyTtl={}s)",
                props.getCache().isEnabled(),
                props.getCache().getTtlSeconds(),
                props.getCache().getEmptyTtlSeconds());
    }

    /** Build the versioned cache key for a request. */
    public String buildKey(SearchRequest req) {
        return Constants.CACHE_RESULT_PREFIX + Md5Util.md5Hex(req.cacheKeySeed());
    }

    public Optional<SearchResponse> get(String key) {
        if (!props.getCache().isEnabled()) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                meters.counter("prismsearch.cache.miss").increment();
                return Optional.empty();
            }
            SearchResponse resp = mapper.readValue(raw, SearchResponse.class);
            meters.counter("prismsearch.cache.hit").increment();
            return Optional.ofNullable(resp);
        } catch (Exception ex) {
            meters.counter("prismsearch.cache.error", "op", "get").increment();
            log.debug("Cache get failed for key {}: {}", key, ex.toString());
            return Optional.empty();
        }
    }

    /** Serialize and store the response asynchronously with jittered TTL. */
    public void putAsync(String key, SearchResponse resp) {
        if (!props.getCache().isEnabled() || resp == null) {
            return;
        }
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        CompletableFuture.runAsync(MdcUtil.wrap(() -> put(key, resp), mdcContext), providerExecutor)
                .exceptionally(ex -> {
                    meters.counter("prismsearch.cache.error", "op", "put_async").increment();
                    log.debug("Cache putAsync failed for key {}: {}", key, ex.toString());
                    return null;
                });
    }

    void put(String key, SearchResponse resp) {
        try {
            String json = mapper.writeValueAsString(resp);
            long ttl = computeTtlSeconds(resp);
            redis.opsForValue().set(key, json, Duration.ofSeconds(ttl));
            log.debug("Cache put key={} ttl={}s bytes={}", key, ttl, json.length());
        } catch (JsonProcessingException ex) {
            meters.counter("prismsearch.cache.error", "op", "serialize").increment();
            log.warn("Cache serialize failed for key {}: {}", key, ex.toString());
        } catch (Exception ex) {
            meters.counter("prismsearch.cache.error", "op", "put").increment();
            log.debug("Cache put failed for key {}: {}", key, ex.toString());
        }
    }

    /**
     * TTL with random +/-jitter to avoid cache avalanche. Empty results use the shorter TTL.
     */
    long computeTtlSeconds(SearchResponse resp) {
        PrismsearchProperties.Cache c = props.getCache();
        boolean empty = resp.getResults() == null || resp.getResults().isEmpty();
        long base = empty ? c.getEmptyTtlSeconds() : c.getTtlSeconds();
        double jitter = c.getJitterRatio();
        if (jitter <= 0) {
            return Math.max(1, base);
        }
        double factor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitter;
        return Math.max(1, (long) (base * factor));
    }
}

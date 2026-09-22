package com.prismsearch.provider;

import com.prismsearch.common.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;

/**
 * Minimal Redis-backed circuit breaker counting provider failures in a rolling 5-minute window.
 * When the count exceeds the threshold, calls short-circuit and return an empty result.
 * <p>
 * Uses a Lua script for atomic INCR + EXPIRE to prevent TTL-loss on crash.
 */
@Component
public class ProviderCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ProviderCircuitBreaker.class);

    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int DEFAULT_THRESHOLD = 5;

    /**
     * Lua script: atomically increment the counter and set TTL on first write.
     * Returns the new counter value.
     */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL_SCRIPT;
    static {
        String lua = """
                local current = redis.call('incr', KEYS[1])
                if current == 1 then
                    redis.call('expire', KEYS[1], ARGV[1])
                end
                return current
                """;
        INCR_WITH_TTL_SCRIPT = new DefaultRedisScript<>(lua, Long.class);
    }

    private final StringRedisTemplate redis;

    public ProviderCircuitBreaker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true when the breaker is currently open for the given provider.
     */
    public boolean isOpen(String provider) {
        try {
            String v = redis.opsForValue().get(key(provider));
            if (v == null) {
                return false;
            }
            return Integer.parseInt(v) >= threshold();
        } catch (Exception ex) {
            // Redis down: fail open, do not disable providers.
            log.debug("Circuit breaker check failed for {}: {}", provider, ex.toString());
            return false;
        }
    }

    /** Increment the failure counter after a provider error (atomic INCR + EXPIRE via Lua). */
    public void recordFailure(String provider) {
        try {
            String k = key(provider);
            Long v = redis.execute(
                    INCR_WITH_TTL_SCRIPT,
                    Collections.singletonList(k),
                    String.valueOf(WINDOW.getSeconds()));
            if (v != null && v >= threshold()) {
                log.warn("Circuit breaker OPEN for provider {} (failures={} within {})", provider, v, WINDOW);
            }
        } catch (Exception ex) {
            log.debug("Circuit breaker recordFailure failed for {}: {}", provider, ex.toString());
        }
    }

    /** Reset the counter after a successful call. */
    public void recordSuccess(String provider) {
        try {
            redis.delete(key(provider));
        } catch (Exception ex) {
            log.debug("Circuit breaker recordSuccess failed for {}: {}", provider, ex.toString());
        }
    }

    private String key(String provider) {
        return Constants.CACHE_PROVIDER_FAIL_PREFIX + provider;
    }

    private int threshold() {
        return DEFAULT_THRESHOLD;
    }
}

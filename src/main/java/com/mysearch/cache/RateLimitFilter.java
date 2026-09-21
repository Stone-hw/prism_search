package com.mysearch.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysearch.common.ApiResponse;
import com.mysearch.common.Constants;
import com.mysearch.common.ErrorCode;
import com.mysearch.config.MysearchProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Per-IP request rate limit. Only guards {@code /api/**}; UI/actuator are excluded.
 * Uses Redis INCR + EXPIRE for a fixed 60-second window.
 * Redis failures degrade to fail-open (never block users because of infra issues).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final MysearchProperties props;
    private final MeterRegistry meters;
    private final ObjectMapper mapper;

    public RateLimitFilter(StringRedisTemplate redis,
                           MysearchProperties props,
                           MeterRegistry meters,
                           ObjectMapper mapper) {
        this.redis = redis;
        this.props = props;
        this.meters = meters;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.getRateLimit().isEnabled()) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = resolveIp(request);
        String key = Constants.CACHE_RATE_PREFIX + ip;

        long count;
        try {
            Long v = redis.opsForValue().increment(key);
            count = v == null ? 0L : v;
            if (count == 1L) {
                redis.expire(key, WINDOW);
            }
        } catch (Exception ex) {
            meters.counter("mysearch.ratelimit.error").increment();
            log.debug("Rate limit backend unavailable, failing open: {}", ex.toString());
            chain.doFilter(request, response);
            return;
        }

        int limit = props.getRateLimit().getPerMinute();
        if (count > limit) {
            meters.counter("mysearch.ratelimit.blocked").increment();
            log.warn("Rate limit exceeded: ip={} count={} limit={}/min", ip, count, limit);
            writeLimited(response, ip);
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeLimited(HttpServletResponse response, String ip) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-RateLimit-Limit", Integer.toString(props.getRateLimit().getPerMinute()));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader("Retry-After", "60");
        ApiResponse<Void> body = ApiResponse.fail(ErrorCode.RATE_LIMITED);
        response.getWriter().write(mapper.writeValueAsString(body));
        if (log.isDebugEnabled()) {
            log.debug("429 written for ip={}", ip);
        }
    }

    /**
     * Resolve the caller IP honouring X-Forwarded-For / X-Real-IP when behind a proxy.
     * Only the first hop is trusted.
     */
    static String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = comma > 0 ? xff.substring(0, comma) : xff;
            first = first.trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}

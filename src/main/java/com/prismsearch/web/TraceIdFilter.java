package com.prismsearch.web;

import com.prismsearch.common.Constants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Injects a short {@code traceId} into MDC and echoes it back via the
 * {@value Constants#HEADER_TRACE_ID} response header for cross-service correlation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(Constants.HEADER_TRACE_ID);
        String traceId = (incoming != null && !incoming.isBlank())
                ? sanitize(incoming)
                : UUID.randomUUID().toString().substring(0, 8);
        MDC.put(Constants.MDC_TRACE_ID, traceId);
        response.setHeader(Constants.HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Constants.MDC_TRACE_ID);
        }
    }

    private static String sanitize(String s) {
        String trimmed = s.trim();
        if (trimmed.length() > 32) {
            trimmed = trimmed.substring(0, 32);
        }
        return trimmed.replaceAll("[^A-Za-z0-9\\-_]", "");
    }
}

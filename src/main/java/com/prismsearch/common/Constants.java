package com.prismsearch.common;

/**
 * Global constants.
 */
public final class Constants {

    private Constants() {
    }

    /** Cache key version prefix, bump when payload becomes incompatible. */
    public static final String CACHE_PREFIX = "prismsearch:v1:";

    /** Cache key: search result. */
    public static final String CACHE_RESULT_PREFIX = CACHE_PREFIX + "result:";

    /** Cache key: pre-computed SimHash by URL. */
    public static final String CACHE_SIMHASH_PREFIX = CACHE_PREFIX + "simhash:";

    /** Cache key: per-IP rate limit counter. */
    public static final String CACHE_RATE_PREFIX = CACHE_PREFIX + "rate:";

    /** Cache key: provider failure counter (circuit breaker). */
    public static final String CACHE_PROVIDER_FAIL_PREFIX = CACHE_PREFIX + "provider:fail:";

    /** Redis key: hot-word manual set (admin-curated). */
    public static final String HOTWORD_MANUAL_KEY = CACHE_PREFIX + "hotwords:manual";

    /** Redis key: hot-word auto set (aggregated from search log). */
    public static final String HOTWORD_AUTO_KEY = CACHE_PREFIX + "hotwords:auto";

    /** Redis key: search log ZSet (member=query, score=frequency). */
    public static final String SEARCHLOG_KEY = CACHE_PREFIX + "searchlog";

    /** MDC key for the trace id. */
    public static final String MDC_TRACE_ID = "traceId";

    /** Header carrying the trace id back to callers. */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** Default User-Agent used when calling upstream providers. */
    public static final String DEFAULT_USER_AGENT = "prismsearch/1.0 (+https://github.com/prismsearch)";

    /** Provider names. */
    public static final String PROVIDER_SEARXNG = "searxng";
    public static final String PROVIDER_GOOGLE = "google";
    public static final String PROVIDER_BING = "bing";
    public static final String PROVIDER_BAIDU = "baidu";
}

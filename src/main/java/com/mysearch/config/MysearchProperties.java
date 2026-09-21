package com.mysearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration bound to prefix {@code mysearch}.
 */
@ConfigurationProperties(prefix = "mysearch")
public class MysearchProperties {

    private Rank rank = new Rank();
    private Cache cache = new Cache();
    private RateLimit rateLimit = new RateLimit();
    private Suggest suggest = new Suggest();

    public Rank getRank() {
        return rank;
    }

    public void setRank(Rank rank) {
        this.rank = rank;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Suggest getSuggest() {
        return suggest;
    }

    public void setSuggest(Suggest suggest) {
        this.suggest = suggest;
    }

    public static class Rank {
        /** RRF smoothing constant. */
        private int rrfK = 60;
        /** SimHash hamming distance threshold for near-duplicate detection. */
        private int simhashThreshold = 3;
        /** TTL of the URL -> SimHash cache in days. */
        private int simhashCacheDays = 7;

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        public int getSimhashThreshold() {
            return simhashThreshold;
        }

        public void setSimhashThreshold(int simhashThreshold) {
            this.simhashThreshold = simhashThreshold;
        }

        public int getSimhashCacheDays() {
            return simhashCacheDays;
        }

        public void setSimhashCacheDays(int simhashCacheDays) {
            this.simhashCacheDays = simhashCacheDays;
        }
    }

    public static class Cache {
        /** TTL of a normal result cache entry in seconds. */
        private long ttlSeconds = 1800;
        /** TTL of an empty-result cache entry in seconds. */
        private long emptyTtlSeconds = 300;
        /** Random jitter ratio applied to TTL to avoid stampede (0.1 = +/-10%). */
        private double jitterRatio = 0.1;
        /** Whether the cache layer is enabled at all. */
        private boolean enabled = true;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public long getEmptyTtlSeconds() {
            return emptyTtlSeconds;
        }

        public void setEmptyTtlSeconds(long emptyTtlSeconds) {
            this.emptyTtlSeconds = emptyTtlSeconds;
        }

        public double getJitterRatio() {
            return jitterRatio;
        }

        public void setJitterRatio(double jitterRatio) {
            this.jitterRatio = jitterRatio;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int perMinute = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(int perMinute) {
            this.perMinute = perMinute;
        }
    }

    public static class Suggest {
        /** Local hot-word dictionary used by /api/suggest in MVP. */
        private List<String> hotWords = new ArrayList<>();
        private int limit = 8;

        public List<String> getHotWords() {
            return hotWords;
        }

        public void setHotWords(List<String> hotWords) {
            this.hotWords = hotWords;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }
}

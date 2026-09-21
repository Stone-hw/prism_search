package com.mysearch.rank;

import com.mysearch.common.Constants;
import com.mysearch.config.MysearchProperties;
import com.mysearch.util.Md5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 64-bit SimHash over tokenized text, per TECH_DESIGN.md appendix B.1.
 * Provides {@link #getOrCompute(String, String)} which caches URL -> fingerprint in Redis
 * for {@code mysearch.rank.simhash-cache-days} days.
 */
@Component
public class SimHasher {

    private static final Logger log = LoggerFactory.getLogger(SimHasher.class);

    private static final int FEATURE_BITS = 64;

    private final Tokenizer tokenizer;
    private final StringRedisTemplate redis;
    private final MysearchProperties props;

    public SimHasher(Tokenizer tokenizer, StringRedisTemplate redis, MysearchProperties props) {
        this.tokenizer = tokenizer;
        this.redis = redis;
        this.props = props;
    }

    /** Compute the 64-bit SimHash fingerprint for the given text. */
    public long compute(String text) {
        List<String> tokens = tokenizer.tokenize(text);
        if (tokens.isEmpty()) {
            return 0L;
        }
        int[] v = new int[FEATURE_BITS];
        for (String token : tokens) {
            long hash = murmurHash64(token);
            for (int i = 0; i < FEATURE_BITS; i++) {
                if (((hash >> i) & 1L) == 1L) {
                    v[i]++;
                } else {
                    v[i]--;
                }
            }
        }
        long fingerprint = 0L;
        for (int i = 0; i < FEATURE_BITS; i++) {
            if (v[i] > 0) {
                fingerprint |= (1L << i);
            }
        }
        return fingerprint;
    }

    /** Hamming distance between two 64-bit fingerprints. */
    public int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /**
     * Read fingerprint from Redis when present, otherwise compute and cache it.
     * Redis failures are swallowed - we simply recompute.
     */
    public long getOrCompute(String url, String text) {
        String key = Constants.CACHE_SIMHASH_PREFIX + Md5Util.md5Hex(url == null ? "" : url);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return Long.parseUnsignedLong(cached);
            }
        } catch (Exception ex) {
            log.debug("SimHash cache read failed for {}: {}", url, ex.toString());
        }

        long fp = compute(text);

        try {
            int days = Math.max(1, props.getRank().getSimhashCacheDays());
            redis.opsForValue().set(key, Long.toUnsignedString(fp), Duration.ofDays(days));
        } catch (Exception ex) {
            log.debug("SimHash cache write failed for {}: {}", url, ex.toString());
        }
        return fp;
    }

    /**
     * 64-bit MurmurHash-inspired mixing function.
     * Deterministic, fast, and good enough for SimHash feature hashing.
     */
    static long murmurHash64(String token) {
        // FNV-1a 64 as a lightweight deterministic hash.
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < token.length(); i++) {
            h ^= token.charAt(i);
            h *= 0x100000001b3L;
        }
        // Extra avalanche (Murmur3 fmix64) to spread low bits.
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}

package com.prismsearch.cache;

import com.prismsearch.common.Constants;
import com.prismsearch.config.PrismsearchProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Redis-backed hot-word management service.
 *
 * <p>Three Redis ZSets are used:
 * <ul>
 *   <li>{@code ps:v1:searchlog} — raw search frequency log (TTL = 7 days)</li>
 *   <li>{@code ps:v1:hotwords:auto} — auto-aggregated hot words (refreshed hourly)</li>
 *   <li>{@code ps:v1:hotwords:manual} — admin-curated hot words (persistent)</li>
 * </ul>
 *
 * <p>When Redis is unavailable the service degrades gracefully to the static
 * hot-word list defined in {@code application.yml}.
 */
@Service
public class HotWordService {

    private static final Logger log = LoggerFactory.getLogger(HotWordService.class);

    /** Default weight for manually added hot words. */
    private static final double MANUAL_DEFAULT_WEIGHT = 1000.0;

    /** Lua: atomic ZINCRBY + EXPIRE to avoid orphan keys. */
    private static final String LUA_ZINCRBY_EXPIRE =
            "redis.call('zincrby', KEYS[1], 1, ARGV[1])\n" +
            "redis.call('expire', KEYS[1], ARGV[2])\n" +
            "return 1";

    private final StringRedisTemplate redis;
    private final PrismsearchProperties props;
    private final MeterRegistry meters;
    private final ExecutorService providerExecutor;

    public HotWordService(StringRedisTemplate redis,
                          PrismsearchProperties props,
                          MeterRegistry meters,
                          ExecutorService providerExecutor) {
        this.redis = redis;
        this.props = props;
        this.meters = meters;
        this.providerExecutor = providerExecutor;
    }

    @PostConstruct
    void init() {
        log.info("HotWordService initialized (enabled={}, staticFallback={} words)",
                props.getSuggest().isEnabled(),
                props.getSuggest().getHotWords().size());
    }

    // ===== 搜索词记录 =====

    /**
     * Record a search query into the search-log ZSet. Called asynchronously
     * from the orchestrator after a successful search.
     */
    public void recordSearch(String query) {
        if (!props.getSuggest().isEnabled() || query == null || query.isBlank()) {
            return;
        }
        String word = query.trim().toLowerCase(Locale.ROOT);
        try {
            redis.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(LUA_ZINCRBY_EXPIRE, Long.class),
                    Collections.singletonList(Constants.SEARCHLOG_KEY),
                    word,
                    String.valueOf(props.getSuggest().getSearchlogTtlDays() * 86400)
            );
        } catch (Exception ex) {
            meters.counter("prismsearch.hotword.error", "op", "record").increment();
            log.debug("recordSearch failed for q={}: {}", word, ex.toString());
        }
    }

    // ===== 建议查询 =====

    /**
     * Return hot-word suggestions matching the given prefix.
     * Merges manual + auto ZSets (manual takes precedence on conflict).
     * Falls back to the static list when Redis is unavailable or disabled.
     */
    public List<String> suggest(String prefix, int limit) {
        if (!props.getSuggest().isEnabled()) {
            return staticSuggest(prefix, limit);
        }
        try {
            Map<String, Double> merged = new LinkedHashMap<>();

            // auto words first (lower priority)
            Set<ZSetOperations.TypedTuple<String>> autoSet =
                    redis.opsForZSet().reverseRangeWithScores(Constants.HOTWORD_AUTO_KEY, 0, -1);
            if (autoSet != null) {
                for (ZSetOperations.TypedTuple<String> t : autoSet) {
                    if (t.getValue() != null && t.getScore() != null) {
                        merged.put(t.getValue(), t.getScore());
                    }
                }
            }

            // manual words override
            Set<ZSetOperations.TypedTuple<String>> manualSet =
                    redis.opsForZSet().reverseRangeWithScores(Constants.HOTWORD_MANUAL_KEY, 0, -1);
            if (manualSet != null) {
                for (ZSetOperations.TypedTuple<String> t : manualSet) {
                    if (t.getValue() != null && t.getScore() != null) {
                        merged.put(t.getValue(), t.getScore());
                    }
                }
            }

            if (merged.isEmpty()) {
                return staticSuggest(prefix, limit);
            }

            String pfx = (prefix == null) ? "" : prefix.trim().toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            merged.entrySet().stream()
                    .filter(e -> pfx.isEmpty() || e.getKey().toLowerCase(Locale.ROOT).startsWith(pfx))
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .forEach(result::add);
            return result;
        } catch (Exception ex) {
            meters.counter("prismsearch.hotword.error", "op", "suggest").increment();
            log.debug("suggest failed, falling back to static: {}", ex.toString());
            return staticSuggest(prefix, limit);
        }
    }

    // ===== 定时聚合 =====

    /**
     * Hourly aggregation: promote top-N frequent search terms to the auto hot-word ZSet.
     */
    @Scheduled(cron = "${prismsearch.suggest.aggregate-cron:0 0 * * * ?}")
    public void aggregate() {
        if (!props.getSuggest().isEnabled()) {
            return;
        }
        try {
            int topN = props.getSuggest().getAggregateTopN();
            Set<ZSetOperations.TypedTuple<String>> top =
                    redis.opsForZSet().reverseRangeWithScores(Constants.SEARCHLOG_KEY, 0, topN - 1);

            if (top == null || top.isEmpty()) {
                log.info("Hot-word aggregation: searchlog is empty, skipping");
                return;
            }

            // Atomic replace: DEL + pipeline ZADD
            redis.delete(Constants.HOTWORD_AUTO_KEY);
            var pipe = redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                byte[] keyBytes = Constants.HOTWORD_AUTO_KEY.getBytes();
                for (ZSetOperations.TypedTuple<String> t : top) {
                    if (t.getValue() != null && t.getScore() != null) {
                        connection.zAdd(keyBytes, t.getScore(), t.getValue().getBytes());
                    }
                }
                return null;
            });

            log.info("Hot-word aggregation complete: promoted {} words from searchlog", top.size());
            meters.counter("prismsearch.hotword.aggregated", "count", String.valueOf(top.size())).increment();
        } catch (Exception ex) {
            meters.counter("prismsearch.hotword.error", "op", "aggregate").increment();
            log.warn("Hot-word aggregation failed: {}", ex.toString());
        }
    }

    // ===== Admin CRUD =====

    /**
     * Add a hot word to the manual set with the given weight.
     */
    public void addManual(String word, double weight) {
        if (word == null || word.isBlank()) {
            return;
        }
        try {
            redis.opsForZSet().add(Constants.HOTWORD_MANUAL_KEY,
                    word.trim().toLowerCase(Locale.ROOT), weight);
            log.info("Manual hot-word added: {} (weight={})", word, weight);
        } catch (Exception ex) {
            meters.counter("prismsearch.hotword.error", "op", "add_manual").increment();
            throw ex;
        }
    }

    /**
     * Remove a hot word from the manual set.
     */
    public void removeManual(String word) {
        if (word == null || word.isBlank()) {
            return;
        }
        try {
            redis.opsForZSet().remove(Constants.HOTWORD_MANUAL_KEY,
                    word.trim().toLowerCase(Locale.ROOT));
            log.info("Manual hot-word removed: {}", word);
        } catch (Exception ex) {
            meters.counter("prismsearch.hotword.error", "op", "remove_manual").increment();
            throw ex;
        }
    }

    /**
     * List all hot words (manual + auto merged, manual wins on conflict).
     */
    public Map<String, Double> listAll() {
        Map<String, Double> merged = new LinkedHashMap<>();
        try {
            Set<ZSetOperations.TypedTuple<String>> autoSet =
                    redis.opsForZSet().reverseRangeWithScores(Constants.HOTWORD_AUTO_KEY, 0, -1);
            if (autoSet != null) {
                for (ZSetOperations.TypedTuple<String> t : autoSet) {
                    if (t.getValue() != null && t.getScore() != null) {
                        merged.put(t.getValue(), t.getScore());
                    }
                }
            }
            Set<ZSetOperations.TypedTuple<String>> manualSet =
                    redis.opsForZSet().reverseRangeWithScores(Constants.HOTWORD_MANUAL_KEY, 0, -1);
            if (manualSet != null) {
                for (ZSetOperations.TypedTuple<String> t : manualSet) {
                    if (t.getValue() != null && t.getScore() != null) {
                        merged.put(t.getValue(), t.getScore());
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("listAll failed: {}", ex.toString());
        }
        return merged;
    }

    // ===== 静态降级 =====

    private List<String> staticSuggest(String prefix, int limit) {
        List<String> dict = props.getSuggest().getHotWords();
        if (dict == null || dict.isEmpty()) {
            return Collections.emptyList();
        }
        String pfx = (prefix == null) ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String w : dict) {
            if (w == null || w.isBlank()) continue;
            if (pfx.isEmpty() || w.toLowerCase(Locale.ROOT).startsWith(pfx)) {
                out.add(w);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }
}

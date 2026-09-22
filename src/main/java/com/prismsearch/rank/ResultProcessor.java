package com.prismsearch.rank;

import com.prismsearch.model.NormalizedResult;
import com.prismsearch.model.RawSearchResult;
import com.prismsearch.provider.SearchProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result processing pipeline: normalize -> dedup -> RRF rank.
 * Consumes the raw per-provider output and returns a fully-scored list.
 */
@Component
public class ResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(ResultProcessor.class);

    private final UrlNormalizer urlNormalizer;
    private final TextCleaner textCleaner;
    private final Deduplicator deduplicator;
    private final RRFRanker ranker;

    public ResultProcessor(UrlNormalizer urlNormalizer,
                           TextCleaner textCleaner,
                           Deduplicator deduplicator,
                           RRFRanker ranker) {
        this.urlNormalizer = urlNormalizer;
        this.textCleaner = textCleaner;
        this.deduplicator = deduplicator;
        this.ranker = ranker;
    }

    /** Boost multiplier for results matching the target language. */
    private static final double LANG_MATCH_BOOST = 1.5;
    /** Penalty multiplier for results NOT matching the target language. */
    private static final double LANG_MISMATCH_PENALTY = 0.8;

    public List<NormalizedResult> process(Map<String, List<RawSearchResult>> byProvider,
                                          List<SearchProvider> providers,
                                          String lang) {
        long t0 = System.nanoTime();

        // 1. Normalize into per-provider URL->rank maps (needed by RRF).
        List<NormalizedResult> all = new ArrayList<>();
        Map<String, Map<String, Integer>> rankByProviderUrl = new HashMap<>();

        for (Map.Entry<String, List<RawSearchResult>> e : byProvider.entrySet()) {
            String provider = e.getKey();
            Map<String, Integer> urlToRank = new HashMap<>();
            List<RawSearchResult> raws = e.getValue() == null ? List.of() : e.getValue();

            for (RawSearchResult raw : raws) {
                NormalizedResult n = normalize(provider, raw);
                if (n == null) {
                    continue;
                }
                all.add(n);
                // Record the best rank per (provider, normalized URL).
                urlToRank.merge(n.getUrl(), n.getBestRank(), Math::min);
            }
            rankByProviderUrl.put(provider, urlToRank);
        }

        // 2. Deduplicate (URL exact + SimHash near-dup).
        List<NormalizedResult> deduped = deduplicator.dedupe(all);

        // 3. RRF rank.
        List<NormalizedResult> ranked = ranker.rank(deduped, providers, rankByProviderUrl);

        // 4. Language boost (skip for "auto" or null).
        String targetLang = LanguageDetector.toLangCode(lang);
        if (targetLang != null) {
            applyLanguageBoost(ranked, targetLang);
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        log.debug("ResultProcessor normalized={} deduped={} ranked={} elapsedMs={}",
                all.size(), deduped.size(), ranked.size(), elapsedMs);
        return ranked;
    }

    /**
     * Adjust scores so that results matching the target language are boosted,
     * and non-matching results are gently penalized. Then re-sort.
     */
    private void applyLanguageBoost(List<NormalizedResult> ranked, String targetLang) {
        boolean anyChanged = false;
        for (NormalizedResult r : ranked) {
            String detected = LanguageDetector.detect(r.getTitle(), r.getSnippet());
            if ("unknown".equals(detected)) {
                continue;
            }
            if (detected.equals(targetLang)) {
                r.setScore(r.getScore() * LANG_MATCH_BOOST);
                anyChanged = true;
            } else {
                r.setScore(r.getScore() * LANG_MISMATCH_PENALTY);
                anyChanged = true;
            }
        }
        if (anyChanged) {
            ranked.sort((a, b) -> {
                int cmp = Double.compare(b.getScore(), a.getScore());
                if (cmp != 0) return cmp;
                return Integer.compare(a.getBestRank(), b.getBestRank());
            });
        }
    }

    /**
     * Convert one raw record into a normalized result, or return null when the URL is unusable.
     */
    private NormalizedResult normalize(String provider, RawSearchResult raw) {
        if (raw == null || raw.getUrl() == null || raw.getUrl().isBlank()) {
            return null;
        }
        String normalizedUrl = urlNormalizer.normalize(raw.getUrl());
        if (normalizedUrl == null || normalizedUrl.isBlank()) {
            return null;
        }
        NormalizedResult n = new NormalizedResult();
        n.setUrl(normalizedUrl);
        n.setTitle(textCleaner.clean(raw.getTitle()));
        n.setSnippet(textCleaner.clean(raw.getSnippet()));
        n.setPublishedAt(raw.getPublishedAt());
        n.addSource(provider);
        n.setSource(provider);
        n.setBestRank(raw.getRank() <= 0 ? Integer.MAX_VALUE : raw.getRank());
        return n;
    }
}

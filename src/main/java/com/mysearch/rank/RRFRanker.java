package com.mysearch.rank;

import com.mysearch.config.MysearchProperties;
import com.mysearch.model.NormalizedResult;
import com.mysearch.provider.SearchProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion per TECH_DESIGN.md section 2.8.3:
 * <pre>score(d) = SUM_i ( w_i / (k + rank_i(d)) )</pre>
 * Documents missing from a provider contribute 0 for that provider, so multi-hit documents
 * naturally score higher.
 */
@Component
public class RRFRanker {

    private final MysearchProperties props;

    public RRFRanker(MysearchProperties props) {
        this.props = props;
    }

    /**
     * Rank deduplicated results.
     *
     * @param results  results already normalized and de-duplicated. Each result carries
     *                 {@code sources} and {@code bestRank}; per-provider ranks are supplied
     *                 via {@code rankByProviderUrl} when available.
     * @param providers the enabled providers used to look up per-provider weights.
     * @param rankByProviderUrl map of provider name -> (normalized URL -> 1-based rank).
     */
    public List<NormalizedResult> rank(List<NormalizedResult> results,
                                       List<SearchProvider> providers,
                                       Map<String, Map<String, Integer>> rankByProviderUrl) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        int k = Math.max(1, props.getRank().getRrfK());

        Map<String, Double> weightByName = new HashMap<>();
        for (SearchProvider p : providers) {
            weightByName.put(p.name(), p.weight());
        }

        for (NormalizedResult r : results) {
            double score = 0.0;
            for (Map.Entry<String, Map<String, Integer>> e : rankByProviderUrl.entrySet()) {
                String provider = e.getKey();
                Integer rank = e.getValue().get(r.getUrl());
                if (rank == null) {
                    continue;
                }
                double w = weightByName.getOrDefault(provider, 1.0);
                score += w / (k + rank);
            }
            if (score == 0.0) {
                // Fallback: rank was not tracked (e.g. cached path). Use bestRank.
                int br = r.getBestRank() == Integer.MAX_VALUE ? 1 : r.getBestRank();
                score = 1.0 / (k + br);
            }
            r.setScore(score);
            // Primary source: pick the provider with the smallest rank among hits.
            String primary = null;
            int best = Integer.MAX_VALUE;
            for (Map.Entry<String, Map<String, Integer>> e : rankByProviderUrl.entrySet()) {
                Integer rank = e.getValue().get(r.getUrl());
                if (rank != null && rank < best) {
                    best = rank;
                    primary = e.getKey();
                }
            }
            if (primary != null) {
                r.setSource(primary);
            }
        }

        List<NormalizedResult> out = new ArrayList<>(results);
        out.sort(Comparator
                .comparingDouble(NormalizedResult::getScore).reversed()
                .thenComparingInt(NormalizedResult::getBestRank)
                .thenComparing(NormalizedResult::getUrl, Comparator.nullsLast(String::compareTo)));
        return out;
    }
}

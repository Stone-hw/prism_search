package com.mysearch.rank;

import com.mysearch.config.MysearchProperties;
import com.mysearch.model.NormalizedResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-layer deduplication:
 * Layer 1 - exact URL merge (URLs are already normalized by {@link UrlNormalizer}).
 * Layer 2 - SimHash hamming-distance merge for near-duplicates.
 */
@Component
public class Deduplicator {

    private static final Logger log = LoggerFactory.getLogger(Deduplicator.class);

    private final SimHasher simHasher;
    private final MysearchProperties props;

    public Deduplicator(SimHasher simHasher, MysearchProperties props) {
        this.simHasher = simHasher;
        this.props = props;
    }

    /**
     * @param results pre-normalized results (URL already normalized, title/snippet cleaned).
     * @return deduplicated list, stable order preserved by first-seen insertion.
     */
    public List<NormalizedResult> dedupe(List<NormalizedResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        // Layer 1: URL exact merge.
        Map<String, NormalizedResult> byUrl = new LinkedHashMap<>();
        for (NormalizedResult r : results) {
            String key = r.getUrl();
            NormalizedResult exist = byUrl.get(key);
            if (exist == null) {
                byUrl.put(key, r);
                continue;
            }
            merge(exist, r);
        }

        // Compute SimHash fingerprints after URL merging so we don't waste cycles on dropped dups.
        List<NormalizedResult> afterUrl = new ArrayList<>(byUrl.values());
        for (NormalizedResult r : afterUrl) {
            if (r.getSimhash() == 0L) {
                String text = (nullSafe(r.getTitle()) + " " + nullSafe(r.getSnippet())).trim();
                r.setSimhash(simHasher.getOrCompute(r.getUrl(), text));
            }
        }

        // Layer 2: SimHash near-duplicate merge.
        int threshold = Math.max(0, props.getRank().getSimhashThreshold());
        List<NormalizedResult> kept = new ArrayList<>(afterUrl.size());
        outer:
        for (NormalizedResult candidate : afterUrl) {
            for (int i = 0; i < kept.size(); i++) {
                NormalizedResult k = kept.get(i);
                if (k.getSimhash() == 0L || candidate.getSimhash() == 0L) {
                    continue;
                }
                int dist = simHasher.hammingDistance(k.getSimhash(), candidate.getSimhash());
                if (dist <= threshold) {
                    // Merge into whichever has the better (smaller) rank.
                    if (candidate.getBestRank() < k.getBestRank()) {
                        merge(candidate, k);
                        kept.set(i, candidate);
                    } else {
                        merge(k, candidate);
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("SimHash dedup merged dist={} keepUrl={} dropUrl={}",
                                dist, kept.get(i).getUrl(), candidate.getUrl());
                    }
                    continue outer;
                }
            }
            kept.add(candidate);
        }
        return kept;
    }

    /**
     * Merge {@code src} into {@code dst}: union sources, keep smaller rank, prefer longer snippet.
     */
    private void merge(NormalizedResult dst, NormalizedResult src) {
        if (src.getSources() != null) {
            src.getSources().forEach(dst::addSource);
        }
        if (src.getBestRank() < dst.getBestRank()) {
            dst.setBestRank(src.getBestRank());
            // Prefer the record whose title/snippet came from the better-ranked source.
            if (src.getTitle() != null && !src.getTitle().isBlank()) {
                dst.setTitle(src.getTitle());
            }
            if (src.getPublishedAt() != null && dst.getPublishedAt() == null) {
                dst.setPublishedAt(src.getPublishedAt());
            }
            // Update primary source when rank improved.
            if (src.getSource() != null) {
                dst.setSource(src.getSource());
            }
        }
        if (dst.getSnippet() == null || (src.getSnippet() != null && src.getSnippet().length() > dst.getSnippet().length())) {
            dst.setSnippet(src.getSnippet());
        }
        if (dst.getPublishedAt() == null && src.getPublishedAt() != null) {
            dst.setPublishedAt(src.getPublishedAt());
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}

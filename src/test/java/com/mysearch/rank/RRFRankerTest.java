package com.mysearch.rank;

import com.mysearch.config.MysearchProperties;
import com.mysearch.model.NormalizedResult;
import com.mysearch.model.RawSearchResult;
import com.mysearch.provider.SearchProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RRFRankerTest {

    private final MysearchProperties props = new MysearchProperties();
    private final RRFRanker ranker = new RRFRanker(props);

    @Test
    void multiHitUrlRanksHigher() {
        // Two providers, URL "a" hits both at rank 1, URL "b" only in one provider at rank 2.
        Map<String, Map<String, Integer>> rankMap = new HashMap<>();
        rankMap.put("google", Map.of("https://a", 1, "https://b", 2));
        rankMap.put("bing", Map.of("https://a", 1));

        List<NormalizedResult> results = new ArrayList<>();
        results.add(result("https://a", 1));
        results.add(result("https://b", 2));

        List<SearchProvider> providers = List.of(
                fakeProvider("google", 1.0),
                fakeProvider("bing", 1.0));

        List<NormalizedResult> ranked = ranker.rank(results, providers, rankMap);

        assertEquals("https://a", ranked.get(0).getUrl());
        assertTrue(ranked.get(0).getScore() > ranked.get(1).getScore(),
                "multi-hit URL must outsell single-hit URL");
    }

    @Test
    void providerWeightsAreApplied() {
        Map<String, Map<String, Integer>> rankMap = new HashMap<>();
        rankMap.put("google", Map.of("https://a", 1));
        rankMap.put("bing", Map.of("https://b", 1));

        List<NormalizedResult> results = new ArrayList<>();
        results.add(result("https://a", 1));
        results.add(result("https://b", 1));

        List<SearchProvider> providers = List.of(
                fakeProvider("google", 1.0),
                fakeProvider("bing", 0.5));

        List<NormalizedResult> ranked = ranker.rank(results, providers, rankMap);

        assertEquals("https://a", ranked.get(0).getUrl());
        assertEquals("https://b", ranked.get(1).getUrl());
        assertTrue(ranked.get(0).getScore() > ranked.get(1).getScore());
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        List<NormalizedResult> out = ranker.rank(List.of(), List.of(), Map.of());
        assertTrue(out.isEmpty());
    }

    @Test
    void tieBreakIsStable() {
        Map<String, Map<String, Integer>> rankMap = new HashMap<>();
        rankMap.put("google", new LinkedHashMap<>(Map.of("https://z", 1, "https://a", 2)));

        List<NormalizedResult> results = new ArrayList<>();
        results.add(result("https://z", 1));
        results.add(result("https://a", 2));

        List<SearchProvider> providers = List.of(fakeProvider("google", 1.0));
        List<NormalizedResult> ranked = ranker.rank(results, providers, rankMap);

        assertEquals("https://z", ranked.get(0).getUrl());
        assertEquals("https://a", ranked.get(1).getUrl());
    }

    @Test
    void kConstantIsConfigurable() {
        props.getRank().setRrfK(10);
        Map<String, Map<String, Integer>> rankMap = new HashMap<>();
        rankMap.put("google", Map.of("https://a", 1));

        List<NormalizedResult> results = new ArrayList<>();
        results.add(result("https://a", 1));

        List<SearchProvider> providers = List.of(fakeProvider("google", 1.0));
        List<NormalizedResult> ranked = ranker.rank(results, providers, rankMap);

        // 1.0 / (10 + 1) ~= 0.0909
        assertEquals(1.0 / 11.0, ranked.get(0).getScore(), 1e-6);
    }

    private static NormalizedResult result(String url, int rank) {
        NormalizedResult r = new NormalizedResult();
        r.setUrl(url);
        r.setBestRank(rank);
        r.setTitle(url);
        return r;
    }

    private static SearchProvider fakeProvider(String name, double weight) {
        return new SearchProvider() {
            @Override public String name() { return name; }
            @Override public boolean enabled() { return true; }
            @Override public double weight() { return weight; }
            @Override public long timeoutMs() { return 2500; }
            @Override public List<RawSearchResult> search(com.mysearch.model.SearchRequest request) {
                return List.of();
            }
        };
    }
}

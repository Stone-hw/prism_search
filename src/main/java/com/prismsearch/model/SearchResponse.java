package com.prismsearch.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response payload of {@code /api/search}.
 */
public class SearchResponse {

    private String query;
    private int total;
    private int page;
    private int size;
    private long elapsedMs;
    private boolean cached;
    private Map<String, ProviderStatus> providers = new LinkedHashMap<>();
    private List<NormalizedResult> results = new ArrayList<>();

    public SearchResponse() {
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public boolean isCached() {
        return cached;
    }

    public void setCached(boolean cached) {
        this.cached = cached;
    }

    public Map<String, ProviderStatus> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderStatus> providers) {
        this.providers = providers;
    }

    public List<NormalizedResult> getResults() {
        return results;
    }

    public void setResults(List<NormalizedResult> results) {
        this.results = results;
    }
}

package com.prismsearch.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Result after normalization, deduplication and RRF scoring.
 * This is what gets returned to the client and cached.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NormalizedResult {

    private String title;
    private String url;
    private String snippet;
    private double score;
    /** Primary source: the provider with the smallest rank among all hits. */
    private String source;
    /** All providers that surfaced this URL. */
    private Set<String> sources = new LinkedHashSet<>();
    private LocalDateTime publishedAt;

    /** Best (smallest) rank across all providers, used as tie-breaker. */
    @JsonIgnore
    private int bestRank = Integer.MAX_VALUE;

    /** 64-bit SimHash of {@code title + snippet}, used by the deduplicator. */
    @JsonIgnore
    private long simhash;

    public NormalizedResult() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Set<String> getSources() {
        return sources;
    }

    public void setSources(Set<String> sources) {
        this.sources = sources;
    }

    public void addSource(String provider) {
        if (provider != null) {
            this.sources.add(provider);
        }
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public int getBestRank() {
        return bestRank;
    }

    public void setBestRank(int bestRank) {
        this.bestRank = bestRank;
    }

    public long getSimhash() {
        return simhash;
    }

    public void setSimhash(long simhash) {
        this.simhash = simhash;
    }
}

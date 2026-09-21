package com.mysearch.model;

import java.time.LocalDateTime;

/**
 * Raw result produced by a single {@code SearchProvider} before normalization.
 */
public class RawSearchResult {

    private String provider;
    private int rank;
    private String title;
    private String url;
    private String snippet;
    private LocalDateTime publishedAt;
    private Double engineScore;

    public RawSearchResult() {
    }

    public static RawSearchResult of(String provider, int rank, String title, String url, String snippet) {
        RawSearchResult r = new RawSearchResult();
        r.provider = provider;
        r.rank = rank;
        r.title = title;
        r.url = url;
        r.snippet = snippet;
        return r;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
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

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Double getEngineScore() {
        return engineScore;
    }

    public void setEngineScore(Double engineScore) {
        this.engineScore = engineScore;
    }
}

package com.mysearch.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SearXNG provider settings.
 */
@ConfigurationProperties(prefix = "mysearch.providers.searxng")
public class SearxngProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:8888";
    private long timeoutMs = 2500;
    private double weight = 0.8;
    /** Max results to fetch per provider per page. */
    private int pageSize = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}

package com.prismsearch.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bing Web Search API v7 settings.
 */
@ConfigurationProperties(prefix = "prismsearch.providers.bing")
public class BingProperties {

    private boolean enabled = false;
    private String apiKey;
    private String endpoint = "https://api.bing.microsoft.com/v7.0/search";
    private long timeoutMs = 2500;
    private double weight = 0.9;
    private int pageSize = 10;
    /** Market code, e.g. zh-CN, en-US. */
    private String mkt = "zh-CN";

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
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

    public String getMkt() {
        return mkt;
    }

    public void setMkt(String mkt) {
        this.mkt = mkt;
    }
}

package com.prismsearch.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Custom Search JSON API settings.
 */
@ConfigurationProperties(prefix = "prismsearch.providers.google")
public class GoogleProperties {

    private boolean enabled = false;
    private String apiKey;
    private String cx;
    private String endpoint = "https://www.googleapis.com/customsearch/v1";
    private long timeoutMs = 2500;
    private double weight = 1.0;
    /** Google caps {@code num} at 10 per call. */
    private int pageSize = 10;

    public boolean isEnabled() {
        // Auto-disable when key material is missing.
        return enabled && apiKey != null && !apiKey.isBlank() && cx != null && !cx.isBlank();
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

    public String getCx() {
        return cx;
    }

    public void setCx(String cx) {
        this.cx = cx;
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
}

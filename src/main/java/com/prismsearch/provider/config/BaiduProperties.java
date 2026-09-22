package com.prismsearch.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Baidu AI Search API settings.
 */
@ConfigurationProperties(prefix = "prismsearch.providers.baidu")
public class BaiduProperties {

    private boolean enabled = false;
    private String apiKey;
    private String endpoint = "https://qianfan.baidubce.com/v2/ai_search/web_search";
    private long timeoutMs = 2500;
    private double weight = 1.0;
    private int pageSize = 10;

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
}

package com.prismsearch.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/**
 * Inbound search request DTO. Field defaults follow TECH_DESIGN.md section 2.3.1.
 */
public class SearchRequest {

    @NotBlank(message = "q 不能为空")
    @Size(max = 200, message = "q 长度不能超过 200")
    private String q;

    @Min(value = 1, message = "page 最小为 1")
    @Max(value = 20, message = "page 最大为 20")
    private int page = 1;

    @Min(value = 1, message = "size 最小为 1")
    @Max(value = 50, message = "size 最大为 50")
    private int size = 10;

    private String lang = "zh-CN";

    @Min(value = 0, message = "safesearch 取值范围 [0,2]")
    @Max(value = 2, message = "safesearch 取值范围 [0,2]")
    private int safesearch = 1;

    /** Comma separated provider whitelist; null or empty means all enabled. */
    private String providers;

    private boolean nocache;

    public SearchRequest() {
    }

    public String getQ() {
        return q;
    }

    public void setQ(String q) {
        this.q = q;
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

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public int getSafesearch() {
        return safesearch;
    }

    public void setSafesearch(int safesearch) {
        this.safesearch = safesearch;
    }

    public String getProviders() {
        return providers;
    }

    public void setProviders(String providers) {
        this.providers = providers;
    }

    public boolean isNocache() {
        return nocache;
    }

    public void setNocache(boolean nocache) {
        this.nocache = nocache;
    }

    /**
     * Stable string used as part of the cache key.
     */
    public String cacheKeySeed() {
        return String.join("|",
                Objects.toString(q, "").trim().toLowerCase(),
                Integer.toString(page),
                Integer.toString(size),
                Objects.toString(lang, ""),
                Integer.toString(safesearch),
                Objects.toString(providers, ""));
    }

    @Override
    public String toString() {
        return "SearchRequest{q='" + q + "', page=" + page + ", size=" + size
                + ", lang='" + lang + "', safesearch=" + safesearch
                + ", providers='" + providers + "', nocache=" + nocache + '}';
    }
}

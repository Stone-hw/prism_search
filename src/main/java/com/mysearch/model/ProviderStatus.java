package com.mysearch.model;

/**
 * Per-provider execution status embedded in {@link SearchResponse}.
 */
public class ProviderStatus {

    /** ok / timeout / error / skipped / circuit_open. */
    private String status;
    private long elapsedMs;
    private int count;
    private String message;

    public ProviderStatus() {
    }

    public ProviderStatus(String status, long elapsedMs, int count, String message) {
        this.status = status;
        this.elapsedMs = elapsedMs;
        this.count = count;
        this.message = message;
    }

    public static ProviderStatus ok(long elapsedMs, int count) {
        return new ProviderStatus("ok", elapsedMs, count, null);
    }

    public static ProviderStatus timeout(long elapsedMs) {
        return new ProviderStatus("timeout", elapsedMs, 0, "timeout");
    }

    public static ProviderStatus error(long elapsedMs, String message) {
        return new ProviderStatus("error", elapsedMs, 0, message);
    }

    public static ProviderStatus skipped(String reason) {
        return new ProviderStatus("skipped", 0, 0, reason);
    }

    public static ProviderStatus circuitOpen() {
        return new ProviderStatus("circuit_open", 0, 0, "circuit breaker open");
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

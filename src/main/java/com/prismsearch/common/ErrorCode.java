package com.prismsearch.common;

/**
 * Business error codes.
 */
public enum ErrorCode {

    PARAM_INVALID(400, "参数校验失败"),
    RATE_LIMITED(429, "请求过于频繁，请稍后再试"),
    INTERNAL_ERROR(500, "系统异常"),
    ALL_PROVIDERS_FAILED(502, "搜索服务暂不可用，请稍后重试");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}

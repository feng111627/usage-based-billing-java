package com.github.feng111627.usagebasedbillingjava.error;

/**
 * 所有可预期的业务失败统一抛出本异常，由 GlobalExceptionHandler 分类映射。
 */
public class BillingException extends RuntimeException {

    private final ErrorCode code;

    public BillingException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}

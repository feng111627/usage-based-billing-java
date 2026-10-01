package com.github.feng111627.usagebasedbillingjava.common;

/**
 * 统一业务异常，携带可区分的 {@link ErrorCode}。
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

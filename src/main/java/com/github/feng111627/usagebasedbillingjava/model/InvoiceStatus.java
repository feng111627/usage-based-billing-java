package com.github.feng111627.usagebasedbillingjava.model;

/**
 * ANOMALY(计量异常待人工确认) -> PENDING -> FAILED(重试中) -> PAST_DUE(宽限)
 * -> PAID；PAID -> REFUNDED。
 */
public enum InvoiceStatus {
    ANOMALY,
    PENDING,
    FAILED,
    PAST_DUE,
    PAID,
    REFUNDED,
    VOID
}

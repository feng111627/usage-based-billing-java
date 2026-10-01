package com.github.feng111627.usagebasedbillingjava.domain;

/**
 * 账单明细行。可负（调账/退款），amount 单位为分。
 */
public record BillLine(BillLineKind kind,
                       String metricKey,
                       String description,
                       long amount) {
}

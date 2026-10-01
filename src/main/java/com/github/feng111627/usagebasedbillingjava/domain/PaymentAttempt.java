package com.github.feng111627.usagebasedbillingjava.domain;

import java.time.Instant;

/**
 * 一次扣款/退款尝试记录。退款由 refundBill 单独产生负向尝试。
 */
public record PaymentAttempt(String reference,
                             String billId,
                             String subscriptionId,
                             long amountCents,
                             boolean refund,
                             PaymentResult result,
                             String failureReason,
                             Instant attemptedAt) {
}

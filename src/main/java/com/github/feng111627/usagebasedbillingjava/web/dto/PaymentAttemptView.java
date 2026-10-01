package com.github.feng111627.usagebasedbillingjava.web.dto;

import java.time.Instant;

public record PaymentAttemptView(String reference, String billId, long amountCents, boolean refund,
                                 String result, String failureReason, Instant attemptedAt) {
}

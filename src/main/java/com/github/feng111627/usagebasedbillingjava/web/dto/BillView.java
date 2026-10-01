package com.github.feng111627.usagebasedbillingjava.web.dto;

import java.time.Instant;
import java.util.List;

public record BillView(String id, String periodKey, String status, long amountCents,
                       Instant issuedAt, Instant dueAt, int attemptCount,
                       String invoiceGroupId, String adjustmentKey,
                       List<BillLineView> lines) {
}

package com.github.feng111627.usagebasedbillingjava.web.dto;

import java.time.Instant;

public record UsageView(String idempotencyKey, String metricKey, long quantity,
                        Instant windowStart, Instant windowEnd, boolean meteringComplete,
                        Instant recordedAt, boolean duplicate) {
}

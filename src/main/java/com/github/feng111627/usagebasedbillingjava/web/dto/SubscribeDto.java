package com.github.feng111627.usagebasedbillingjava.web.dto;

import java.time.Instant;

public record SubscribeDto(String subscriptionId, String customerId, String planId, Instant effectiveAt) {
}

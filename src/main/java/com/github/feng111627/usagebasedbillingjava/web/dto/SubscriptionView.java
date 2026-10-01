package com.github.feng111627.usagebasedbillingjava.web.dto;

import java.time.Instant;
import java.util.List;

public record SubscriptionView(String id, String customerId, String status, String currentPlanId,
                               Instant anchoredAt, List<EventView> events) {
}

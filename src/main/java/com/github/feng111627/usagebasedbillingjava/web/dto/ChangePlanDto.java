package com.github.feng111627.usagebasedbillingjava.web.dto;

import java.time.Instant;

public record ChangePlanDto(String newPlanId, Instant effectiveAt) {
}

package com.github.feng111627.usagebasedbillingjava.web.dto;

import java.util.List;

public record PlanDto(String id, String name, long basePriceCents, List<MetricDefDto> metrics) {
}

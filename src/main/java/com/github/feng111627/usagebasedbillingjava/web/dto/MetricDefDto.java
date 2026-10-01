package com.github.feng111627.usagebasedbillingjava.web.dto;

public record MetricDefDto(String key, long includedUnits, long overagePriceCents, String unit) {
}

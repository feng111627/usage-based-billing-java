package com.github.feng111627.usagebasedbillingjava.web.dto;

public record MetricUsageView(String metricKey, long totalQuantity, long includedUnits,
                              long overageUnits, long overageAmountCents,
                              boolean missingMetering, boolean overQuota) {
}

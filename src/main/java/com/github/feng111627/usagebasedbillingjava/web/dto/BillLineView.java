package com.github.feng111627.usagebasedbillingjava.web.dto;

public record BillLineView(String kind, String metricKey, String description, long amountCents) {
}

package com.github.feng111627.usagebasedbillingjava.web.dto;

import java.time.Instant;

public record EventView(long seq, String type, String planId,
                        Instant effectiveAt, Instant recordedAt) {
}

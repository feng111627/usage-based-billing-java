package com.github.feng111627.usagebasedbillingjava.web.dto;

import java.time.Instant;

public record ErrorDto(String code, String message, Instant at) {
}

package com.github.feng111627.usagebasedbillingjava.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 基于系统时间的默认时钟，固定使用 UTC 作为计费时区，保证跨月末判定一致。
 */
@Component
public class SystemBillingClock implements BillingClock {

    private static final ZoneId ZONE = ZoneId.of("UTC");

    @Override
    public ZoneId zone() {
        return ZONE;
    }

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public LocalDate today() {
        return LocalDate.now(ZONE);
    }
}

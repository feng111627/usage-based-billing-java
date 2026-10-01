package com.github.feng111627.usagebasedbillingjava.support;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 测试用可控时钟：固定 UTC，时间可手动推进。 */
public class FixedBillingClock implements BillingClock {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private Instant now;

    public FixedBillingClock(Instant now) {
        this.now = now;
    }

    public void set(Instant t) {
        this.now = t;
    }

    public void advanceSeconds(long seconds) {
        this.now = now.plusSeconds(seconds);
    }

    public void advanceDays(long days) {
        this.now = now.plusSeconds(days * 86400);
    }

    @Override
    public ZoneId zone() {
        return ZONE;
    }

    @Override
    public Instant now() {
        return now;
    }

    @Override
    public LocalDate today() {
        return LocalDate.ofInstant(now, ZONE);
    }
}

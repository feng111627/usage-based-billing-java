package com.github.feng111627.usagebasedbillingjava.support;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.SystemBillingClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试中用固定时钟替换系统时钟：起始 2026-09-01T00:00:00Z。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    @Bean
    @Primary
    public BillingClock fixedBillingClock() {
        return new FixedBillingClock(START);
    }
}

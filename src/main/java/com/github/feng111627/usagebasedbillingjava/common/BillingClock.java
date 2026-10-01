package com.github.feng111627.usagebasedbillingjava.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 可替换的计费时钟，便于测试中精确控制时间。
 */
public interface BillingClock {

    ZoneId zone();

    Instant now();

    LocalDate today();
}

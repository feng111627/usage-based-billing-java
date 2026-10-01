package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.common.Money;
import com.github.feng111627.usagebasedbillingjava.engine.Proration;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金额与按比例计价：验证全程整数、四舍五入、半月半价。
 */
class MoneyProrationTest {

    private static final Logger log = LoggerFactory.getLogger(MoneyProrationTest.class);

    @Test
    void prorateHalfPeriodRoundsToHalf() {
        Money monthly = Money.of(1000);
        Instant pStart = Instant.parse("2026-09-01T00:00:00Z");
        Instant pEnd = Instant.parse("2026-10-01T00:00:00Z");
        Instant mid = Instant.parse("2026-09-16T00:00:00Z"); // 15 天
        Money part = Proration.prorate(monthly, pStart, mid, pStart, pEnd);
        long expected = Math.round(1000.0 * (15.0 / 30.0));
        log.info("[case] input monthly=1000 cents segment=15d/30d -> prorated={} (basis: seconds ratio, round half up)",
                part.cents());
        assertEquals(expected, part.cents());
        assertTrue(part.cents() >= 499 && part.cents() <= 501);
    }

    @Test
    void fullPeriodIsFullPrice() {
        Money monthly = Money.of(1234);
        Instant pStart = Instant.parse("2026-09-01T00:00:00Z");
        Instant pEnd = Instant.parse("2026-10-01T00:00:00Z");
        Money part = Proration.prorate(monthly, pStart, pEnd, pStart, pEnd);
        log.info("[case] input full segment ratio=1 -> {} (basis: full period)", part.cents());
        assertEquals(1234, part.cents());
    }

    @Test
    void arithmeticStaysIntegralAndNegativeSupported() {
        Money a = Money.of(500);
        Money b = Money.of(200);
        log.info("[case] 500-200={} -(-300)={} (basis: exact long arithmetic)",
                a.minus(b).cents(), b.minus(a).negate().cents());
        assertEquals(300, a.minus(b).cents());
        assertEquals(-300, b.minus(a).cents());
    }
}

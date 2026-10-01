package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.service.impl.BillingCalendarImpl;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨月末/闰年的周期边界与比例纯单测，不依赖 Spring。
 */
class BillingCalendarTest {

    private static final Logger log = LoggerFactory.getLogger(BillingCalendarTest.class);
    private final BillingCalendarImpl cal = new BillingCalendarImpl();

    @Test
    void periodBoundariesAcrossMonthEnds() {
        // anchor=15：1 月 1 日落在 2025-12-15 ~ 2026-01-15
        Instant jan1 = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(Instant.parse("2025-12-15T00:00:00Z"), cal.periodStart(jan1, 15));
        assertEquals(Instant.parse("2026-01-15T00:00:00Z"), cal.periodEnd(jan1, 15));

        // 2 月短月：anchor=15 时 2 月周期 2026-02-15 ~ 2026-03-15（28 天，比例分母自动反映）
        Instant feb20 = Instant.parse("2026-02-20T00:00:00Z");
        Instant s = cal.periodStart(feb20, 15);
        Instant e = cal.periodEnd(feb20, 15);
        assertEquals(Instant.parse("2026-02-15T00:00:00Z"), s);
        assertEquals(Instant.parse("2026-03-15T00:00:00Z"), e);

        BigDecimal ratioAtStart = cal.remainingRatio(s, s, e);
        BigDecimal ratioAtEnd = cal.remainingRatio(e, s, e);
        assertEquals(0, BigDecimal.ONE.compareTo(ratioAtStart), "周期起点剩余比例应为 1");
        assertEquals(0, BigDecimal.ZERO.compareTo(ratioAtEnd), "周期终点剩余比例应为 0");

        // 周期中点比例可手算（2 月 15 天/28 天）
        Instant mid = Instant.parse("2026-03-01T00:00:00Z");
        BigDecimal midRatio = cal.remainingRatio(mid, s, e);
        BigDecimal expected = new BigDecimal("14").divide(new BigDecimal("28"), 6, RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(midRatio), "3/1 距 3/15 14 天，比例 14/28，实际 " + midRatio);
        log.info("[calendar] 输入：anchor=15, t=2026-02-20；判定依据：周期 {} ~ {}（28 天跨月末），3/1 剩余比例 {}",
                s, e, midRatio);
    }

    @Test
    void invalidAnchorRejected() {
        assertThrows(Exception.class, () -> cal.periodStart(Instant.now(), 0));
        assertThrows(Exception.class, () -> cal.periodStart(Instant.now(), 31));
    }
}

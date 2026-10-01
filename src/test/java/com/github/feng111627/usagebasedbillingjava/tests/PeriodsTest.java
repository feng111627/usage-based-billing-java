package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.engine.Periods;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 账期：月末锚定（31 日开通遇到短月落在月末，次月回到 31），跨月末确定。
 */
class PeriodsTest {

    private static final Logger log = LoggerFactory.getLogger(PeriodsTest.class);
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void anchorOn31stHandlesShortMonths() {
        Instant anchor = Instant.parse("2026-01-31T10:00:00Z");
        Instant p1 = Periods.periodStart(anchor, 1, UTC); // 2 月
        Instant p2 = Periods.periodStart(anchor, 2, UTC); // 3 月
        log.info("[case] anchor=2026-01-31 -> period1 start={} period2 start={} "
                        + "(basis: min(anchorDay, month length), next month back to 31st)",
                p1, p2);
        assertEquals(Instant.parse("2026-02-28T00:00:00Z"), p1);
        assertEquals(Instant.parse("2026-03-31T00:00:00Z"), p2);
    }

    @Test
    void periodIndexLocatesAcrossMonthEnd() {
        Instant anchor = Instant.parse("2026-09-15T00:00:00Z");
        int idxBefore = Periods.periodIndex(anchor, Instant.parse("2026-09-30T23:00:00Z"), UTC);
        int idxAfter = Periods.periodIndex(anchor, Instant.parse("2026-10-15T00:00:00Z"), UTC);
        log.info("[case] anchor=09-15 at 09-30 -> period {}; at 10-15 boundary -> period {} "
                        + "(basis: half-open [start,end))",
                idxBefore, idxAfter);
        assertEquals(0, idxBefore);
        assertEquals(1, idxAfter);
    }
}

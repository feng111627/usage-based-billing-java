package com.github.feng111627.usagebasedbillingjava.service;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 周期日历：以订阅锚点日（anchor day）切分月度周期，负责跨月末周期边界与比例计算。
 */
public interface BillingCalendar {

    /** 返回 t 所在周期的起点（锚点日 00:00 UTC）。 */
    Instant periodStart(Instant t, int anchorDay);

    /** 返回 t 所在周期的终点（下一锚点日，不含）。 */
    Instant periodEnd(Instant t, int anchorDay);

    /** 周期键，如 2026-09。 */
    String periodKey(Instant t, int anchorDay);

    /** 从 from 推进到下一个周期起点。 */
    Instant nextPeriodStart(Instant from, int anchorDay);

    /**
     * 剩余周期比例 = (periodEnd - effectiveAt) / (periodEnd - periodStart)，
     * 保留 6 位小数（ROUND_HALF_UP），跨月末的周期也由此统一处理。
     */
    java.math.BigDecimal remainingRatio(Instant effectiveAt, Instant periodStart, Instant periodEnd);

    ZoneOffset zone();
}

package com.github.feng111627.usagebasedbillingjava.engine;

import com.github.feng111627.usagebasedbillingjava.common.Money;

import java.time.Duration;
import java.time.Instant;

/**
 * 按剩余周期比例计价工具：账段基础费 = 月费 × 账段秒数 / 账期秒数（四舍五入到分）。
 *
 * <p>各账段独立舍入可能产生最多 N 分的舍入差，由 BillingEngine 增加一行 ROUNDING 找平，
 * 保证“账段之和”与账单总额严格一致，便于对账解释。</p>
 */
public final class Proration {

    private Proration() {
    }

    public static Money prorate(Money fullPeriodPrice,
                                Instant segmentStart,
                                Instant segmentEnd,
                                Instant periodStart,
                                Instant periodEndExclusive) {
        long segmentSeconds = Duration.between(segmentStart, segmentEnd).getSeconds();
        long periodSeconds = Duration.between(periodStart, periodEndExclusive).getSeconds();
        if (periodSeconds <= 0) {
            throw new IllegalArgumentException("period duration must be positive");
        }
        return fullPeriodPrice.prorate(segmentSeconds, periodSeconds);
    }
}

package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.model.ChangeType;
import com.github.feng111627.usagebasedbillingjava.model.PlanChange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 暂停窗口推导：由订阅的 PAUSE/RESUME 变更序列（seq 全序）确定性地计算
 * 任意时刻是否处于客户主动暂停，以及任一周期内处于活跃状态的时长比例。
 * 纯函数、无副作用，供用量上报、周期出账、对账共用同一口径。
 *
 * <p>口径约定：PAUSE 的 effectiveAt 起进入暂停（含该时刻），RESUME 的 effectiveAt
 * 起恢复活跃（含该时刻）；同一订阅的变更按 seq 单调递增排列即得唯一时间线。
 */
public final class PauseWindows {

    private PauseWindows() {
    }

    /** 判断 at 时刻订阅是否处于客户主动暂停（PAUSE 生效起至下一条 RESUME 生效前）。 */
    public static boolean isPausedAt(Instant at, List<PlanChange> changes) {
        boolean paused = false;
        for (PlanChange c : sorted(changes)) {
            // effectiveAt <= at 的最后一条 PAUSE/RESUME 决定 at 时刻状态
            if (!c.getEffectiveAt().isAfter(at)) {
                paused = c.getType() == ChangeType.PAUSE;
            } else {
                break;
            }
        }
        return paused;
    }

    /**
     * 周期 [periodStart, periodEnd) 内活跃时长比例 = 活跃秒数 / 周期总秒数，
     * 保留 6 位小数（HALF_UP），与 BillingCalendar.remainingRatio 口径一致。
     * 完全无暂停的周期返回 1.000000，配额折算退化为原配额，不影响既有流程。
     */
    public static BigDecimal activeRatio(Instant periodStart, Instant periodEnd, List<PlanChange> changes) {
        long total = Duration.between(periodStart, periodEnd).getSeconds();
        if (total <= 0) {
            throw new IllegalArgumentException("周期长度非法: " + periodStart + " ~ " + periodEnd);
        }
        boolean paused = isPausedAt(periodStart, changes);
        long active = 0;
        Instant cursor = periodStart;
        for (PlanChange c : sorted(changes)) {
            Instant eff = c.getEffectiveAt();
            if (!eff.isAfter(periodStart) || !eff.isBefore(periodEnd)) {
                continue; // 只关心落在 (periodStart, periodEnd) 内的状态切换
            }
            if (!paused) {
                active += Duration.between(cursor, eff).getSeconds();
            }
            paused = c.getType() == ChangeType.PAUSE;
            cursor = eff;
        }
        if (!paused) {
            active += Duration.between(cursor, periodEnd).getSeconds();
        }
        return BigDecimal.valueOf(active).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    /** 仅取 PAUSE/RESUME 变更并按 seq 升序（seq 即全序）。 */
    private static List<PlanChange> sorted(List<PlanChange> changes) {
        return changes.stream()
                .filter(c -> c.getType() == ChangeType.PAUSE || c.getType() == ChangeType.RESUME)
                .sorted(Comparator.comparingLong(PlanChange::getSeq))
                .toList();
    }
}

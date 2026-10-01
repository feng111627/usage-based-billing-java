package com.github.feng111627.usagebasedbillingjava.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * 账期计算：以订阅 anchoredAt 的“日”为锚点的月度账期。
 *
 * <p>锚日大于短月天数时（如 31 日开通），该月账期边界落在月末；下个月仍回到锚日，
 * 因此跨月末结算确定且可解释，不存在“漂移的账期日”。</p>
 */
public final class Periods {

    private Periods() {
    }

    /** 开通日期（UTC）。 */
    public static LocalDate anchorDate(Instant anchoredAt, ZoneId zone) {
        return LocalDate.ofInstant(anchoredAt, zone);
    }

    /** 计算第 n 个账期（n 从 0 开始）的起点。 */
    public static Instant periodStart(Instant anchoredAt, int n, ZoneId zone) {
        if (n < 0) {
            throw new IllegalArgumentException("period index must be >= 0: " + n);
        }
        LocalDate anchor = LocalDate.ofInstant(anchoredAt, zone);
        LocalDate start = boundaryOf(anchor, n);
        return start.atStartOfDay(zone).toInstant();
    }

    /** 计算第 n 个账期的终点（不含），即第 n+1 个账期起点。 */
    public static Instant periodEndExclusive(Instant anchoredAt, int n, ZoneId zone) {
        return periodStart(anchoredAt, n + 1, zone);
    }

    private static LocalDate boundaryOf(LocalDate anchor, int n) {
        YearMonth ym = YearMonth.from(anchor).plusMonths(n);
        int day = Math.min(anchor.getDayOfMonth(), ym.lengthOfMonth());
        return LocalDate.of(ym.getYear(), ym.getMonth(), day);
    }

    /**
     * at 时刻所属账期序号。用二分在合理范围内查找，避免硬编码月份数。
     */
    public static int periodIndex(Instant anchoredAt, Instant at, ZoneId zone) {
        LocalDate anchor = LocalDate.ofInstant(anchoredAt, zone);
        YearMonth atYm = YearMonth.from(LocalDate.ofInstant(at, zone));
        int rough = (atYm.getYear() - YearMonth.from(anchor).getYear()) * 12
                + atYm.getMonthValue() - YearMonth.from(anchor).getMonthValue();
        // rough 可能因锚日靠后而多算 1，向下校正
        for (int n = Math.max(0, rough - 1); n <= rough; n++) {
            Instant s = periodStart(anchoredAt, n, zone);
            Instant e = periodEndExclusive(anchoredAt, n, zone);
            if (!at.isBefore(s) && at.isBefore(e)) {
                return n;
            }
        }
        throw new IllegalStateException("cannot locate period for " + at + " anchored at " + anchoredAt);
    }

    /** 账期键，如 2026-09（按账期起点所在年月）。 */
    public static String periodKey(Instant periodStart, ZoneId zone) {
        return YearMonth.from(LocalDate.ofInstant(periodStart, zone)).toString();
    }
}

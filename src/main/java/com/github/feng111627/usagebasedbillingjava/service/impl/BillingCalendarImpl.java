package com.github.feng111627.usagebasedbillingjava.service.impl;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.service.BillingCalendar;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 月度周期切分。锚点日只允许 1-28，保证每个月都有该日，跨月末/闰年的结论统一确定。
 * 周期 = [锚点日 00:00 UTC, 下一锚点日 00:00 UTC)，故 1 月有 31 天、2 月 28/29 天
 * 都自然反映在比例分母里，无需特殊处理。
 */
@Service
public class BillingCalendarImpl implements BillingCalendar {

    @Override
    public Instant periodStart(Instant t, int anchorDay) {
        validate(anchorDay);
        LocalDate today = LocalDate.ofInstant(t, ZoneOffset.UTC);
        LocalDate thisMonthAnchor = today.withDayOfMonth(anchorDay);
        LocalDate start = today.compareTo(thisMonthAnchor) >= 0
                ? thisMonthAnchor
                : thisMonthAnchor.minusMonths(1);
        return start.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Override
    public Instant periodEnd(Instant t, int anchorDay) {
        validate(anchorDay);
        return LocalDate.ofInstant(periodStart(t, anchorDay), ZoneOffset.UTC)
                .plusMonths(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    @Override
    public String periodKey(Instant t, int anchorDay) {
        LocalDate start = LocalDate.ofInstant(periodStart(t, anchorDay), ZoneOffset.UTC);
        return start.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    @Override
    public Instant nextPeriodStart(Instant from, int anchorDay) {
        validate(anchorDay);
        LocalDate start = LocalDate.ofInstant(periodStart(from, anchorDay), ZoneOffset.UTC);
        return start.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Override
    public BigDecimal remainingRatio(Instant effectiveAt, Instant periodStart, Instant periodEnd) {
        long total = java.time.Duration.between(periodStart, periodEnd).getSeconds();
        long remaining = java.time.Duration.between(effectiveAt, periodEnd).getSeconds();
        if (total <= 0) {
            throw new BillingException(ErrorCode.VALIDATION, "周期长度非法: " + periodStart + " ~ " + periodEnd);
        }
        if (remaining < 0) {
            remaining = 0;
        }
        if (remaining > total) {
            remaining = total;
        }
        return BigDecimal.valueOf(remaining)
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    @Override
    public ZoneOffset zone() {
        return ZoneOffset.UTC;
    }

    private void validate(int anchorDay) {
        if (anchorDay < 1 || anchorDay > 28) {
            throw new BillingException(ErrorCode.VALIDATION,
                    "anchorDay 必须在 1-28 之间（避免月末日期缺失导致周期不确定），实际: " + anchorDay);
        }
    }
}

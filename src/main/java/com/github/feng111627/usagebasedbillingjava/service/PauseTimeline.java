package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.model.PauseAction;
import com.github.feng111627.usagebasedbillingjava.model.PauseRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 暂停账段（[start,end) 半开半闭，end 为 null 表示仍在暂停）与活跃时长的纯计算。
 * 供暂停/恢复结算、出账配额折算、用量上报判定共用，保证口径唯一、可手算、可对账。
 */
public final class PauseTimeline {

    private PauseTimeline() {
    }

    /** 一条暂停账段。 */
    public record Segment(Instant start, Instant end) {
        /** t 落在 [start,end)；end 为 null 时表示暂停持续至今。 */
        public boolean contains(Instant t) {
            return !t.isBefore(start) && (end == null || t.isBefore(end));
        }
    }

    /**
     * 由按 seq 升序的 PAUSE/RESUME 记录构造暂停账段。
     * 没有匹配 RESUME 的尾部 PAUSE（跨周期仍暂停）以 openEnd 收口，
     * 这样与固定周期做交集时口径确定。
     */
    public static List<Segment> segments(List<PauseRecord> records, Instant openEnd) {
        List<Segment> result = new ArrayList<>();
        Instant openStart = null;
        for (PauseRecord r : records) {
            if (r.getAction() == PauseAction.PAUSE && openStart == null) {
                openStart = r.getEffectiveAt();
            } else if (r.getAction() == PauseAction.RESUME && openStart != null) {
                result.add(new Segment(openStart, r.getEffectiveAt()));
                openStart = null;
            }
        }
        if (openStart != null) {
            result.add(new Segment(openStart, openEnd));
        }
        return result;
    }

    /**
     * 给定周期内真正处于活跃（未暂停）的秒数
     * = 周期长度 − 各暂停账段与 [periodStart,periodEnd) 交集的总秒数。
     */
    public static long activeSeconds(List<Segment> segments, Instant periodStart, Instant periodEnd) {
        long total = Duration.between(periodStart, periodEnd).getSeconds();
        long paused = 0;
        for (Segment seg : segments) {
            Instant s = seg.start().isBefore(periodStart) ? periodStart : seg.start();
            Instant e = seg.end() == null || seg.end().isAfter(periodEnd) ? periodEnd : seg.end();
            if (e.isAfter(s)) {
                paused += Duration.between(s, e).getSeconds();
            }
        }
        long active = total - paused;
        return Math.max(0L, active);
    }

    /** 给定时刻是否落在任一暂停账段内（含仍未恢复的尾部账段）。 */
    public static boolean isPausedAt(List<Segment> segments, Instant t) {
        return segments.stream().anyMatch(seg -> seg.contains(t));
    }
}

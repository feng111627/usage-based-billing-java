package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.domain.ChangeType;
import com.github.feng111627.usagebasedbillingjava.domain.Subscription;
import com.github.feng111627.usagebasedbillingjava.domain.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.engine.PlanSegment;
import com.github.feng111627.usagebasedbillingjava.engine.Timeline;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间线投影：同刻多次变更按 seq 折叠为一个边界；乱序到达（晚收先生效时刻）投影确定。
 */
class TimelineTest {

    private static final Logger log = LoggerFactory.getLogger(TimelineTest.class);

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant PE = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void multipleChangesAtSameInstantProduceSingleBoundary() {
        Subscription s = new Subscription("sub", "cust", T0);
        s.appendEvent(ChangeType.ACTIVATED, "basic", T0, T0);
        // 同一时刻两次升降配：最终以 seq 更大者为准，且只形成一个账段边界
        s.appendEvent(ChangeType.PLAN_CHANGED, "pro", T1, T1);
        s.appendEvent(ChangeType.PLAN_CHANGED, "enterprise", T1, T1.plusSeconds(1));
        List<PlanSegment> segments = Timeline.activeSegments(s, T0, PE);
        log.info("[case] two PLAN_CHANGED at same instant -> segments={} plans={} "
                        + "(basis: same-instant events fold by seq into one boundary)",
                segments.size(), segments.stream().map(PlanSegment::planId).toList());
        assertEquals(2, segments.size());
        assertEquals("basic", segments.get(0).planId());
        assertEquals("enterprise", segments.get(1).planId());
        assertEquals(T1, segments.get(1).start());
    }

    @Test
    void outOfOrderArrivalProjectsByEffectiveAt() {
        Subscription s = new Subscription("sub", "cust", T0);
        s.appendEvent(ChangeType.ACTIVATED, "basic", T0, T0);
        // 先收 T2 的变更，再“迟到”收 T1 的变更
        s.appendEvent(ChangeType.PLAN_CHANGED, "pro", T2, T2);
        s.appendEvent(ChangeType.PLAN_CHANGED, "enterprise", T1, T2.plusSeconds(10));
        log.info("[case] events arrived out of order: effectiveAt T2 received before T1; "
                        + "plan at T1+1d={} at T2+1d={} (basis: projection sorts by effectiveAt then seq)",
                Timeline.planIdAt(s, T1.plusSeconds(86400)), Timeline.planIdAt(s, T2.plusSeconds(86400)));
        assertEquals("enterprise", Timeline.planIdAt(s, T1.plusSeconds(86400)));
        assertEquals("pro", Timeline.planIdAt(s, T2.plusSeconds(86400)));
    }

    @Test
    void cancelAndSuspendProduceNoActiveSegmentsAfterwards() {
        Subscription s = new Subscription("sub", "cust", T0);
        s.appendEvent(ChangeType.ACTIVATED, "basic", T0, T0);
        s.appendEvent(ChangeType.SUSPENDED, null, T1, T1);
        s.appendEvent(ChangeType.RESUMED, "basic", T1.plusSeconds(86400), T1.plusSeconds(86400));
        s.appendEvent(ChangeType.CANCELLED, null, T2, T2);
        List<PlanSegment> segments = Timeline.activeSegments(s, T0, PE);
        log.info("[case] suspend/resume/cancel -> {} active segments, status at period end={} "
                        + "(basis: non-ACTIVE ranges excluded from billable segments)",
                segments.size(), Timeline.statusAt(s, PE));
        assertEquals(2, segments.size());
        assertFalse(Timeline.isActiveAt(s, T1.plusSeconds(3600)));
        assertTrue(Timeline.isActiveAt(s, T1.plusSeconds(2 * 86400)));
        assertEquals(SubscriptionStatus.CANCELLED, Timeline.statusAt(s, PE));
    }
}

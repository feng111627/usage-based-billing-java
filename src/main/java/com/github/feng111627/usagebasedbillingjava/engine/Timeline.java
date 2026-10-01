package com.github.feng111627.usagebasedbillingjava.engine;

import com.github.feng111627.usagebasedbillingjava.domain.ChangeType;
import com.github.feng111627.usagebasedbillingjava.domain.Subscription;
import com.github.feng111627.usagebasedbillingjava.domain.SubscriptionEvent;
import com.github.feng111627.usagebasedbillingjava.domain.SubscriptionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 订阅时间线投影：从只追加事件流计算任意时刻状态与账期内账段。
 *
 * <p>折叠状态机（按 (effectiveAt, seq) 排序后逐条应用）：</p>
 * <pre>
 *   (未开通) --ACTIVATED(plan)--&gt; ACTIVE
 *   ACTIVE   --PLAN_CHANGED(p)--&gt; ACTIVE(plan=p)
 *   ACTIVE   --SUSPENDED--&gt; SUSPENDED
 *   SUSPENDED--RESUMED----&gt; ACTIVE
 *   任意已开通 --CANCELLED--&gt; CANCELLED（终态）
 * </pre>
 * 同一 effectiveAt 的事件按接收 seq 先后生效，因此同一时刻多次升降配结果确定：
 * 最终以最后一条为准，中间过程不产生费用残留。
 */
public final class Timeline {

    private Timeline() {
    }

    private static final class State {
        SubscriptionStatus status = null; // null = 尚未开通
        String planId = null;
    }

    private static State foldAt(Subscription subscription, Instant at) {
        State state = new State();
        for (SubscriptionEvent e : subscription.orderedEvents()) {
            if (e.effectiveAt().isAfter(at)) {
                break;
            }
            apply(state, e);
        }
        return state;
    }

    private static void apply(State state, SubscriptionEvent e) {
        switch (e.type()) {
            case ACTIVATED -> {
                state.status = SubscriptionStatus.ACTIVE;
                state.planId = e.planId();
            }
            case PLAN_CHANGED -> state.planId = e.planId();
            case SUSPENDED -> state.status = SubscriptionStatus.SUSPENDED;
            case RESUMED -> state.status = SubscriptionStatus.ACTIVE;
            case CANCELLED -> state.status = SubscriptionStatus.CANCELLED;
        }
    }

    /** 指定时刻订阅是否处于 ACTIVE（未开通/暂停/退订均为 false）。 */
    public static boolean isActiveAt(Subscription subscription, Instant at) {
        return foldAt(subscription, at).status == SubscriptionStatus.ACTIVE;
    }

    /** 指定时刻的订阅状态（未开通返回 null）。 */
    public static SubscriptionStatus statusAt(Subscription subscription, Instant at) {
        return foldAt(subscription, at).status;
    }

    /** 指定时刻生效套餐；未开通时为 null。 */
    public static String planIdAt(Subscription subscription, Instant at) {
        return foldAt(subscription, at).planId;
    }

    /** 最新（当前）状态。 */
    public static SubscriptionStatus currentStatus(Subscription subscription) {
        List<SubscriptionEvent> ordered = subscription.orderedEvents();
        if (ordered.isEmpty()) {
            return null;
        }
        return foldAt(subscription, ordered.get(ordered.size() - 1).effectiveAt()).status;
    }

    /**
     * 切分账期 [periodStart, periodEnd) 内的账段：每个账段内 active 状态与套餐恒定。
     * 只返回 ACTIVE 账段（暂停/退订之后的时段不产生基础费）。
     *
     * <p>边界取“落在账期内的事件生效时刻”，同一时刻多条事件只形成一个边界，
     * 账段属性取该时刻所有事件折叠后的最终状态，所以同刻升降配不会重复计价。</p>
     */
    public static List<PlanSegment> activeSegments(Subscription subscription,
                                                   Instant periodStart,
                                                   Instant periodEndExclusive) {
        List<SubscriptionEvent> events = subscription.orderedEvents();

        // 收集账期内的事件边界（去重，保持升序）
        List<Instant> boundaries = new ArrayList<>();
        for (SubscriptionEvent e : events) {
            Instant t = e.effectiveAt();
            if (!t.isBefore(periodStart) && t.isBefore(periodEndExclusive)
                    && (boundaries.isEmpty() || !boundaries.get(boundaries.size() - 1).equals(t))) {
                boundaries.add(t);
            }
        }

        List<PlanSegment> result = new ArrayList<>();
        Instant segStart = periodStart;
        // 账段属性取 segStart 时刻（含该时刻事件）的折叠状态
        for (Instant boundary : boundaries) {
            addSegmentIfActive(result, subscription, segStart, boundary);
            segStart = boundary;
        }
        addSegmentIfActive(result, subscription, segStart, periodEndExclusive);
        return result;
    }

    private static void addSegmentIfActive(List<PlanSegment> out, Subscription subscription,
                                           Instant start, Instant end) {
        if (!end.isAfter(start)) {
            return;
        }
        State state = foldAt(subscription, start);
        if (state.status == SubscriptionStatus.ACTIVE && state.planId != null) {
            out.add(new PlanSegment(state.planId, start, end, true));
        }
    }
}

package com.github.feng111627.usagebasedbillingjava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 订阅聚合：一个订阅对应一条只追加的生命周期事件流，另有账期锚点。
 * 状态、各时刻生效套餐全部由事件流投影计算，不单独保存“中间态”，
 * 因此同一时刻多次变更、乱序到达都能得到确定结论。
 *
 * <p>事件以接收顺序分配单调递增 seq；投影排序键为 (effectiveAt, seq)，
 * 即先按业务生效时间排，同一时刻按接收先后排。</p>
 */
public class Subscription {

    public static final Comparator<SubscriptionEvent> EVENT_ORDER =
            Comparator.comparing(SubscriptionEvent::effectiveAt)
                    .thenComparingLong(SubscriptionEvent::seq);

    private final String id;
    private final String customerId;
    private final Instant anchoredAt;
    private final List<SubscriptionEvent> events = new ArrayList<>();

    public Subscription(String id, String customerId, Instant anchoredAt) {
        this.id = id;
        this.customerId = customerId;
        this.anchoredAt = anchoredAt;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Instant getAnchoredAt() {
        return anchoredAt;
    }

    public List<SubscriptionEvent> getEvents() {
        return events;
    }

    /** 按接收顺序追加事件并分配 seq。 */
    public SubscriptionEvent appendEvent(ChangeType type, String planId,
                                         Instant effectiveAt, Instant recordedAt) {
        SubscriptionEvent event = new SubscriptionEvent(
                events.size(), id, type, planId, effectiveAt, recordedAt);
        events.add(event);
        return event;
    }

    /** 投影用的有序事件视图 (effectiveAt, seq)。 */
    public List<SubscriptionEvent> orderedEvents() {
        List<SubscriptionEvent> sorted = new ArrayList<>(events);
        sorted.sort(EVENT_ORDER);
        return sorted;
    }

    /** 最后一条（投影顺序）事件的生效时间；空流返回 null。 */
    public Instant lastEffectiveAt() {
        List<SubscriptionEvent> ordered = orderedEvents();
        return ordered.isEmpty() ? null : ordered.get(ordered.size() - 1).effectiveAt();
    }
}

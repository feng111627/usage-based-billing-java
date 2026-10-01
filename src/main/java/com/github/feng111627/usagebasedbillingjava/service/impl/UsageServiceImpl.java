package com.github.feng111627.usagebasedbillingjava.service.impl;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.Subscription;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.model.UsageAccumulation;
import com.github.feng111627.usagebasedbillingjava.model.UsageEvent;
import com.github.feng111627.usagebasedbillingjava.repository.Repositories;
import com.github.feng111627.usagebasedbillingjava.service.BillingCalendar;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionLocks;
import com.github.feng111627.usagebasedbillingjava.service.UsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 用量上报与累计。
 *
 * <p>正确性规则：
 * <ul>
 *   <li>eventId 全局幂等：重复上报返回既有结果、绝不重复加量；同 eventId 但内容冲突被拒绝；
 *       eventId 去重段用全局锁，同订阅累计段用订阅锁，保证并发下累计一致；</li>
 *   <li>迟到（usageAt 落在已关账周期）照样累计并标记 late，不丢量；
 *       是否生成调整由 BillingService 决定；</li>
 *   <li>新周期累计行 missing=true，收到首个事件后转 false；整周期零事件则出账判 ANOMALY，
 *       不会悄悄按零处理（显式上报 quantity=0 视为已计量）。</li>
 * </ul>
 */
@Service
public class UsageServiceImpl implements UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageServiceImpl.class);
    private final Object eventIdLock = new Object();

    private final Repositories.UsageEventRepository eventRepository;
    private final Repositories.UsageAccumulationRepository accumulationRepository;
    private final Repositories.SubscriptionRepository subscriptionRepository;
    private final Repositories.InvoiceRepository invoiceRepository;
    private final BillingCalendar calendar;
    private final ClockService clock;
    private final SubscriptionLocks locks;

    public UsageServiceImpl(Repositories.UsageEventRepository eventRepository,
                            Repositories.UsageAccumulationRepository accumulationRepository,
                            Repositories.SubscriptionRepository subscriptionRepository,
                            Repositories.InvoiceRepository invoiceRepository,
                            BillingCalendar calendar, ClockService clock, SubscriptionLocks locks) {
        this.eventRepository = eventRepository;
        this.accumulationRepository = accumulationRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.calendar = calendar;
        this.clock = clock;
        this.locks = locks;
    }

    @Override
    public UsageAccumulation report(String eventId, String subscriptionId, String metric,
                                   long quantity, Instant usageAt) {
        if (eventId == null || eventId.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "用量上报必须携带 eventId（幂等标识）");
        }
        if (subscriptionId == null || subscriptionId.isBlank() || metric == null || metric.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "subscriptionId/metric 不能为空");
        }
        if (quantity < 0) {
            throw new BillingException(ErrorCode.VALIDATION, "quantity 不能为负: " + quantity);
        }
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "订阅不存在: " + subscriptionId));
        if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
            throw new BillingException(ErrorCode.SUBSCRIPTION_INACTIVE,
                    "订阅已退订，不接受用量上报: " + subscriptionId);
        }
        Instant at = usageAt != null ? usageAt : clock.now();
        String periodKey = calendar.periodKey(at, sub.getAnchorDay());

        // 第一步：全局 eventId 幂等判定（跨订阅也不允许复用 eventId 携带不同内容）
        UsageEvent existing;
        synchronized (eventIdLock) {
            existing = eventRepository.findByEventId(eventId).orElse(null);
            if (existing == null) {
                UsageEvent e = new UsageEvent();
                e.setEventId(eventId);
                e.setSubscriptionId(subscriptionId);
                e.setMetric(metric);
                e.setQuantity(quantity);
                e.setUsageAt(at);
                e.setReceivedAt(clock.now());
                eventRepository.save(e);
            }
        }
        if (existing != null) {
            if (!existing.getSubscriptionId().equals(subscriptionId) || !existing.getMetric().equals(metric)
                    || existing.getQuantity() != quantity) {
                throw new BillingException(ErrorCode.DUPLICATE_REQUEST,
                        "eventId=" + eventId + " 已存在但内容冲突: 既有(sub=" + existing.getSubscriptionId()
                                + ",metric=" + existing.getMetric() + ",qty=" + existing.getQuantity() + ")");
            }
            log.info("[usage] 幂等重复 eventId={} sub={} metric={} qty={} -> 不加量、不丢量，返回既有累计",
                    eventId, subscriptionId, metric, quantity);
            return accumulationRepository.find(subscriptionId, periodKey, metric)
                    .orElseGet(() -> accumulationRepository
                            .find(subscriptionId, calendar.periodKey(existing.getUsageAt(), sub.getAnchorDay()), metric)
                            .orElseThrow(() -> new BillingException(ErrorCode.CONFLICT, "幂等命中但累计行缺失")));
        }

        // 第二步：同订阅串行累计
        return locks.withLock(subscriptionId, () -> {
            boolean closed = invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, periodKey)
                    .filter(Invoice::isCycle).isPresent();
            UsageAccumulation acc = accumulationRepository.find(subscriptionId, periodKey, metric)
                    .orElseGet(() -> {
                        UsageAccumulation a = new UsageAccumulation();
                        a.setSubscriptionId(subscriptionId);
                        a.setPeriodKey(periodKey);
                        a.setMetric(metric);
                        a.setMissing(true);
                        return a;
                    });
            if (acc.getEventIds().add(eventId)) {
                acc.setTotal(acc.getTotal() + quantity);
                acc.setVersion(acc.getVersion() + 1);
                acc.setMissing(false);
                if (closed) {
                    acc.getLateEventIds().add(eventId);
                }
                accumulationRepository.save(acc);
                log.info("[usage] 输入 eventId={} sub={} metric={} qty={} usageAt={} receivedAt={}；判定依据：周期={} 已关账={} 去重后新增，累计={} version={} {}",
                        eventId, subscriptionId, metric, quantity, at, clock.now(), periodKey, closed,
                        acc.getTotal(), acc.getVersion(), closed ? "（标记迟到，待调整）" : "");
            } else {
                log.info("[usage] eventId={} 已在累计行去重集合中，跳过，累计仍为={}", eventId, acc.getTotal());
            }
            return acc;
        });
    }

    @Override
    public void markMissing(String subscriptionId, String periodKey, String metric, boolean missing) {
        locks.withLock(subscriptionId, () -> {
            UsageAccumulation acc = accumulationRepository.find(subscriptionId, periodKey, metric)
                    .orElseGet(() -> {
                        UsageAccumulation a = new UsageAccumulation();
                        a.setSubscriptionId(subscriptionId);
                        a.setPeriodKey(periodKey);
                        a.setMetric(metric);
                        return a;
                    });
            acc.setMissing(missing);
            accumulationRepository.save(acc);
            log.info("[usage] 人工标记缺计量 sub={} period={} metric={} missing={}", subscriptionId, periodKey, metric, missing);
        });
    }

    @Override
    public List<UsageEvent> listEvents(String subscriptionId) {
        return eventRepository.findBySubscription(subscriptionId);
    }
}

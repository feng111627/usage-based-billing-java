package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.domain.ChangeType;
import com.github.feng111627.usagebasedbillingjava.domain.Subscription;
import com.github.feng111627.usagebasedbillingjava.domain.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.engine.Timeline;
import com.github.feng111627.usagebasedbillingjava.repo.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订阅生命周期服务。
 *
 * <p>关键规则：</p>
 * <ul>
 *   <li>所有变更只追加事件，按 (effectiveAt, seq) 投影；同一时刻多次变更按接收顺序生效，
 *       最终状态确定，不产生中间态费用残留；</li>
 *   <li>乱序/迟到变更：允许 effectiveAt 早于当前（事件溯源天然支持），
 *       若对应账期已出账，由 BillingService 生成调账账单；</li>
 *   <li>每个订阅一把锁，保证并发变更时事件顺序与费用结论一致；</li>
 *   <li>退订为终态，之后不可升降配/恢复；暂停态不可升降配（需先恢复）。</li>
 * </ul>
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final PlanService planService;
    private final BillingClock clock;

    /** 记录已创建的订阅 ID，防止并发重复开通 */
    private final Map<String, Boolean> created = new ConcurrentHashMap<>();

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                                PlanService planService,
                                BillingClock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.planService = planService;
        this.clock = clock;
    }

    public Subscription subscribe(String subscriptionId, String customerId,
                                  String planId, Instant effectiveAt) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT, "subscriptionId is required");
        }
        // 校验套餐存在（错误原因可区分：PLAN_NOT_FOUND）
        planService.getPlan(planId);
        synchronized (subscriptionRepository.lockOf(subscriptionId)) {
            if (created.putIfAbsent(subscriptionId, Boolean.TRUE) != null) {
                throw new BillingException(ErrorCode.INVALID_ARGUMENT,
                        "subscription " + subscriptionId + " already exists");
            }
            Instant at = effectiveAt != null ? effectiveAt : clock.now();
            Subscription subscription = new Subscription(subscriptionId, customerId, at);
            subscriptionRepository.save(subscription);
            subscription.appendEvent(ChangeType.ACTIVATED, planId, at, clock.now());
            log.info("[subscription] open id={} customer={} plan={} effectiveAt={} => ACTIVE; "
                            + "basis: ACTIVATED is the first event of the stream, period anchor fixed at effectiveAt",
                    subscriptionId, customerId, planId, at);
            return subscription;
        }
    }

    public Subscription changePlan(String subscriptionId, String newPlanId, Instant effectiveAt) {
        Subscription subscription = get(subscriptionId);
        planService.getPlan(newPlanId);
        synchronized (subscriptionRepository.lockOf(subscriptionId)) {
            Instant at = effectiveAt != null ? effectiveAt : clock.now();
            SubscriptionStatus statusAt = Timeline.statusAt(subscription, at);
            String currentPlan = Timeline.planIdAt(subscription, at);
            // 判定依据：只有 ACTIVE 时刻的升降配才成立；同刻多条变更按 seq 折叠
            if (statusAt == SubscriptionStatus.CANCELLED) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_ALREADY_CANCELLED,
                        "cannot change plan of cancelled subscription " + subscriptionId
                                + " at " + at);
            }
            if (statusAt != SubscriptionStatus.ACTIVE) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_SUSPENDED,
                        "cannot change plan while subscription " + subscriptionId
                                + " is " + statusAt + " at " + at + "; resume first");
            }
            if (newPlanId.equals(currentPlan)) {
                log.info("[subscription] change-plan id={} to={} at={} ignored: same plan already effective",
                        subscriptionId, newPlanId, at);
                return subscription;
            }
            subscription.appendEvent(ChangeType.PLAN_CHANGED, newPlanId, at, clock.now());
            log.info("[subscription] change-plan id={} {} -> {} at={}; basis: PLAN_CHANGED event appended, "
                            + "proration computed per segment at bill time, same-instant events ordered by seq",
                    subscriptionId, currentPlan, newPlanId, at);
            return subscription;
        }
    }

    public Subscription cancel(String subscriptionId, Instant effectiveAt) {
        Subscription subscription = get(subscriptionId);
        synchronized (subscriptionRepository.lockOf(subscriptionId)) {
            Instant at = effectiveAt != null ? effectiveAt : clock.now();
            SubscriptionStatus statusAt = Timeline.statusAt(subscription, at);
            if (statusAt == SubscriptionStatus.CANCELLED) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_ALREADY_CANCELLED,
                        "subscription " + subscriptionId + " already cancelled at " + at);
            }
            subscription.appendEvent(ChangeType.CANCELLED, null, at, clock.now());
            log.info("[subscription] cancel id={} at={}; basis: CANCELLED terminal event appended, "
                            + "no base-fee segment after this instant, refund handled by billing/payment",
                    subscriptionId, at);
            return subscription;
        }
    }

    public Subscription suspend(String subscriptionId, Instant at) {
        Subscription subscription = get(subscriptionId);
        synchronized (subscriptionRepository.lockOf(subscriptionId)) {
            Instant when = at != null ? at : clock.now();
            if (Timeline.statusAt(subscription, when) != SubscriptionStatus.ACTIVE) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_NOT_ACTIVE,
                        "only ACTIVE subscription can be suspended: " + subscriptionId + " at " + when);
            }
            subscription.appendEvent(ChangeType.SUSPENDED, null, when, clock.now());
            log.info("[subscription] suspend id={} at={} due to overdue; basis: SUSPENDED event, "
                            + "no active segments while suspended",
                    subscriptionId, when);
            return subscription;
        }
    }

    public Subscription resume(String subscriptionId, Instant at) {
        Subscription subscription = get(subscriptionId);
        synchronized (subscriptionRepository.lockOf(subscriptionId)) {
            Instant when = at != null ? at : clock.now();
            SubscriptionStatus statusAt = Timeline.statusAt(subscription, when);
            if (statusAt == SubscriptionStatus.CANCELLED) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_ALREADY_CANCELLED,
                        "cancelled subscription cannot resume: " + subscriptionId);
            }
            if (statusAt != SubscriptionStatus.SUSPENDED) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_NOT_ACTIVE,
                        "only SUSPENDED subscription can resume: " + subscriptionId
                                + " is " + statusAt + " at " + when);
            }
            subscription.appendEvent(ChangeType.RESUMED, Timeline.planIdAt(subscription, when), when, clock.now());
            log.info("[subscription] resume id={} at={}; basis: RESUMED restores ACTIVE with plan carried over",
                    subscriptionId, when);
            return subscription;
        }
    }

    public Subscription get(String subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "subscription not found: " + subscriptionId));
    }
}

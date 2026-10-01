package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.model.PlanChange;
import com.github.feng111627.usagebasedbillingjava.model.Subscription;

import java.time.Instant;

public interface SubscriptionService {

    /** 开通订阅。anchorDay 为每月出账日（1-28）。 */
    Subscription subscribe(String subscriptionId, String customerId, String planId, int anchorDay);

    /**
     * 周期中途变更套餐。proration 按剩余周期比例计算差价，立即出一张变更账单。
     * 同一时刻的多次变更按到达顺序串行（每订阅一把锁 + seq 全序），结论确定。
     */
    PlanChange changePlan(String subscriptionId, String targetPlanId, String idempotencyKey, Instant effectiveAt);

    /** 退订：按剩余周期比例退回当前周期未消耗基础费。 */
    PlanChange cancel(String subscriptionId, String idempotencyKey, Instant effectiveAt);

    Subscription getSubscription(String subscriptionId);
}

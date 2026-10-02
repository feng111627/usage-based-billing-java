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

    /**
     * 客户主动暂停：自 effectiveAt 起不再产生基础费，按剩余周期比例退回本周期未消耗基础费。
     * 与收款失败的 SUSPENDED 是不同状态（PAUSED）。幂等键重复提交返回首次结果。
     */
    PlanChange pause(String subscriptionId, String idempotencyKey, Instant effectiveAt);

    /**
     * 从暂停恢复：自 effectiveAt 起按剩余周期比例补收本周期基础费，
     * 本周期配额按活跃时长等比例折算。幂等键重复提交返回首次结果。
     */
    PlanChange resume(String subscriptionId, String idempotencyKey, Instant effectiveAt);

    Subscription getSubscription(String subscriptionId);
}

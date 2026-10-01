package com.github.feng111627.usagebasedbillingjava.model;

import java.time.Instant;

/**
 * 订阅：以 anchorDay（每月几号，1-28）为周期锚点。
 * 每次升降配会产生一条 {@link PlanChange}，并把当前套餐前移。
 */
public class Subscription {
    private String subscriptionId;
    private String customerId;
    private String currentPlanId;
    private SubscriptionStatus status;
    private int anchorDay;
    private Instant createdAt;
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;
    /** 单调递增的变更序号，用于拒绝乱序变更（同一时刻多次变更按接收顺序排队）。 */
    private long lastChangeSeq;
    private String pendingPaymentIdempotencyKey;

    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCurrentPlanId() { return currentPlanId; }
    public void setCurrentPlanId(String currentPlanId) { this.currentPlanId = currentPlanId; }
    public SubscriptionStatus getStatus() { return status; }
    public void setStatus(SubscriptionStatus status) { this.status = status; }
    public int getAnchorDay() { return anchorDay; }
    public void setAnchorDay(int anchorDay) { this.anchorDay = anchorDay; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(Instant t) { this.currentPeriodStart = t; }
    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant t) { this.currentPeriodEnd = t; }
    public long getLastChangeSeq() { return lastChangeSeq; }
    public void setLastChangeSeq(long lastChangeSeq) { this.lastChangeSeq = lastChangeSeq; }
    public String getPendingPaymentIdempotencyKey() { return pendingPaymentIdempotencyKey; }
    public void setPendingPaymentIdempotencyKey(String k) { this.pendingPaymentIdempotencyKey = k; }
}

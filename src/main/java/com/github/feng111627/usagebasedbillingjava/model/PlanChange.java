package com.github.feng111627.usagebasedbillingjava.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一次订阅变更（升级/降级/退订）。proration 为本次按剩余周期比例结算的差价，
 * 正数向客户补收，负数应退。顺序由 seq 唯一确定。
 */
public class PlanChange {
    private long seq;
    private String subscriptionId;
    private ChangeType type;
    private String fromPlanId;
    private String toPlanId;
    private Instant effectiveAt;
    private BigDecimal proration;
    private String invoiceId;
    private String idempotencyKey;

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public ChangeType getType() { return type; }
    public void setType(ChangeType type) { this.type = type; }
    public String getFromPlanId() { return fromPlanId; }
    public void setFromPlanId(String fromPlanId) { this.fromPlanId = fromPlanId; }
    public String getToPlanId() { return toPlanId; }
    public void setToPlanId(String toPlanId) { this.toPlanId = toPlanId; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public BigDecimal getProration() { return proration; }
    public void setProration(BigDecimal proration) { this.proration = proration; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}

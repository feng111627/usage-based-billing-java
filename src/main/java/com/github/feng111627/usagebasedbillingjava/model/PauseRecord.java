package com.github.feng111627.usagebasedbillingjava.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条客户主动的暂停/恢复记录。PAUSE 与紧随其后的 RESUME 构成一个暂停账段
 * （[pauseAt, resumeAt)，半开半闭；跨周期恢复时 resumeAt 仍可能为 null，
 * 表示截至当前仍处于暂停）。
 *
 * <p>amount 为本动作立刻结算的金额：PAUSE 为负（按剩余比例退预付基础费），
 * RESUME 为正（按恢复时刻到周期末比例补收）。seq 与升降配/退订共用订阅级全序。
 */
public class PauseRecord {
    private long seq;
    private String subscriptionId;
    private PauseAction action;
    private Instant effectiveAt;
    private BigDecimal amount;
    private String invoiceId;
    private String idempotencyKey;
    private String periodKey;
    /** 该动作适用的套餐快照（暂停时为当前套餐，恢复时同套餐）。 */
    private String planId;

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public PauseAction getAction() { return action; }
    public void setAction(PauseAction action) { this.action = action; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
}

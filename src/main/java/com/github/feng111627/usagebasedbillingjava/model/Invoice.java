package com.github.feng111627.usagebasedbillingjava.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 周期账单：金额 = 各明细行之和，必须与用量汇总对得上（对账规则）。
 * ANOMALY 状态表示存在缺计量/异常，必须先处理才能收款。
 */
public class Invoice {
    private String invoiceId;
    private String subscriptionId;
    private String periodKey;
    private Instant periodStart;
    private Instant periodEnd;
    private Instant issuedAt;
    private BigDecimal amount;
    private InvoiceStatus status;
    private final List<InvoiceLine> lines = new ArrayList<>();
    private String anomalyReason;
    private String confirmedZeroBy;
    private int attemptCount;
    private Instant lastAttemptAt;
    /** 首次扣款失败时刻：重试窗口/宽限窗口的固定起算点，重试不刷新它。 */
    private Instant failedAt;
    private Instant dueAt;
    private Instant paidAt;
    private String refundIdempotencyKey;
    /** true=周期出账账单（含基础费/超配额），false=变更差价账单；对账只认周期账单。 */
    private boolean cycle;
    /** 出账时适用的套餐快照 id，迟到用量按该周期套餐计价。 */
    private String planId;

    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }
    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant periodStart) { this.periodStart = periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant periodEnd) { this.periodEnd = periodEnd; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }
    public List<InvoiceLine> getLines() { return lines; }
    public String getAnomalyReason() { return anomalyReason; }
    public void setAnomalyReason(String anomalyReason) { this.anomalyReason = anomalyReason; }
    public String getConfirmedZeroBy() { return confirmedZeroBy; }
    public void setConfirmedZeroBy(String confirmedZeroBy) { this.confirmedZeroBy = confirmedZeroBy; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public Instant getFailedAt() { return failedAt; }
    public void setFailedAt(Instant failedAt) { this.failedAt = failedAt; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public String getRefundIdempotencyKey() { return refundIdempotencyKey; }
    public void setRefundIdempotencyKey(String k) { this.refundIdempotencyKey = k; }
    public boolean isCycle() { return cycle; }
    public void setCycle(boolean cycle) { this.cycle = cycle; }
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
}

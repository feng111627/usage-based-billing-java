package com.github.feng111627.usagebasedbillingjava.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一次支付通道交互（扣款/退款）。attemptNo 标识同一张账单的第几次重试。
 */
public class PaymentAttempt {
    private String id;
    private String invoiceId;
    private String subscriptionId;
    private int attemptNo;
    private BigDecimal amount;
    private boolean success;
    private String channelReference;
    private String failureReason;
    private Instant attemptedAt;
    private String idempotencyKey;
    private boolean refund;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public int getAttemptNo() { return attemptNo; }
    public void setAttemptNo(int attemptNo) { this.attemptNo = attemptNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getChannelReference() { return channelReference; }
    public void setChannelReference(String channelReference) { this.channelReference = channelReference; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Instant getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(Instant attemptedAt) { this.attemptedAt = attemptedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public boolean isRefund() { return refund; }
    public void setRefund(boolean refund) { this.refund = refund; }
}

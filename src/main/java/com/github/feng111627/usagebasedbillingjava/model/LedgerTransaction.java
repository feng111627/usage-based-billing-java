package com.github.feng111627.usagebasedbillingjava.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 复式记账事务：一组借贷分录，分录金额之和必须为 0。
 * 客户充值记 SUBSCRIBER 正、PLATFORM 负；收款记 SUBSCRIBER 负、PLATFORM 正、CHANNEL 手续费正……
 */
public class LedgerTransaction {
    private String txId;
    private String refType;
    private String refId;
    private Instant createdAt;
    private final Map<Account, BigDecimal> entries = new LinkedHashMap<>();
    private String idempotencyKey;

    public String getTxId() { return txId; }
    public void setTxId(String txId) { this.txId = txId; }
    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Map<Account, BigDecimal> getEntries() { return entries; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String k) { this.idempotencyKey = k; }
}

package com.github.feng111627.usagebasedbillingjava.model;

import java.util.HashSet;
import java.util.Set;

/**
 * 单个「订阅 × 计量周期」的累计结果。
 * 通过 eventIds 去重保证幂等；通过 version 乐观锁保证并发累计结果一致。
 */
public class UsageAccumulation {
    private String subscriptionId;
    private String periodKey;
    private String metric;
    private long total;
    private long version;
    /** true 表示该周期未收到任何该计量项的上报，出账时不得悄悄按零处理。 */
    private boolean missing;
    private final Set<String> eventIds = new HashSet<>();
    /** 周期关账后迟到的事件 id，保留以便生成 ADJUSTMENT，不丢量。 */
    private final Set<String> lateEventIds = new HashSet<>();

    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public boolean isMissing() { return missing; }
    public void setMissing(boolean missing) { this.missing = missing; }
    public Set<String> getEventIds() { return eventIds; }
    public Set<String> getLateEventIds() { return lateEventIds; }
}

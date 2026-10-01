package com.github.feng111627.usagebasedbillingjava.model;

import java.time.Instant;

/**
 * 用量上报事件。eventId 为上报方幂等标识；usageAt 为用量实际发生时间（可能迟到）；
 * receivedAt 为服务端接收时间。重复上报（同 eventId）永不重复计费也不丢量。
 */
public class UsageEvent {
    private String eventId;
    private String subscriptionId;
    private String metric;
    private long quantity;
    private Instant usageAt;
    private Instant receivedAt;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public long getQuantity() { return quantity; }
    public void setQuantity(long quantity) { this.quantity = quantity; }
    public Instant getUsageAt() { return usageAt; }
    public void setUsageAt(Instant usageAt) { this.usageAt = usageAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
}

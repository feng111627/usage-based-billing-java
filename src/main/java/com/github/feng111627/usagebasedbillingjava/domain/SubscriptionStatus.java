package com.github.feng111627.usagebasedbillingjava.domain;

/**
 * 订阅状态。
 *
 * <pre>
 * ACTIVE ──暂停(欠费宽限用尽)──&gt; SUSPENDED ──补缴──&gt; ACTIVE
 *   │                              │
 *   └────────退订──────────────────┴──退订──&gt; CANCELLED
 * </pre>
 */
public enum SubscriptionStatus {
    ACTIVE,
    SUSPENDED,
    CANCELLED
}

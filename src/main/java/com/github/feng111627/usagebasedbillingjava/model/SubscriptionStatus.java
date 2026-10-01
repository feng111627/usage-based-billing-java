package com.github.feng111627.usagebasedbillingjava.model;

/**
 * 订阅状态流转：ACTIVE -> PAST_DUE(宽限) -> SUSPENDED ->(补缴) ACTIVE；任意活跃态 -> CANCELLED。
 */
public enum SubscriptionStatus {
    ACTIVE,
    PAST_DUE,
    SUSPENDED,
    CANCELLED
}

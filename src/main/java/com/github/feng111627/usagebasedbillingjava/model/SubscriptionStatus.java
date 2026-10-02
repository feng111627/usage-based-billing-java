package com.github.feng111627.usagebasedbillingjava.model;

/**
 * 订阅状态流转：ACTIVE -> PAST_DUE(宽限) -> SUSPENDED ->(补缴) ACTIVE；任意活跃态 -> CANCELLED。
 * PAUSED 为客户主动暂停（可恢复），与收款失败导致的 SUSPENDED 是两类互不相干的状态。
 */
public enum SubscriptionStatus {
    ACTIVE,
    PAST_DUE,
    SUSPENDED,
    PAUSED,
    CANCELLED
}

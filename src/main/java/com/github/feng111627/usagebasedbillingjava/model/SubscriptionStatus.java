package com.github.feng111627.usagebasedbillingjava.model;

/**
 * 订阅状态流转：ACTIVE -> PAST_DUE(宽限) -> SUSPENDED ->(补缴) ACTIVE；任意活跃态 -> CANCELLED。
 * PAUSED 为客户主动暂停（与收款失败的 SUSPENDED 相区分）：ACTIVE -> PAUSED ->(恢复) ACTIVE。
 */
public enum SubscriptionStatus {
    ACTIVE,
    PAST_DUE,
    SUSPENDED,
    /** 客户主动暂停：不产生基础费、暂停时段不接受用量上报，可恢复。 */
    PAUSED,
    CANCELLED
}

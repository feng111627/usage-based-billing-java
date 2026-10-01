package com.github.feng111627.usagebasedbillingjava.domain;

/**
 * 订阅生命周期事件类型，作为只追加事件流保存；当前状态由事件流投影得到，
 * 从而保证同一时刻多次变更、乱序到达时结论确定。
 */
public enum ChangeType {
    ACTIVATED,
    PLAN_CHANGED,
    SUSPENDED,
    RESUMED,
    CANCELLED
}

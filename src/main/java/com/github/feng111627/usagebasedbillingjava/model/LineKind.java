package com.github.feng111627.usagebasedbillingjava.model;

/**
 * 账单项类型：周期基础费、按比例差价、超配额用量、迟到用量调整。
 */
public enum LineKind {
    BASE,
    PRORATION,
    OVERAGE,
    ADJUSTMENT
}

package com.github.feng111627.usagebasedbillingjava.domain;

/**
 * 账单明细行类型。
 */
public enum BillLineKind {
    BASE,       // 基础费（按账段比例）
    OVERAGE,    // 超额用量费（配额按账段时长比例缩放）
    ADJUSTMENT, // 关闭账期后的迟到事件调账（可正可负）
    REFUND      // 退款
}

package com.github.feng111627.usagebasedbillingjava.model;

/**
 * UPGRADE / DOWNGRADE 由新旧套餐价格比较确定性得出；CANCEL 表示退订。
 * PAUSE / RESUME 表示客户主动暂停 / 恢复（与收款失败的停用区分），同样纳入 seq 全序。
 */
public enum ChangeType {
    UPGRADE,
    DOWNGRADE,
    CANCEL,
    PAUSE,
    RESUME
}

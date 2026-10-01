package com.github.feng111627.usagebasedbillingjava.model;

/**
 * UPGRADE / DOWNGRADE 由新旧套餐价格比较确定性得出；CANCEL 表示退订。
 */
public enum ChangeType {
    UPGRADE,
    DOWNGRADE,
    CANCEL
}

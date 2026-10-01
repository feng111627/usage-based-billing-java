package com.github.feng111627.usagebasedbillingjava.domain;

/**
 * 分类账账户余额（分）。
 */
public record LedgerAccount(AccountType type, long balanceCents) {
}

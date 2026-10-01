package com.github.feng111627.usagebasedbillingjava.domain;

import java.time.Instant;

/**
 * 不可变分类账分录。一次成功收款产生一组借贷平衡的分录；退款做反向分录。
 */
public record LedgerEntry(String id,
                          String billId,
                          String paymentReference,
                          AccountType account,
                          long debitCents,
                          long creditCents,
                          Instant postedAt,
                          String memo) {
}

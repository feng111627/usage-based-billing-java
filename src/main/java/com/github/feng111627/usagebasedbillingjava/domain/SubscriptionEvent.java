package com.github.feng111627.usagebasedbillingjava.domain;

import java.time.Instant;

/**
 * 订阅变更事件（只追加）。同一 effectiveAt 的多条事件按 seq 排序，保证确定性。
 *
 * @param seq         单调递增序号（事件流内的全局顺序）
 * @param subscriptionId 所属订阅
 * @param type        事件类型
 * @param planId      ACTIVATED/PLAN_CHANGED 时生效的套餐
 * @param effectiveAt 业务生效时间（不是入库时间）
 * @param recordedAt  系统记录时间
 */
public record SubscriptionEvent(long seq,
                                String subscriptionId,
                                ChangeType type,
                                String planId,
                                Instant effectiveAt,
                                Instant recordedAt) {
}

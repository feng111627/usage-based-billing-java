package com.github.feng111627.usagebasedbillingjava.domain;

import java.time.Instant;

/**
 * 一次用量上报（只追加原始事件）。
 *
 * <p>语义：本次上报的指标数量表示计量窗口 [{@code windowStart}, {@code windowEnd})
 * 内“增量发生量”，系统对同一周期内全部窗口求和。{@code meteringComplete=false}
 * 表示该窗口计量数据缺失/不完整，出账时必须显式标记，绝不按 0 或上一次值替代。</p>
 *
 * @param idempotencyKey 调用方幂等标识，重复上报只生效一次
 * @param subscriptionId 订阅
 * @param metricKey      指标编码
 * @param quantity       窗口内用量（非负）
 * @param windowStart    计量窗口起点（含）
 * @param windowEnd      计量窗口终点（不含）
 * @param recordedAt     系统接收时间
 * @param meteringComplete 计量是否完整（false 表示存在数据缺失）
 */
public record UsageReport(String idempotencyKey,
                          String subscriptionId,
                          String metricKey,
                          long quantity,
                          Instant windowStart,
                          Instant windowEnd,
                          Instant recordedAt,
                          boolean meteringComplete) {
}

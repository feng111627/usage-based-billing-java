package com.github.feng111627.usagebasedbillingjava.engine;

import java.time.Instant;

/**
 * 账期内的一个“套餐账段”：[start, end) 内订阅处于 active 且使用同一套餐。
 * 账段由订阅事件流确定性切分得到。
 */
public record PlanSegment(String planId, Instant start, Instant end, boolean active) {
}

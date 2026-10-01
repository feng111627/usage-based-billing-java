package com.github.feng111627.usagebasedbillingjava.domain;

import com.github.feng111627.usagebasedbillingjava.common.Money;

/**
 * 单个计量指标的定义。
 *
 * @param key           指标编码
 * @param includedUnits 每周期包含配额
 * @param overagePrice  超出配额后每单位单价（分）
 * @param unit          计量单位名称（仅展示用）
 */
public record MetricDef(String key, long includedUnits, Money overagePrice, String unit) {
}

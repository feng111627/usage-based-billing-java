package com.github.feng111627.usagebasedbillingjava.domain;

/**
 * 单个指标在一个账期/账段内的汇总结果。
 *
 * @param metricKey       指标编码
 * @param totalQuantity   累计用量
 * @param includedUnits   套餐包含配额
 * @param overageUnits    超额量
 * @param overageAmount   超额费用
 * @param missingMetering 是否存在计量缺失
 * @param overQuota       是否超出配额
 */
public record MetricUsage(String metricKey,
                          long totalQuantity,
                          long includedUnits,
                          long overageUnits,
                          long overageAmount,
                          boolean missingMetering,
                          boolean overQuota) {
}

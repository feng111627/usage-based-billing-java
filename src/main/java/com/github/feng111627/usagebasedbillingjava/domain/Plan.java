package com.github.feng111627.usagebasedbillingjava.domain;

import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.common.Money;

import java.util.Map;

/**
 * 计费套餐：基础月费 + 各计量指标的包含配额与超额单价。
 *
 * @param id        套餐标识
 * @param name      套餐名称
 * @param basePrice 月基础费（按完整自然月账期）
 * @param metrics   指标编码 -> 计量规则（配额/超额单价/单位）
 */
public record Plan(String id, String name, Money basePrice, Map<String, MetricDef> metrics) {

    public Plan {
        metrics = Map.copyOf(metrics);
    }

    /** 取指标定义；指标不属于本套餐时错误可区分（INVALID_ARGUMENT），绝不按 0 单价吞掉。 */
    public MetricDef metric(String metricKey) {
        MetricDef def = metrics.get(metricKey);
        if (def == null) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT,
                    "metric '" + metricKey + "' is not defined in plan " + id);
        }
        return def;
    }

    public boolean hasMetric(String metricKey) {
        return metrics.containsKey(metricKey);
    }
}

package com.github.feng111627.usagebasedbillingjava.service;

import java.math.BigDecimal;

/**
 * 本地模拟支付通道：可配置失败次数（用于验证重试），不依赖任何外部服务。
 */
public interface LocalPaymentGateway {

    /** 模拟扣款。返回通道流水号；配置的前 N 次失败，之后成功。 */
    GatewayResult charge(String reference, BigDecimal amount);

    GatewayResult refund(String reference, BigDecimal amount);

    /** 设置接下来连续失败的次数。 */
    void failNext(int times);

    /** 清零失败计数（恢复通道）。 */
    void reset();

    record GatewayResult(boolean success, String channelReference, String failureReason) {
    }
}

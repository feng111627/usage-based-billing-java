package com.github.feng111627.usagebasedbillingjava.service.impl;

import com.github.feng111627.usagebasedbillingjava.service.LocalPaymentGateway;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地模拟支付通道。
 * failNext(n) 让接下来的 n 次扣款失败（余额不足），用于确定性验证“重试 -> 宽限 -> 暂停”；
 * 退款通道始终可用（模拟原路退回）。不发起任何真实网络调用。
 */
@Service
public class LocalPaymentGatewayImpl implements LocalPaymentGateway {

    private final AtomicInteger remainingFailures = new AtomicInteger(0);

    @Override
    public GatewayResult charge(String reference, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return new GatewayResult(false, null, "通道拒绝：金额必须为正: " + amount);
        }
        if (remainingFailures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            return new GatewayResult(false, null, "INSUFFICIENT_FUNDS: 模拟账户余额不足 ref=" + reference);
        }
        return new GatewayResult(true, "CH-" + UUID.randomUUID(), null);
    }

    @Override
    public GatewayResult refund(String reference, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return new GatewayResult(false, null, "通道拒绝：退款金额必须为正: " + amount);
        }
        return new GatewayResult(true, "RF-" + UUID.randomUUID(), null);
    }

    @Override
    public void failNext(int times) {
        if (times < 0) {
            throw new IllegalArgumentException("times 不能为负");
        }
        remainingFailures.set(times);
    }

    @Override
    public void reset() {
        remainingFailures.set(0);
    }
}

package com.github.feng111627.usagebasedbillingjava.gateway;

import com.github.feng111627.usagebasedbillingjava.domain.PaymentResult;

/**
 * 支付通道抽象。
 */
public interface PaymentGateway {

    /**
     * 发起一次扣款。
     *
     * @param reference   平台侧支付引用（成功扣款的幂等键：同引用重复发起不重复扣款；
     *                    失败不占位，允许用同引用重试）
     * @param amountCents 金额（分），正数
     */
    PaymentResult charge(String reference, long amountCents);

    /**
     * 发起一次退款（原通道退回订阅方）。
     *
     * @param reference         本次退款引用（每次退款唯一，支持同笔扣款的多次部分退款）
     * @param originalReference 原扣款引用
     * @param amountCents       退款金额（分），正数
     */
    PaymentResult refund(String reference, String originalReference, long amountCents);
}

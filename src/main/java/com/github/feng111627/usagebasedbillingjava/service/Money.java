package com.github.feng111627.usagebasedbillingjava.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额统一规则：2 位小数、四舍五入（HALF_UP）。所有乘比例的结果都经由此处归一，
 * 避免各路径舍入不一致造成的对不平账。
 */
public final class Money {

    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    public static BigDecimal of(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 单价 × 数量后归一为分元。 */
    public static BigDecimal mul(BigDecimal price, long units) {
        return price.multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.HALF_UP);
    }

    /** 金额 × 比例后归一。 */
    public static BigDecimal prorate(BigDecimal amount, BigDecimal ratio) {
        return amount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
    }
}

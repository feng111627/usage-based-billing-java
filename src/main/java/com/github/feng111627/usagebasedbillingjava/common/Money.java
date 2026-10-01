package com.github.feng111627.usagebasedbillingjava.common;

/**
 * 金额值对象，统一使用“分”（长整型）表示，杜绝浮点误差。允许为负（调账/退款）。
 */
public record Money(long cents) {

    public static final Money ZERO = new Money(0L);

    public static Money of(long cents) {
        return new Money(cents);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(cents, other.cents));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(cents, other.cents));
    }

    public Money negate() {
        return new Money(-cents);
    }

    public boolean isNegative() {
        return cents < 0;
    }

    public boolean isZero() {
        return cents == 0;
    }

    /**
     * 按分子/分母比例计价：cents * numerator / denominator，四舍五入（半数向上），
     * 与 Long 数学语义一致；比例必须满足 0 &lt;= numerator &lt;= denominator。
     */
    public Money prorate(long numerator, long denominator) {
        if (denominator <= 0 || numerator < 0 || numerator > denominator) {
            throw new IllegalArgumentException(
                    "bad proration ratio: " + numerator + "/" + denominator);
        }
        long product = Math.multiplyExact(cents, numerator);
        long rounded = (product + (product >= 0 ? denominator / 2 : -denominator / 2)) / denominator;
        return new Money(rounded);
    }

    @Override
    public String toString() {
        return "Money(" + cents + " cents)";
    }
}

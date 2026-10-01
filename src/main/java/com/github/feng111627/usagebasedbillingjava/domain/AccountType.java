package com.github.feng111627.usagebasedbillingjava.domain;

/**
 * 分类账账户。
 *
 * <p>复式记账（每笔资金动作借贷相等）：</p>
 * <pre>
 * 收款 A、手续费 F：
 *   借 CASH_CLEARING     A（资金从通道清算进来）
 *     贷 SUBSCRIBER_PAID   A（来源：订阅方已付）
 *   借 SUBSCRIBER_PAID   A（资金归属拆分）
 *     贷 PLATFORM_REVENUE A-F
 *     贷 GATEWAY_FEES     F
 * 退款 R（手续费不退）：
 *   借 PLATFORM_REVENUE   R
 *     贷 SUBSCRIBER_PAID   R
 *   借 SUBSCRIBER_PAID    R
 *     贷 CASH_CLEARING     R
 * </pre>
 * 按“贷-借”计余额恒有：
 * SUBSCRIBER_PAID = PLATFORM_REVENUE + GATEWAY_FEES。
 */
public enum AccountType {
    /** 外部往来/通道清算（对方科目）。 */
    CASH_CLEARING,
    SUBSCRIBER_PAID,
    PLATFORM_REVENUE,
    GATEWAY_FEES
}

package com.github.feng111627.usagebasedbillingjava.error;

/**
 * 业务错误分类码：不同失败原因必须可区分。
 */
public enum ErrorCode {
    VALIDATION,
    NOT_FOUND,
    PLAN_NOT_FOUND,
    SUBSCRIPTION_NOT_FOUND,
    INVOICE_NOT_FOUND,
    SUBSCRIPTION_INACTIVE,
    SUBSCRIPTION_SUSPENDED,
    SUBSCRIPTION_PAUSED,
    CHANGE_OUT_OF_ORDER,
    /** 同一订阅的同一生效时间点已经有一次操作生效：先到者占位，后到者当场拒绝（不出账、不走资金、不动状态）。 */
    EFFECTIVE_TIME_CONFLICT,
    DUPLICATE_REQUEST,
    INVALID_AMOUNT,
    INVOICE_ANOMALY,
    INVOICE_NOT_PAYABLE,
    INVOICE_ALREADY_PAID,
    NOTHING_TO_CLOSE,
    GATEWAY_REJECTED,
    CONFLICT
}

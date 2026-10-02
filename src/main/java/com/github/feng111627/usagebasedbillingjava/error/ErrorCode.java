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
    CHANGE_OUT_OF_ORDER,
    DUPLICATE_REQUEST,
    INVALID_AMOUNT,
    INVOICE_ANOMALY,
    INVOICE_NOT_PAYABLE,
    INVOICE_ALREADY_PAID,
    NOTHING_TO_CLOSE,
    GATEWAY_REJECTED,
    /** 客户主动暂停（PAUSED）引发的操作被拒，与收款失败 SUSPENDED 明确区分。 */
    SUBSCRIPTION_PAUSED_BY_CUSTOMER,
    /** 用量发生时间落在暂停账段内，明确拒绝，既不当零也不记到别的周期。 */
    USAGE_DURING_PAUSE,
    /** 退款累计将超过该订阅已经收到的钱，被守恒规则拦住。 */
    REFUND_LIMIT_EXCEEDED,
    CONFLICT
}

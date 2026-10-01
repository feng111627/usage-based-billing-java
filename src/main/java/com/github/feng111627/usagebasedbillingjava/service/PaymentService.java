package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.PaymentAttempt;

public interface PaymentService {

    /**
     * 对账单发起收款（带幂等键）。失败抛 BillingException(GATEWAY_REJECTED)，
     * 由 BillingService 驱动重试/宽限/暂停流转。
     */
    PaymentAttempt charge(Invoice invoice, String idempotencyKey);

    /** 补缴（暂停后恢复）。 */
    PaymentAttempt repay(String invoiceId, String idempotencyKey);

    /** 退款（退订比例退款 / 已付账单的迟到调整）。三方金额守恒。 */
    PaymentAttempt refund(String invoiceId, java.math.BigDecimal amount, String idempotencyKey);
}

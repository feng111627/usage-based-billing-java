package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.model.Invoice;

import java.util.List;

public interface BillingService {

    /** 周期出账：汇总该周期用量、基础费、超配额费；缺计量 -> ANOMALY。 */
    Invoice closePeriod(String subscriptionId);

    /** 人工确认某异常账单确实零用量，解除 ANOMALY 进入待收款。 */
    Invoice confirmZeroUsage(String invoiceId, String operator);

    /** 迟到用量追加到指定账单生成 ADJUSTMENT 行；若已支付则产生退款账单。 */
    Invoice appendLateUsage(String invoiceId, String eventId);

    /** 推进收款状态机：重试 / 宽限(PAST_DUE) / 暂停(SUSPENDED)。 */
    Invoice runBillingTick(String subscriptionId);

    List<Invoice> listInvoices(String subscriptionId);

    Invoice getInvoice(String invoiceId);
}

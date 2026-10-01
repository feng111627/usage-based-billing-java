package com.github.feng111627.usagebasedbillingjava.service;

import java.util.List;
import java.util.Map;

/**
 * 对账：账单金额 vs 用量汇总；复式记账平衡；支付流水 vs 账单状态。
 */
public interface ReconciliationService {

    /** 单张账单对账，返回发现的问题（空 = 对得上）。 */
    List<String> reconcileInvoice(String invoiceId);

    /** 全量对账。 */
    Map<String, List<String>> reconcileAll();
}

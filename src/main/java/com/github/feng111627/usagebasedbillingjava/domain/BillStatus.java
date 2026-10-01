package com.github.feng111627.usagebasedbillingjava.domain;

/**
 * 账单状态。
 *
 * <pre>
 * ISSUED ──收款成功──&gt; PAID
 *   │ ├─重试中(失败)──&gt; RETRYING ─成功─&gt; PAID
 *   │ ├─宽限期到期──&gt; OVERDUE ─成功─&gt; PAID；超时未付──&gt; SUSPENDED(订阅暂停)
 *   └─争议/调账后作废──&gt; VOID
 * 退款产生独立的负向账单 REFUND(PAID)，不修改原账单，保证可解释。
 * </pre>
 */
public enum BillStatus {
    ISSUED,
    RETRYING,
    OVERDUE,
    PAID,
    VOID,
    REFUND
}

package com.github.feng111627.usagebasedbillingjava.domain;

import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.common.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 账单：一个账期出一张周期账单；关闭账期后迟到的事件/上报产生独立 ADJUSTMENT 账单；
 * 退款产生独立的负向 REFUND 账单。已出账单不可变，保证可解释、可对账。
 *
 * <p>状态机：</p>
 * <pre>
 *   ISSUED ──收款失败──&gt; RETRYING ──再失败──&gt; RETRYING（attempt 累计）
 *   ISSUED/RETRYING ──宽限期到──&gt; OVERDUE ──收款成功──&gt; PAID
 *   任意未付 ──收款成功──&gt; PAID
 *   ISSUED ──作废──&gt; VOID
 * 退款账单独立创建即为 REFUND（金额为负，通道退款成功后等同已付）。
 * </pre>
 */
public class Bill {

    private final String id;
    private final String subscriptionId;
    private final String periodKey;
    private final Instant periodStart;
    private final Instant periodEndExclusive;
    private final List<BillLine> lines;
    private final Money amount;
    private BillStatus status;
    private final Instant issuedAt;
    private final Instant dueAt;
    private int attemptCount;
    private final String invoiceGroupId;
    private final String adjustmentKey;
    private String paidByReference;

    public Bill(String id, String subscriptionId, String periodKey,
                Instant periodStart, Instant periodEndExclusive,
                List<BillLine> lines, BillStatus status, Instant issuedAt, Instant dueAt,
                String invoiceGroupId, String adjustmentKey) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.periodKey = periodKey;
        this.periodStart = periodStart;
        this.periodEndExclusive = periodEndExclusive;
        this.lines = List.copyOf(lines);
        this.amount = lines.stream().map(l -> Money.of(l.amount())).reduce(Money.ZERO, Money::plus);
        this.status = status;
        this.issuedAt = issuedAt;
        this.dueAt = dueAt;
        this.invoiceGroupId = invoiceGroupId;
        this.adjustmentKey = adjustmentKey;
    }

    /** 创建普通周期/调账账单（ISSUED）。 */
    public static Bill issue(String subscriptionId, String periodKey,
                             Instant periodStart, Instant periodEndExclusive,
                             List<BillLine> lines, Instant issuedAt, Instant dueAt,
                             String invoiceGroupId) {
        return new Bill("bill-" + UUID.randomUUID(), subscriptionId, periodKey,
                periodStart, periodEndExclusive, lines, BillStatus.ISSUED, issuedAt, dueAt,
                invoiceGroupId, null);
    }

    /** 创建负向退款账单，直接为 REFUND 状态（需随后走通道退款）。 */
    public static Bill refund(String subscriptionId, String periodKey,
                              Instant periodStart, Instant periodEndExclusive,
                              List<BillLine> lines, Instant issuedAt,
                              String invoiceGroupId) {
        return new Bill("bill-" + UUID.randomUUID(), subscriptionId, periodKey,
                periodStart, periodEndExclusive, lines, BillStatus.REFUND, issuedAt, issuedAt,
                invoiceGroupId, null);
    }

    public String getId() { return id; }
    public String getSubscriptionId() { return subscriptionId; }
    public String getPeriodKey() { return periodKey; }
    public Instant getPeriodStart() { return periodStart; }
    public Instant getPeriodEndExclusive() { return periodEndExclusive; }
    public List<BillLine> getLines() { return lines; }
    public BillStatus getStatus() { return status; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getDueAt() { return dueAt; }
    public Money getAmount() { return amount; }
    public int getAttemptCount() { return attemptCount; }
    public String getInvoiceGroupId() { return invoiceGroupId; }
    public String getAdjustmentKey() { return adjustmentKey; }
    public String getPaidByReference() { return paidByReference; }

    /** 创建账期关闭后的调账账单（ISSUED，金额可正可负）。 */
    public static Bill adjustment(String subscriptionId, String periodKey,
                                  Instant periodStart, Instant periodEndExclusive,
                                  List<BillLine> lines, Instant issuedAt, Instant dueAt,
                                  String invoiceGroupId, String adjustmentKey) {
        return new Bill("bill-" + UUID.randomUUID(), subscriptionId, periodKey,
                periodStart, periodEndExclusive, lines, BillStatus.ISSUED, issuedAt, dueAt,
                invoiceGroupId, adjustmentKey);
    }

    /** 金额是否为 0（零元账单直接标记已付，无需走通道）。 */
    public boolean isZeroAmount() {
        return amount.isZero();
    }

    private void requireNotPaidOrVoid() {
        if (status == BillStatus.PAID) {
            throw new BillingException(ErrorCode.BILL_ALREADY_PAID,
                    "bill " + id + " is already paid via " + paidByReference);
        }
        if (status == BillStatus.VOID || status == BillStatus.REFUND) {
            throw new BillingException(ErrorCode.BILL_NOT_PAYABLE,
                    "bill " + id + " is " + status + " and cannot be collected");
        }
    }

    /** 记录一次收款尝试（无论成败），并按结果推进状态。 */
    public void recordAttempt(boolean success, String reference) {
        if (status == BillStatus.REFUND) {
            // 退款账单：通道退款成功即视为完成
            if (success) {
                this.paidByReference = reference;
            }
            return;
        }
        requireNotPaidOrVoid();
        attemptCount++;
        if (success) {
            status = BillStatus.PAID;
            paidByReference = reference;
        } else if (status == BillStatus.ISSUED || status == BillStatus.RETRYING) {
            status = BillStatus.RETRYING;
        }
    }

    /** 宽限期到期仍未付清：RETRYING/ISSUED -> OVERDUE。 */
    public void markOverdue() {
        if (status == BillStatus.ISSUED || status == BillStatus.RETRYING) {
            status = BillStatus.OVERDUE;
        } else if (status == BillStatus.PAID || status == BillStatus.VOID) {
            throw new BillingException(ErrorCode.BILL_NOT_PAYABLE,
                    "bill " + id + " is " + status + ", cannot mark overdue");
        }
    }

    /** OVERDUE 账单补缴成功 -> PAID。 */
    public void markPaid(String reference) {
        if (status != BillStatus.OVERDUE && status != BillStatus.RETRYING && status != BillStatus.ISSUED) {
            throw new BillingException(ErrorCode.BILL_NOT_PAYABLE,
                    "bill " + id + " is " + status + ", cannot pay");
        }
        status = BillStatus.PAID;
        paidByReference = reference;
    }

    public void markVoid() {
        if (status != BillStatus.ISSUED) {
            throw new BillingException(ErrorCode.BILL_NOT_PAYABLE,
                    "only ISSUED bill can be voided, bill " + id + " is " + status);
        }
        status = BillStatus.VOID;
    }

    public boolean isUnpaid() {
        return status != BillStatus.PAID && status != BillStatus.VOID && status != BillStatus.REFUND;
    }
}

package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.domain.AccountType;
import com.github.feng111627.usagebasedbillingjava.domain.Bill;
import com.github.feng111627.usagebasedbillingjava.domain.BillLine;
import com.github.feng111627.usagebasedbillingjava.domain.BillLineKind;
import com.github.feng111627.usagebasedbillingjava.domain.BillStatus;
import com.github.feng111627.usagebasedbillingjava.domain.PaymentAttempt;
import com.github.feng111627.usagebasedbillingjava.domain.PaymentResult;
import com.github.feng111627.usagebasedbillingjava.domain.Subscription;
import com.github.feng111627.usagebasedbillingjava.engine.Periods;
import com.github.feng111627.usagebasedbillingjava.gateway.LocalMockPaymentGateway;
import com.github.feng111627.usagebasedbillingjava.gateway.PaymentGateway;
import com.github.feng111627.usagebasedbillingjava.repo.BillRepository;
import com.github.feng111627.usagebasedbillingjava.repo.LedgerRepository;
import com.github.feng111627.usagebasedbillingjava.repo.PaymentRepository;
import com.github.feng111627.usagebasedbillingjava.repo.SubscriptionRepository;
import com.github.feng111627.usagebasedbillingjava.domain.LedgerEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 收款与催收服务。
 *
 * <p>流程：收款失败自动进入 RETRYING（可反复 collect 重试）；{@link #evaluateDunning}
 * 在宽限期（账单 dueAt）过后仍未付清则账单转 OVERDUE 并暂停订阅；补缴成功账单转 PAID、
 * 订阅恢复 ACTIVE。每张账单独立锁 + 通道引用幂等，保证并发收款不重复扣款。</p>
 *
 * <p>资金守恒（成功扣款金额 A，手续费 F）：</p>
 * <pre>
 *   贷 SUBSCRIBER_PAID  A  | （资金来源：订阅方已付）
 *   贷 PLATFORM_REVENUE A-F| （收入归属：平台）
 *   贷 GATEWAY_FEES     F  | （收入归属：通道手续费）
 * 退款金额 R（手续费不退）：
 *   借 PLATFORM_REVENUE R  | 贷 SUBSCRIBER_PAID R
 * 按“贷-借”计各账户余额，恒有
 * SUBSCRIBER_PAID = PLATFORM_REVENUE + GATEWAY_FEES，且全账 Σ借=Σ贷。
 * </pre>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentGateway gateway;
    private final PaymentRepository paymentRepository;
    private final BillRepository billRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final LedgerRepository ledgerRepository;
    private final SubscriptionService subscriptionService;
    private final BillingClock clock;

    public PaymentService(PaymentGateway gateway,
                          PaymentRepository paymentRepository,
                          BillRepository billRepository,
                          SubscriptionRepository subscriptionRepository,
                          LedgerRepository ledgerRepository,
                          SubscriptionService subscriptionService,
                          BillingClock clock) {
        this.gateway = gateway;
        this.paymentRepository = paymentRepository;
        this.billRepository = billRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.ledgerRepository = ledgerRepository;
        this.subscriptionService = subscriptionService;
        this.clock = clock;
    }

    /**
     * 对账单发起一次收款。引用固定为 {@code charge:billId}：
     * 成功后并发/重复调用直接被账单 PAID 状态与通道重放双重拦截；
     * 失败后重试沿用同一引用，通道在临时故障恢复时可成功且只扣一次。
     */
    public PaymentAttempt collect(String billId) {
        Bill bill = requireBill(billId);
        synchronized (billRepository.lockOf(billId)) {
            if (bill.getStatus() == BillStatus.PAID) {
                throw new BillingException(ErrorCode.BILL_ALREADY_PAID,
                        "bill " + billId + " already paid via " + bill.getPaidByReference());
            }
            if (bill.getStatus() == BillStatus.VOID || bill.getStatus() == BillStatus.REFUND) {
                throw new BillingException(ErrorCode.BILL_NOT_PAYABLE,
                        "bill " + billId + " is " + bill.getStatus());
            }
            long amount = bill.getAmount().cents();
            if (amount <= 0) {
                // 负向票（调账退款）应走 refund 通道流程；这里零元票直接置付
                bill.markPaid("zero-amount");
                return paymentRepository.save(new PaymentAttempt("zero-amount", billId,
                        bill.getSubscriptionId(), 0, false, PaymentResult.SUCCESS, null, clock.now()));
            }
            String reference = "charge:" + billId;
            PaymentResult result = gateway.charge(reference, amount);
            String reason = result == PaymentResult.SUCCESS ? null
                    : (gateway instanceof LocalMockPaymentGateway m ? m.failureReasonOf(reference) : "declined");
            PaymentAttempt attempt = new PaymentAttempt(reference, billId, bill.getSubscriptionId(),
                    amount, false, result, reason, clock.now());
            paymentRepository.save(attempt);
            bill.recordAttempt(result == PaymentResult.SUCCESS, reference);

            if (result == PaymentResult.SUCCESS) {
                postChargeEntries(bill, reference, amount, clock.now());
                log.info("[payment] collected bill={} amount={} attempt#{} ref={}; basis: gateway SUCCESS, "
                                + "ledger posted (subscriber=platform+fee), bill PAID",
                        billId, amount, bill.getAttemptCount(), reference);
            } else {
                log.warn("[payment] collect FAILED bill={} amount={} attempt#{} reason='{}' status={}; "
                                + "basis: retry allowed, grace deadline {}",
                        billId, amount, bill.getAttemptCount(), reason, bill.getStatus(), bill.getDueAt());
            }
            return attempt;
        }
    }

    /**
     * 催收检查：at 已过 dueAt 且账单仍未付 -> OVERDUE 并暂停订阅（同一订阅存在多张
     * 逾期票时，任一逾期即暂停；全部付清后由补缴路径恢复）。
     */
    public Bill evaluateDunning(String billId, Instant at) {
        Bill bill = requireBill(billId);
        synchronized (billRepository.lockOf(billId)) {
            Instant when = at != null ? at : clock.now();
            if (bill.isUnpaid() && when.isAfter(bill.getDueAt())) {
                boolean wasOverdue = bill.getStatus() == BillStatus.OVERDUE;
                bill.markOverdue();
                if (!wasOverdue) {
                    log.warn("[dunning] bill={} OVERDUE at={} dueAt={}; suspending subscription={}; "
                                    + "basis: grace period elapsed without payment",
                            billId, when, bill.getDueAt(), bill.getSubscriptionId());
                    try {
                        subscriptionService.suspend(bill.getSubscriptionId(), when);
                    } catch (BillingException ex) {
                        // 订阅可能已退订/已暂停：非 ACTIVE 不再重复暂停，结论仍确定
                        log.info("[dunning] suspend skipped for bill={} reason={}", billId, ex.getMessage());
                    }
                }
            } else {
                log.info("[dunning] bill={} status={} no action at={} (dueAt={})",
                        billId, bill.getStatus(), when, bill.getDueAt());
            }
            return bill;
        }
    }

    /**
     * 补缴：对 OVERDUE（或 RETRYING）票收款；成功后若该订阅再无其他未付票，则恢复订阅。
     */
    public PaymentAttempt payOverdue(String billId) {
        Bill bill = requireBill(billId);
        PaymentAttempt attempt = collect(billId);
        if (attempt.result() == PaymentResult.SUCCESS) {
            synchronized (billRepository.lockOf(billId)) {
                boolean anyUnpaid = billRepository.findBySubscription(bill.getSubscriptionId()).stream()
                        .anyMatch(Bill::isUnpaid);
                if (!anyUnpaid) {
                    try {
                        // 在暂停事件的同一时刻恢复（seq 更大）：事件流无时间空隙，当前状态为 ACTIVE
                        Instant suspendAt = subscriptionService.get(bill.getSubscriptionId()).lastEffectiveAt();
                        subscriptionService.resume(bill.getSubscriptionId(), suspendAt);
                        log.info("[payment] overdue paid bill={}, subscription={} resumed ACTIVE at {}; "
                                        + "basis: no unpaid bills remain, resume event same instant as suspend",
                                billId, bill.getSubscriptionId(), suspendAt);
                    } catch (BillingException ex) {
                        // 已退订等终态不可恢复，属预期
                        log.info("[payment] resume skipped after payOverdue bill={}: {}", billId, ex.getMessage());
                    }
                } else {
                    log.info("[payment] bill={} paid but subscription={} stays suspended: other unpaid bills",
                            billId, bill.getSubscriptionId());
                }
            }
        }
        return attempt;
    }

    /**
     * 退款：为已付账单开立独立负向 REFUND 票并走通道退款（支持部分退款，累计不得超过已付）。
     * 退款票与原票都保留，资金流向由反向分录表达，守恒不变。
     */
    public Bill refund(String paidBillId, long amountCents, String reason) {
        if (amountCents <= 0) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT, "refund amount must be > 0");
        }
        Bill original = requireBill(paidBillId);
        synchronized (billRepository.lockOf(paidBillId)) {
            if (original.getStatus() != BillStatus.PAID) {
                throw new BillingException(ErrorCode.BILL_NOT_PAYABLE,
                        "only PAID bill can be refunded: " + paidBillId + " is " + original.getStatus());
            }
            // 已累计退款额
            long refunded = billRepository.findBySubscription(original.getSubscriptionId()).stream()
                    .filter(b -> b.getStatus() == BillStatus.REFUND)
                    .filter(b -> paidBillId.equals(originalPaidBillId(b)))
                    .mapToLong(b -> -b.getAmount().cents())
                    .sum();
            long paidNet = original.getAmount().cents();
            if (refunded + amountCents > paidNet) {
                throw new BillingException(ErrorCode.INVALID_ARGUMENT,
                        "refund exceeds paid amount: paid=" + paidNet + " alreadyRefunded=" + refunded
                                + " requested=" + amountCents);
            }

            String refundRef = "refund:" + paidBillId + ":" + UUID.randomUUID();
            String chargeRef = original.getPaidByReference();
            PaymentResult result = gateway.refund(refundRef, chargeRef, amountCents);
            Instant now = clock.now();
            BillLine line = new BillLine(BillLineKind.REFUND, null,
                    "refund against " + paidBillId + ": " + reason, -amountCents);
            Bill refundBill = Bill.refund(original.getSubscriptionId(), original.getPeriodKey(),
                    original.getPeriodStart(), original.getPeriodEndExclusive(),
                    List.of(line), now, original.getInvoiceGroupId());
            billRepository.save(refundBill);
            paymentRepository.save(new PaymentAttempt(refundRef, refundBill.getId(),
                    original.getSubscriptionId(), amountCents, true, result,
                    result == PaymentResult.SUCCESS ? null : "refund failed", now));
            if (result == PaymentResult.SUCCESS) {
                postRefundEntries(refundBill, refundRef, chargeRef, amountCents, now);
            }
            log.info("[payment] refund bill={} against={} amount={} ref={}; basis: independent negative bill, "
                            + "reverse ledger entries, conservation unchanged (fees non-refundable)",
                    refundBill.getId(), paidBillId, amountCents, refundRef);
            return refundBill;
        }
    }

    /** 从退款票备注中还原原票 id（简洁起见用发票组+行描述不便，直接在 memo 编码）。 */
    private String originalPaidBillId(Bill refundBill) {
        return refundBill.getLines().stream()
                .filter(l -> l.kind() == BillLineKind.REFUND)
                .findFirst()
                .map(l -> {
                    String marker = "refund against ";
                    int i = l.description().indexOf(marker);
                    if (i < 0) {
                        return "";
                    }
                    String tail = l.description().substring(i + marker.length());
                    int colon = tail.indexOf(':');
                    return colon > 0 ? tail.substring(0, colon) : tail;
                })
                .orElse("");
    }

    private void postChargeEntries(Bill bill, String reference, long amount, Instant at) {
        long fee = gateway instanceof LocalMockPaymentGateway m ? LocalMockPaymentGateway.FEE_CENTS : 0L;
        long net = amount - fee;
        String group = UUID.randomUUID().toString();
        // 1) 资金流入：借清算，贷订阅方已付
        ledgerRepository.post(new LedgerEntry("le-" + UUID.randomUUID(), bill.getId(), reference,
                AccountType.CASH_CLEARING, amount, 0, at, "charge-in " + group));
        ledgerRepository.post(new LedgerEntry("le-" + UUID.randomUUID(), bill.getId(), reference,
                AccountType.SUBSCRIBER_PAID, 0, amount, at, "charge-in " + group));
        // 2) 收入归属拆分：借订阅方已付，贷平台收入/通道手续费
        ledgerRepository.post(new LedgerEntry("le-" + UUID.randomUUID(), bill.getId(), reference,
                AccountType.SUBSCRIBER_PAID, amount, 0, at, "charge-alloc " + group));
        ledgerRepository.post(new LedgerEntry("le-" + UUID.randomUUID(), bill.getId(), reference,
                AccountType.PLATFORM_REVENUE, 0, net, at, "charge-alloc " + group));
        ledgerRepository.post(new LedgerEntry("le-" + UUID.randomUUID(), bill.getId(), reference,
                AccountType.GATEWAY_FEES, 0, fee, at, "charge-alloc " + group));
    }

    private void postRefundEntries(Bill refundBill, String refundRef, String chargeRef,
                                   long amount, Instant at) {
        String group = UUID.randomUUID().toString();
        // 1) 收入冲减：借平台收入，贷订阅方已付（手续费不退）
        ledgerRepository.post(new LedgerEntry("le-" + UUID.randomUUID(), refundBill.getId(), refundRef,
                AccountType.PLATFORM_REVENUE, amount, 0, at, "refund " + group + " of " + chargeRef));
        ledgerRepository.post(new LedgerEntry("le-" + UUID.randomUUID(), refundBill.getId(), refundRef,
                AccountType.SUBSCRIBER_PAID, 0, amount, at, "refund " + group + " of " + chargeRef));
        // 2) 资金流出：借订阅方已付，贷清算
        ledgerRepository.post(new LedgerEntry("le-" + UUID.randomUUID(), refundBill.getId(), refundRef,
                AccountType.SUBSCRIBER_PAID, amount, 0, at, "refund-out " + group + " of " + chargeRef));
        ledgerRepository.post(new LedgerEntry("le-" + UUID.randomUUID(), refundBill.getId(), refundRef,
                AccountType.CASH_CLEARING, 0, amount, at, "refund-out " + group + " of " + chargeRef));
    }

    private Bill requireBill(String billId) {
        return billRepository.findById(billId)
                .orElseThrow(() -> new BillingException(ErrorCode.BILL_NOT_FOUND, "bill not found: " + billId));
    }

    public List<PaymentAttempt> attemptsOf(String billId) {
        requireBill(billId);
        return paymentRepository.findByBill(billId);
    }
}

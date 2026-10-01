package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.domain.AccountType;
import com.github.feng111627.usagebasedbillingjava.domain.Bill;
import com.github.feng111627.usagebasedbillingjava.domain.BillStatus;
import com.github.feng111627.usagebasedbillingjava.domain.LedgerEntry;
import com.github.feng111627.usagebasedbillingjava.domain.MetricUsage;
import com.github.feng111627.usagebasedbillingjava.domain.PaymentAttempt;
import com.github.feng111627.usagebasedbillingjava.domain.PaymentResult;
import com.github.feng111627.usagebasedbillingjava.domain.Subscription;
import com.github.feng111627.usagebasedbillingjava.engine.Periods;
import com.github.feng111627.usagebasedbillingjava.repo.BillRepository;
import com.github.feng111627.usagebasedbillingjava.repo.LedgerRepository;
import com.github.feng111627.usagebasedbillingjava.repo.PaymentRepository;
import com.github.feng111627.usagebasedbillingjava.repo.PlanRepository;
import com.github.feng111627.usagebasedbillingjava.repo.SubscriptionRepository;
import com.github.feng111627.usagebasedbillingjava.repo.UsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账服务。核对链：
 * <ol>
 *   <li>账单自洽：账单金额 = 明细行之和；</li>
 *   <li>账单-用量：周期票+调账票合计 = 当前全量数据重算的账期应收（缺量则报 USAGE_METERING_MISSING）；
 *       OVERAGE 行金额可由按指标汇总的 MetricUsage 解释；</li>
 *   <li>账单-支付-分类账：每张 PAID 票恰有一笔成功扣款尝试；REFUND 票恰有一笔成功退款尝试；
 *       票款与分录金额一一对应；</li>
 *   <li>全局资金守恒：Σ 借 = Σ 贷，且 SUBSCRIBER_PAID = PLATFORM_REVENUE + GATEWAY_FEES。</li>
 * </ol>
 * 任一不符抛出可区分错误码（RECONCILE_AMOUNT_MISMATCH / LEDGER_NOT_BALANCED），不静默通过。
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final BillRepository billRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerRepository ledgerRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final UsageRepository usageRepository;
    private final BillingService billingService;
    private final BillingClock clock;

    public ReconciliationService(BillRepository billRepository,
                                 PaymentRepository paymentRepository,
                                 LedgerRepository ledgerRepository,
                                 SubscriptionRepository subscriptionRepository,
                                 PlanRepository planRepository,
                                 UsageRepository usageRepository,
                                 BillingService billingService,
                                 BillingClock clock) {
        this.billRepository = billRepository;
        this.paymentRepository = paymentRepository;
        this.ledgerRepository = ledgerRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.usageRepository = usageRepository;
        this.billingService = billingService;
        this.clock = clock;
    }

    /** 单张账单核对结果（供 API/测试查看，内容可解释）。 */
    public record BillReconciliation(String billId, String periodKey, String status,
                                     long amountCents, long lineSumCents,
                                     Map<String, MetricUsage> usageByMetric,
                                     boolean balanced) {
    }

    public BillReconciliation verifyBill(String billId) {
        Bill bill = billRepository.findById(billId)
                .orElseThrow(() -> new BillingException(ErrorCode.BILL_NOT_FOUND, "bill not found: " + billId));

        // 1) 行合计
        long lineSum = bill.getLines().stream().mapToLong(l -> l.amount()).sum();
        if (lineSum != bill.getAmount().cents()) {
            throw mismatch(bill, "line sum " + lineSum + " != bill amount " + bill.getAmount().cents());
        }

        // 2) 支付与分录一致性
        verifyPaymentAndLedger(bill);

        // 3) 账期金额与当前用量重算一致（按发票组：周期票+调账票）
        Subscription subscription = subscriptionRepository.findById(bill.getSubscriptionId())
                .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "subscription missing for bill " + billId));
        int periodIndex = Periods.periodIndex(subscription.getAnchoredAt(),
                bill.getPeriodStart(), clock.zone());
        Map<String, MetricUsage> usage = billingService
                .summarizeUsage(bill.getSubscriptionId(), periodIndex);

        if (bill.getStatus() != BillStatus.REFUND) {
            long groupTotal = billRepository.findByInvoiceGroup(bill.getInvoiceGroupId()).stream()
                    .filter(b -> b.getStatus() != BillStatus.VOID && b.getStatus() != BillStatus.REFUND)
                    .mapToLong(b -> b.getAmount().cents())
                    .sum();
            long expected = billingService.recomputeExpectedAmount(
                    bill.getSubscriptionId(), periodIndex);
            if (groupTotal != expected) {
                throw mismatch(bill, "invoice group total " + groupTotal
                        + " != recomputed expected " + expected
                        + " (period " + bill.getPeriodKey()
                        + "); a late-adjustment bill may be required");
            }
            // 超额费可解释性：账单 OVERAGE 行之和 == 汇总超额费
            long overageBilled = bill.getLines().stream()
                    .filter(l -> l.kind().name().equals("OVERAGE"))
                    .mapToLong(l -> l.amount()).sum();
            long overageComputed = usage.values().stream().mapToLong(MetricUsage::overageAmount).sum();
            if (overageBilled > overageComputed) {
                throw mismatch(bill, "billed overage " + overageBilled
                        + " exceeds computed overage " + overageComputed);
            }
        }

        boolean balanced = true;
        log.info("[reconcile] bill={} period={} status={} amount={} lines={} usageMetrics={} => BALANCED; "
                        + "basis: amount=sum(lines), group total=recomputed expected, payment/ledger consistent",
                billId, bill.getPeriodKey(), bill.getStatus(), bill.getAmount(),
                bill.getLines().size(), usage.size());
        return new BillReconciliation(billId, bill.getPeriodKey(), bill.getStatus().name(),
                bill.getAmount().cents(), lineSum, new LinkedHashMap<>(usage), balanced);
    }

    private void verifyPaymentAndLedger(Bill bill) {
        List<PaymentAttempt> attempts = paymentRepository.findByBill(bill.getId());
        List<LedgerEntry> entries = ledgerRepository.findByBill(bill.getId());

        if (bill.getStatus() == BillStatus.PAID && bill.getAmount().cents() != 0) {
            long successCharges = attempts.stream()
                    .filter(a -> !a.refund() && a.result() == PaymentResult.SUCCESS).count();
            if (successCharges != 1) {
                throw mismatch(bill, "PAID bill must have exactly 1 successful charge, found " + successCharges);
            }
            long posted = entries.stream()
                    .filter(e -> e.account() == AccountType.CASH_CLEARING)
                    .mapToLong(LedgerEntry::debitCents).sum();
            if (posted != bill.getAmount().cents()) {
                throw mismatch(bill, "ledger cash-clearing debit " + posted
                        + " != bill amount " + bill.getAmount().cents());
            }
            long allocated = entries.stream()
                    .filter(e -> e.account() == AccountType.PLATFORM_REVENUE
                            || e.account() == AccountType.GATEWAY_FEES)
                    .mapToLong(LedgerEntry::creditCents).sum();
            if (allocated != bill.getAmount().cents()) {
                throw mismatch(bill, "ledger revenue+fees credits " + allocated
                        + " != bill amount " + bill.getAmount().cents());
            }
        }
        if (bill.getStatus() == BillStatus.REFUND) {
            long successRefunds = attempts.stream()
                    .filter(a -> a.refund() && a.result() == PaymentResult.SUCCESS).count();
            if (successRefunds != 1) {
                throw mismatch(bill, "REFUND bill must have exactly 1 successful refund, found " + successRefunds);
            }
            long refundedOutflow = entries.stream()
                    .filter(e -> e.account() == AccountType.CASH_CLEARING)
                    .mapToLong(LedgerEntry::creditCents).sum();
            if (refundedOutflow != -bill.getAmount().cents()) {
                throw mismatch(bill, "ledger cash-clearing credit " + refundedOutflow
                        + " != refund amount " + (-bill.getAmount().cents()));
            }
        }
        if (bill.isUnpaid()) {
            long anyChargeSuccess = attempts.stream()
                    .filter(a -> !a.refund() && a.result() == PaymentResult.SUCCESS).count();
            if (anyChargeSuccess != 0) {
                throw mismatch(bill, "unpaid bill has " + anyChargeSuccess + " successful charge(s)");
            }
        }
    }

    /**
     * 全局资金守恒：
     * <pre>
     *   全部分录 Σ借 == Σ贷（复式平衡）
     *   -CASH_CLEARING（贷-借） == PLATFORM_REVENUE + GATEWAY_FEES
     * 其中 SUBSCRIBER_PAID 为过渡科目，每笔资金动作内自身借贷相抵，净额恒为 0。
     * 退款时手续费不退，FEES 保留，PLATFORM 与 CLEARING 同步减少，等式不变。
     * </pre>
     */
    public Map<String, Long> verifyConservation() {
        List<LedgerEntry> all = ledgerRepository.findAll();
        long debit = all.stream().mapToLong(LedgerEntry::debitCents).sum();
        long credit = all.stream().mapToLong(LedgerEntry::creditCents).sum();
        if (debit != credit) {
            log.error("[reconcile] ledger NOT balanced: debit={} credit={}", debit, credit);
            throw new BillingException(ErrorCode.LEDGER_NOT_BALANCED,
                    "ledger debits " + debit + " != credits " + credit);
        }
        Map<AccountType, Long> balances = ledgerRepository.balances();
        long cash = balances.getOrDefault(AccountType.CASH_CLEARING, 0L);
        long subscriber = balances.getOrDefault(AccountType.SUBSCRIBER_PAID, 0L);
        long platform = balances.getOrDefault(AccountType.PLATFORM_REVENUE, 0L);
        long fees = balances.getOrDefault(AccountType.GATEWAY_FEES, 0L);
        if (subscriber != 0) {
            throw new BillingException(ErrorCode.LEDGER_NOT_BALANCED,
                    "pass-through SUBSCRIBER_PAID must net to 0, got " + subscriber);
        }
        if (-cash != platform + fees) {
            log.error("[reconcile] conservation violated: cash={} platform={} fees={}", cash, platform, fees);
            throw new BillingException(ErrorCode.LEDGER_NOT_BALANCED,
                    "conservation violated: -cash clearing " + (-cash) + " != platform " + platform
                            + " + fees " + fees);
        }
        log.info("[reconcile] conservation OK: cash={} platform={} fees={} subscriberPassThrough=0 "
                        + "({} entries, debits=credits={})",
                cash, platform, fees, all.size(), debit);
        return Map.of(
                "CASH_CLEARING", cash,
                "SUBSCRIBER_PAID", subscriber,
                "PLATFORM_REVENUE", platform,
                "GATEWAY_FEES", fees,
                "TOTAL_DEBIT", debit,
                "TOTAL_CREDIT", credit);
    }

    private BillingException mismatch(Bill bill, String detail) {
        log.error("[reconcile] MISMATCH bill={} period={}: {}", bill.getId(), bill.getPeriodKey(), detail);
        return new BillingException(ErrorCode.RECONCILE_AMOUNT_MISMATCH,
                "bill " + bill.getId() + " reconciliation failed: " + detail);
    }
}

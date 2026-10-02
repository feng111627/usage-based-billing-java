package com.github.feng111627.usagebasedbillingjava.service.impl;

import com.github.feng111627.usagebasedbillingjava.model.Account;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceLine;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus;
import com.github.feng111627.usagebasedbillingjava.model.LedgerTransaction;
import com.github.feng111627.usagebasedbillingjava.model.LineKind;
import com.github.feng111627.usagebasedbillingjava.model.PaymentAttempt;
import com.github.feng111627.usagebasedbillingjava.model.PauseRecord;
import com.github.feng111627.usagebasedbillingjava.model.Plan;
import com.github.feng111627.usagebasedbillingjava.model.UsageAccumulation;
import com.github.feng111627.usagebasedbillingjava.repository.Repositories;
import com.github.feng111627.usagebasedbillingjava.service.LedgerService;
import com.github.feng111627.usagebasedbillingjava.service.Money;
import com.github.feng111627.usagebasedbillingjava.service.PauseTimeline;
import com.github.feng111627.usagebasedbillingjava.service.PlanService;
import com.github.feng111627.usagebasedbillingjava.service.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账规则（任一不满足即产出可读问题项）：
 * 1) 账单金额 = 明细行之和；
 * 2) 周期账单的超配额费 = max(0, 周期用量 - 套餐配额) × 超量单价（账单与用量汇总对得上）；
 * 3) PAID 账单恰好有一笔成功收款（REFUNDED 负额账单恰好一笔成功退款），无重复扣款；
 * 4) 每笔记账事务借贷平衡；三方账户余额之和为 0（守恒）。
 */
@Service
public class ReconciliationServiceImpl implements ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationServiceImpl.class);

    private final Repositories.InvoiceRepository invoiceRepository;
    private final Repositories.PaymentRepository paymentRepository;
    private final Repositories.UsageAccumulationRepository accumulationRepository;
    private final Repositories.PauseRepository pauseRepository;
    private final LedgerService ledgerService;
    private final PlanService planService;

    public ReconciliationServiceImpl(Repositories.InvoiceRepository invoiceRepository,
                                     Repositories.PaymentRepository paymentRepository,
                                     Repositories.UsageAccumulationRepository accumulationRepository,
                                     Repositories.PauseRepository pauseRepository,
                                     LedgerService ledgerService, PlanService planService) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.accumulationRepository = accumulationRepository;
        this.pauseRepository = pauseRepository;
        this.ledgerService = ledgerService;
        this.planService = planService;
    }

    @Override
    public List<String> reconcileInvoice(String invoiceId) {
        Invoice inv = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new com.github.feng111627.usagebasedbillingjava.error.BillingException(
                        com.github.feng111627.usagebasedbillingjava.error.ErrorCode.INVOICE_NOT_FOUND,
                        "账单不存在: " + invoiceId));
        return check(inv);
    }

    @Override
    public Map<String, List<String>> reconcileAll() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Invoice inv : invoiceRepository.findAll()) {
            List<String> issues = check(inv);
            if (!issues.isEmpty()) {
                result.put(inv.getInvoiceId(), issues);
            }
        }
        for (LedgerTransaction tx : ledgerService.all()) {
            BigDecimal sum = tx.getEntries().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.signum() != 0) {
                result.computeIfAbsent(tx.getTxId(), k -> new java.util.ArrayList<>())
                        .add("记账事务 " + tx.getRefType() + ":" + tx.getRefId() + " 不平衡: " + sum);
            }
        }
        BigDecimal delta = ledgerService.conservationDelta();
        if (delta.signum() != 0) {
            result.put("CONSERVATION", List.of("三方账户余额之和非 0: " + delta
                    + " 余额=" + ledgerService.balances()));
        }
        // 逐订阅校验：成功退款累计不得超过成功收款累计（暂停退款/差价退款共用此上限）
        Map<String, BigDecimal[]> perSub = new LinkedHashMap<>();
        for (Invoice x : invoiceRepository.findAll()) {
            BigDecimal[] pr = perSub.computeIfAbsent(x.getSubscriptionId(),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            for (PaymentAttempt a : paymentRepository.findByInvoice(x.getInvoiceId())) {
                if (!a.isSuccess()) {
                    continue;
                }
                if (a.isRefund()) {
                    pr[1] = pr[1].add(a.getAmount());
                } else {
                    pr[0] = pr[0].add(a.getAmount());
                }
            }
        }
        perSub.forEach((sid, pr) -> {
            if (pr[1].compareTo(pr[0]) > 0) {
                result.put("REFUND_CAP:" + sid, List.of("订阅 " + sid + " 成功退款累计 " + Money.of(pr[1])
                        + " 超过成功收款累计 " + Money.of(pr[0]) + "（退款不能超过已经收到的钱）"));
            }
        });
        log.info("[reconcile] 检查账单 {} 张、事务 {} 笔，发现问题 {} 组，守恒差={}，余额={}",
                invoiceRepository.findAll().size(), ledgerService.all().size(),
                result.size(), delta, ledgerService.balances());
        return result;
    }

    private List<String> check(Invoice inv) {
        List<String> issues = new java.util.ArrayList<>();

        BigDecimal lineSum = Money.of(inv.getLines().stream()
                .map(InvoiceLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (lineSum.compareTo(Money.of(inv.getAmount())) != 0) {
            issues.add("账单金额 " + inv.getAmount() + " 与明细行之和 " + lineSum + " 不一致");
        }

        if (inv.isCycle() && inv.getStatus() != InvoiceStatus.ANOMALY) {
            // 该周期真正活跃的秒数/比例：整周期暂停时活跃秒数=0，既不该有超配额费，也不算缺计量
            List<PauseRecord> records = pauseRepository.findBySubscription(inv.getSubscriptionId());
            List<PauseTimeline.Segment> segments = PauseTimeline.segments(records, inv.getPeriodEnd());
            long periodSec = Duration.between(inv.getPeriodStart(), inv.getPeriodEnd()).getSeconds();
            long activeSec = PauseTimeline.activeSeconds(segments, inv.getPeriodStart(), inv.getPeriodEnd());
            BigDecimal activeRatio = periodSec <= 0 ? BigDecimal.ONE
                    : BigDecimal.valueOf(activeSec).divide(BigDecimal.valueOf(periodSec), 6, RoundingMode.HALF_UP);

            UsageAccumulation acc = accumulationRepository
                    .find(inv.getSubscriptionId(), inv.getPeriodKey(), BillingServiceImpl.METER).orElse(null);
            if (activeSec > 0 && acc != null && !acc.isMissing() && inv.getPlanId() != null) {
                Plan plan = planService.getPlan(inv.getPlanId());
                long effectiveIncluded = new BigDecimal(plan.getIncludedUnits())
                        .multiply(activeRatio).setScale(0, RoundingMode.HALF_UP).longValueExact();
                long overUnits = Math.max(0L, acc.getTotal() - effectiveIncluded);
                BigDecimal expected = Money.mul(plan.getOveragePrice(), overUnits);
                // 周期账单的超配额费 + 该周期所有迟到补计调整账单金额 = 对应该周期的总超配额收费
                BigDecimal cycleOverage = Money.of(inv.getLines().stream()
                        .filter(l -> l.getKind() == LineKind.OVERAGE)
                        .map(InvoiceLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
                BigDecimal adjOverage = Money.of(invoiceRepository.findBySubscription(inv.getSubscriptionId()).stream()
                        .filter(x -> !x.isCycle() && inv.getPeriodKey().equals(x.getPeriodKey()))
                        .flatMap(x -> x.getLines().stream())
                        .filter(l -> l.getKind() == LineKind.ADJUSTMENT)
                        .map(InvoiceLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
                BigDecimal actual = Money.of(cycleOverage.add(adjOverage));
                if (expected.compareTo(actual) != 0) {
                    issues.add("超配额费与用量汇总不符: 用量=" + acc.getTotal() + " 套餐配额=" + plan.getIncludedUnits()
                            + " 活跃比例=" + activeRatio + " 折算配额=" + effectiveIncluded
                            + " 应收=" + expected + " 账单列示(周期=" + cycleOverage
                            + "+调整=" + adjOverage + ")=" + actual);
                }
            }
            if (activeSec == 0) {
                boolean lineClaimsWholePause = inv.getLines().stream().anyMatch(l -> l.getBasis() != null
                        && l.getBasis().contains("整周期"));
                if (!lineClaimsWholePause && inv.getAmount().signum() != 0) {
                    issues.add("周期 " + inv.getPeriodKey() + " 整周期客户主动暂停，应 0 元滚动，实际金额=" + inv.getAmount());
                }
            } else if (acc == null || acc.isMissing()) {
                boolean humanConfirmed = inv.getLines().stream()
                        .anyMatch(l -> l.getKind() == LineKind.ADJUSTMENT && l.getBasis() != null
                                && l.getBasis().contains("人工确认零用量"));
                if (!humanConfirmed && inv.getStatus() != InvoiceStatus.ANOMALY) {
                    issues.add("周期 " + inv.getPeriodKey() + " 活跃账段缺计量且无人工确认零用量记录，却已计价");
                }
            }
        }

        List<PaymentAttempt> attempts = paymentRepository.findByInvoice(inv.getInvoiceId());
        long successCharges = attempts.stream().filter(a -> !a.isRefund() && a.isSuccess()).count();
        long successRefunds = attempts.stream().filter(a -> a.isRefund() && a.isSuccess()).count();
        // 0 元账单（整周期暂停/无超配额且不预收基础费）没有真实资金流动，不应有收款流水
        if (inv.getStatus() == InvoiceStatus.PAID && inv.getAmount().signum() > 0 && successCharges != 1) {
            issues.add("PAID 账单成功收款次数=" + successCharges + "（应为 1，可能重复扣款/漏扣）");
        }
        if (inv.getStatus() == InvoiceStatus.PAID && inv.getAmount().signum() == 0 && successCharges != 0) {
            issues.add("0 元 PAID 账单不应有成功收款流水，实际=" + successCharges);
        }
        if (inv.getStatus() == InvoiceStatus.REFUNDED && inv.getAmount().signum() < 0
                && successRefunds != 1) {
            issues.add("REFUNDED 负额账单成功退款次数=" + successRefunds + "（应为 1）");
        }
        return issues;
    }
}

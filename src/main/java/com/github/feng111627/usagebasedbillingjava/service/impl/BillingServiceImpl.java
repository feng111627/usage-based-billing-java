package com.github.feng111627.usagebasedbillingjava.service.impl;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.Account;
import com.github.feng111627.usagebasedbillingjava.model.ChangeType;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceLine;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus;
import com.github.feng111627.usagebasedbillingjava.model.LineKind;
import com.github.feng111627.usagebasedbillingjava.model.PauseRecord;
import com.github.feng111627.usagebasedbillingjava.model.Plan;
import com.github.feng111627.usagebasedbillingjava.model.PlanChange;
import com.github.feng111627.usagebasedbillingjava.model.Subscription;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.model.UsageAccumulation;
import com.github.feng111627.usagebasedbillingjava.model.UsageEvent;
import com.github.feng111627.usagebasedbillingjava.repository.Repositories;
import com.github.feng111627.usagebasedbillingjava.service.BillingCalendar;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.Money;
import com.github.feng111627.usagebasedbillingjava.service.PauseTimeline;
import com.github.feng111627.usagebasedbillingjava.service.PaymentService;
import com.github.feng111627.usagebasedbillingjava.service.PlanService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionLocks;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 周期出账与收款状态机。
 *
 * <p>出账口径（账单必须与用量汇总对得上）：
 * 周期账单 = 上一周期超配额费 OVERAGE + 下一周期基础费 BASE，每行写明判定依据；
 * 中途升降配差价另立 PRORATION 账单，不在此重复。
 *
 * <p>状态流转：ANOMALY(缺计量，人工确认零用量/补报) → PENDING →
 * FAILED(3 天自动重试窗口) → PAST_DUE(7 天宽限) → SUSPENDED；
 * 任意未付节点补缴成功 → PAID 且订阅恢复 ACTIVE 并滚动周期。
 */
@Service
public class BillingServiceImpl implements BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingServiceImpl.class);

    /** 计量项名称：仅对该计量项做超配额计费与缺计量判定。 */
    public static final String METER = "api_calls";

    private static final Duration RETRY_WINDOW = Duration.ofDays(3);
    private static final Duration GRACE_WINDOW = Duration.ofDays(7);

    private final Repositories.SubscriptionRepository subscriptionRepository;
    private final Repositories.InvoiceRepository invoiceRepository;
    private final Repositories.UsageAccumulationRepository accumulationRepository;
    private final Repositories.UsageEventRepository eventRepository;
    private final Repositories.ChangeRepository changeRepository;
    private final Repositories.PaymentRepository paymentRepository;
    private final Repositories.PauseRepository pauseRepository;
    private final PlanService planService;
    private final PaymentService paymentService;
    private final BillingCalendar calendar;
    private final ClockService clock;
    private final SubscriptionLocks locks;

    public BillingServiceImpl(Repositories.SubscriptionRepository subscriptionRepository,
                              Repositories.InvoiceRepository invoiceRepository,
                              Repositories.UsageAccumulationRepository accumulationRepository,
                              Repositories.UsageEventRepository eventRepository,
                              Repositories.ChangeRepository changeRepository,
                              Repositories.PaymentRepository paymentRepository,
                              Repositories.PauseRepository pauseRepository,
                              PlanService planService, PaymentService paymentService,
                              BillingCalendar calendar, ClockService clock, SubscriptionLocks locks) {
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.accumulationRepository = accumulationRepository;
        this.eventRepository = eventRepository;
        this.changeRepository = changeRepository;
        this.paymentRepository = paymentRepository;
        this.pauseRepository = pauseRepository;
        this.planService = planService;
        this.paymentService = paymentService;
        this.calendar = calendar;
        this.clock = clock;
        this.locks = locks;
    }

    @Override
    public Invoice closePeriod(String subscriptionId) {
        Subscription sub = require(subscriptionId);
        return locks.withLock(subscriptionId, () -> {
            Instant now = clock.now();
            Instant start = sub.getCurrentPeriodStart();
            Instant end = sub.getCurrentPeriodEnd();
            String periodKey = calendar.periodKey(start, sub.getAnchorDay());
            if (now.isBefore(end)) {
                throw new BillingException(ErrorCode.NOTHING_TO_CLOSE,
                        "当前周期尚未结束: " + start + " ~ " + end + "（现在 " + now + "），不能出账");
            }
            if (invoiceRepository.findBySubscriptionAndPeriod(subscriptionId, periodKey)
                    .filter(Invoice::isCycle).isPresent()) {
                throw new BillingException(ErrorCode.DUPLICATE_REQUEST,
                        "周期 " + periodKey + " 已出账，重复出账被拒绝");
            }
            if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_INACTIVE, "订阅已退订，无周期账单");
            }

            UsageAccumulation acc = accumulationRepository.find(subscriptionId, periodKey, METER).orElse(null);
            Plan endingPlan = planAtPeriodEnd(sub, end);

            // 本周期真正活跃（未处于客户主动暂停）的秒数与配额折算口径
            List<PauseRecord> pauseRecords = pauseRepository.findBySubscription(subscriptionId);
            List<PauseTimeline.Segment> segments = PauseTimeline.segments(pauseRecords, end);
            long periodSeconds = Duration.between(start, end).getSeconds();
            long activeSeconds = PauseTimeline.activeSeconds(segments, start, end);
            BigDecimal activeRatio = periodSeconds <= 0 ? BigDecimal.ONE
                    : BigDecimal.valueOf(activeSeconds)
                            .divide(BigDecimal.valueOf(periodSeconds), 6, RoundingMode.HALF_UP);
            // 配额按真正活跃时长等比例折算（暂停掉的时间不算在内），半秒以下 HALF_UP
            long effectiveIncluded = new BigDecimal(endingPlan.getIncludedUnits())
                    .multiply(activeRatio)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            boolean whollyPaused = activeSeconds == 0;

            Invoice inv = new Invoice();
            inv.setInvoiceId("INV-" + subscriptionId + "-CYC-" + periodKey);
            inv.setSubscriptionId(subscriptionId);
            inv.setPeriodKey(periodKey);
            inv.setPeriodStart(start);
            inv.setPeriodEnd(end);
            inv.setIssuedAt(now);
            inv.setCycle(true);
            inv.setPlanId(endingPlan.getPlanId());

            if (whollyPaused) {
                // 整个周期都处于客户主动暂停：不产生基础费/超配额费；无用量是暂停的确定结果，
                // 绝不能误判为缺计量异常。出 0 元周期账单并正常滚动（订阅保持 PAUSED）。
                inv.getLines().add(new InvoiceLine(LineKind.ADJUSTMENT,
                        "整周期客户主动暂停，无基础费且无用量属确定结果（非缺计量）",
                        BigDecimal.ZERO.setScale(2),
                        "周期 " + start + " ~ " + end + " 活跃秒数=0（整周期暂停），活跃比例=0，"
                                + "折算配额=0，不收基础费/超配额费；对账不得判缺计量"));
                inv.setAmount(BigDecimal.ZERO.setScale(2));
                inv.setStatus(InvoiceStatus.PAID);
                inv.setPaidAt(now);
                invoiceRepository.save(inv);
                rolloverKeepingPause(sub, inv);
                log.info("[close-period] 输入 sub={} period={}；判定依据：整周期客户主动暂停（活跃比例=0）-> 0 元、无异常、滚动并保持 PAUSED",
                        subscriptionId, periodKey);
                return invoiceRepository.findById(inv.getInvoiceId()).orElseThrow();
            }

            if (acc == null || acc.isMissing()) {
                // 缺计量（仅针对周期内存在活跃时长的情况）：绝不悄悄按零，账单挂 ANOMALY，不计费、不滚动周期
                inv.setAmount(BigDecimal.ZERO.setScale(2));
                inv.setStatus(InvoiceStatus.ANOMALY);
                inv.setAnomalyReason("计量项 " + METER + " 在周期 " + periodKey + " 的活跃账段无任何上报（缺失），"
                        + "活跃比例=" + activeRatio + "；需补报或由人工确认零用量；整周期暂停不算缺失");
                invoiceRepository.save(inv);
                log.warn("[close-period] 输入 sub={} period={}；判定依据：活跃比例={} 但 {} -> 账单 ANOMALY（不计费）",
                        subscriptionId, periodKey, activeRatio, inv.getAnomalyReason());
                return inv;
            }

            long used = acc.getTotal();
            long overUnits = Math.max(0L, used - effectiveIncluded);
            BigDecimal overage = Money.mul(endingPlan.getOveragePrice(), overUnits);
            inv.getLines().add(new InvoiceLine(LineKind.OVERAGE,
                    "周期 " + periodKey + " 超配额 " + METER, overage,
                    "用量=" + used + " 套餐配额=" + endingPlan.getIncludedUnits()
                            + " 活跃比例=" + activeRatio + "（活跃秒=" + activeSeconds + "/" + periodSeconds
                            + "）折算配额=" + effectiveIncluded + " 超出=" + overUnits
                            + " 单价=" + endingPlan.getOveragePrice()));

            BigDecimal total = overage;
            if (sub.getStatus() == SubscriptionStatus.ACTIVE) {
                // 下一周期基础费按订阅当前套餐预收（中途差价/暂停结算已由独立账单结清，此处不重复）
                Plan nextPlan = planService.getPlan(sub.getCurrentPlanId());
                BigDecimal nextBase = Money.of(nextPlan.getBasePrice());
                Instant ns = end;
                Instant ne = calendar.nextPeriodStart(end, sub.getAnchorDay());
                inv.getLines().add(new InvoiceLine(LineKind.BASE,
                        "下一周期基础费 " + nextPlan.getName(), nextBase,
                        "预付 " + ns + " ~ " + ne + " basePrice=" + nextPlan.getBasePrice()
                                + "（当前套餐 " + nextPlan.getPlanId() + "）"));
                total = total.add(nextBase);
            } else {
                log.info("[close-period] sub={} 状态={}，不预收下一周期基础费", subscriptionId, sub.getStatus());
            }
            inv.setAmount(Money.of(total));
            inv.setStatus(InvoiceStatus.PENDING);
            invoiceRepository.save(inv);
            log.info("[close-period] 输入 sub={} period={}；判定依据：plan={} 用量={} 活跃比例={} 折算配额={} 超量={} 超配额费={}；账单金额={} PENDING",
                    subscriptionId, periodKey, endingPlan.getPlanId(), used, activeRatio,
                    effectiveIncluded, overUnits, overage, inv.getAmount());

            if (total.signum() == 0) {
                // 暂停后恢复且本周期用量未超折算配额：无基础费（暂停/恢复已比例结算）、无超配额，0 元直接收讫滚动
                inv.setStatus(InvoiceStatus.PAID);
                inv.setPaidAt(now);
                invoiceRepository.save(inv);
                rolloverKeepingPause(sub, inv);
                return invoiceRepository.findById(inv.getInvoiceId()).orElseThrow();
            }

            // 出账收款失败：账单落 FAILED（可解释的确定状态），同时把 GATEWAY_REJECTED 抛给调用方分类处理
            try {
                paymentService.charge(inv, "cycle:" + subscriptionId + ":" + periodKey);
            } catch (BillingException ex) {
                if (ex.getCode() != ErrorCode.GATEWAY_REJECTED) {
                    throw ex;
                }
                Subscription failedSub = require(subscriptionId);
                if (failedSub.getStatus() == SubscriptionStatus.ACTIVE) {
                    failedSub.setStatus(SubscriptionStatus.PAST_DUE);
                    subscriptionRepository.save(failedSub);
                }
                throw ex;
            }
            return invoiceRepository.findById(inv.getInvoiceId()).orElseThrow();
        });
    }

    /** 周期滚动：已收款周期推进到下一周期，但客户主动暂停态必须保持 PAUSED，不得被滚成 ACTIVE。 */
    private void rolloverKeepingPause(Subscription sub, Invoice inv) {
        if (!inv.isCycle() || inv.getStatus() != InvoiceStatus.PAID) {
            return;
        }
        if (!inv.getPeriodStart().equals(sub.getCurrentPeriodStart())) {
            return;
        }
        boolean keepPaused = sub.getStatus() == SubscriptionStatus.PAUSED;
        Instant ns = inv.getPeriodEnd();
        Instant ne = calendar.nextPeriodStart(ns, sub.getAnchorDay());
        sub.setCurrentPeriodStart(ns);
        sub.setCurrentPeriodEnd(ne);
        sub.setStatus(keepPaused ? SubscriptionStatus.PAUSED : SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(sub);
        log.info("[rollover] sub={} 周期账单 {} 收讫 -> 滚动到新周期 {} ~ {}，订阅状态保持 {}",
                sub.getSubscriptionId(), inv.getInvoiceId(), ns, ne, sub.getStatus());
    }

    @Override
    public Invoice confirmZeroUsage(String invoiceId, String operator) {
        Invoice inv = getInvoice(invoiceId);
        return locks.withLock(inv.getSubscriptionId(), () -> {
            if (inv.getStatus() != InvoiceStatus.ANOMALY) {
                throw new BillingException(ErrorCode.CONFLICT,
                        "账单不是 ANOMALY 状态，无需确认: " + inv.getStatus());
            }
            if (operator == null || operator.isBlank()) {
                throw new BillingException(ErrorCode.VALIDATION, "确认零用量必须提供 operator 以留痕");
            }
            inv.setConfirmedZeroBy(operator);
            // 无用量则无超配额费；若订阅仍活跃，补下一周期基础费
            Subscription sub = require(inv.getSubscriptionId());
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            if (sub.getStatus() == SubscriptionStatus.ACTIVE) {
                Plan p = planService.getPlan(sub.getCurrentPlanId());
                BigDecimal nextBase = Money.of(p.getBasePrice());
                Instant ne = calendar.nextPeriodStart(inv.getPeriodEnd(), sub.getAnchorDay());
                inv.getLines().add(new InvoiceLine(LineKind.BASE,
                        "下一周期基础费 " + p.getName() + "（缺计量经人工确认零用量后补计）", nextBase,
                        "operator=" + operator + " 预付 " + inv.getPeriodEnd() + " ~ " + ne));
                total = total.add(nextBase);
            }
            inv.getLines().add(new InvoiceLine(LineKind.ADJUSTMENT,
                    "缺计量人工确认零用量", BigDecimal.ZERO.setScale(2),
                    "operator=" + operator + " 人工确认零用量 周期 " + inv.getPeriodKey()
                            + " 用量按确认的零处理（有留痕，非默认零）"));
            inv.setAnomalyReason(null);
            inv.setAmount(Money.of(total));
            inv.setStatus(InvoiceStatus.PENDING);
            invoiceRepository.save(inv);
            log.info("[confirm-zero] invoice={} operator={}；判定依据：人工确认缺计量周期零用量，补计基础费={} -> PENDING",
                    invoiceId, operator, total);
            settleOrMarkFailed(inv, "cycle:" + inv.getSubscriptionId() + ":" + inv.getPeriodKey());
            return invoiceRepository.findById(invoiceId).orElseThrow();
        });
    }

    @Override
    public Invoice appendLateUsage(String invoiceId, String eventId) {
        Invoice inv = getInvoice(invoiceId);
        return locks.withLock(inv.getSubscriptionId(), () -> {
            UsageEvent ev = eventRepository.findByEventId(eventId)
                    .orElseThrow(() -> new BillingException(ErrorCode.NOT_FOUND, "用量事件不存在: " + eventId));
            if (!ev.getSubscriptionId().equals(inv.getSubscriptionId())) {
                throw new BillingException(ErrorCode.VALIDATION, "事件不属于该账单的订阅");
            }
            String evPeriod = calendar.periodKey(ev.getUsageAt(),
                    require(inv.getSubscriptionId()).getAnchorDay());
            if (!evPeriod.equals(inv.getPeriodKey())) {
                throw new BillingException(ErrorCode.VALIDATION,
                        "事件周期 " + evPeriod + " 与账单周期 " + inv.getPeriodKey() + " 不一致");
            }
            // 跨所有账单判定该迟到事件是否已补计（原账单明细行 + ADJ 账单），杜绝重复计价
            boolean already = invoiceRepository.findBySubscription(inv.getSubscriptionId()).stream()
                    .flatMap(x -> x.getLines().stream())
                    .anyMatch(l -> l.getBasis() != null && l.getBasis().contains("eventId=" + eventId));
            if (already) {
                throw new BillingException(ErrorCode.DUPLICATE_REQUEST,
                        "迟到事件 " + eventId + " 已计入调整，重复追加被拒绝");
            }
            Plan plan = planService.getPlan(inv.getPlanId() != null ? inv.getPlanId()
                    : require(inv.getSubscriptionId()).getCurrentPlanId());

            // 重算该周期总用量与超配额（含本次迟到事件，累计行已含它），得到相对原账单的补计差额。
            // 配额按该周期真正活跃时长折算（暂停掉的时间不算），与出账口径一致、可对账。
            UsageAccumulation acc = accumulationRepository
                    .find(inv.getSubscriptionId(), inv.getPeriodKey(), METER).orElse(null);
            long usedTotal = acc != null ? acc.getTotal() : ev.getQuantity();
            List<PauseRecord> latePauseRecords = pauseRepository.findBySubscription(inv.getSubscriptionId());
            List<PauseTimeline.Segment> lateSegments = PauseTimeline.segments(latePauseRecords, inv.getPeriodEnd());
            long lateActiveSec = PauseTimeline.activeSeconds(lateSegments, inv.getPeriodStart(), inv.getPeriodEnd());
            long latePeriodSec = Duration.between(inv.getPeriodStart(), inv.getPeriodEnd()).getSeconds();
            BigDecimal lateActiveRatio = latePeriodSec <= 0 ? BigDecimal.ONE
                    : BigDecimal.valueOf(lateActiveSec)
                            .divide(BigDecimal.valueOf(latePeriodSec), 6, RoundingMode.HALF_UP);
            long effectiveIncluded = new BigDecimal(plan.getIncludedUnits())
                    .multiply(lateActiveRatio).setScale(0, RoundingMode.HALF_UP).longValueExact();
            long overUnits = Math.max(0L, usedTotal - effectiveIncluded);
            BigDecimal correctedOverage = Money.mul(plan.getOveragePrice(), overUnits);
            BigDecimal priorOverage = inv.getLines().stream()
                    .filter(l -> l.getKind() == LineKind.OVERAGE)
                    .map(InvoiceLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal extra = Money.of(correctedOverage.subtract(priorOverage));

            InvoiceLine line = new InvoiceLine(LineKind.ADJUSTMENT,
                    "迟到用量补计 eventId=" + eventId, extra,
                    "eventId=" + eventId + " qty=" + ev.getQuantity() + " 周期总用量=" + usedTotal
                            + " 活跃比例=" + lateActiveRatio + " 折算配额=" + effectiveIncluded
                            + " 重算超配额费=" + correctedOverage + " 原超配额费=" + priorOverage);

            if (inv.getStatus() == InvoiceStatus.PAID) {
                // 已付账单：原账单金额与明细完全不动，另立一张调整账单即时补收（对账口径清晰、可解释）
                Invoice adj = new Invoice();
                adj.setInvoiceId("INV-" + inv.getSubscriptionId() + "-ADJ-" + eventId);
                adj.setSubscriptionId(inv.getSubscriptionId());
                adj.setPeriodKey(inv.getPeriodKey());
                adj.setPeriodStart(inv.getPeriodStart());
                adj.setPeriodEnd(inv.getPeriodEnd());
                adj.setIssuedAt(clock.now());
                adj.setCycle(false);
                adj.setPlanId(plan.getPlanId());
                adj.setStatus(InvoiceStatus.PENDING);
                adj.getLines().add(line);
                adj.setAmount(extra);
                invoiceRepository.save(adj);
                if (extra.signum() > 0) {
                    settleOrMarkFailed(adj, "adj:" + inv.getSubscriptionId() + ":" + eventId);
                } else {
                    adj.setStatus(InvoiceStatus.PAID);
                    adj.setPaidAt(clock.now());
                    invoiceRepository.save(adj);
                }
                log.info("[late-usage] 已付账单 {} 迟到事件 {} 补计 {}，另立调整账单 {}",
                        invoiceId, eventId, extra, adj.getInvoiceId());
                return invoiceRepository.findById(adj.getInvoiceId()).orElseThrow();
            }

            // 未付账单：直接把调整行并入并重算金额（同一笔尚未结清的账单，不产生第二张）
            inv.getLines().add(line);
            inv.setAmount(Money.of(inv.getAmount().add(extra)));
            invoiceRepository.save(inv);
            log.info("[late-usage] 未付账单 {} 追加迟到事件 {} 补计 {}，账单重算为 {}",
                    invoiceId, eventId, extra, inv.getAmount());
            return inv;
        });
    }

    @Override
    public Invoice runBillingTick(String subscriptionId) {
        Subscription sub = require(subscriptionId);
        return locks.withLock(subscriptionId, () -> {
            if (sub.getStatus() == SubscriptionStatus.PAUSED) {
                // 客户主动暂停与收款失败停用互不相干：暂停期不驱动任何收款状态机
                log.info("[tick] sub={} 处于客户主动暂停 PAUSED，tick 不做收款推进（区别于 SUSPENDED）", subscriptionId);
                return invoiceRepository.findBySubscription(subscriptionId).stream()
                        .filter(Invoice::isCycle)
                        .reduce((a, b) -> b).orElse(null);
            }
            Invoice inv = latestCycleInvoice(subscriptionId);
            if (inv == null) {
                throw new BillingException(ErrorCode.NOTHING_TO_CLOSE,
                        "没有可推进的周期账单，请先出账: " + subscriptionId);
            }
            Instant now = clock.now();
            switch (inv.getStatus()) {
                case PENDING -> {
                    log.info("[tick] sub={} invoice={} PENDING -> 立即收款", subscriptionId, inv.getInvoiceId());
                    settleOrMarkFailed(inv, "cycle:" + subscriptionId + ":" + inv.getPeriodKey());
                }
                case FAILED -> {
                    Instant failedAt = inv.getFailedAt() != null ? inv.getFailedAt() : inv.getLastAttemptAt();
                    Duration since = Duration.between(failedAt, now);
                    if (since.compareTo(RETRY_WINDOW) <= 0) {
                        log.info("[tick] sub={} invoice={} FAILED 距上次={} 在重试窗口{}内 -> 重试收款",
                                subscriptionId, inv.getInvoiceId(), since, RETRY_WINDOW);
                        settleOrMarkFailed(inv,
                                "retry:" + subscriptionId + ":" + inv.getPeriodKey() + ":" + (inv.getAttemptCount() + 1));
                    } else {
                        inv.setStatus(InvoiceStatus.PAST_DUE);
                        inv.setDueAt(now.plus(GRACE_WINDOW));
                        subscriptionRepository.save(sub);
                        invoiceRepository.save(inv);
                        log.info("[tick] sub={} invoice={} 超过重试窗口 -> 进入宽限 PAST_DUE，宽限截止={}",
                                subscriptionId, inv.getInvoiceId(), inv.getDueAt());
                    }
                }
                case PAST_DUE -> {
                    if (now.isBefore(inv.getDueAt())) {
                        log.info("[tick] sub={} invoice={} 宽限中（截止 {}）-> 尝试主动补缴",
                                subscriptionId, inv.getInvoiceId(), inv.getDueAt());
                        settleOrMarkFailed(inv,
                                "grace:" + subscriptionId + ":" + inv.getPeriodKey() + ":" + (inv.getAttemptCount() + 1));
                    } else {
                        sub.setStatus(SubscriptionStatus.SUSPENDED);
                        subscriptionRepository.save(sub);
                        log.warn("[tick] sub={} invoice={} 超过宽限截止 {} -> 订阅 SUSPENDED",
                                subscriptionId, inv.getInvoiceId(), inv.getDueAt());
                    }
                }
                case PAID -> rolloverIfDue(sub, inv);
                case ANOMALY -> log.info("[tick] sub={} invoice={} 仍 ANOMALY，等待人工处理",
                        subscriptionId, inv.getInvoiceId());
                default -> log.info("[tick] sub={} invoice={} 状态 {} 无需推进",
                        subscriptionId, inv.getInvoiceId(), inv.getStatus());
            }
            return invoiceRepository.findById(inv.getInvoiceId()).orElseThrow();
        });
    }

    @Override
    public List<Invoice> listInvoices(String subscriptionId) {
        return invoiceRepository.findBySubscription(subscriptionId);
    }

    @Override
    public Invoice getInvoice(String invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BillingException(ErrorCode.INVOICE_NOT_FOUND, "账单不存在: " + invoiceId));
    }

    // ---------- 内部 ----------

    private void settleOrMarkFailed(Invoice inv, String idempotencyKey) {
        if (inv.getAmount().signum() == 0) {
            inv.setStatus(InvoiceStatus.PAID);
            inv.setPaidAt(clock.now());
            invoiceRepository.save(inv);
            rolloverIfDue(require(inv.getSubscriptionId()), inv);
            return;
        }
        try {
            paymentService.charge(inv, idempotencyKey);
            rolloverIfDue(require(inv.getSubscriptionId()), inv);
        } catch (BillingException ex) {
            if (ex.getCode() != ErrorCode.GATEWAY_REJECTED) {
                throw ex;
            }
            // 通道失败：保留可解释的确定状态——宽限期中保持 PAST_DUE，其余落 FAILED，等待 tick 继续推进
            Subscription sub = require(inv.getSubscriptionId());
            if (inv.getStatus() == InvoiceStatus.PAID) {
                inv.setStatus(InvoiceStatus.FAILED);
            } else if (inv.getDueAt() != null && clock.now().isBefore(inv.getDueAt())) {
                inv.setStatus(InvoiceStatus.PAST_DUE);
            } else {
                inv.setStatus(InvoiceStatus.FAILED);
            }
            invoiceRepository.save(inv);
            if (sub.getStatus() == SubscriptionStatus.ACTIVE) {
                sub.setStatus(SubscriptionStatus.PAST_DUE);
                subscriptionRepository.save(sub);
            }
            log.warn("[settle] invoice={} 收款失败 -> {}，等待 tick 继续推进，不滚周期",
                    inv.getInvoiceId(), inv.getStatus());
        }
    }

    private void rolloverIfDue(Subscription sub, Invoice inv) {
        if (!inv.isCycle() || inv.getStatus() != InvoiceStatus.PAID) {
            return;
        }
        // 账单周期 == 订阅当前周期时，收款完成即把订阅推进到下一周期
        if (!inv.getPeriodStart().equals(sub.getCurrentPeriodStart())) {
            return;
        }
        boolean keepPaused = sub.getStatus() == SubscriptionStatus.PAUSED;
        Instant ns = inv.getPeriodEnd();
        Instant ne = calendar.nextPeriodStart(ns, sub.getAnchorDay());
        sub.setCurrentPeriodStart(ns);
        sub.setCurrentPeriodEnd(ne);
        sub.setStatus(keepPaused ? SubscriptionStatus.PAUSED : SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(sub);
        log.info("[rollover] sub={} 周期账单 {} 收讫 -> 滚动到新周期 {} ~ {}，订阅状态 {}",
                sub.getSubscriptionId(), inv.getInvoiceId(), ns, ne, sub.getStatus());
    }

    private Invoice latestCycleInvoice(String subscriptionId) {
        return invoiceRepository.findBySubscription(subscriptionId).stream()
                .filter(Invoice::isCycle)
                .reduce((a, b) -> a.getPeriodEnd().isAfter(b.getPeriodEnd()) ? a : b)
                .orElse(null);
    }

    /**
     * 周期结束时生效的套餐 = 该周期内最后一次生效且不晚于周期结束的变更的目标套餐；
     * 无变更则为订阅当前套餐。配额按此套餐口径，确定性、可解释。
     */
    private Plan planAtPeriodEnd(Subscription sub, Instant periodEnd) {
        String planId = changeRepository.findBySubscription(sub.getSubscriptionId()).stream()
                .filter(c -> c.getType() != ChangeType.CANCEL)
                .filter(c -> !c.getEffectiveAt().isAfter(periodEnd))
                .reduce((a, b) -> b)
                .map(PlanChange::getToPlanId)
                .orElse(sub.getCurrentPlanId());
        return planService.getPlan(planId);
    }

    private Subscription require(String id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "订阅不存在: " + id));
    }
}

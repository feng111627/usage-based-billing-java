package com.github.feng111627.usagebasedbillingjava.service.impl;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceLine;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus;
import com.github.feng111627.usagebasedbillingjava.model.LineKind;
import com.github.feng111627.usagebasedbillingjava.model.PauseAction;
import com.github.feng111627.usagebasedbillingjava.model.PauseRecord;
import com.github.feng111627.usagebasedbillingjava.model.Plan;
import com.github.feng111627.usagebasedbillingjava.model.PlanChange;
import com.github.feng111627.usagebasedbillingjava.model.Subscription;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.repository.Repositories;
import com.github.feng111627.usagebasedbillingjava.service.BillingCalendar;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.Money;
import com.github.feng111627.usagebasedbillingjava.service.PaymentService;
import com.github.feng111627.usagebasedbillingjava.service.PlanService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionLocks;
import com.github.feng111627.usagebasedbillingjava.service.PauseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 客户主动暂停/恢复实现。
 *
 * <p>计费口径（与升降配完全独立的账单，不重复计基础费）：
 * <ul>
 *   <li>PAUSE：从 eff 起到周期末按剩余比例退回“当前套餐”已预付未消耗的基础费，
 *       amount = -basePrice × remainingRatio，订阅转 PAUSED；</li>
 *   <li>RESUME：从 eff 起到周期末按剩余比例补收同一套餐基础费，
 *       amount = +basePrice × remainingRatio，订阅转 ACTIVE；
 *       周期配额的活跃时长折算在出账时统一计算（{@link PauseTimeline}）。</li>
 * </ul>
 *
 * <p>确定性/幂等：订阅锁内串行；seq 与升降配共用订阅级全序；
 * effectiveAt 不晚于任一既有升降配/暂停/恢复即 CHANGE_OUT_OF_ORDER；
 * 幂等键在变更与暂停两个仓储间共同唯一，重复提交返回首次结果，不重复退款/扣款；
 * 通道失败时仅留 PENDING/FAILED 账单草稿，不推进任何状态，同键可安全重试。
 */
@Service
public class PauseServiceImpl implements PauseService {

    private static final Logger log = LoggerFactory.getLogger(PauseServiceImpl.class);

    private final Repositories.PauseRepository pauseRepository;
    private final Repositories.ChangeRepository changeRepository;
    private final Repositories.SubscriptionRepository subscriptionRepository;
    private final Repositories.InvoiceRepository invoiceRepository;
    private final PlanService planService;
    private final PaymentService paymentService;
    private final BillingCalendar calendar;
    private final ClockService clock;
    private final SubscriptionLocks locks;

    public PauseServiceImpl(Repositories.PauseRepository pauseRepository,
                            Repositories.ChangeRepository changeRepository,
                            Repositories.SubscriptionRepository subscriptionRepository,
                            Repositories.InvoiceRepository invoiceRepository,
                            PlanService planService, PaymentService paymentService,
                            BillingCalendar calendar, ClockService clock, SubscriptionLocks locks) {
        this.pauseRepository = pauseRepository;
        this.changeRepository = changeRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRepository = invoiceRepository;
        this.planService = planService;
        this.paymentService = paymentService;
        this.calendar = calendar;
        this.clock = clock;
        this.locks = locks;
    }

    @Override
    public PauseRecord pause(String subscriptionId, String idempotencyKey, Instant effectiveAt) {
        return locks.withLock(subscriptionId,
                () -> apply(subscriptionId, idempotencyKey, effectiveAt, PauseAction.PAUSE));
    }

    @Override
    public PauseRecord resume(String subscriptionId, String idempotencyKey, Instant effectiveAt) {
        return locks.withLock(subscriptionId,
                () -> apply(subscriptionId, idempotencyKey, effectiveAt, PauseAction.RESUME));
    }

    @Override
    public List<PauseRecord> list(String subscriptionId) {
        return pauseRepository.findBySubscription(subscriptionId);
    }

    private PauseRecord apply(String subscriptionId, String key, Instant requestedAt, PauseAction action) {
        Subscription s = requireSubscription(subscriptionId);
        if (key == null || key.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, action + " 必须携带 idempotencyKey");
        }
        // 幂等重放优先于一切业务校验（含暂停/变更键空间互斥）
        var replay = pauseRepository.findByIdempotencyKey(key);
        if (replay.isPresent()) {
            PauseRecord r = replay.get();
            if (!r.getSubscriptionId().equals(subscriptionId)) {
                throw new BillingException(ErrorCode.DUPLICATE_REQUEST,
                        "幂等键 " + key + " 已用于其他订阅 " + r.getSubscriptionId());
            }
            log.info("[{}] 幂等重放 key={} seq={} amount={} -> 返回首次结果，不重复结算",
                    action, key, r.getSeq(), r.getAmount());
            return r;
        }
        changeRepository.findByIdempotencyKey(key).ifPresent(c -> {
            throw new BillingException(ErrorCode.DUPLICATE_REQUEST,
                    "幂等键 " + key + " 已被一次升降配/退订占用");
        });

        Instant eff = requestedAt != null ? requestedAt : clock.now();
        if (eff.isBefore(s.getCurrentPeriodStart()) || !eff.isBefore(s.getCurrentPeriodEnd())) {
            throw new BillingException(ErrorCode.VALIDATION,
                    "生效时间 " + eff + " 不在当前周期 [" + s.getCurrentPeriodStart() + ","
                            + s.getCurrentPeriodEnd() + ") 内");
        }
        Instant last = lastLifecycleEffectiveAt(subscriptionId);
        if (last != null && !eff.isAfter(last)) {
            throw new BillingException(ErrorCode.CHANGE_OUT_OF_ORDER,
                    action + " 生效时间 " + eff + " 不晚于上一次升降配/暂停/恢复 " + last
                            + "，同一时刻仅允许一个操作生效，乱序/并发请求被拒绝");
        }

        if (action == PauseAction.PAUSE) {
            if (s.getStatus() == SubscriptionStatus.PAUSED) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_PAUSED_BY_CUSTOMER,
                        "订阅已处于客户主动暂停态，不能重复暂停: " + subscriptionId);
            }
            if (s.getStatus() != SubscriptionStatus.ACTIVE) {
                throw new BillingException(ErrorCode.CONFLICT,
                        "仅 ACTIVE 订阅可由客户主动暂停，当前状态=" + s.getStatus());
            }
        } else {
            if (s.getStatus() == SubscriptionStatus.ACTIVE) {
                throw new BillingException(ErrorCode.CONFLICT,
                        "订阅并未暂停（ACTIVE），无需恢复: " + subscriptionId);
            }
            if (s.getStatus() != SubscriptionStatus.PAUSED) {
                throw new BillingException(ErrorCode.CONFLICT,
                        "仅客户主动暂停(PAUSED)可恢复，当前状态=" + s.getStatus()
                                + "；收款失败停用(SUSPENDED)请走补缴");
            }
        }

        Plan plan = planService.getPlan(s.getCurrentPlanId());
        BigDecimal ratio = calendar.remainingRatio(eff, s.getCurrentPeriodStart(), s.getCurrentPeriodEnd());
        BigDecimal signed = Money.prorate(plan.getBasePrice(), ratio);
        BigDecimal amount = action == PauseAction.PAUSE ? signed.negate() : signed;
        String periodKey = calendar.periodKey(s.getCurrentPeriodStart(), s.getAnchorDay());

        long seq = s.getLastChangeSeq() + 1;
        Invoice invoice = buildInvoice(s, seq, action, eff, plan, periodKey, amount, ratio);
        invoiceRepository.save(invoice);

        // 通道结算失败则直接抛出：除账单草稿外无任何状态推进；调用方用同一幂等键重试安全
        if (amount.signum() < 0) {
            paymentService.refund(invoice.getInvoiceId(), amount.negate(), "pause-rfnd:" + key);
        } else {
            paymentService.charge(invoice, "pause-chg:" + key);
        }

        PauseRecord record = new PauseRecord();
        record.setSeq(seq);
        record.setSubscriptionId(subscriptionId);
        record.setAction(action);
        record.setEffectiveAt(eff);
        record.setAmount(amount);
        record.setInvoiceId(invoice.getInvoiceId());
        record.setIdempotencyKey(key);
        record.setPeriodKey(periodKey);
        record.setPlanId(plan.getPlanId());
        pauseRepository.save(record);

        s.setLastChangeSeq(seq);
        s.setStatus(action == PauseAction.PAUSE ? SubscriptionStatus.PAUSED : SubscriptionStatus.ACTIVE);
        s.setPausedSince(action == PauseAction.PAUSE ? eff : null);
        subscriptionRepository.save(s);

        log.info("[{}] 输入 sub={} plan={}(base={}) key={} eff={}；判定依据：剩余比例={} 结算金额={} 发票={} seq={} 订阅->{}",
                action, subscriptionId, plan.getPlanId(), plan.getBasePrice(), key, eff,
                ratio, amount, invoice.getInvoiceId(), seq, s.getStatus());
        return record;
    }

    private Invoice buildInvoice(Subscription s, long seq, PauseAction action, Instant eff,
                                 Plan plan, String periodKey, BigDecimal amount, BigDecimal ratio) {
        Invoice inv = new Invoice();
        inv.setInvoiceId("INV-" + s.getSubscriptionId() + "-PSE" + seq);
        inv.setSubscriptionId(s.getSubscriptionId());
        inv.setPeriodKey(periodKey);
        inv.setPeriodStart(s.getCurrentPeriodStart());
        inv.setPeriodEnd(s.getCurrentPeriodEnd());
        inv.setIssuedAt(clock.now());
        inv.setCycle(false);
        inv.setPlanId(plan.getPlanId());
        inv.setStatus(InvoiceStatus.PENDING);
        String desc;
        String basis;
        if (action == PauseAction.PAUSE) {
            desc = "客户主动暂停，按剩余周期比例退回 " + plan.getName() + " 已预付基础费";
            basis = "客户主动暂停 eff=" + eff + " basePrice=" + plan.getBasePrice()
                    + " 剩余比例=" + ratio + " 退款=" + amount.negate();
        } else {
            desc = "客户恢复订阅，按剩余周期比例补收 " + plan.getName() + " 基础费";
            basis = "客户恢复 eff=" + eff + " basePrice=" + plan.getBasePrice()
                    + " 剩余比例=" + ratio + " 补收=" + amount;
        }
        inv.getLines().add(new InvoiceLine(LineKind.PAUSE_PRORATION, desc, Money.of(amount), basis));
        inv.setAmount(Money.of(amount));
        return inv;
    }

    /** 升降配/退订与暂停/恢复中最晚的生效时刻；为订阅级全序提供乱序判定依据。 */
    private Instant lastLifecycleEffectiveAt(String subscriptionId) {
        Instant last = changeRepository.findBySubscription(subscriptionId).stream()
                .map(PlanChange::getEffectiveAt)
                .reduce((a, b) -> a.isAfter(b) ? a : b)
                .orElse(null);
        Instant pauseLast = pauseRepository.findBySubscription(subscriptionId).stream()
                .map(PauseRecord::getEffectiveAt)
                .reduce((a, b) -> a.isAfter(b) ? a : b)
                .orElse(null);
        if (pauseLast != null && (last == null || pauseLast.isAfter(last))) {
            return pauseLast;
        }
        return last;
    }

    private Subscription requireSubscription(String id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "订阅不存在: " + id));
    }
}

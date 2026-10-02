package com.github.feng111627.usagebasedbillingjava.service.impl;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.ChangeType;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceLine;
import com.github.feng111627.usagebasedbillingjava.model.LineKind;
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
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 订阅生命周期与周期中途差价结算。
 *
 * <p>确定性保证：
 * <ul>
 *   <li>同一订阅的变更在 {@link SubscriptionLocks} 内串行，seq 单调递增形成全序，
 *       同一时刻到达的多次变更严格按接收顺序计价；</li>
 *   <li>effectiveAt 早于上一次变更的请求直接判 CHANGE_OUT_OF_ORDER 拒绝，杜绝乱序覆盖；</li>
 *   <li>差价立即通过本地通道结清，通道失败则整体不落库（无中间态），重试由幂等键兜底；</li>
 *   <li>差价 = (新基础价 - 旧基础价) × 剩余周期比例（6 位比例、金额 HALF_UP 到分）。</li>
 * </ul>
 */
@Service
public class SubscriptionServiceImpl implements SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionServiceImpl.class);

    private final Repositories.SubscriptionRepository subscriptionRepository;
    private final Repositories.ChangeRepository changeRepository;
    private final Repositories.InvoiceRepository invoiceRepository;
    private final PlanService planService;
    private final PaymentService paymentService;
    private final BillingCalendar calendar;
    private final ClockService clock;
    private final SubscriptionLocks locks;

    public SubscriptionServiceImpl(Repositories.SubscriptionRepository subscriptionRepository,
                                   Repositories.ChangeRepository changeRepository,
                                   Repositories.InvoiceRepository invoiceRepository,
                                   PlanService planService,
                                   PaymentService paymentService,
                                   BillingCalendar calendar,
                                   ClockService clock,
                                   SubscriptionLocks locks) {
        this.subscriptionRepository = subscriptionRepository;
        this.changeRepository = changeRepository;
        this.invoiceRepository = invoiceRepository;
        this.planService = planService;
        this.paymentService = paymentService;
        this.calendar = calendar;
        this.clock = clock;
        this.locks = locks;
    }

    @Override
    public Subscription subscribe(String subscriptionId, String customerId, String planId, int anchorDay) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "subscriptionId 不能为空");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "customerId 不能为空");
        }
        Plan plan = planService.getPlan(planId);
        Instant now = clock.now();
        Instant start = calendar.periodStart(now, anchorDay);
        Instant end = calendar.periodEnd(now, anchorDay);

        var existed = subscriptionRepository.findById(subscriptionId);
        if (existed.isPresent()) {
            Subscription s = existed.get();
            if (!s.getCurrentPlanId().equals(planId)) {
                throw new BillingException(ErrorCode.DUPLICATE_REQUEST,
                        "订阅 " + subscriptionId + " 已存在且套餐不同: " + s.getCurrentPlanId());
            }
            log.info("[subscribe] 幂等命中 subscriptionId={} plan={} -> 直接返回既有订阅", subscriptionId, planId);
            return s;
        }

        Subscription s = new Subscription();
        s.setSubscriptionId(subscriptionId);
        s.setCustomerId(customerId);
        s.setCurrentPlanId(planId);
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setAnchorDay(anchorDay);
        s.setCreatedAt(now);
        s.setCurrentPeriodStart(start);
        s.setCurrentPeriodEnd(end);
        s.setLastChangeSeq(0);
        subscriptionRepository.save(s);

        // 预付首期基础费（本周期 [start,end)）
        Invoice first = buildProrationInvoice(s, 0, plan.getBasePrice(),
                "首期基础费 " + plan.getName() + " " + start + " ~ " + end,
                "全额预付: basePrice=" + plan.getBasePrice());
        invoiceRepository.save(first);
        paymentService.charge(first, "sub:" + subscriptionId + ":create");
        log.info("[subscribe] 输入 subscriptionId={} customer={} plan={}(base={}) anchor={}；判定依据：周期 {} ~ {} 全额预付，发票={}",
                subscriptionId, customerId, planId, plan.getBasePrice(), anchorDay, start, end, first.getInvoiceId());
        return s;
    }

    @Override
    public PlanChange changePlan(String subscriptionId, String targetPlanId,
                                 String idempotencyKey, Instant effectiveAt) {
        return locks.withLock(subscriptionId, () -> doChange(subscriptionId, targetPlanId, idempotencyKey, effectiveAt));
    }

    @Override
    public PlanChange cancel(String subscriptionId, String idempotencyKey, Instant effectiveAt) {
        return locks.withLock(subscriptionId, () -> doCancel(subscriptionId, idempotencyKey, effectiveAt));
    }

    private PlanChange doChange(String subscriptionId, String targetPlanId, String key, Instant requestedAt) {
        Subscription s = requireSubscription(subscriptionId);
        if (key == null || key.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "变更必须携带 idempotencyKey");
        }
        // 幂等重放优先于一切业务校验：同一请求重试（含已变更到目标套餐后）必须返回原结果
        var replay = changeRepository.findByIdempotencyKey(key);
        if (replay.isPresent()) {
            PlanChange pc = replay.get();
            if (!pc.getSubscriptionId().equals(subscriptionId)) {
                throw new BillingException(ErrorCode.DUPLICATE_REQUEST,
                        "幂等键 " + key + " 已用于其他订阅 " + pc.getSubscriptionId());
            }
            log.info("[change] 幂等重放 key={} seq={} proration={} -> 返回原结果，不重复计价",
                    key, pc.getSeq(), pc.getProration());
            return pc;
        }
        requireActive(s);
        if (targetPlanId == null) {
            throw new BillingException(ErrorCode.VALIDATION, "targetPlanId 不能为空");
        }
        if (targetPlanId.equals(s.getCurrentPlanId())) {
            throw new BillingException(ErrorCode.VALIDATION, "目标套餐与当前套餐相同，无需变更: " + targetPlanId);
        }
        Plan from = planService.getPlan(s.getCurrentPlanId());
        Plan to = planService.getPlan(targetPlanId);
        return applyChange(s, from, to, key, requestedAt);
    }

    private PlanChange doCancel(String subscriptionId, String key, Instant requestedAt) {
        Subscription s = requireSubscription(subscriptionId);
        if (key == null || key.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "变更必须携带 idempotencyKey");
        }
        var replay = changeRepository.findByIdempotencyKey(key);
        if (replay.isPresent()) {
            PlanChange pc = replay.get();
            if (!pc.getSubscriptionId().equals(subscriptionId)) {
                throw new BillingException(ErrorCode.DUPLICATE_REQUEST,
                        "幂等键 " + key + " 已用于其他订阅 " + pc.getSubscriptionId());
            }
            log.info("[cancel] 幂等重放 key={} -> 返回原退订结果", key);
            return pc;
        }
        if (s.getStatus() == SubscriptionStatus.CANCELLED) {
            throw new BillingException(ErrorCode.SUBSCRIPTION_INACTIVE, "订阅已退订: " + subscriptionId);
        }
        requireActive(s);
        Plan from = planService.getPlan(s.getCurrentPlanId());
        return applyChange(s, from, null, key, requestedAt);
    }

    private PlanChange applyChange(Subscription s, Plan from, Plan to, String key, Instant requestedAt) {
        Instant eff = validateEffectiveAt(s, requestedAt);
        BigDecimal ratio = calendar.remainingRatio(eff, s.getCurrentPeriodStart(), s.getCurrentPeriodEnd());
        ChangeType type;
        BigDecimal delta;
        String desc;
        if (to == null) {
            type = ChangeType.CANCEL;
            delta = Money.prorate(from.getBasePrice().negate(), ratio);
            desc = "退订按剩余周期比例退回 " + from.getName() + " 基础费";
        } else {
            int cmp = to.getBasePrice().compareTo(from.getBasePrice());
            type = cmp > 0 ? ChangeType.UPGRADE : ChangeType.DOWNGRADE;
            delta = Money.prorate(to.getBasePrice().subtract(from.getBasePrice()), ratio);
            desc = (type == ChangeType.UPGRADE ? "升级" : "降级") + " " + from.getName()
                    + " -> " + to.getName() + " 剩余周期差价";
        }
        String basis = "旧价=" + from.getBasePrice()
                + (to != null ? " 新价=" + to.getBasePrice() : "")
                + " 剩余比例=" + ratio + " 生效=" + eff;

        long seq = s.getLastChangeSeq() + 1;
        Invoice invoice = buildProrationInvoice(s, seq, delta, desc, basis);
        invoiceRepository.save(invoice);

        // 通道结算失败则直接抛出：上方除发票草稿外未做任何持久化状态推进，调用方可用同幂等键重试
        if (delta.signum() > 0) {
            paymentService.charge(invoice, "chg:" + key);
        } else if (delta.signum() < 0) {
            paymentService.refund(invoice.getInvoiceId(), delta.negate(), "rfnd:" + key);
        }

        PlanChange pc = new PlanChange();
        pc.setSeq(seq);
        pc.setSubscriptionId(s.getSubscriptionId());
        pc.setType(type);
        pc.setFromPlanId(from.getPlanId());
        pc.setToPlanId(to != null ? to.getPlanId() : null);
        pc.setEffectiveAt(eff);
        pc.setProration(delta);
        pc.setInvoiceId(invoice.getInvoiceId());
        pc.setIdempotencyKey(key);
        changeRepository.save(pc);

        s.setLastChangeSeq(seq);
        if (type == ChangeType.CANCEL) {
            s.setStatus(SubscriptionStatus.CANCELLED);
        } else {
            s.setCurrentPlanId(to.getPlanId());
        }
        subscriptionRepository.save(s);

        log.info("[change] 输入 sub={} {} -> {} key={} eff={}；判定依据：{}；差价={} 发票={} seq={}",
                s.getSubscriptionId(), from.getPlanId(), to != null ? to.getPlanId() : "CANCELLED",
                key, eff, basis, delta, invoice.getInvoiceId(), seq);
        return pc;
    }

    @Override
    public PlanChange pause(String subscriptionId, String idempotencyKey, Instant effectiveAt) {
        return locks.withLock(subscriptionId, () -> doPause(subscriptionId, idempotencyKey, effectiveAt));
    }

    @Override
    public PlanChange resume(String subscriptionId, String idempotencyKey, Instant effectiveAt) {
        return locks.withLock(subscriptionId, () -> doResume(subscriptionId, idempotencyKey, effectiveAt));
    }

    private PlanChange doPause(String subscriptionId, String key, Instant requestedAt) {
        Subscription s = requireSubscription(subscriptionId);
        if (key == null || key.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "暂停必须携带 idempotencyKey");
        }
        // 幂等重放优先于一切业务校验：重复提交返回首次结果，不重复退款
        var replay = changeRepository.findByIdempotencyKey(key);
        if (replay.isPresent()) {
            PlanChange pc = replay.get();
            if (!pc.getSubscriptionId().equals(subscriptionId)) {
                throw new BillingException(ErrorCode.DUPLICATE_REQUEST,
                        "幂等键 " + key + " 已用于其他订阅 " + pc.getSubscriptionId());
            }
            log.info("[pause] 幂等重放 key={} seq={} -> 返回原暂停结果，不重复退款", key, pc.getSeq());
            return pc;
        }
        switch (s.getStatus()) {
            case ACTIVE -> { }
            case PAUSED -> throw new BillingException(ErrorCode.SUBSCRIPTION_PAUSED,
                    "订阅已处于客户主动暂停中，无需重复暂停: " + subscriptionId);
            case PAST_DUE -> throw new BillingException(ErrorCode.CONFLICT,
                    "订阅处于宽限期（存在未收账单），结清后才能暂停: " + subscriptionId);
            case SUSPENDED -> throw new BillingException(ErrorCode.SUBSCRIPTION_SUSPENDED,
                    "订阅因收款失败被停用（非客户主动暂停），需补缴恢复: " + subscriptionId);
            case CANCELLED -> throw new BillingException(ErrorCode.SUBSCRIPTION_INACTIVE,
                    "订阅已退订: " + subscriptionId);
        }
        Plan plan = planService.getPlan(s.getCurrentPlanId());
        Instant eff = validateEffectiveAt(s, requestedAt);
        BigDecimal ratio = calendar.remainingRatio(eff, s.getCurrentPeriodStart(), s.getCurrentPeriodEnd());
        // 暂停：按暂停时刻到周期末的剩余比例退回本周期已预付但未消耗的基础费
        BigDecimal delta = Money.prorate(plan.getBasePrice().negate(), ratio);
        String basis = "暂停退回未消耗基础费 basePrice=" + plan.getBasePrice()
                + " 剩余比例=" + ratio + " 生效=" + eff;
        return settlePauseResume(s, plan, ChangeType.PAUSE, eff, delta, key,
                "客户主动暂停 " + plan.getName() + " 剩余周期基础费退回", basis);
    }

    private PlanChange doResume(String subscriptionId, String key, Instant requestedAt) {
        Subscription s = requireSubscription(subscriptionId);
        if (key == null || key.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "恢复必须携带 idempotencyKey");
        }
        var replay = changeRepository.findByIdempotencyKey(key);
        if (replay.isPresent()) {
            PlanChange pc = replay.get();
            if (!pc.getSubscriptionId().equals(subscriptionId)) {
                throw new BillingException(ErrorCode.DUPLICATE_REQUEST,
                        "幂等键 " + key + " 已用于其他订阅 " + pc.getSubscriptionId());
            }
            log.info("[resume] 幂等重放 key={} seq={} -> 返回原恢复结果，不重复扣款", key, pc.getSeq());
            return pc;
        }
        switch (s.getStatus()) {
            case PAUSED -> { }
            case ACTIVE -> throw new BillingException(ErrorCode.CONFLICT,
                    "订阅处于活跃状态，无需恢复: " + subscriptionId);
            case PAST_DUE -> throw new BillingException(ErrorCode.CONFLICT,
                    "订阅处于宽限期（存在未收账单），非客户暂停状态，请结清账单: " + subscriptionId);
            case SUSPENDED -> throw new BillingException(ErrorCode.SUBSCRIPTION_SUSPENDED,
                    "订阅因收款失败被停用（非客户主动暂停），请走补缴恢复: " + subscriptionId);
            case CANCELLED -> throw new BillingException(ErrorCode.SUBSCRIPTION_INACTIVE,
                    "订阅已退订: " + subscriptionId);
        }
        Plan plan = planService.getPlan(s.getCurrentPlanId());
        Instant eff = validateEffectiveAt(s, requestedAt);
        BigDecimal ratio = calendar.remainingRatio(eff, s.getCurrentPeriodStart(), s.getCurrentPeriodEnd());
        // 恢复：按恢复时刻到周期末的剩余比例重新收取本周期基础费
        BigDecimal delta = Money.prorate(plan.getBasePrice(), ratio);
        String basis = "恢复补收本周期基础费 basePrice=" + plan.getBasePrice()
                + " 剩余比例=" + ratio + " 生效=" + eff;
        return settlePauseResume(s, plan, ChangeType.RESUME, eff, delta, key,
                "暂停恢复 " + plan.getName() + " 剩余周期基础费补收", basis);
    }

    /** 暂停/恢复共用：出差价账单 -> 通道结清（失败则状态不推进）-> 落变更 -> 推进状态。 */
    private PlanChange settlePauseResume(Subscription s, Plan plan, ChangeType type, Instant eff,
                                         BigDecimal delta, String key, String desc, String basis) {
        long seq = s.getLastChangeSeq() + 1;
        Invoice invoice = buildProrationInvoice(s, seq, delta, desc, basis);
        invoiceRepository.save(invoice);

        // 通道结算失败则直接抛出：除发票草稿外未做任何状态推进，同幂等键重试安全
        if (delta.signum() > 0) {
            paymentService.charge(invoice, "resume:" + key);
        } else if (delta.signum() < 0) {
            paymentService.refund(invoice.getInvoiceId(), delta.negate(), "rfnd:" + key);
        }

        PlanChange pc = new PlanChange();
        pc.setSeq(seq);
        pc.setSubscriptionId(s.getSubscriptionId());
        pc.setType(type);
        pc.setFromPlanId(plan.getPlanId());
        pc.setToPlanId(plan.getPlanId());
        pc.setEffectiveAt(eff);
        pc.setProration(delta);
        pc.setInvoiceId(invoice.getInvoiceId());
        pc.setIdempotencyKey(key);
        changeRepository.save(pc);

        s.setLastChangeSeq(seq);
        s.setStatus(type == ChangeType.PAUSE ? SubscriptionStatus.PAUSED : SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(s);

        log.info("[{}] 输入 sub={} key={} eff={}；判定依据：{}；差价={} 发票={} seq={} -> 状态={}",
                type == ChangeType.PAUSE ? "pause" : "resume",
                s.getSubscriptionId(), key, eff, basis, delta, invoice.getInvoiceId(), seq, s.getStatus());
        return pc;
    }

    /** 生效时间校验：不得早于上一次变更（乱序拒绝），且必须落在当前周期内。 */
    private Instant validateEffectiveAt(Subscription s, Instant requestedAt) {
        Instant eff = requestedAt != null ? requestedAt : clock.now();
        if (s.getLastChangeSeq() > 0) {
            PlanChange last = changeRepository.findBySubscription(s.getSubscriptionId())
                    .stream().reduce((a, b) -> b).orElseThrow();
            if (eff.isBefore(last.getEffectiveAt())) {
                throw new BillingException(ErrorCode.CHANGE_OUT_OF_ORDER,
                        "变更生效时间 " + eff + " 早于上一次变更 " + last.getEffectiveAt()
                                + "（seq=" + last.getSeq() + "），乱序请求被拒绝");
            }
        }
        if (eff.isBefore(s.getCurrentPeriodStart()) || !eff.isBefore(s.getCurrentPeriodEnd())) {
            throw new BillingException(ErrorCode.VALIDATION,
                    "生效时间 " + eff + " 不在当前周期 [" + s.getCurrentPeriodStart() + ","
                            + s.getCurrentPeriodEnd() + ") 内");
        }
        return eff;
    }

    @Override
    public Subscription getSubscription(String subscriptionId) {
        return requireSubscription(subscriptionId);
    }

    private Invoice buildProrationInvoice(Subscription s, long seq, BigDecimal amount, String desc, String basis) {
        Invoice inv = new Invoice();
        inv.setInvoiceId("INV-" + s.getSubscriptionId() + "-CHG" + seq);
        inv.setSubscriptionId(s.getSubscriptionId());
        inv.setPeriodKey(calendar.periodKey(s.getCurrentPeriodStart(), s.getAnchorDay()));
        inv.setPeriodStart(s.getCurrentPeriodStart());
        inv.setPeriodEnd(s.getCurrentPeriodEnd());
        inv.setIssuedAt(clock.now());
        inv.setStatus(com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus.PENDING);
        inv.getLines().add(new InvoiceLine(LineKind.PRORATION, desc, Money.of(amount), basis));
        inv.setAmount(Money.of(amount));
        if (amount.signum() < 0) {
            inv.setStatus(com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus.REFUNDED);
        }
        return inv;
    }

    private Subscription requireSubscription(String id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "订阅不存在: " + id));
    }

    private void requireActive(Subscription s) {
        switch (s.getStatus()) {
            case ACTIVE -> { }
            case PAST_DUE -> throw new BillingException(ErrorCode.CONFLICT,
                    "订阅处于宽限期（存在未收账单），结清后才能变更: " + s.getSubscriptionId());
            case SUSPENDED -> throw new BillingException(ErrorCode.SUBSCRIPTION_SUSPENDED,
                    "订阅因收款失败被停用，补缴恢复后才能变更: " + s.getSubscriptionId());
            case PAUSED -> throw new BillingException(ErrorCode.SUBSCRIPTION_PAUSED,
                    "订阅处于客户主动暂停中，恢复（resume）后才能变更/退订: " + s.getSubscriptionId());
            case CANCELLED -> throw new BillingException(ErrorCode.SUBSCRIPTION_INACTIVE,
                    "订阅已退订: " + s.getSubscriptionId());
        }
    }
}

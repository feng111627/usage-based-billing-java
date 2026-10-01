package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.common.Money;
import com.github.feng111627.usagebasedbillingjava.domain.Bill;
import com.github.feng111627.usagebasedbillingjava.domain.BillLine;
import com.github.feng111627.usagebasedbillingjava.domain.BillLineKind;
import com.github.feng111627.usagebasedbillingjava.domain.BillStatus;
import com.github.feng111627.usagebasedbillingjava.domain.MetricDef;
import com.github.feng111627.usagebasedbillingjava.domain.MetricUsage;
import com.github.feng111627.usagebasedbillingjava.domain.Plan;
import com.github.feng111627.usagebasedbillingjava.domain.Subscription;
import com.github.feng111627.usagebasedbillingjava.domain.UsageReport;
import com.github.feng111627.usagebasedbillingjava.engine.Periods;
import com.github.feng111627.usagebasedbillingjava.engine.PlanSegment;
import com.github.feng111627.usagebasedbillingjava.engine.Proration;
import com.github.feng111627.usagebasedbillingjava.engine.Timeline;
import com.github.feng111627.usagebasedbillingjava.repo.BillRepository;
import com.github.feng111627.usagebasedbillingjava.repo.PlanRepository;
import com.github.feng111627.usagebasedbillingjava.repo.SubscriptionRepository;
import com.github.feng111627.usagebasedbillingjava.repo.UsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出账服务。
 *
 * <p>计价模型（金额单位：分，全程整数）：</p>
 * <ol>
 *   <li>用 {@link Timeline} 把账期切成 ACTIVE 账段，每段套餐恒定；同刻多次变更只形成一个边界，
 *       折叠后状态唯一，不会重复计价；</li>
 *   <li>基础费逐段按秒比例：月费 × 段秒数/账期秒数，四舍五入到分；</li>
 *   <li>配额随套餐账段走：指标在每个账段的包含配额 = floor(月配额 × 段秒数/账期秒数)，
 *       末段承接取整余差使各段配额之和尽量贴近按比例总量；
 *       上报量按窗口与账段重叠秒数比例分摊到账段，逐段算超额，再按指标汇总；</li>
 *   <li>计量缺失 meteringComplete=false 明确标记并阻塞出账（USAGE_METERING_MISSING），
 *       绝不按 0 或上一次值出账；超配在汇总 overQuota=true 标记；</li>
 *   <li>周期账单幂等（同订阅+账期唯一）；关闭账期后的迟到数据不动原票，按 adjustmentKey
 *       幂等产生仅含差额的独立 ADJUSTMENT 账单。</li>
 * </ol>
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    /** 出账宽限期天数。 */
    public static final long GRACE_DAYS = 7;

    private final BillRepository billRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final UsageRepository usageRepository;
    private final BillingClock clock;

    public BillingService(BillRepository billRepository,
                          SubscriptionRepository subscriptionRepository,
                          PlanRepository planRepository,
                          UsageRepository usageRepository,
                          BillingClock clock) {
        this.billRepository = billRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.usageRepository = usageRepository;
        this.clock = clock;
    }

    /** 指标级累加器。 */
    private static final class Acc {
        long quantity;
        long includedUnits;
        long overageUnits;
        long overageAmount;
        boolean missing;
        boolean overQuota;
        /** 每个套餐账段内已分摊的用量，用于段级配额只免一次。 */
        final Map<String, Long> segmentQty = new LinkedHashMap<>();
    }

    /** 账段上下文：切好的账段、套餐、账段秒长、账期总秒长。 */
    private record SegmentCtx(PlanSegment segment, Plan plan, long seconds, long periodSeconds) {
    }

    public Map<String, MetricUsage> summarizeUsage(String subscriptionId, int periodIndex) {
        Subscription subscription = requireSubscription(subscriptionId);
        synchronized (subscriptionRepository.lockOf(subscriptionId)) {
            Instant ps = Periods.periodStart(subscription.getAnchoredAt(), periodIndex, clock.zone());
            Instant pe = Periods.periodEndExclusive(subscription.getAnchoredAt(), periodIndex, clock.zone());
            List<SegmentCtx> ctxs = segments(subscription, ps, pe);
            List<UsageReport> reports = reportsInPeriod(subscriptionId, ps, pe);
            Map<String, Acc> accs = accumulate(ctxs, reports);
            return toMetricUsages(accs);
        }
    }

    public Bill issueBill(String subscriptionId, int periodIndex) {
        Subscription subscription = requireSubscription(subscriptionId);
        synchronized (subscriptionRepository.lockOf(subscriptionId)) {
            String pKey = periodKeyOf(subscription, periodIndex);
            for (Bill existing : billRepository.findBySubscription(subscriptionId)) {
                if (existing.getAdjustmentKey() == null && pKey.equals(existing.getPeriodKey())) {
                    throw new BillingException(ErrorCode.BILL_ALREADY_EXISTS,
                            "period bill for " + pKey + " already exists: " + existing.getId()
                                    + "; late data must go through adjustment bills");
                }
            }
            return doIssue(subscription, periodIndex);
        }
    }

    private Bill doIssue(Subscription subscription, int periodIndex) {
        Instant ps = Periods.periodStart(subscription.getAnchoredAt(), periodIndex, clock.zone());
        Instant pe = Periods.periodEndExclusive(subscription.getAnchoredAt(), periodIndex, clock.zone());
        String pKey = Periods.periodKey(ps, clock.zone());
        List<SegmentCtx> ctxs = segments(subscription, ps, pe);
        List<UsageReport> reports = reportsInPeriod(subscription.getId(), ps, pe);

        List<BillLine> lines = new ArrayList<>();
        for (SegmentCtx ctx : ctxs) {
            Money part = Proration.prorate(ctx.plan().basePrice(),
                    ctx.segment().start(), ctx.segment().end(), ps, pe);
            lines.add(new BillLine(BillLineKind.BASE, null,
                    "base plan " + ctx.plan().id() + " active segment ["
                            + ctx.segment().start() + "," + ctx.segment().end() + "): "
                            + ctx.seconds() + "s/" + Duration.between(ps, pe).getSeconds() + "s of period",
                    part.cents()));
        }

        Map<String, Acc> accs = accumulate(ctxs, reports);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Acc> en : accs.entrySet()) {
            Acc a = en.getValue();
            if (a.missing) {
                missing.add(en.getKey());
            }
            if (a.overageAmount != 0) {
                lines.add(new BillLine(BillLineKind.OVERAGE, en.getKey(),
                        "overage metric " + en.getKey() + ": " + a.overageUnits
                                + " units beyond segment-prorated included quota "
                                + "priced by each segment plan",
                        a.overageAmount));
            }
        }
        if (!missing.isEmpty()) {
            log.error("[billing] BLOCKED sub={} period={} missingMetrics={}; "
                            + "basis: meteringComplete=false reports present, refusing to zero-fill",
                    subscription.getId(), pKey, missing);
            throw new BillingException(ErrorCode.USAGE_METERING_MISSING,
                    "cannot issue bill for " + pKey + ": metering data missing for metrics "
                            + missing + "; complete the metering data first");
        }

        Instant now = clock.now();
        Bill bill = Bill.issue(subscription.getId(), pKey, ps, pe, lines, now,
                now.plus(Duration.ofDays(GRACE_DAYS)), "grp-" + subscription.getId() + "-" + pKey);
        if (bill.isZeroAmount()) {
            bill.markPaid("zero-amount");
        }
        billRepository.save(bill);
        log.info("[billing] issued bill={} sub={} period={} amount={} baseLines={} overageMetrics={}; "
                        + "basis: per-segment prorated base + per-segment overage, total=sum(lines)",
                bill.getId(), subscription.getId(), pKey, bill.getAmount(), ctxs.size(),
                accs.values().stream().filter(a -> a.overageAmount != 0).count());
        return bill;
    }

    /**
     * 关闭账期后的迟到数据：以当前全量事件+上报重算期望金额，减去该账期已出全部有效票
     * （周期票+历次调账票，排除 VOID/REFUND），差额生成一张仅含 ADJUSTMENT 行的票；
     * 同 adjustmentKey 幂等。差额为 0 不出票。
     */
    public Bill applyLateAdjustment(String subscriptionId, int periodIndex, String adjustmentKey) {
        if (adjustmentKey == null || adjustmentKey.isBlank()) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT, "adjustmentKey is required");
        }
        Subscription subscription = requireSubscription(subscriptionId);
        synchronized (subscriptionRepository.lockOf(subscriptionId)) {
            String pKey = periodKeyOf(subscription, periodIndex);
            for (Bill b : billRepository.findBySubscription(subscriptionId)) {
                if (adjustmentKey.equals(b.getAdjustmentKey())) {
                    log.info("[billing] adjustment key={} replayed -> bill={}", adjustmentKey, b.getId());
                    return b;
                }
            }
            long alreadyBilled = billRepository.findBySubscription(subscriptionId).stream()
                    .filter(b -> pKey.equals(b.getPeriodKey()))
                    .filter(b -> b.getStatus() != BillStatus.VOID && b.getStatus() != BillStatus.REFUND)
                    .mapToLong(b -> b.getAmount().cents())
                    .sum();
            long expected = expectedAmountCents(subscription, periodIndex);
            long delta = expected - alreadyBilled;
            if (delta == 0) {
                log.info("[billing] adjustment key={} sub={} period={} delta=0 (expected={} billed={}), no bill",
                        adjustmentKey, subscriptionId, pKey, expected, alreadyBilled);
                return null;
            }
            Instant ps = Periods.periodStart(subscription.getAnchoredAt(), periodIndex, clock.zone());
            Instant pe = Periods.periodEndExclusive(subscription.getAnchoredAt(), periodIndex, clock.zone());
            BillLine line = new BillLine(BillLineKind.ADJUSTMENT, null,
                    "late-data adjustment " + pKey + ": expected " + expected
                            + " - already billed " + alreadyBilled + " (key=" + adjustmentKey + ")",
                    delta);
            Instant now = clock.now();
            Bill bill = Bill.adjustment(subscriptionId, pKey, ps, pe, List.of(line), now,
                    now.plus(Duration.ofDays(GRACE_DAYS)),
                    "grp-" + subscriptionId + "-" + pKey, adjustmentKey);
            if (bill.isZeroAmount()) {
                bill.markPaid("zero-amount");
            }
            billRepository.save(bill);
            log.info("[billing] adjustment bill={} sub={} period={} delta={} expected={} billed={}; "
                            + "basis: original bills immutable, only the difference is posted",
                    bill.getId(), subscriptionId, pKey, delta, expected, alreadyBilled);
            return bill;
        }
    }

    /** 以当前全量数据重算账期应收（基础费+超额费），缺量同样阻塞。对账只读入口使用。 */
    public long recomputeExpectedAmount(String subscriptionId, int periodIndex) {
        Subscription subscription = requireSubscription(subscriptionId);
        synchronized (subscriptionRepository.lockOf(subscriptionId)) {
            return expectedAmountCents(subscription, periodIndex);
        }
    }

    /** 以当前全量数据重算账期应收（基础费+超额费），缺量同样阻塞。 */
    private long expectedAmountCents(Subscription subscription, int periodIndex) {
        Instant ps = Periods.periodStart(subscription.getAnchoredAt(), periodIndex, clock.zone());
        Instant pe = Periods.periodEndExclusive(subscription.getAnchoredAt(), periodIndex, clock.zone());
        List<SegmentCtx> ctxs = segments(subscription, ps, pe);
        List<UsageReport> reports = reportsInPeriod(subscription.getId(), ps, pe);
        long total = 0;
        for (SegmentCtx ctx : ctxs) {
            total += Proration.prorate(ctx.plan().basePrice(),
                    ctx.segment().start(), ctx.segment().end(), ps, pe).cents();
        }
        Map<String, Acc> accs = accumulate(ctxs, reports);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Acc> en : accs.entrySet()) {
            if (en.getValue().missing) {
                missing.add(en.getKey());
            }
            total += en.getValue().overageAmount;
        }
        if (!missing.isEmpty()) {
            throw new BillingException(ErrorCode.USAGE_METERING_MISSING,
                    "cannot adjust period " + periodIndex + ": metering missing for " + missing);
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 账段与用量分摊
    // ------------------------------------------------------------------

    private List<SegmentCtx> segments(Subscription subscription, Instant ps, Instant pe) {
        List<SegmentCtx> out = new ArrayList<>();
        long periodSeconds = Duration.between(ps, pe).getSeconds();
        for (PlanSegment seg : Timeline.activeSegments(subscription, ps, pe)) {
            Plan plan = requirePlan(seg.planId());
            long seconds = Duration.between(seg.start(), seg.end()).getSeconds();
            out.add(new SegmentCtx(seg, plan, seconds, periodSeconds));
        }
        return out;
    }

    private List<UsageReport> reportsInPeriod(String subscriptionId, Instant ps, Instant pe) {
        return usageRepository.findBySubscription(subscriptionId).stream()
                .filter(r -> r.windowStart().isBefore(pe) && r.windowEnd().isAfter(ps))
                .toList();
    }

    /**
     * 把每条上报按“窗口×账段重叠秒数”分摊到账段；段配额 = floor(月配额 × 段秒/账期秒)，
     * 取整余差并入该指标的最后一个有配额账段，保证各段配额之和确定。
     * 段内累计超段配额部分按段单价计超额费。
     */
    private Map<String, Acc> accumulate(List<SegmentCtx> ctxs, List<UsageReport> reports) {
        Map<String, Acc> accs = new LinkedHashMap<>();

        // 预建指标累加器并计算每指标的账段缩放配额
        for (SegmentCtx ctx : ctxs) {
            for (MetricDef def : ctx.plan().metrics().values()) {
                accs.computeIfAbsent(def.key(), k -> new Acc());
            }
        }

        // 段配额（按指标）：floor 比例分配 + 余差并入最后段
        // 段配额（按指标）：每段配额 = floor(该段套餐月配额 × 段秒/账期秒)。
        // 结构：metric -> 与 ctxs 等长的配额数组（-1 表示该段套餐不含此指标）。
        // 不同套餐配额不同，因此不存在“跨段共享一个月配额”的余差问题。
        Map<String, long[]> segQuota = new LinkedHashMap<>();
        long periodSeconds = ctxs.isEmpty() ? 0L : ctxs.get(0).periodSeconds();
        for (String metric : accs.keySet()) {
            long[] arr = new long[ctxs.size()];
            for (int i = 0; i < ctxs.size(); i++) {
                MetricDef def = metricDefOrNull(ctxs.get(i).plan(), metric);
                arr[i] = def == null ? -1
                        : def.includedUnits() * ctxs.get(i).seconds() / periodSeconds;
            }
            segQuota.put(metric, arr);
        }

        for (UsageReport r : reports) {
            Acc acc = accs.computeIfAbsent(r.metricKey(), k -> new Acc());
            acc.quantity = Math.addExact(acc.quantity, r.quantity());
            if (!r.meteringComplete()) {
                acc.missing = true;
            }

            // 窗口与各账段求交
            int[] idxs = new int[ctxs.size()];
            long[] overlaps = new long[ctxs.size()];
            long overlapTotal = 0;
            int n = 0;
            for (int i = 0; i < ctxs.size(); i++) {
                PlanSegment seg = ctxs.get(i).segment();
                Instant s = r.windowStart().isAfter(seg.start()) ? r.windowStart() : seg.start();
                Instant e = r.windowEnd().isBefore(seg.end()) ? r.windowEnd() : seg.end();
                if (e.isAfter(s)) {
                    idxs[n] = i;
                    long secs = Duration.between(s, e).getSeconds();
                    overlaps[n] = secs;
                    overlapTotal += secs;
                    n++;
                }
            }
            if (n == 0) {
                log.warn("[billing] usage key={} metric={} qty={} window=[{},{}) overlaps no ACTIVE segment; "
                                + "kept in cumulative quantity, not charged",
                        r.idempotencyKey(), r.metricKey(), r.quantity(), r.windowStart(), r.windowEnd());
                continue;
            }
            long[] quota = segQuota.computeIfAbsent(r.metricKey(), k -> new long[ctxs.size()]);
            long allocated = 0;
            for (int j = 0; j < n; j++) {
                int i = idxs[j];
                long units;
                if (j == n - 1) {
                    units = r.quantity() - allocated; // 分摊取整余差给最后相交段
                } else {
                    units = r.quantity() * overlaps[j] / overlapTotal;
                    allocated += units;
                }
                if (units <= 0) {
                    continue;
                }
                Plan plan = ctxs.get(i).plan();
                MetricDef def = metricDefOrNull(plan, r.metricKey());
                String segKey = r.metricKey() + "#" + i;
                long prev = acc.segmentQty.getOrDefault(segKey, 0L);
                long merged = prev + units;
                acc.segmentQty.put(segKey, merged);

                long q = quota[i];
                if (def == null || q < 0) {
                    // 该段套餐不含此指标：全部超额，单价缺失属配置错误，明确报错而非按 0
                    if (def == null) {
                        throw new BillingException(ErrorCode.INVALID_ARGUMENT,
                                "metric " + r.metricKey() + " used in segment of plan " + plan.id()
                                        + " which does not define it");
                    }
                }
                long prevOver = Math.max(0, prev - Math.max(0, q));
                long nowOver = Math.max(0, merged - Math.max(0, q));
                long extra = nowOver - prevOver;
                acc.overageUnits += extra;
                acc.overageAmount += extra * def.overagePrice().cents();
                if (merged > q) {
                    acc.overQuota = true;
                }
            }
        }

        // 指标展示配额 = 各段缩放配额之和
        for (Map.Entry<String, Acc> en : accs.entrySet()) {
            long[] arr = segQuota.get(en.getKey());
            long sum = 0;
            if (arr != null) {
                for (long q : arr) {
                    if (q > 0) {
                        sum += q;
                    }
                }
            }
            en.getValue().includedUnits = sum;
        }
        return accs;
    }

    private static MetricDef metricDefOrNull(Plan plan, String metricKey) {
        return plan.metrics().get(metricKey);
    }

    private Map<String, MetricUsage> toMetricUsages(Map<String, Acc> accs) {
        Map<String, MetricUsage> out = new LinkedHashMap<>();
        for (Map.Entry<String, Acc> en : accs.entrySet()) {
            Acc a = en.getValue();
            out.put(en.getKey(), new MetricUsage(en.getKey(), a.quantity, a.includedUnits,
                    a.overageUnits, a.overageAmount, a.missing, a.overQuota));
        }
        return out;
    }

    private Plan requirePlan(String planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new BillingException(ErrorCode.PLAN_NOT_FOUND,
                        "plan referenced by timeline not found: " + planId));
    }

    private Subscription requireSubscription(String subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "subscription not found: " + subscriptionId));
    }

    private String periodKeyOf(Subscription subscription, int periodIndex) {
        return Periods.periodKey(
                Periods.periodStart(subscription.getAnchoredAt(), periodIndex, clock.zone()),
                clock.zone());
    }

    public Bill getBill(String billId) {
        return billRepository.findById(billId)
                .orElseThrow(() -> new BillingException(ErrorCode.BILL_NOT_FOUND, "bill not found: " + billId));
    }

    public List<Bill> listBills(String subscriptionId) {
        return billRepository.findBySubscription(subscriptionId);
    }
}

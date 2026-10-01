package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.domain.Subscription;
import com.github.feng111627.usagebasedbillingjava.domain.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.domain.UsageReport;
import com.github.feng111627.usagebasedbillingjava.engine.Timeline;
import com.github.feng111627.usagebasedbillingjava.repo.SubscriptionRepository;
import com.github.feng111627.usagebasedbillingjava.repo.UsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 用量上报服务。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>幂等：以调用方 idempotencyKey 去重，并发/重复上报只生效一次；同键不同内容报
 *       IDEMPOTENCY_KEY_CONFLICT，不覆盖首次数据；</li>
 *   <li>乱序/迟到：上报按原始窗口保存，累计时按窗口排序后求和，与到达顺序无关；
 *       窗口落在已关闭账期时仍受理，出账侧通过调账账单补收，不丢量；</li>
 *   <li>并发一致：同一订阅的上报串行化（订阅锁），累计结果确定；</li>
 *   <li>计量缺失：meteringComplete=false 的窗口不会被按 0 处理，汇总与账单上明确标记
 *       MISSING_METERING，出账阻塞为“待核”（BillingService 负责）；</li>
 *   <li>归属校验：窗口必须落在订阅已开通且未退订的时间范围内；指标必须属于该时刻套餐。</li>
 * </ul>
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private final UsageRepository usageRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanService planService;
    private final BillingClock clock;

    public UsageService(UsageRepository usageRepository,
                        SubscriptionRepository subscriptionRepository,
                        PlanService planService,
                        BillingClock clock) {
        this.usageRepository = usageRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planService = planService;
        this.clock = clock;
    }

    /** 上报结果：report 为生效记录，duplicate=true 表示命中幂等重放（未产生新计费量）。 */
    public record ReportResult(UsageReport report, boolean duplicate) {
    }

    public ReportResult report(String idempotencyKey,
                              String subscriptionId,
                              String metricKey,
                              long quantity,
                              Instant windowStart,
                              Instant windowEnd,
                              Boolean meteringComplete) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT, "idempotencyKey is required");
        }
        if (metricKey == null || metricKey.isBlank()) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT, "metricKey is required");
        }
        if (quantity < 0) {
            throw new BillingException(ErrorCode.USAGE_QTY_INVALID,
                    "quantity must be >= 0, got " + quantity + " for key " + idempotencyKey);
        }
        if (windowStart == null || windowEnd == null || !windowEnd.isAfter(windowStart)) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT,
                    "window must satisfy start < end for key " + idempotencyKey);
        }
        boolean complete = meteringComplete == null || meteringComplete;

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new BillingException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "subscription not found: " + subscriptionId));

        synchronized (subscriptionRepository.lockOf(subscriptionId)) {
            // 窗口归属：起点时刻订阅必须已开通且未退订；暂停期不受理用量（原因可区分）
            SubscriptionStatus statusAtStart = Timeline.statusAt(subscription, windowStart);
            if (statusAtStart == null) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_NOT_ACTIVE,
                        "usage window " + windowStart + " is before subscription activation, key="
                                + idempotencyKey);
            }
            if (statusAtStart == SubscriptionStatus.CANCELLED) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_ALREADY_CANCELLED,
                        "usage window " + windowStart + " is after cancellation, key=" + idempotencyKey);
            }
            if (statusAtStart == SubscriptionStatus.SUSPENDED) {
                throw new BillingException(ErrorCode.SUBSCRIPTION_SUSPENDED,
                        "usage reported for suspended period " + windowStart + ", key=" + idempotencyKey);
            }
            String planIdAtWindow = Timeline.planIdAt(subscription, windowStart);
            // 指标合法性：不属于套餐的计量绝不静默按 0 或任意单价处理
            planService.getPlan(planIdAtWindow).metric(metricKey);

            UsageReport incoming = new UsageReport(idempotencyKey, subscriptionId, metricKey,
                    quantity, windowStart, windowEnd, clock.now(), complete);

            UsageRepository.AppendResult result = usageRepository.append(incoming);
            if (result.duplicate()) {
                UsageReport first = result.report();
                UsageRepository.requireSame(first, incoming);
                log.info("[usage] duplicate key={} sub={} metric={} qty={} window=[{},{}) "
                                + "-> ignored, returning first record receivedAt={}; "
                                + "basis: idempotency replay, no double counting",
                        idempotencyKey, subscriptionId, metricKey, quantity,
                        windowStart, windowEnd, first.recordedAt());
                return new ReportResult(first, true);
            }
            if (!complete) {
                log.warn("[usage] MISSING_METERING key={} sub={} metric={} window=[{},{}) "
                                + "accepted but flagged; basis: incomplete metering must never be zero-filled, "
                                + "bill will be blocked pending review",
                        idempotencyKey, subscriptionId, metricKey, windowStart, windowEnd);
            } else {
                log.info("[usage] accepted key={} sub={} metric={} qty={} window=[{},{}) late={}; "
                                + "basis: appended to event-style log, aggregation is order-independent",
                        idempotencyKey, subscriptionId, metricKey, quantity,
                        windowStart, windowEnd,
                        windowEnd.isBefore(clock.now().minusSeconds(1)));
            }
            return new ReportResult(incoming, false);
        }
    }

    public List<UsageReport> listReports(String subscriptionId) {
        return usageRepository.findBySubscription(subscriptionId);
    }
}

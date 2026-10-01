package com.github.feng111627.usagebasedbillingjava.repo;

import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.domain.UsageReport;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用量上报内存仓储：只追加原始上报，按 (窗口起点, 窗口终点, 指标) 排序保证累计确定。
 */
@Repository
public class UsageRepository {

    private final ConcurrentHashMap<String, UsageReport> byKey = new ConcurrentHashMap<>();
    private final List<UsageReport> all = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * 追加上报。
     *
     * @return 追加结果；若幂等键已存在，返回 {@link AppendResult#duplicate()} 标记，
     *         由服务层区分“重复幂等返回”与“同键不同内容冲突”
     */
    public AppendResult append(UsageReport report) {
        UsageReport existing = byKey.putIfAbsent(report.idempotencyKey(), report);
        if (existing != null) {
            return new AppendResult(existing, true);
        }
        all.add(report);
        return new AppendResult(report, false);
    }

    public Optional<UsageReport> findByIdempotencyKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public List<UsageReport> findBySubscription(String subscriptionId) {
        synchronized (all) {
            return all.stream()
                    .filter(r -> r.subscriptionId().equals(subscriptionId))
                    .sorted(Comparator.comparing(UsageReport::windowStart)
                            .thenComparing(UsageReport::windowEnd)
                            .thenComparing(UsageReport::metricKey)
                            .thenComparing(UsageReport::idempotencyKey))
                    .toList();
        }
    }

    public List<UsageReport> findAll() {
        synchronized (all) {
            return List.copyOf(all);
        }
    }

    /** 同键不同内容 -> 冲突错误（防止调用方误用幂等键覆盖计费语义）。 */
    public static void requireSame(UsageReport existing, UsageReport incoming) {
        boolean same = existing.subscriptionId().equals(incoming.subscriptionId())
                && existing.metricKey().equals(incoming.metricKey())
                && existing.quantity() == incoming.quantity()
                && existing.windowStart().equals(incoming.windowStart())
                && existing.windowEnd().equals(incoming.windowEnd())
                && existing.meteringComplete() == incoming.meteringComplete();
        if (!same) {
            throw new BillingException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "idempotency key " + incoming.idempotencyKey()
                            + " reused with a different payload; first report kept: "
                            + existing.subscriptionId() + "/" + existing.metricKey()
                            + " qty=" + existing.quantity()
                            + " window=[" + existing.windowStart() + "," + existing.windowEnd() + ")");
        }
    }

    public record AppendResult(UsageReport report, boolean duplicate) {
    }
}

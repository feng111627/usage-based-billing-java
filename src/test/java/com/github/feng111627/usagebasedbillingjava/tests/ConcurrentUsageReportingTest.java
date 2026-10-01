package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.Money;
import com.github.feng111627.usagebasedbillingjava.domain.MetricDef;
import com.github.feng111627.usagebasedbillingjava.domain.MetricUsage;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import com.github.feng111627.usagebasedbillingjava.service.PlanService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
import com.github.feng111627.usagebasedbillingjava.service.UsageService;
import com.github.feng111627.usagebasedbillingjava.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景一：并发重复/乱序上报。
 * 多线程用同一幂等键上报必须只生效一次；不同键并发累计必须一致；
 * 重复上报返回 duplicate=true；迟到窗口的量不丢。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class ConcurrentUsageReportingTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentUsageReportingTest.class);

    @Autowired PlanService planService;
    @Autowired SubscriptionService subscriptionService;
    @Autowired UsageService usageService;
    @Autowired BillingService billingService;
    @Autowired BillingClock clock;

    @Test
    void concurrentDuplicateAndAggregation() throws Exception {
        planService.createPlan("p", "P", Money.of(1000),
                Map.of("api", new MetricDef("api", 100, Money.of(2), "call")));
        Instant anchor = clock.now();
        subscriptionService.subscribe("sub-conc", "cust", "p", anchor);

        Instant w1s = anchor;
        Instant w1e = anchor.plusSeconds(86400);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger duplicates = new AtomicInteger();

        // 16 个线程同一幂等键并发上报同一窗口
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    UsageService.ReportResult r = usageService.report("dup-key", "sub-conc", "api",
                            50, w1s, w1e, true);
                    if (r.duplicate()) {
                        duplicates.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("worker error", e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.info("[case] 16 concurrent reports with same idempotency key -> duplicate replays={}, "
                + "accepted exactly once (basis: key unique constraint + sub lock)", duplicates.get());
        assertEquals(threads - 1, duplicates.get());

        // 不同键、不同窗口、故意乱序提交（先交第 3 个窗口）
        usageService.report("k3", "sub-conc", "api", 30,
                anchor.plusSeconds(2 * 86400), anchor.plusSeconds(3 * 86400), true);
        usageService.report("k1", "sub-conc", "api", 10,
                anchor, anchor.plusSeconds(86400), true);
        // k1 重放
        UsageService.ReportResult replay = usageService.report("k1", "sub-conc", "api", 10,
                anchor, anchor.plusSeconds(86400), true);
        usageService.report("k2", "sub-conc", "api", 20,
                anchor.plusSeconds(86400), anchor.plusSeconds(2 * 86400), true);
        assertTrue(replay.duplicate());

        MetricUsage usage = billingService.summarizeUsage("sub-conc", 0).get("api");
        log.info("[case] windows 50(k1-dedup)+10(k1)+20(k2)+30(k3 out-of-order) -> totalQuantity={} "
                + "(basis: append-only log aggregated by window order, replay not counted)",
                usage.totalQuantity());
        // dup-key 窗口 50 + k1 10 + k2 20 + k3 30 = 110
        assertEquals(110, usage.totalQuantity());
        assertTrue(usage.overQuota());
    }
}

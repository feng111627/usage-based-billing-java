package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.PauseAction;
import com.github.feng111627.usagebasedbillingjava.model.PauseRecord;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LedgerService;
import com.github.feng111627.usagebasedbillingjava.service.PauseService;
import com.github.feng111627.usagebasedbillingjava.service.ReconciliationService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
import com.github.feng111627.usagebasedbillingjava.service.UsageService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 暂停/恢复并发与幂等：
 * - 同一时刻并发“暂停 + 恢复”只有一个能生效，另一个被可区分原因拒绝，不出现退了又扣、状态来回跳；
 * - 同一时刻并发“暂停 + 升降配”只有一个生效；
 * - 暂停/恢复与出账/收款串行；重复幂等键返回首次结果，不重复退款/扣款；
 * - 恢复后并发二次暂停/恢复仍全序；客户主动 PAUSED 与收款失败 SUSPENDED 错误码可区分。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PauseResumeConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(PauseResumeConcurrencyTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired PauseService pauseService;
    @Autowired UsageService usage;
    @Autowired BillingService billing;
    @Autowired ClockService clock;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;

    @Test
    void concurrentPauseAndResumeAtSameInstantOnlyOneTakesEffect() throws Exception {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-c1", "cust-c", "BASIC", 1);

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger paused = new AtomicInteger();
        AtomicInteger resumed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Instant same = Instant.parse("2026-09-12T00:00:00Z");
        for (int i = 0; i < n; i++) {
            final int i2 = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (i2 % 2 == 0) {
                        PauseRecord r = pauseService.pause("sub-c1", "race-pause-" + i2, same);
                        if (r.getAction() == PauseAction.PAUSE) {
                            paused.incrementAndGet();
                        }
                    } else {
                        pauseService.resume("sub-c1", "race-resume-" + i2, same);
                        resumed.incrementAndGet();
                    }
                } catch (BillingException ex) {
                    rejected.incrementAndGet();
                    assertTrue(ex.getCode() == ErrorCode.CHANGE_OUT_OF_ORDER
                                    || ex.getCode() == ErrorCode.SUBSCRIPTION_PAUSED_BY_CUSTOMER
                                    || ex.getCode() == ErrorCode.CONFLICT,
                            "拒绝原因必须可区分: " + ex.getCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long effects = paused.get() + resumed.get();
        log.info("[race] 输入：同一时刻 {} 个并发暂停/恢复；判定依据：订阅锁+全序只放行 1 个；生效={} 拒绝={} (暂停成功={}, 恢复成功={})",
                n, effects, rejected.get(), paused.get(), resumed.get());
        assertEquals(1, effects, "同一时刻暂停/恢复只能有一个生效，禁止退了又扣/状态来回跳");
        assertEquals(n - 1, rejected.get());
        long seq = subscriptions.getSubscription("sub-c1").getLastChangeSeq();
        assertEquals(1, seq, "无半截状态：seq 只前进一次");
        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "并发暂停/恢复后对账必须平: " + issues);
        assertEquals(0, ledger.conservationDelta().signum());
    }

    @Test
    void concurrentPauseAndPlanChangeAtSameInstantAreTotallyOrdered() throws Exception {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-c2", "cust-c2", "BASIC", 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Instant same = Instant.parse("2026-09-13T00:00:00Z");
        Runnable pauseTask = () -> {
            try {
                start.await();
                pauseService.pause("sub-c2", "race2-pause", same);
                ok.incrementAndGet();
            } catch (BillingException ex) {
                rejected.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Runnable changeTask = () -> {
            try {
                start.await();
                subscriptions.changePlan("sub-c2", "PRO", "race2-change", same);
                ok.incrementAndGet();
            } catch (BillingException ex) {
                rejected.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(pauseTask);
        pool.submit(changeTask);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("[race] 输入：同一时刻暂停 vs 升级 BASIC->PRO；判定依据：跨操作共用 seq 全序；生效={} 拒绝={}",
                ok.get(), rejected.get());
        assertEquals(1, ok.get(), "暂停与升降配同一时刻只能一个生效");
        assertEquals(1, rejected.get());
        assertEquals(1, subscriptions.getSubscription("sub-c2").getLastChangeSeq());
        assertTrue(reconciliation.reconcileAll().isEmpty());
    }

    @Test
    void idempotentPauseResumeNeverSettlesTwiceAndPauseVsSuspendedAreDistinct() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-c3", "cust-c3", "BASIC", 1);

        PauseRecord p1 = pauseService.pause("sub-c3", "pause-c3", Instant.parse("2026-09-08T00:00:00Z"));
        PauseRecord p2 = pauseService.pause("sub-c3", "pause-c3", Instant.parse("2026-09-08T00:00:00Z"));
        PauseRecord p3 = pauseService.pause("sub-c3", "pause-c3", Instant.parse("2026-09-09T00:00:00Z"));
        assertEquals(p1.getSeq(), p2.getSeq());
        assertEquals(p1.getSeq(), p3.getSeq(), "同键不同参数重放也必须返回首次结果");
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-c3").getStatus());

        // 暂停态做升降配：必须是“客户暂停”错误码，区别于收款失败 SUSPENDED
        BillingException ex = assertThrows(BillingException.class,
                () -> subscriptions.changePlan("sub-c3", "PRO", "x-while-paused",
                        Instant.parse("2026-09-15T00:00:00Z")));
        assertEquals(ErrorCode.SUBSCRIPTION_PAUSED_BY_CUSTOMER, ex.getCode());
        log.info("[idemp] 暂停态升降配被拒 code={}（与收款失败 {} 可区分）",
                ex.getCode(), ErrorCode.SUBSCRIPTION_SUSPENDED);

        // 重复暂停：可区分原因
        BillingException dup = assertThrows(BillingException.class,
                () -> pauseService.pause("sub-c3", "pause-c3-again", Instant.parse("2026-09-16T00:00:00Z")));
        assertEquals(ErrorCode.SUBSCRIPTION_PAUSED_BY_CUSTOMER, dup.getCode());

        // 恢复幂等
        PauseRecord r1 = pauseService.resume("sub-c3", "resume-c3", Instant.parse("2026-09-20T00:00:00Z"));
        PauseRecord r2 = pauseService.resume("sub-c3", "resume-c3", Instant.parse("2026-09-20T00:00:00Z"));
        assertEquals(r1.getSeq(), r2.getSeq());
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscription("sub-c3").getStatus());

        // 乱序恢复（早于已发生的暂停/恢复）被拒
        BillingException ooo = assertThrows(BillingException.class,
                () -> pauseService.pause("sub-c3", "pause-c3-late", Instant.parse("2026-09-10T00:00:00Z")));
        assertEquals(ErrorCode.CHANGE_OUT_OF_ORDER, ooo.getCode());

        // 只有两笔结算：一次退款(暂停 seq=1) + 一次补收(恢复 seq=2)
        assertEquals(2, pauseService.list("sub-c3").size());
        assertEquals(2, subscriptions.getSubscription("sub-c3").getLastChangeSeq());
        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "幂等/乱序后对账平: " + issues);
        assertEquals(0, ledger.conservationDelta().signum());
        log.info("[idemp] sub-c3 结算记录数={} 末 seq={} 对账问题={} 守恒差={}",
                pauseService.list("sub-c3").size(),
                subscriptions.getSubscription("sub-c3").getLastChangeSeq(),
                issues, ledger.conservationDelta());
    }

    @Test
    void pausedStatusSurvivesBillingTickAndClosePeriodSerialExecution() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-c4", "cust-c4", "BASIC", 1);
        usage.report("z-c4", "sub-c4", "api_calls", 0, Instant.parse("2026-09-02T00:00:00Z"));
        pauseService.pause("sub-c4", "pause-c4", Instant.parse("2026-09-10T00:00:00Z"));

        // 暂停期间 tick 不得驱动收款状态机、不得改状态
        var inv = billing.runBillingTick("sub-c4");
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-c4").getStatus());

        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice cyc = billing.closePeriod("sub-c4");
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-c4").getStatus());
        // 出账与暂停共用订阅锁，串行执行后状态确定、对账平
        assertTrue(reconciliation.reconcileAll().isEmpty());
        log.info("[serial] sub-c4 暂停期 tick/出账串行后仍 PAUSED，周期账单金额={}", cyc.getAmount());
    }
}

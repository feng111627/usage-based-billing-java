package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.ChangeType;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus;
import com.github.feng111627.usagebasedbillingjava.model.LineKind;
import com.github.feng111627.usagebasedbillingjava.model.PlanChange;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LedgerService;
import com.github.feng111627.usagebasedbillingjava.service.ReconciliationService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
import com.github.feng111627.usagebasedbillingjava.service.UsageService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户主动暂停 / 恢复（与收款失败的 SUSPENDED 相区分）：
 * - 周期中途暂停：按暂停时刻到周期末的剩余比例退回未消耗基础费；
 * - 恢复：按恢复时刻到周期末的剩余比例补收基础费，周期配额按活跃时长等比例折算；
 * - 暂停窗口内的用量上报被 SUBSCRIPTION_PAUSED 明确拒绝；
 *   暂停之前真实发生的用量在暂停期间补报仍正常累计；
 * - 暂停跨周期：周期结算不产生基础费、整周期暂停不算缺计量；
 * - 并发暂停/恢复串行成确定全序，幂等键重复提交返回首次结果，不重复退款/扣款。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PauseResumeTest {

    private static final Logger log = LoggerFactory.getLogger(PauseResumeTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired UsageService usage;
    @Autowired BillingService billing;
    @Autowired ClockService clock;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;

    @Test
    void midCyclePauseRefundsRemainingBaseProportionally() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-pause1", "cust-p1", "BASIC", 1);

        // 9 月周期 30 天，9/11 00:00 暂停 -> 剩余 20 天，比例 20/30=0.666667，退款 30*0.666667=20.00
        Instant eff = Instant.parse("2026-09-11T00:00:00Z");
        log.info("[case] 输入：sub-pause1 BASIC(30) 暂停 eff={}；判定依据：退款=30*剩余比例(20/30=0.666667)=20.00", eff);
        PlanChange pause = subscriptions.pause("sub-pause1", "pause-1", eff);

        assertEquals(ChangeType.PAUSE, pause.getType());
        assertEquals(0, new BigDecimal("-20.00").compareTo(pause.getProration()),
                "暂停退款应为 -30*20/30=-20.00，实际 " + pause.getProration());
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-pause1").getStatus());
        Invoice refundInv = billing.getInvoice(pause.getInvoiceId());
        assertEquals(InvoiceStatus.REFUNDED, refundInv.getStatus(), "负额退款账单通道退款成功后应为 REFUNDED");
        log.info("[case] 判定：proration={} 状态=PAUSED 退款账单={} REFUNDED", pause.getProration(), refundInv.getInvoiceId());

        // 暂停不是收款失败停用：暂停中变更套餐以 SUBSCRIPTION_PAUSED 拒绝，与 SUBSCRIPTION_SUSPENDED 可区分
        BillingException ex = assertThrows(BillingException.class,
                () -> subscriptions.changePlan("sub-pause1", "PRO", "chg-while-paused",
                        Instant.parse("2026-09-12T00:00:00Z")));
        assertEquals(ErrorCode.SUBSCRIPTION_PAUSED, ex.getCode());
        assertNotEquals(ErrorCode.SUBSCRIPTION_SUSPENDED, ex.getCode());
        log.info("[case] 判定：暂停中变更被拒绝 code={}（与 SUBSCRIPTION_SUSPENDED 区分）", ex.getCode());

        assertReconcileClean();
    }

    @Test
    void resumeChargesProratedBaseAndProratesQuota() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-pause2", "cust-p2", "BASIC", 1);
        // 活跃窗口内（9/5）上报 700 次
        usage.report("ev-p2-1", "sub-pause2", "api_calls", 700, Instant.parse("2026-09-05T08:00:00Z"));

        subscriptions.pause("sub-pause2", "pause-2", Instant.parse("2026-09-11T00:00:00Z"));
        // 9/21 00:00 恢复 -> 剩余 10 天，比例 10/30=0.333333，补收 30*0.333333=10.00
        Instant resumeEff = Instant.parse("2026-09-21T00:00:00Z");
        log.info("[case] 输入：sub-pause2 恢复 eff={}；判定依据：补收=30*剩余比例(10/30=0.333333)=10.00", resumeEff);
        PlanChange resume = subscriptions.resume("sub-pause2", "resume-2", resumeEff);

        assertEquals(ChangeType.RESUME, resume.getType());
        assertEquals(0, new BigDecimal("10.00").compareTo(resume.getProration()),
                "恢复补收应为 30*10/30=10.00，实际 " + resume.getProration());
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscription("sub-pause2").getStatus());

        // 活跃时长 = 9/1~9/11 + 9/21~10/1 = 20 天 -> 活跃比例 0.666667，配额折算 1000*0.666667=667
        // 用量 700 -> 超 33 * 0.05 = 1.65；账单 = 1.65 + 下周期基础费 30 = 31.65
        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice inv = billing.closePeriod("sub-pause2");
        log.info("[case] 判定：出账 invoice={} amount={} 明细={}", inv.getInvoiceId(), inv.getAmount(), inv.getLines());
        assertEquals(InvoiceStatus.PAID, inv.getStatus());
        assertEquals(0, new BigDecimal("31.65").compareTo(inv.getAmount()),
                "账单应为 超配额1.65 + 基础费30 = 31.65，实际 " + inv.getAmount());
        var overageLine = inv.getLines().stream().filter(l -> l.getKind() == LineKind.OVERAGE).findFirst().orElseThrow();
        assertTrue(overageLine.getBasis().contains("配额=667") && overageLine.getBasis().contains("活跃比例=0.666667"),
                "超配额行应写明折算配额与活跃比例，实际: " + overageLine.getBasis());
        assertEquals(0, new BigDecimal("1.65").compareTo(overageLine.getAmount()));
        log.info("[case] 判定：配额折算 1000*0.666667=667，超量 33*0.05=1.65，依据={}", overageLine.getBasis());

        assertReconcileClean();
    }

    @Test
    void usageReportedDuringPauseWindowIsRejectedWithDistinctReason() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-pause3", "cust-p3", "BASIC", 1);
        subscriptions.pause("sub-pause3", "pause-3", Instant.parse("2026-09-11T00:00:00Z"));

        // 暂停窗口内发生的用量：明确拒绝，不按零、不记到别的周期
        BillingException ex = assertThrows(BillingException.class, () -> usage.report(
                "ev-p3-during", "sub-pause3", "api_calls", 50, Instant.parse("2026-09-15T08:00:00Z")));
        assertEquals(ErrorCode.SUBSCRIPTION_PAUSED, ex.getCode());
        log.info("[case] 判定：暂停窗口内上报被拒绝 code={} message={}", ex.getCode(), ex.getMessage());

        // 恢复后补报“暂停窗口内”的用量仍被拒绝（窗口由变更序列确定，不随当前状态变化）
        subscriptions.resume("sub-pause3", "resume-3", Instant.parse("2026-09-21T00:00:00Z"));
        BillingException ex2 = assertThrows(BillingException.class, () -> usage.report(
                "ev-p3-during-2", "sub-pause3", "api_calls", 50, Instant.parse("2026-09-15T09:00:00Z")));
        assertEquals(ErrorCode.SUBSCRIPTION_PAUSED, ex2.getCode());
        log.info("[case] 判定：恢复后补报暂停窗口内用量仍被拒绝 code={}", ex2.getCode());

        assertReconcileClean();
    }

    @Test
    void lateUsageFromBeforePauseReportedDuringPauseIsAccepted() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-pause4", "cust-p4", "BASIC", 1);
        subscriptions.pause("sub-pause4", "pause-4", Instant.parse("2026-09-11T00:00:00Z"));

        // 暂停期间补报暂停之前（9/5）真实发生的用量：按当时的活跃账段正常累计
        clock.advanceTo(Instant.parse("2026-09-15T00:00:00Z"));
        var acc = usage.report("ev-p4-late", "sub-pause4", "api_calls", 800, Instant.parse("2026-09-05T08:00:00Z"));
        assertEquals(800, acc.getTotal());
        log.info("[case] 判定：暂停期间补报 9/5 用量被接受，累计={}", acc.getTotal());

        // 同 eventId 重复补报：不丢量也不重复
        var again = usage.report("ev-p4-late", "sub-pause4", "api_calls", 800, Instant.parse("2026-09-05T08:00:00Z"));
        assertEquals(800, again.getTotal(), "重复补报不得重复加量");
        log.info("[case] 判定：同 eventId 重放累计仍为 {}", again.getTotal());

        // 恢复（9/21）后出账：活跃 20 天 -> 配额 667，用量 800 -> 超 133*0.05=6.65，账单 6.65+30=36.65
        subscriptions.resume("sub-pause4", "resume-4", Instant.parse("2026-09-21T00:00:00Z"));
        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice inv = billing.closePeriod("sub-pause4");
        log.info("[case] 判定：出账 invoice={} amount={} 明细={}", inv.getInvoiceId(), inv.getAmount(), inv.getLines());
        assertEquals(0, new BigDecimal("36.65").compareTo(inv.getAmount()),
                "补报用量应计入原周期：6.65 超配额 + 30 基础费 = 36.65，实际 " + inv.getAmount());

        assertReconcileClean();
    }

    @Test
    void pauseAcrossPeriodsProducesNoBaseFeeAndNoMeteringAnomaly() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-pause5", "cust-p5", "BASIC", 1);
        usage.report("ev-p5-1", "sub-pause5", "api_calls", 10, Instant.parse("2026-09-03T08:00:00Z"));
        subscriptions.pause("sub-pause5", "pause-5", Instant.parse("2026-09-11T00:00:00Z"));

        // 第一期结算：活跃 10 天 -> 配额 333，用量 10 未超；暂停中不预收下周期基础费 -> 0 元账单
        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice inv1 = billing.closePeriod("sub-pause5");
        log.info("[case] 判定：第一期 invoice={} status={} amount={} 明细={}",
                inv1.getInvoiceId(), inv1.getStatus(), inv1.getAmount(), inv1.getLines());
        assertEquals(InvoiceStatus.PAID, inv1.getStatus());
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(inv1.getAmount()));
        assertTrue(inv1.getLines().stream().noneMatch(l -> l.getKind() == LineKind.BASE),
                "暂停中不得预收下一周期基础费");
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-pause5").getStatus(),
                "跨周期结算不得把客户主动暂停刷成 ACTIVE");
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"),
                subscriptions.getSubscription("sub-pause5").getCurrentPeriodStart());

        // 第二期整周期暂停：无任何用量上报是预期行为，不得判计量异常；仍无基础费
        clock.advanceTo(Instant.parse("2026-11-01T00:00:00Z"));
        Invoice inv2 = billing.closePeriod("sub-pause5");
        log.info("[case] 判定：整周期暂停 invoice={} status={} amount={}",
                inv2.getInvoiceId(), inv2.getStatus(), inv2.getAmount());
        assertNotEquals(InvoiceStatus.ANOMALY, inv2.getStatus(), "整周期暂停不得误判缺计量异常");
        assertEquals(InvoiceStatus.PAID, inv2.getStatus());
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(inv2.getAmount()));
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-pause5").getStatus());

        // 11/5 恢复：11 月周期 30 天，剩余 26 天，比例 26/30=0.866667，补收 30*0.866667=26.00
        PlanChange resume = subscriptions.resume("sub-pause5", "resume-5", Instant.parse("2026-11-05T00:00:00Z"));
        log.info("[case] 判定：跨周期恢复 proration={}（30*26/30=26.00）", resume.getProration());
        assertEquals(0, new BigDecimal("26.00").compareTo(resume.getProration()));
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscription("sub-pause5").getStatus());

        assertReconcileClean();
    }

    @Test
    void concurrentPauseAndResumeAreSerializedDeterministically() throws Exception {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-pause6", "cust-p6", "BASIC", 1);

        // 第一阶段：8 个并发暂停（不同幂等键、同一生效时刻）-> 恰好 1 个生效，
        // 其余在出账/退款之前即被 EFFECTIVE_TIME_CONFLICT 拒绝（同一生效时间点只允许一个操作生效）。
        int n = 8;
        ConcurrentLinkedQueue<String> pauseOutcomes = runConcurrent(n, i -> {
            subscriptions.pause("sub-pause6", "race-pause-" + i, Instant.parse("2026-09-10T00:00:00Z"));
            return "OK";
        });
        long pauseOk = pauseOutcomes.stream().filter("OK"::equals).count();
        long pauseRejected = pauseOutcomes.stream()
                .filter(o -> o.equals(ErrorCode.EFFECTIVE_TIME_CONFLICT.name())).count();
        log.info("[case] 判定：并发暂停结果={}（恰好 1 个 OK，其余 EFFECTIVE_TIME_CONFLICT，拒绝无退款/无状态残留）", pauseOutcomes);
        assertEquals(1, pauseOk, "并发暂停只能有一个生效: " + pauseOutcomes);
        assertEquals(n - 1, pauseRejected);
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-pause6").getStatus());

        // 第二阶段：8 个并发恢复（生效时刻 9/15，与第一阶段错开）-> 恰好 1 个生效，其余 EFFECTIVE_TIME_CONFLICT
        ConcurrentLinkedQueue<String> resumeOutcomes = runConcurrent(n, i -> {
            subscriptions.resume("sub-pause6", "race-resume-" + i, Instant.parse("2026-09-15T00:00:00Z"));
            return "OK";
        });
        long resumeOk = resumeOutcomes.stream().filter("OK"::equals).count();
        long resumeRejected = resumeOutcomes.stream()
                .filter(o -> o.equals(ErrorCode.EFFECTIVE_TIME_CONFLICT.name())).count();
        log.info("[case] 判定：并发恢复结果={}（恰好 1 个 OK，其余 EFFECTIVE_TIME_CONFLICT，拒绝无扣款/无状态残留）", resumeOutcomes);
        assertEquals(1, resumeOk, "并发恢复只能有一个生效: " + resumeOutcomes);
        assertEquals(n - 1, resumeRejected);
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscription("sub-pause6").getStatus());

        // 资金结论确定：一次暂停退款 + 一次恢复补收，三方守恒、对账无问题
        assertReconcileClean();
    }

    @Test
    void duplicatePauseAndResumeSubmissionsAreIdempotent() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-pause7", "cust-p7", "BASIC", 1);

        PlanChange p1 = subscriptions.pause("sub-pause7", "idem-pause-1", Instant.parse("2026-09-11T00:00:00Z"));
        PlanChange p2 = subscriptions.pause("sub-pause7", "idem-pause-1", Instant.parse("2026-09-11T00:00:00Z"));
        assertEquals(p1.getSeq(), p2.getSeq());
        assertEquals(p1.getProration(), p2.getProration());
        log.info("[case] 判定：暂停幂等重放 seq={} proration={}，不重复退款", p2.getSeq(), p2.getProration());

        PlanChange r1 = subscriptions.resume("sub-pause7", "idem-resume-1", Instant.parse("2026-09-21T00:00:00Z"));
        PlanChange r2 = subscriptions.resume("sub-pause7", "idem-resume-1", Instant.parse("2026-09-21T00:00:00Z"));
        assertEquals(r1.getSeq(), r2.getSeq());
        assertEquals(r1.getProration(), r2.getProration());
        log.info("[case] 判定：恢复幂等重放 seq={} proration={}，不重复扣款", r2.getSeq(), r2.getProration());

        // 退款/扣款流水各恰好一笔：对账（REFUNDED 负额账单 1 笔退款、PAID 账单 1 笔收款、退款不超收款）兜底
        assertReconcileClean();
    }

    // ---------- 内部 ----------

    private ConcurrentLinkedQueue<String> runConcurrent(int n, ThrowingTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    outcomes.add(task.run(idx));
                } catch (BillingException ex) {
                    outcomes.add(ex.getCode().name());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return outcomes;
    }

    private interface ThrowingTask {
        String run(int i);
    }

    private void assertReconcileClean() {
        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "对账应无问题: " + issues);
        assertEquals(0, ledger.conservationDelta().signum(),
                "三方金额必须守恒: " + ledger.balances());
        log.info("[case] 判定：对账无问题，三方余额={} 守恒差=0", ledger.balances());
    }
}

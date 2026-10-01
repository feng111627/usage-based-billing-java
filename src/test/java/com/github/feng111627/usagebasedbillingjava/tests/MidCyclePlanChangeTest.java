package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.ChangeType;
import com.github.feng111627.usagebasedbillingjava.model.PlanChange;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LedgerService;
import com.github.feng111627.usagebasedbillingjava.service.ReconciliationService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 周期中途升降配：
 * - 差价 = (新价-旧价) × 剩余周期比例，金额可手工核算；
 * - 同一时刻多次并发变更按到达顺序形成全序，结果确定、无重复计价、无中间态残留；
 * - 乱序（生效时间早于上次变更）被 CHANGE_OUT_OF_ORDER 拒绝；
 * - 重复幂等键返回原结果；
 * - 退订按剩余比例退款；变更后对账守恒。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MidCyclePlanChangeTest {

    private static final Logger log = LoggerFactory.getLogger(MidCyclePlanChangeTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired BillingService billing;
    @Autowired ClockService clock;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;

    @Test
    void upgradeAtHalfCycleProratesByRemainingRatio() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-p1", "cust-p", "BASIC", 1);

        // 2026-09-15T00:00:00Z：9 月周期 30 天，此刻到 10/01 还剩 16 天，比例 16/30
        Instant eff = Instant.parse("2026-09-15T00:00:00Z");
        BigDecimal ratio16 = new BigDecimal("16").divide(new BigDecimal("30"), 6,
                java.math.RoundingMode.HALF_UP);
        log.info("[case] 输入：sub-p1 BASIC(30) -> PRO(80) eff={}；判定依据：差价=(80-30)*剩余比例{}={}",
                eff, ratio16, new BigDecimal("50").multiply(ratio16).setScale(2, java.math.RoundingMode.HALF_UP));
        PlanChange up = subscriptions.changePlan("sub-p1", "PRO", "chg-up-1", eff);

        assertEquals(ChangeType.UPGRADE, up.getType());
        assertEquals(0, new BigDecimal("26.67").compareTo(up.getProration()),
                "9/15 升级补收应为 (80-30)*16/30=26.67，实际 " + up.getProration());
        assertEquals("PRO", subscriptions.getSubscription("sub-p1").getCurrentPlanId());
        assertEquals(1, up.getSeq());

        // 同幂等键重放：返回原结果，不产生第二次扣款
        PlanChange replay = subscriptions.changePlan("sub-p1", "PRO", "chg-up-1", eff);
        assertEquals(up.getSeq(), replay.getSeq());
        assertEquals(up.getProration(), replay.getProration());
        log.info("[case] 幂等重放 chg-up-1 -> seq 仍为 {}，无重复计价", replay.getSeq());
    }

    @Test
    void downgradeProducesNegativeRefund() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-p2", "cust-p2", "PRO", 1);

        // 2026-09-10T00:00:00Z：到 10/01 还剩 21 天，比例 21/30
        Instant eff = Instant.parse("2026-09-10T00:00:00Z");
        BigDecimal expectedRatio = new BigDecimal("21").divide(new BigDecimal("30"), 6, RoundingMode.HALF_UP);
        log.info("[case] 输入：sub-p2 PRO(80) -> BASIC(30) eff={}；判定依据：(30-80)*{}≈{}",
                eff, expectedRatio, new BigDecimal("-50").multiply(expectedRatio).setScale(2, RoundingMode.HALF_UP));
        PlanChange down = subscriptions.changePlan("sub-p2", "BASIC", "chg-down-1", eff);

        assertEquals(ChangeType.DOWNGRADE, down.getType());
        BigDecimal expected = new BigDecimal("-50").multiply(expectedRatio).setScale(2, RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(down.getProration()),
                "降级退款应≈" + expected + "，实际 " + down.getProration());
        assertTrue(down.getProration().signum() < 0);
    }

    @Test
    void multipleSameMomentChangesAreTotallyOrderedAndDeterministic() throws Exception {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-p3", "cust-p3", "BASIC", 1);
        // 同一时刻 8 个并发的 BASIC->PRO（各自携带不同幂等键）：
        // 分段锁 + seq 全序保证仅 1 个生效，其余在锁内看到“当前套餐已是 PRO”被 VALIDATION 可解释拒绝。
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<String> outcomes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < n; i++) {
            final int i2 = i;
            pool.submit(() -> {
                try {
                    start.await();
                    subscriptions.changePlan("sub-p3", "PRO", "chg-race-" + i2,
                            Instant.parse("2026-09-12T00:00:00Z"));
                    outcomes.add("OK:" + i2);
                } catch (BillingException ex) {
                    outcomes.add(ex.getCode().name() + ":" + i2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long ok = outcomes.stream().filter(o -> o.startsWith("OK")).count();
        long rejected = outcomes.stream().filter(o -> o.startsWith("VALIDATION")).count();
        log.info("[race] 输入：同一时刻 {} 个并发变更 BASIC->PRO；判定依据：仅 1 个成功，其余因已为 PRO 被可解释拒绝；成功={} 拒绝={}",
                n, ok, rejected);
        assertEquals(1, ok, "同一时刻只能有一次变更生效（全序）");
        assertEquals(n - 1, rejected);
        assertEquals(1, subscriptions.getSubscription("sub-p3").getLastChangeSeq(), "无中间态残留：seq 只前进一次");
        assertEquals("PRO", subscriptions.getSubscription("sub-p3").getCurrentPlanId());
    }

    @Test
    void outOfOrderChangeIsRejectedAndCancelledRefunds() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-p4", "cust-p4", "BASIC", 1);
        subscriptions.changePlan("sub-p4", "PRO", "chg-seq-1", Instant.parse("2026-09-20T00:00:00Z"));

        // 乱序：生效时间早于上一次变更
        BillingException ex = assertThrows(BillingException.class, () ->
                subscriptions.changePlan("sub-p4", "BASIC", "chg-seq-2", Instant.parse("2026-09-10T00:00:00Z")));
        assertEquals(ErrorCode.CHANGE_OUT_OF_ORDER, ex.getCode());
        log.info("[ordering] 乱序变更被拒绝，code={} reason={}", ex.getCode(), ex.getMessage());
        assertEquals(1, subscriptions.getSubscription("sub-p4").getLastChangeSeq(), "乱序不得推进 seq");

        // 退订：2026-09-24（周期还剩 7/30），按 PRO 80 元比例退回
        PlanChange cancel = subscriptions.cancel("sub-p4", "cancel-1", Instant.parse("2026-09-24T00:00:00Z"));
        assertEquals(ChangeType.CANCEL, cancel.getType());
        BigDecimal expected = new BigDecimal("80")
                .multiply(new BigDecimal("7").divide(new BigDecimal("30"), 6, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP).negate();
        assertEquals(0, expected.compareTo(cancel.getProration()),
                "退订应退≈" + expected + "，实际 " + cancel.getProration());
        assertEquals(SubscriptionStatus.CANCELLED, subscriptions.getSubscription("sub-p4").getStatus());

        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "升降配/退订后对账必须平: " + issues);
        assertEquals(0, ledger.conservationDelta().signum(),
                "补收与退款后三方守恒: " + ledger.balances());
        log.info("[case] 退订退款={}；对账问题={} 余额={} 守恒差={}",
                cancel.getProration(), issues, ledger.balances(), ledger.conservationDelta());
    }
}

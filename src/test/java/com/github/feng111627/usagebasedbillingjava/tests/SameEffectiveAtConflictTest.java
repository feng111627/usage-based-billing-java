package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.Account;
import com.github.feng111627.usagebasedbillingjava.model.ChangeType;
import com.github.feng111627.usagebasedbillingjava.model.PlanChange;
import com.github.feng111627.usagebasedbillingjava.model.Subscription;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同一生效时间点互斥：同一订阅、同一 effectiveAt 最多只有一个操作
 * （暂停/恢复/升降配/退订）真正生效；后到请求当场以 EFFECTIVE_AT_CONFLICT
 * （与 CHANGE_OUT_OF_ORDER、状态类错误可区分）拒绝，且拒绝不产生任何
 * 退款/扣款、不推进订阅状态与 seq。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SameEffectiveAtConflictTest {

    private static final Logger log = LoggerFactory.getLogger(SameEffectiveAtConflictTest.class);

    /** 同刻冲突可接受的拒绝原因集合（依到达顺序不同而不同，但都必须可区分）。 */
    private static final Set<ErrorCode> DISTINGUISHABLE_REJECTIONS = Set.of(
            ErrorCode.EFFECTIVE_AT_CONFLICT,   // 同刻已有生效操作
            ErrorCode.SUBSCRIPTION_PAUSED,     // 先到的是暂停，后续变更/重复暂停被拒
            ErrorCode.CONFLICT,                // 先到操作使状态不再满足前置（如已活跃无需恢复）
            ErrorCode.VALIDATION);             // 先到变更已切到目标套餐，重复变更无需执行

    @Autowired SubscriptionService subscriptions;
    @Autowired BillingService billing;
    @Autowired ClockService clock;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;

    @Test
    void pauseThenResumeAtSameEffectiveAtIsRejectedWithoutResidue() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-same1", "cust-s1", "BASIC", 1);

        // 9/11 暂停：剩余 20/30，退款 30*0.666667=20.00
        Instant t = Instant.parse("2026-09-11T00:00:00Z");
        PlanChange pause = subscriptions.pause("sub-same1", "same-pause-1", t);
        assertEquals(ChangeType.PAUSE, pause.getType());
        assertEquals(0, new BigDecimal("-20.00").compareTo(pause.getProration()));
        log.info("[case] 输入：sub-same1 暂停 eff={} 生效，退款={}；随后同刻恢复应被拒", t, pause.getProration());

        // 拒绝前的完整快照：状态、套餐、seq、三方余额
        Map<Account, BigDecimal> balancesBefore = ledger.balances();
        long seqBefore = subscriptions.getSubscription("sub-same1").getLastChangeSeq();

        // 同一生效时间点再恢复：当场拒绝，原因可区分（不是乱序、不是状态错误）
        BillingException ex = assertThrows(BillingException.class,
                () -> subscriptions.resume("sub-same1", "same-resume-1", t));
        assertEquals(ErrorCode.EFFECTIVE_AT_CONFLICT, ex.getCode());
        assertNotEquals(ErrorCode.CHANGE_OUT_OF_ORDER, ex.getCode());
        log.info("[case] 判定：同刻恢复被拒 code={} message={}", ex.getCode(), ex.getMessage());

        // 无资金/状态残留：余额、状态、套餐、seq 与被拒前完全一致
        assertEquals(balancesBefore, ledger.balances(), "被拒请求不得产生任何退款/扣款");
        Subscription after = subscriptions.getSubscription("sub-same1");
        assertEquals(SubscriptionStatus.PAUSED, after.getStatus(), "被拒后状态应保持 PAUSED");
        assertEquals("BASIC", after.getCurrentPlanId());
        assertEquals(seqBefore, after.getLastChangeSeq(), "被拒请求不得推进 seq");
        log.info("[case] 判定：余额={} 状态={} seq={} 与被拒前一致", ledger.balances(), after.getStatus(), after.getLastChangeSeq());

        // 反向：错开时间恢复正常（9/21），再在 9/21 同刻暂停也应被拒
        PlanChange resume = subscriptions.resume("sub-same1", "same-resume-2", Instant.parse("2026-09-21T00:00:00Z"));
        assertEquals(0, new BigDecimal("10.00").compareTo(resume.getProration()));
        Map<Account, BigDecimal> balancesBefore2 = ledger.balances();
        BillingException ex2 = assertThrows(BillingException.class,
                () -> subscriptions.pause("sub-same1", "same-pause-2", Instant.parse("2026-09-21T00:00:00Z")));
        assertEquals(ErrorCode.EFFECTIVE_AT_CONFLICT, ex2.getCode());
        assertEquals(balancesBefore2, ledger.balances());
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscription("sub-same1").getStatus());
        log.info("[case] 判定：恢复生效后同刻暂停被拒 code={}，状态=ACTIVE 余额不变", ex2.getCode());

        assertReconcileClean();
    }

    @Test
    void planChangeThenPauseAtSameEffectiveAtIsRejectedWithoutResidue() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-same2", "cust-s2", "BASIC", 1);

        // 9/11 升级 BASIC->PRO：剩余 20/30，补收 (80-30)*0.666667=33.33
        Instant t = Instant.parse("2026-09-11T00:00:00Z");
        PlanChange up = subscriptions.changePlan("sub-same2", "PRO", "same-up-1", t);
        assertEquals(ChangeType.UPGRADE, up.getType());
        assertEquals(0, new BigDecimal("33.33").compareTo(up.getProration()));
        log.info("[case] 输入：sub-same2 升级 eff={} 生效，补收={}；随后同刻暂停应被拒", t, up.getProration());

        Map<Account, BigDecimal> balancesBefore = ledger.balances();
        long seqBefore = subscriptions.getSubscription("sub-same2").getLastChangeSeq();

        BillingException ex = assertThrows(BillingException.class,
                () -> subscriptions.pause("sub-same2", "same-pause-1", t));
        assertEquals(ErrorCode.EFFECTIVE_AT_CONFLICT, ex.getCode());
        log.info("[case] 判定：升级后同刻暂停被拒 code={} message={}", ex.getCode(), ex.getMessage());

        // 无残留：套餐仍为 PRO、状态 ACTIVE、seq 不变、余额不变（不能既收升级差价又退暂停款）
        assertEquals(balancesBefore, ledger.balances(), "被拒请求不得产生任何退款/扣款");
        Subscription after = subscriptions.getSubscription("sub-same2");
        assertEquals("PRO", after.getCurrentPlanId());
        assertEquals(SubscriptionStatus.ACTIVE, after.getStatus());
        assertEquals(seqBefore, after.getLastChangeSeq());
        log.info("[case] 判定：套餐={} 状态={} seq={} 余额={} 与被拒前一致",
                after.getCurrentPlanId(), after.getStatus(), after.getLastChangeSeq(), ledger.balances());

        // 错开生效时间的暂停不受影响：9/21 暂停，剩余 10/30，退 80*0.333333=26.67
        PlanChange pause = subscriptions.pause("sub-same2", "same-pause-2", Instant.parse("2026-09-21T00:00:00Z"));
        assertEquals(0, new BigDecimal("-26.67").compareTo(pause.getProration()));
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-same2").getStatus());
        log.info("[case] 判定：错开时间的暂停正常生效，退款={}", pause.getProration());

        assertReconcileClean();
    }

    @Test
    void concurrentMixedOpsAtSameEffectiveAtOnlyOneWins() throws Exception {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-same3", "cust-s3", "BASIC", 1);

        Instant t = Instant.parse("2026-09-11T00:00:00Z");
        int ledgerTxBefore = ledger.all().size();
        log.info("[case] 输入：同一生效时间 {} 并发 暂停x3 + 恢复x3 + 升级x3（不同幂等键）", t);

        // 同一生效时间点混合并发：暂停、恢复、升降配混在一起，只能有一个生效
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            pool.submit(() -> race(start, outcomes, () -> {
                subscriptions.pause("sub-same3", "mix-pause-" + idx, t);
                return "OK:pause-" + idx;
            }));
            pool.submit(() -> race(start, outcomes, () -> {
                subscriptions.resume("sub-same3", "mix-resume-" + idx, t);
                return "OK:resume-" + idx;
            }));
            pool.submit(() -> race(start, outcomes, () -> {
                subscriptions.changePlan("sub-same3", "PRO", "mix-up-" + idx, t);
                return "OK:up-" + idx;
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long ok = outcomes.stream().filter(o -> o.startsWith("OK")).count();
        log.info("[case] 判定：并发结果={}（恰好 1 个 OK，其余均被可区分原因拒绝）", outcomes);
        assertEquals(1, ok, "同一生效时间点混合并发只能有一个生效: " + outcomes);
        outcomes.stream().filter(o -> !o.startsWith("OK")).forEach(o -> {
            ErrorCode code = ErrorCode.valueOf(o.split(":")[0]);
            assertTrue(DISTINGUISHABLE_REJECTIONS.contains(code),
                    "拒绝原因必须可区分，实际: " + code);
        });

        // 资金结论确定：相比并发前只多出恰好一笔资金流水（胜出的那一次），不存在既退又扣
        assertEquals(ledgerTxBefore + 1, ledger.all().size(),
                "同一生效时间点只能产生一笔资金流水，不得既退款又扣款");
        assertEquals(1, subscriptions.getSubscription("sub-same3").getLastChangeSeq(),
                "无中间态残留：seq 只前进一次");
        log.info("[case] 判定：资金流水净增 1 笔，seq={}，余额={}",
                subscriptions.getSubscription("sub-same3").getLastChangeSeq(), ledger.balances());

        assertReconcileClean();
    }

    @Test
    void idempotentReplayOfWinnerAndRejectedKeyResubmission() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-same4", "cust-s4", "BASIC", 1);

        Instant t = Instant.parse("2026-09-11T00:00:00Z");
        PlanChange p1 = subscriptions.pause("sub-same4", "idem-win-1", t);
        BillingException rejected = assertThrows(BillingException.class,
                () -> subscriptions.resume("sub-same4", "idem-lose-1", t));
        assertEquals(ErrorCode.EFFECTIVE_AT_CONFLICT, rejected.getCode());
        Map<Account, BigDecimal> balancesAfterReject = ledger.balances();
        log.info("[case] 输入：暂停 key=idem-win-1 生效；同刻恢复 key=idem-lose-1 被拒 code={}", rejected.getCode());

        // 胜出的幂等键重复提交：返回第一次的结果，不重复退款
        PlanChange p2 = subscriptions.pause("sub-same4", "idem-win-1", t);
        assertEquals(p1.getSeq(), p2.getSeq());
        assertEquals(p1.getProration(), p2.getProration());
        assertEquals(balancesAfterReject, ledger.balances(), "幂等重放不得重复退款");
        log.info("[case] 判定：胜出键重放 seq={} proration={} 余额不变", p2.getSeq(), p2.getProration());

        // 被拒的幂等键再次提交：仍被同一可区分原因拒绝，仍无资金/状态残留
        BillingException again = assertThrows(BillingException.class,
                () -> subscriptions.resume("sub-same4", "idem-lose-1", t));
        assertEquals(ErrorCode.EFFECTIVE_AT_CONFLICT, again.getCode());
        assertEquals(balancesAfterReject, ledger.balances(), "被拒键重复提交不得产生任何资金流水");
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-same4").getStatus());
        log.info("[case] 判定：被拒键重复提交仍 code={}，余额={} 状态=PAUSED", again.getCode(), ledger.balances());

        assertReconcileClean();
    }

    // ---------- 内部 ----------

    private void race(CountDownLatch start, ConcurrentLinkedQueue<String> outcomes, java.util.concurrent.Callable<String> task) {
        try {
            start.await();
            outcomes.add(task.call());
        } catch (BillingException ex) {
            outcomes.add(ex.getCode().name() + ":" + ex.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            outcomes.add("UNEXPECTED:" + e);
        }
    }

    private void assertReconcileClean() {
        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "对账应无问题: " + issues);
        assertEquals(0, ledger.conservationDelta().signum(),
                "三方金额必须守恒: " + ledger.balances());
        log.info("[case] 判定：对账无问题，三方余额={} 守恒差=0", ledger.balances());
    }
}

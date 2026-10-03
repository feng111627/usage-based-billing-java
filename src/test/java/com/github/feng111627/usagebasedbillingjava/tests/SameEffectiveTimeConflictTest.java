package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.PlanChange;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.repository.Repositories;
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
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同一生效时间点互斥：同一条订阅在同一个 effectiveAt 上最多只有一个操作
 * （暂停/恢复/升降配/退订）真正生效，后到者以可区分的 EFFECTIVE_TIME_CONFLICT 当场拒绝；
 * 被拒请求不产生退款/扣款、不推进 seq、不改状态与套餐；
 * 多种操作在同一时刻并发时同样只允许一个成功；幂等重放不重复走钱；
 * 生效时间错开的正常暂停/恢复与跨周期暂停行为不变。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SameEffectiveTimeConflictTest {

    private static final Logger log = LoggerFactory.getLogger(SameEffectiveTimeConflictTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired ClockService clock;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;
    @Autowired Repositories.ChangeRepository changeRepository;

    @Test
    void pauseThenResumeAtSameEffectiveTimeOnlyPauseTakesEffect() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-conf1", "cust-c1", "BASIC", 1);
        Instant eff = Instant.parse("2026-09-11T00:00:00Z");
        log.info("[case] 输入：sub-conf1 同一生效时间 {} 先暂停(key=pause-c1)再恢复(key=resume-c1)；判定依据：同刻只允许一个操作生效，先到的暂停占位", eff);

        // 先到：暂停生效（9/11 -> 10/1 剩余 20/30，退款 -20.00）
        PlanChange pause = subscriptions.pause("sub-conf1", "pause-c1", eff);
        assertEquals(0, new BigDecimal("-20.00").compareTo(pause.getProration()));
        log.info("[case] 判定：先到暂停成功 seq={} proration={} 状态=PAUSED", pause.getSeq(), pause.getProration());

        long seqAfterPause = pause.getSeq();
        var balancesAfterPause = ledger.balances();
        int changesAfterPause = changeRepository.findBySubscription("sub-conf1").size();

        // 后到：同刻恢复必须以 EFFECTIVE_TIME_CONFLICT 被拒绝
        BillingException ex = assertThrows(BillingException.class,
                () -> subscriptions.resume("sub-conf1", "resume-c1", eff));
        assertEquals(ErrorCode.EFFECTIVE_TIME_CONFLICT, ex.getCode(),
                "同刻第二个操作必须以 EFFECTIVE_TIME_CONFLICT 拒绝，实际 " + ex.getCode());
        log.info("[case] 判定：同刻恢复被拒 code={} message={}", ex.getCode(), ex.getMessage());

        // 被拒后：状态/套餐/seq/已生效操作的金额与被拒之前完全一致；无半截账单、无第二笔资金
        var sub = subscriptions.getSubscription("sub-conf1");
        assertEquals(SubscriptionStatus.PAUSED, sub.getStatus(), "被拒不得改动订阅状态");
        assertEquals("BASIC", sub.getCurrentPlanId(), "被拒不得改动当前套餐");
        assertEquals(seqAfterPause, sub.getLastChangeSeq(), "被拒不得推进 seq");
        assertEquals(changesAfterPause, changeRepository.findBySubscription("sub-conf1").size(),
                "被拒不得落第二条 PlanChange");
        assertNull(changeRepository.findByIdempotencyKey("resume-c1").orElse(null),
                "被拒请求不得写入幂等记录");
        assertEquals(balancesAfterPause, ledger.balances(), "被拒不得产生任何资金流水");
        log.info("[case] 判定：被拒后状态=PAUSED 套餐=BASIC seq={} 余额未变={}，只有暂停退款一笔账",
                seqAfterPause, ledger.balances());

        assertReconcileClean();
    }

    @Test
    void resumeThenPauseAtSameEffectiveTimeOnlyResumeTakesEffect() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-conf2", "cust-c2", "BASIC", 1);
        // 先在 9/11 正常暂停（错开生效时间，保持既有行为）
        subscriptions.pause("sub-conf2", "pause-c2-first", Instant.parse("2026-09-11T00:00:00Z"));
        Instant eff = Instant.parse("2026-09-21T00:00:00Z");
        log.info("[case] 输入：sub-conf2 在同一生效时间 {} 先恢复(key=resume-c2)再暂停(key=pause-c2)；判定依据：同刻只允许一个操作生效，先到的恢复占位", eff);

        // 先到：恢复生效（9/21 -> 10/1 剩余 10/30，补收 10.00）
        PlanChange resume = subscriptions.resume("sub-conf2", "resume-c2", eff);
        assertEquals(0, new BigDecimal("10.00").compareTo(resume.getProration()));
        log.info("[case] 判定：先到恢复成功 seq={} proration={} 状态=ACTIVE", resume.getSeq(), resume.getProration());

        long seqAfterResume = resume.getSeq();
        var balancesAfterResume = ledger.balances();
        int changesAfterResume = changeRepository.findBySubscription("sub-conf2").size();

        // 后到：同刻暂停必须被 EFFECTIVE_TIME_CONFLICT 拒绝（而不是 SUBSCRIPTION_PAUSED 之类）
        BillingException ex = assertThrows(BillingException.class,
                () -> subscriptions.pause("sub-conf2", "pause-c2", eff));
        assertEquals(ErrorCode.EFFECTIVE_TIME_CONFLICT, ex.getCode());
        log.info("[case] 判定：同刻暂停被拒 code={} message={}", ex.getCode(), ex.getMessage());

        var sub = subscriptions.getSubscription("sub-conf2");
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus(), "被拒不得把状态改回 PAUSED");
        assertEquals("BASIC", sub.getCurrentPlanId());
        assertEquals(seqAfterResume, sub.getLastChangeSeq());
        assertEquals(changesAfterResume, changeRepository.findBySubscription("sub-conf2").size());
        assertNull(changeRepository.findByIdempotencyKey("pause-c2").orElse(null));
        assertEquals(balancesAfterResume, ledger.balances(), "被拒不得再产生退款");
        log.info("[case] 判定：被拒后状态仍=ACTIVE 套餐=BASIC seq={} 余额未变={}（只有 9/11 暂停退款 + 9/21 恢复补收两笔）",
                seqAfterResume, ledger.balances());

        assertReconcileClean();
    }

    @Test
    void planChangeThenPauseAtSameEffectiveTimeOnlyChangeTakesEffect() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-conf3", "cust-c3", "BASIC", 1);
        Instant eff = Instant.parse("2026-09-15T00:00:00Z");
        log.info("[case] 输入：sub-conf3 在同一生效时间 {} 先升级 BASIC->PRO(key=up-c3)再暂停(key=pause-c3)；判定依据：先到升级占位", eff);

        // 先到：9/15 升级，剩余 16/30，补收 50*16/30=26.67
        PlanChange up = subscriptions.changePlan("sub-conf3", "PRO", "up-c3", eff);
        assertEquals(0, new BigDecimal("26.67").compareTo(up.getProration()));
        log.info("[case] 判定：先到升级成功 seq={} proration={} 套餐=PRO", up.getSeq(), up.getProration());

        long seqAfterUp = up.getSeq();
        var balancesAfterUp = ledger.balances();

        // 后到：同刻暂停 -> EFFECTIVE_TIME_CONFLICT；绝不能在同一时刻又补收又退款
        BillingException ex = assertThrows(BillingException.class,
                () -> subscriptions.pause("sub-conf3", "pause-c3", eff));
        assertEquals(ErrorCode.EFFECTIVE_TIME_CONFLICT, ex.getCode());
        log.info("[case] 判定：同刻暂停被拒 code={} message={}", ex.getCode(), ex.getMessage());

        var sub = subscriptions.getSubscription("sub-conf3");
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus(), "被拒不得把状态改成 PAUSED");
        assertEquals("PRO", sub.getCurrentPlanId(), "升级结果必须保留");
        assertEquals(seqAfterUp, sub.getLastChangeSeq());
        assertNull(changeRepository.findByIdempotencyKey("pause-c3").orElse(null));
        assertEquals(balancesAfterUp, ledger.balances(), "同一时刻不得既扣款又退款");
        log.info("[case] 判定：被拒后状态=ACTIVE 套餐=PRO seq={} 余额未变={}（只有升级补收一笔，无暂停退款）",
                seqAfterUp, ledger.balances());

        assertReconcileClean();
    }

    @Test
    void pauseThenUpgradeAtSameEffectiveTimeOnlyPauseTakesEffect() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-conf4", "cust-c4", "BASIC", 1);
        Instant eff = Instant.parse("2026-09-15T00:00:00Z");
        log.info("[case] 输入：sub-conf4 在同一生效时间 {} 先暂停(key=pause-c4)再升级 BASIC->PRO(key=up-c4)；判定依据：先到暂停占位", eff);

        // 先到：9/15 暂停，剩余 16/30，退款 -30*16/30=-16.00
        PlanChange pause = subscriptions.pause("sub-conf4", "pause-c4", eff);
        assertEquals(0, new BigDecimal("-16.00").compareTo(pause.getProration()));
        log.info("[case] 判定：先到暂停成功 seq={} proration={} 状态=PAUSED", pause.getSeq(), pause.getProration());

        long seqAfterPause = pause.getSeq();
        var balancesAfterPause = ledger.balances();

        // 后到：同刻升级 -> EFFECTIVE_TIME_CONFLICT（先于 SUBSCRIPTION_PAUSED 的状态判定）
        BillingException ex = assertThrows(BillingException.class,
                () -> subscriptions.changePlan("sub-conf4", "PRO", "up-c4", eff));
        assertEquals(ErrorCode.EFFECTIVE_TIME_CONFLICT, ex.getCode(),
                "同刻冲突必须以 EFFECTIVE_TIME_CONFLICT 区分于暂停态变更 SUBSCRIPTION_PAUSED");
        log.info("[case] 判定：同刻升级被拒 code={} message={}", ex.getCode(), ex.getMessage());

        var sub = subscriptions.getSubscription("sub-conf4");
        assertEquals(SubscriptionStatus.PAUSED, sub.getStatus());
        assertEquals("BASIC", sub.getCurrentPlanId(), "被拒不得改动当前套餐");
        assertEquals(seqAfterPause, sub.getLastChangeSeq());
        assertNull(changeRepository.findByIdempotencyKey("up-c4").orElse(null));
        assertEquals(balancesAfterPause, ledger.balances(), "同一时刻不得既退款又扣款");
        log.info("[case] 判定：被拒后状态=PAUSED 套餐=BASIC seq={} 余额未变={}（只有暂停退款一笔，无升级补收）",
                seqAfterPause, ledger.balances());

        assertReconcileClean();
    }

    @Test
    void mixedOperationsAtSameEffectiveTimeConcurrentlyOnlyOneSucceeds() throws Exception {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-conf5", "cust-c5", "BASIC", 1);
        Instant eff = Instant.parse("2026-09-12T00:00:00Z");

        // 同一时刻 12 个请求混打：4 暂停 + 4 恢复 + 4 升级，各自不同幂等键。
        // 分段锁把它们串成确定全序：谁先抢到锁谁生效，其余要么因为生效点已被占用
        // （EFFECTIVE_TIME_CONFLICT），要么因为状态已被先到者改变（CONFLICT/SUBSCRIPTION_PAUSED）；
        // 所有失败原因都可与其他原因区分，但恰好只能有 1 个成功。
        int perKind = 4;
        ExecutorService pool = Executors.newFixedThreadPool(perKind * 3);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> outcomes = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < perKind; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    subscriptions.pause("sub-conf5", "mix-pause-" + idx, eff);
                    outcomes.add("OK:PAUSE:" + idx);
                } catch (BillingException ex) {
                    outcomes.add(ex.getCode().name() + ":PAUSE:" + idx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    subscriptions.resume("sub-conf5", "mix-resume-" + idx, eff);
                    outcomes.add("OK:RESUME:" + idx);
                } catch (BillingException ex) {
                    outcomes.add(ex.getCode().name() + ":RESUME:" + idx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    subscriptions.changePlan("sub-conf5", "PRO", "mix-up-" + idx, eff);
                    outcomes.add("OK:UPGRADE:" + idx);
                } catch (BillingException ex) {
                    outcomes.add(ex.getCode().name() + ":UPGRADE:" + idx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        log.info("[case] 输入：sub-conf5 同一生效时间 {} 并发 {} 暂停 + {} 恢复 + {} 升级", eff, perKind, perKind, perKind);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long okCount = outcomes.stream().filter(o -> o.startsWith("OK")).count();
        log.info("[case] 判定：并发混打结果={}", outcomes);
        assertEquals(1, okCount, "同刻多种操作并发只能有一个成功: " + outcomes);

        // 失败原因必须全部可区分：只能是“生效点被占”或“状态已变”这两类可解释拒绝，
        // 不允许出现 GATEWAY_REJECTED / INTERNAL 等意外失败。
        long conflictCodeRejects = outcomes.stream()
                .filter(o -> o.startsWith(ErrorCode.EFFECTIVE_TIME_CONFLICT.name())).count();
        long stateRejects = outcomes.stream()
                .filter(o -> o.startsWith(ErrorCode.CONFLICT.name())
                        || o.startsWith(ErrorCode.SUBSCRIPTION_PAUSED.name())).count();
        assertEquals(perKind * 3 - 1, conflictCodeRejects + stateRejects,
                "其余请求必须全部被可区分地拒绝: " + outcomes);
        assertTrue(conflictCodeRejects >= 1, "获胜之后的同刻请求必须至少有一个命中 EFFECTIVE_TIME_CONFLICT: " + outcomes);

        // 恰好一条变更落在该生效点：最终状态/套餐与唯一获胜者自洽，seq 只前进一格
        var atEff = changeRepository.findBySubscription("sub-conf5").stream()
                .filter(c -> c.getEffectiveAt().equals(eff)).toList();
        assertEquals(1, atEff.size(), "同一生效点只能落一条 PlanChange: " + atEff);
        var winner = atEff.get(0);
        var sub = subscriptions.getSubscription("sub-conf5");
        assertEquals(1, sub.getLastChangeSeq(), "并发下 seq 只前进一次，无中间态残留");
        switch (winner.getType()) {
            case PAUSE -> {
                assertEquals(SubscriptionStatus.PAUSED, sub.getStatus());
                assertEquals("BASIC", sub.getCurrentPlanId());
            }
            case RESUME -> {
                assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
                assertEquals("BASIC", sub.getCurrentPlanId());
            }
            case UPGRADE -> {
                assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
                assertEquals("PRO", sub.getCurrentPlanId());
            }
            default -> fail("获胜操作类型异常: " + winner.getType());
        }
        log.info("[case] 判定：唯一获胜者 seq={} type={} proration={}；最终状态={} 套餐={}；EFFECTIVE_TIME_CONFLICT={} 状态类拒绝={}",
                winner.getSeq(), winner.getType(), winner.getProration(), sub.getStatus(),
                sub.getCurrentPlanId(), conflictCodeRejects, stateRejects);

        assertReconcileClean();
    }

    @Test
    void rejectedIdempotencyKeyDoesNotChargeOrRefundOnReplay() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-conf6", "cust-c6", "BASIC", 1);
        Instant eff = Instant.parse("2026-09-11T00:00:00Z");
        PlanChange pause = subscriptions.pause("sub-conf6", "pause-c6", eff);
        var balancesAfterPause = ledger.balances();
        log.info("[case] 输入：sub-conf6 同刻暂停后，恢复 key=resume-c6 被拒，随后用同一 key 重复提交；判定依据：拒绝不留痕，重复提交仍被同因拒绝，不扣款");

        // 第一次：同刻恢复被拒
        BillingException first = assertThrows(BillingException.class,
                () -> subscriptions.resume("sub-conf6", "resume-c6", eff));
        assertEquals(ErrorCode.EFFECTIVE_TIME_CONFLICT, first.getCode());
        // 第二次：同一幂等键重复提交 —— 因为首次从未落库，结论必须仍然是 EFFECTIVE_TIME_CONFLICT，
        // 不能因为“重试”而突然生效（否则就会在同一时刻补扣一笔，造成两头走账）。
        BillingException replay = assertThrows(BillingException.class,
                () -> subscriptions.resume("sub-conf6", "resume-c6", eff));
        assertEquals(ErrorCode.EFFECTIVE_TIME_CONFLICT, replay.getCode());
        assertNull(changeRepository.findByIdempotencyKey("resume-c6").orElse(null),
                "被拒键不得占用幂等索引");
        assertEquals(balancesAfterPause, ledger.balances(), "重复提交被拒不得扣款");
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-conf6").getStatus());
        log.info("[case] 判定：同键重复提交两次均被拒 code={}，余额未变={} 状态仍 PAUSED",
                replay.getCode(), ledger.balances());

        // 成功操作的同键重放：即使此刻订阅仍处于“同刻冲突”窗口，也必须返回首次结果而不是再走钱
        PlanChange replayPause = subscriptions.pause("sub-conf6", "pause-c6", eff);
        assertEquals(pause.getSeq(), replayPause.getSeq());
        assertEquals(pause.getProration(), replayPause.getProration());
        assertEquals(balancesAfterPause, ledger.balances(), "成功键重放不得重复退款");
        log.info("[case] 判定：成功键 pause-c6 重放 seq={} proration={}，余额仍未变（不重复退款）",
                replayPause.getSeq(), replayPause.getProration());

        // 错开生效时间后的正常恢复仍可进行，且新键正常补收（互斥只作用于“同一时刻”）
        Instant later = Instant.parse("2026-09-21T00:00:00Z");
        PlanChange resumeLater = subscriptions.resume("sub-conf6", "resume-c6-later", later);
        assertEquals(0, new BigDecimal("10.00").compareTo(resumeLater.getProration()));
        log.info("[case] 判定：错开生效时间 {} 的恢复正常补收 {}，互斥不误伤正常请求", later, resumeLater.getProration());

        assertReconcileClean();
    }

    @Test
    void staggeredPauseAndResumeKeepExistingBehaviour() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-conf7", "cust-c7", "BASIC", 1);

        // 生效时间错开：9/11 暂停（退 20.00）、9/21 恢复（补 10.00），互斥规则不得误伤
        log.info("[case] 输入：sub-conf7 错开生效时间：9/11 暂停、9/21 恢复；判定依据：时间不同不构成同刻冲突，按既有比例计价");
        PlanChange pause = subscriptions.pause("sub-conf7", "pause-c7", Instant.parse("2026-09-11T00:00:00Z"));
        PlanChange resume = subscriptions.resume("sub-conf7", "resume-c7", Instant.parse("2026-09-21T00:00:00Z"));
        assertEquals(0, new BigDecimal("-20.00").compareTo(pause.getProration()));
        assertEquals(0, new BigDecimal("10.00").compareTo(resume.getProration()));
        assertEquals(2, subscriptions.getSubscription("sub-conf7").getLastChangeSeq());
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscription("sub-conf7").getStatus());
        log.info("[case] 判定：错开暂停/恢复均正常生效 seq={}/{} proration={}/{} 状态=ACTIVE",
                pause.getSeq(), resume.getSeq(), pause.getProration(), resume.getProration());

        // 乱序（早于上一次变更）仍按 CHANGE_OUT_OF_ORDER 拒绝，与同刻冲突码可区分
        BillingException ooo = assertThrows(BillingException.class,
                () -> subscriptions.pause("sub-conf7", "pause-c7-late", Instant.parse("2026-09-15T00:00:00Z")));
        assertEquals(ErrorCode.CHANGE_OUT_OF_ORDER, ooo.getCode());
        assertNotEquals(ErrorCode.EFFECTIVE_TIME_CONFLICT, ooo.getCode());
        log.info("[case] 判定：乱序请求 code={}（与 EFFECTIVE_TIME_CONFLICT 可区分）message={}",
                ooo.getCode(), ooo.getMessage());
        assertEquals(2, subscriptions.getSubscription("sub-conf7").getLastChangeSeq(), "乱序被拒不推进 seq");

        assertReconcileClean();
    }

    // ---------- 内部 ----------

    private void assertReconcileClean() {
        Map<String, java.util.List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "对账应无问题: " + issues);
        assertEquals(0, ledger.conservationDelta().signum(),
                "三方金额必须守恒: " + ledger.balances());
        log.info("[case] 判定：对账无问题，三方余额={} 守恒差=0", ledger.balances());
    }
}

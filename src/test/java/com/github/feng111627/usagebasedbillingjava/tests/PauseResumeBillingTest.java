package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus;
import com.github.feng111627.usagebasedbillingjava.model.LineKind;
import com.github.feng111627.usagebasedbillingjava.model.PauseAction;
import com.github.feng111627.usagebasedbillingjava.model.PauseRecord;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.model.UsageAccumulation;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户主动暂停/恢复计费：
 * 1) 周期中途暂停按剩余比例退预付基础费；
 * 2) 恢复按剩余比例补收、周期配额按真正活跃时长折算，恢复后用量在原结果上继续累加（不丢不重）；
 * 3) 暂停期间拒绝用量上报（独立错误码）；
 * 4) 暂停期间补报暂停前真实用量按活跃账段正常计费；
 * 5) 暂停跨周期不产生基础费、不误判缺计量、滚动保持 PAUSED。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PauseResumeBillingTest {

    private static final Logger log = LoggerFactory.getLogger(PauseResumeBillingTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired PauseService pauseService;
    @Autowired UsageService usage;
    @Autowired BillingService billing;
    @Autowired ClockService clock;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;

    private BigDecimal ratio(long remainingDays, long totalDays) {
        return new BigDecimal(remainingDays).divide(new BigDecimal(totalDays), 6, RoundingMode.HALF_UP);
    }

    @Test
    void midCyclePauseRefundsUnusedPrepaidBaseByRemainingRatio() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-z1", "cust-z", "BASIC", 1); // base 30.00

        Instant eff = Instant.parse("2026-09-15T00:00:00Z"); // 9 月周期 30 天，剩 16 天
        BigDecimal expected = new BigDecimal("30").multiply(ratio(16, 30)).setScale(2, RoundingMode.HALF_UP).negate();
        log.info("[case1] 输入 sub-z1 BASIC(30) 暂停 eff={}；判定依据：退款=-30*剩余比例(16/30)={}", eff, expected);

        PauseRecord pr = pauseService.pause("sub-z1", "pause-z1-1", eff);
        assertEquals(PauseAction.PAUSE, pr.getAction());
        assertEquals(0, expected.compareTo(pr.getAmount()),
                "暂停退款应为 -30*16/30=" + expected + "，实际 " + pr.getAmount());
        assertTrue(pr.getAmount().signum() < 0);
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-z1").getStatus());
        Invoice inv = billing.getInvoice(pr.getInvoiceId());
        assertEquals(InvoiceStatus.REFUNDED, inv.getStatus());
        assertEquals(LineKind.PAUSE_PRORATION, inv.getLines().get(0).getKind());

        // 幂等重放：返回首次结果，不产生第二笔退款
        PauseRecord replay = pauseService.pause("sub-z1", "pause-z1-1", eff);
        assertEquals(pr.getSeq(), replay.getSeq());
        assertEquals(pr.getAmount(), replay.getAmount());
        log.info("[case1] 幂等重放 pause-z1-1 seq={} 金额仍={}，无重复退款", replay.getSeq(), replay.getAmount());

        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "暂停退款后对账必须平: " + issues);
        assertEquals(0, ledger.conservationDelta().signum(), "退款后三方守恒: " + ledger.balances());
    }

    @Test
    void resumeChargesRemainingRatioAndProratesQuotaAndKeepsAccumulatedUsage() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-z2", "cust-z2", "BASIC", 1); // base 30, 配额 1000
        // 暂停前已有 600 次用量（活跃账段真实消耗）
        UsageAccumulation before = usage.report("z2-e1", "sub-z2", "api_calls", 600,
                Instant.parse("2026-09-05T00:00:00Z"));
        assertEquals(600, before.getTotal());

        Instant pauseAt = Instant.parse("2026-09-10T00:00:00Z"); // 剩 21 天
        PauseRecord paused = pauseService.pause("sub-z2", "pause-z2-1", pauseAt);
        BigDecimal refundExpected = new BigDecimal("-30").multiply(ratio(21, 30)).setScale(2, RoundingMode.HALF_UP);
        assertEquals(0, refundExpected.compareTo(paused.getAmount()), "暂停退款=" + refundExpected);

        Instant resumeAt = Instant.parse("2026-09-20T00:00:00Z"); // 到周期末剩 11 天
        PauseRecord resumed = pauseService.resume("sub-z2", "resume-z2-1", resumeAt);
        BigDecimal chargeExpected = new BigDecimal("30").multiply(ratio(11, 30)).setScale(2, RoundingMode.HALF_UP);
        log.info("[case2] 输入 sub-z2 恢复 eff={}；判定依据：补收=30*(11/30)={}；活跃=前9天+后11天=20天",
                resumeAt, chargeExpected);
        assertEquals(PauseAction.RESUME, resumed.getAction());
        assertEquals(0, chargeExpected.compareTo(resumed.getAmount()),
                "恢复补收应为 30*11/30=" + chargeExpected + "，实际 " + resumed.getAmount());
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscription("sub-z2").getStatus());
        assertEquals(InvoiceStatus.PAID, billing.getInvoice(resumed.getInvoiceId()).getStatus());

        // 恢复后继续上报：在原累计上累加，不丢量不重复
        UsageAccumulation after = usage.report("z2-e2", "sub-z2", "api_calls", 100,
                Instant.parse("2026-09-22T00:00:00Z"));
        assertEquals(700, after.getTotal(), "恢复后用量必须在原累计 600 上继续累加得到 700");

        // 周期末出账：活跃时长 = 9/1~9/10（9 天）+ 9/20~10/1（11 天）= 20 天；折算配额 = 1000*20/30 ≈ 667
        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice cyc = billing.closePeriod("sub-z2");
        long effectiveQuota = Math.round(1000.0 * 20.0 / 30.0); // HALF_UP 667
        long overUnits = Math.max(0L, 700 - effectiveQuota);
        BigDecimal overage = new BigDecimal("0.05").multiply(BigDecimal.valueOf(overUnits))
                .setScale(2, RoundingMode.HALF_UP);
        log.info("[case2] 出账判定依据：用量=700 活跃比例=20/30 折算配额={} 超量={} 超配额费={} + 下一周期基础费30",
                effectiveQuota, overUnits, overage);
        String basis = cyc.getLines().stream().filter(l -> l.getKind() == LineKind.OVERAGE)
                .findFirst().orElseThrow().getBasis();
        assertTrue(basis.contains("折算配额=" + effectiveQuota), "超配额行应写明折算配额: " + basis);
        assertEquals(0, overage.add(new BigDecimal("30.00")).compareTo(cyc.getAmount()),
                "周期账单应为 超配额 " + overage + " + 下一周期基础费 30 = " + cyc.getAmount());

        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "暂停恢复全流程对账必须平: " + issues);
        assertEquals(0, ledger.conservationDelta().signum());
    }

    @Test
    void rejectsNewUsageDuringPauseWithDistinctReason() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-z3", "cust-z3", "BASIC", 1);
        pauseService.pause("sub-z3", "pause-z3-1", Instant.parse("2026-09-10T00:00:00Z"));

        // 暂停账段内的新用量：明确拒绝（USAGE_DURING_PAUSE），不当零、不记到别的周期
        BillingException ex = assertThrows(BillingException.class, () ->
                usage.report("z3-bad", "sub-z3", "api_calls", 50,
                        Instant.parse("2026-09-15T00:00:00Z")));
        assertEquals(ErrorCode.USAGE_DURING_PAUSE, ex.getCode());
        log.info("[case3] 暂停期内上报被拒绝 code={} reason={}", ex.getCode(), ex.getMessage());

        // 恢复后，落在暂停窗内的用量依旧被拒绝
        pauseService.resume("sub-z3", "resume-z3-1", Instant.parse("2026-09-20T00:00:00Z"));
        BillingException ex2 = assertThrows(BillingException.class, () ->
                usage.report("z3-bad2", "sub-z3", "api_calls", 50,
                        Instant.parse("2026-09-15T00:00:00Z")));
        assertEquals(ErrorCode.USAGE_DURING_PAUSE, ex2.getCode());

        // 被拒事件没有落库累计
        UsageAccumulation acc = usage.report("z3-ok", "sub-z3", "api_calls", 1,
                Instant.parse("2026-09-25T00:00:00Z"));
        assertEquals(1, acc.getTotal(), "被暂停拒绝的用量不得混入累计");
    }

    @Test
    void lateReportOfPrePauseUsageDuringPauseIsBilledNormally() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-z4", "cust-z4", "BASIC", 1);
        // 9/10 暂停
        pauseService.pause("sub-z4", "pause-z4-1", Instant.parse("2026-09-10T00:00:00Z"));
        // 暂停期间（接收时刻在 9 月内暂停态）补报 9/8 真实发生（暂停之前、活跃账段）的用量 -> 必须放行
        UsageAccumulation acc = usage.report("z4-late", "sub-z4", "api_calls", 120,
                Instant.parse("2026-09-08T00:00:00Z"));
        assertEquals(120, acc.getTotal(), "暂停前真实用量的迟到补报必须正常累计，不能被暂停状态吞掉");
        log.info("[case4] 暂停期间补报暂停前(9/8)用量 120 -> 正常累计到周期 2026-09，累计={}", acc.getTotal());

        // 周期末出账：9/10 起整段暂停到周期末，活跃 9 天，折算配额=1000*9/30=300，120 未超；无下一周期基础费
        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice cyc = billing.closePeriod("sub-z4");
        assertEquals(0, cyc.getAmount().signum(), "120 未超折算配额且暂停态不预收基础费，账单应为 0 元");
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-z4").getStatus(),
                "跨周期出账不得把 PAUSED 滚成 ACTIVE");
        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "暂停前迟到补报正常计费后对账平: " + issues);
    }

    @Test
    void pauseAcrossWholeCycleProducesNoBaseFeeAndNoAnomaly() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-z5", "cust-z5", "BASIC", 1);
        // 暂停前活跃段显式上报一次 0：显式 0 属于“已计量”，确保 9 月不是缺计量
        usage.report("z5-zero", "sub-z5", "api_calls", 0, Instant.parse("2026-09-02T00:00:00Z"));
        // 9/15 暂停，一直不恢复：9 月周期后段暂停
        pauseService.pause("sub-z5", "pause-z5-1", Instant.parse("2026-09-15T00:00:00Z"));
        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice sep = billing.closePeriod("sub-z5");
        assertEquals(0, sep.getAmount().signum());
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-z5").getStatus());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"),
                subscriptions.getSubscription("sub-z5").getCurrentPeriodStart());

        // 整个 10 月周期都暂停，无任何上报：必须 0 元、无基础费、且不得判缺计量 ANOMALY
        clock.advanceTo(Instant.parse("2026-11-01T00:00:00Z"));
        Invoice oct = billing.closePeriod("sub-z5");
        log.info("[case5] 输入 sub-z5 整个10月暂停无上报；判定依据：活跃秒=0 -> 0元非ANOMALY，实际 status={} amount={}",
                oct.getStatus(), oct.getAmount());
        assertNotEquals(InvoiceStatus.ANOMALY, oct.getStatus(), "整周期暂停无用量是确定结果，不是缺计量");
        assertEquals(InvoiceStatus.PAID, oct.getStatus());
        assertEquals(0, oct.getAmount().signum());
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-z5").getStatus());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"),
                subscriptions.getSubscription("sub-z5").getCurrentPeriodStart());

        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "跨周期暂停出账后对账必须平: " + issues);
        assertEquals(0, ledger.conservationDelta().signum());
    }
}

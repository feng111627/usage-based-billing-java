package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.Money;
import com.github.feng111627.usagebasedbillingjava.domain.Bill;
import com.github.feng111627.usagebasedbillingjava.domain.BillLineKind;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景二：周期中途升降配与退订。
 * 验证基础费按账段秒数比例、配额按账段缩放、同刻多次变更不重复计价、退订后无费用。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class MidPeriodPlanChangeTest {

    private static final Logger log = LoggerFactory.getLogger(MidPeriodPlanChangeTest.class);

    @Autowired PlanService planService;
    @Autowired SubscriptionService subscriptionService;
    @Autowired UsageService usageService;
    @Autowired BillingService billingService;
    @Autowired BillingClock clock;

    @Test
    void upgradeHalfwayThenDowngradeAndCancel() {
        // basic: 月费 1000，api 配额 100，超额 2/单位
        planService.createPlan("basic", "Basic", Money.of(1000),
                Map.of("api", new MetricDef("api", 100, Money.of(2), "call")));
        // pro: 月费 3000，api 配额 500，超额 1/单位
        planService.createPlan("pro", "Pro", Money.of(3000),
                Map.of("api", new MetricDef("api", 500, Money.of(1), "call")));

        Instant anchor = clock.now(); // 2026-09-01
        subscriptionService.subscribe("sub-change", "cust", "basic", anchor);
        Instant mid = anchor.plusSeconds(15 * 86400); // 半月
        subscriptionService.changePlan("sub-change", "pro", mid);
        // 同刻再降到 basic：按 seq 折叠，最终 basic，且只一个边界
        subscriptionService.changePlan("sub-change", "basic", mid);

        // 用量：前半月 60（超 basic 半月配额 50 -> 10 单位超额按 2）
        // 后半月 40（basic 半月配额 50 内 -> 0）
        usageService.report("u1", "sub-change", "api", 60,
                anchor, mid, true);
        usageService.report("u2", "sub-change", "api", 40,
                mid, anchor.plusSeconds(30L * 86400), true);

        Bill bill = billingService.issueBill("sub-change", 0);
        long baseLines = bill.getLines().stream()
                .filter(l -> l.kind() == BillLineKind.BASE).mapToLong(l -> l.amount()).sum();
        long overageLines = bill.getLines().stream()
                .filter(l -> l.kind() == BillLineKind.OVERAGE).mapToLong(l -> l.amount()).sum();

        log.info("[case] two same-instant changes net to basic all month: baseSum={} (expect 1000), "
                        + "overage={} (expect 20 = 10 units x 2), total={} (basis: same-instant fold by seq, "
                        + "quota prorated by segment seconds)",
                baseLines, overageLines, bill.getAmount().cents());
        // 两个半月账段都是 basic：500+500=1000
        assertEquals(1000, baseLines);
        // 段配额各 50：前段 60-50=10 超额 ×2=20；后段 40 ≤ 50 不超额
        assertEquals(20, overageLines);
        assertEquals(1020, bill.getAmount().cents());

        MetricUsage u = billingService.summarizeUsage("sub-change", 0).get("api");
        log.info("[case] metric usage total={} included={} overageUnits={} overQuota={}",
                u.totalQuantity(), u.includedUnits(), u.overageUnits(), u.overQuota());
        assertEquals(100, u.totalQuantity());
        assertTrue(u.overQuota());
    }

    @Test
    void upgradeToProHalfwayReducesOverage() {
        planService.createPlan("b2", "B", Money.of(1000),
                Map.of("api", new MetricDef("api", 100, Money.of(5), "call")));
        planService.createPlan("p2", "P", Money.of(3000),
                Map.of("api", new MetricDef("api", 500, Money.of(1), "call")));
        Instant anchor = clock.now();
        subscriptionService.subscribe("sub-up", "cust", "b2", anchor);
        Instant mid = anchor.plusSeconds(15 * 86400);
        subscriptionService.changePlan("sub-up", "p2", mid);

        // 前半月 80（配额 50 内 -> 0 超额），后半月 400（配额 250 内 -> 0 超额）
        usageService.report("a1", "sub-up", "api", 80, anchor, mid, true);
        usageService.report("a2", "sub-up", "api", 400, mid, anchor.plusSeconds(30L * 86400), true);
        Bill bill = billingService.issueBill("sub-up", 0);
        long overage = bill.getLines().stream()
                .filter(l -> l.kind() == BillLineKind.OVERAGE).mapToLong(l -> l.amount()).sum();
        long base = bill.getLines().stream()
                .filter(l -> l.kind() == BillLineKind.BASE).mapToLong(l -> l.amount()).sum();
        log.info("[case] upgrade halfway: base={} (500 basic + 1500 pro), overage={} (expect 0: "
                        + "80<=50 basic quota, 400>250 pro quota?); basis: segment-scaled quotas",
                base, overage);
        assertEquals(2000, base);
        // 后半月配额 floor(500*15/30)=250：400-250=150 ×1=150；前段 80 对 50 配额：30 ×5=150
        // 合计 300
        assertEquals(300, overage);
    }

    @Test
    void cancelMidwayStopsBaseFeeAfterCancellation() {
        planService.createPlan("b3", "B", Money.of(1000), Map.of());
        Instant anchor = clock.now();
        subscriptionService.subscribe("sub-cancel", "cust", "b3", anchor);
        Instant cancelAt = anchor.plusSeconds(10 * 86400);
        subscriptionService.cancel("sub-cancel", cancelAt);
        Bill bill = billingService.issueBill("sub-cancel", 0);
        long base = bill.getLines().stream()
                .filter(l -> l.kind() == BillLineKind.BASE).mapToLong(l -> l.amount()).sum();
        log.info("[case] cancel after 10/30 days: base={} ~ 333 (basis: no active segment after cancel instant)",
                base);
        assertTrue(base > 330 && base < 336, "base should be ~10/30 of 1000, got " + base);
    }
}

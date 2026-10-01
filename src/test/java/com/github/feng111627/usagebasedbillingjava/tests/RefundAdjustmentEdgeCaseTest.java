package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.common.Money;
import com.github.feng111627.usagebasedbillingjava.domain.Bill;
import com.github.feng111627.usagebasedbillingjava.domain.MetricDef;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import com.github.feng111627.usagebasedbillingjava.service.PaymentService;
import com.github.feng111627.usagebasedbillingjava.service.PlanService;
import com.github.feng111627.usagebasedbillingjava.service.ReconciliationService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边界场景：部分退款守恒、缺量阻塞出账且标记明确、迟到上报调账、
 * 幂等键冲突、错误原因可区分。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class RefundAdjustmentEdgeCaseTest {

    private static final Logger log = LoggerFactory.getLogger(RefundAdjustmentEdgeCaseTest.class);

    @Autowired PlanService planService;
    @Autowired SubscriptionService subscriptionService;
    @Autowired UsageService usageService;
    @Autowired BillingService billingService;
    @Autowired PaymentService paymentService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired BillingClock clock;

    @Test
    void partialRefundKeepsConservationAndFeesSticky() {
        planService.createPlan("pr", "P", Money.of(1000), Map.of());
        subscriptionService.subscribe("sub-r", "cust", "pr", clock.now());
        Bill bill = billingService.issueBill("sub-r", 0);
        paymentService.collect(bill.getId());

        Map<String, Long> before = reconciliationService.verifyConservation();
        // 退 300（手续费 10 不退）
        Bill refund = paymentService.refund(bill.getId(), 300, "goodwill");
        Map<String, Long> after = reconciliationService.verifyConservation();
        log.info("[case] refund 300 of paid 1000: before platform={} fees={}; after platform={} fees={} "
                        + "(basis: reverse entries, fees non-refundable, conservation holds)",
                before.get("PLATFORM_REVENUE"), before.get("GATEWAY_FEES"),
                after.get("PLATFORM_REVENUE"), after.get("GATEWAY_FEES"));
        assertEquals(990 - 300, after.get("PLATFORM_REVENUE"));
        assertEquals(10, after.get("GATEWAY_FEES"));
        assertEquals(-300, refund.getAmount().cents());

        // 超额退款被拒（已退 300，再退 800 > 1000）
        BillingException ex = assertThrows(BillingException.class,
                () -> paymentService.refund(bill.getId(), 800, "too much"));
        log.info("[case] over-refund classified as {} (basis: cumulative refunds must not exceed paid)",
                ex.getCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.getCode());
        // 对未付票退款被拒
        Bill bill2 = billingService.issueBill("sub-r", 1);
        BillingException ex2 = assertThrows(BillingException.class,
                () -> paymentService.refund(bill2.getId(), 1, "x"));
        assertEquals(ErrorCode.BILL_NOT_PAYABLE, ex2.getCode());
    }

    @Test
    void missingMeteringBlocksBillWithClearFlag() {
        planService.createPlan("pm", "P", Money.of(1000),
                Map.of("api", new MetricDef("api", 100, Money.of(1), "call")));
        Instant anchor = clock.now();
        subscriptionService.subscribe("sub-m", "cust", "pm", anchor);
        usageService.report("ok1", "sub-m", "api", 10, anchor, anchor.plusSeconds(3600), true);
        usageService.report("miss1", "sub-m", "api", 0,
                anchor.plusSeconds(3600), anchor.plusSeconds(7200), false);

        var usage = billingService.summarizeUsage("sub-m", 0).get("api");
        assertTrue(usage.missingMetering());
        BillingException ex = assertThrows(BillingException.class,
                () -> billingService.issueBill("sub-m", 0));
        log.info("[case] incomplete metering -> issue blocked with code {} totalQty still counted={} "
                        + "(basis: never zero-fill missing windows)",
                ex.getCode(), usage.totalQuantity());
        assertEquals(ErrorCode.USAGE_METERING_MISSING, ex.getCode());
        assertEquals(10, usage.totalQuantity());
    }

    @Test
    void lateReportAfterBillProducesIdempotentAdjustment() {
        planService.createPlan("pl", "P", Money.of(1000),
                Map.of("api", new MetricDef("api", 0, Money.of(2), "call")));
        Instant anchor = clock.now();
        subscriptionService.subscribe("sub-l", "cust", "pl", anchor);
        usageService.report("early", "sub-l", "api", 5,
                anchor, anchor.plusSeconds(86400), true);
        Bill original = billingService.issueBill("sub-l", 0);
        paymentService.collect(original.getId());
        long originalAmount = original.getAmount().cents();

        // 重复出账被拒（原因可区分）
        BillingException dup = assertThrows(BillingException.class,
                () -> billingService.issueBill("sub-l", 0));
        assertEquals(ErrorCode.BILL_ALREADY_EXISTS, dup.getCode());

        // 迟到上报落在已关闭账期
        usageService.report("late", "sub-l", "api", 7,
                anchor.plusSeconds(86400), anchor.plusSeconds(2 * 86400), true);
        // 原票核对发现组总额与重算不符
        BillingException mismatch = assertThrows(BillingException.class,
                () -> reconciliationService.verifyBill(original.getId()));
        assertEquals(ErrorCode.RECONCILE_AMOUNT_MISMATCH, mismatch.getCode());
        log.info("[case] late report before adjustment -> reconcile code {} (basis: gap is visible, "
                + "never silently fixed)", mismatch.getCode());

        Bill adj = billingService.applyLateAdjustment("sub-l", 0, "adj-1");
        assertNotNull(adj);
        // 期望 1000 + 12*2=1024；原票 1010；差额 14
        log.info("[case] original={} adjustment={} expected=1024 (basis: original immutable, delta only)",
                originalAmount, adj.getAmount().cents());
        assertEquals(14, adj.getAmount().cents());

        // 调账幂等：同 key 重放返回同票，不产生第二张
        Bill replay = billingService.applyLateAdjustment("sub-l", 0, "adj-1");
        assertEquals(adj.getId(), replay.getId());
        // 无新差额时返回 null
        Bill again = billingService.applyLateAdjustment("sub-l", 0, "adj-2");
        assertNull(again);

        paymentService.collect(adj.getId());
        reconciliationService.verifyBill(original.getId());
        reconciliationService.verifyBill(adj.getId());
        reconciliationService.verifyConservation();
    }

    @Test
    void errorCausesAreDistinguishable() {
        // 不存在的订阅 / 套餐
        BillingException e1 = assertThrows(BillingException.class,
                () -> subscriptionService.changePlan("nope", "x", clock.now()));
        assertEquals(ErrorCode.SUBSCRIPTION_NOT_FOUND, e1.getCode());

        planService.createPlan("pe", "P", Money.of(100), Map.of());
        subscriptionService.subscribe("sub-e", "cust", "pe", clock.now());
        BillingException e2 = assertThrows(BillingException.class,
                () -> subscriptionService.changePlan("sub-e", "missing-plan", clock.now()));
        assertEquals(ErrorCode.PLAN_NOT_FOUND, e2.getCode());

        // 同幂等键不同内容
        planService.createPlan("pe2", "P", Money.of(100),
                Map.of("api", new MetricDef("api", 1, Money.of(1), "c")));
        subscriptionService.subscribe("sub-e2", "cust", "pe2", clock.now());
        Instant a = clock.now();
        usageService.report("k", "sub-e2", "api", 5, a, a.plusSeconds(60), true);
        BillingException e3 = assertThrows(BillingException.class,
                () -> usageService.report("k", "sub-e2", "api", 6, a, a.plusSeconds(60), true));
        log.info("[case] same idempotency key different payload -> code {} msg='{}'",
                e3.getCode(), e3.getMessage());
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, e3.getCode());

        // 负用量
        BillingException e4 = assertThrows(BillingException.class,
                () -> usageService.report("k2", "sub-e2", "api", -1, a, a.plusSeconds(60), true));
        assertEquals(ErrorCode.USAGE_QTY_INVALID, e4.getCode());

        // 退订后再升降配
        subscriptionService.cancel("sub-e2", clock.now());
        BillingException e5 = assertThrows(BillingException.class,
                () -> subscriptionService.changePlan("sub-e2", "pe", clock.now()));
        assertEquals(ErrorCode.SUBSCRIPTION_ALREADY_CANCELLED, e5.getCode());
        log.info("[case] error codes distinguished: {}, {}, {}, {}, {}",
                e1.getCode(), e2.getCode(), e3.getCode(), e4.getCode(), e5.getCode());
    }
}

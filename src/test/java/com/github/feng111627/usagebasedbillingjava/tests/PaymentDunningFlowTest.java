package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.common.BillingClock;
import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.common.Money;
import com.github.feng111627.usagebasedbillingjava.domain.Bill;
import com.github.feng111627.usagebasedbillingjava.domain.BillStatus;
import com.github.feng111627.usagebasedbillingjava.domain.MetricDef;
import com.github.feng111627.usagebasedbillingjava.domain.PaymentAttempt;
import com.github.feng111627.usagebasedbillingjava.domain.PaymentResult;
import com.github.feng111627.usagebasedbillingjava.domain.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.engine.Timeline;
import com.github.feng111627.usagebasedbillingjava.gateway.LocalMockPaymentGateway;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import com.github.feng111627.usagebasedbillingjava.service.PaymentService;
import com.github.feng111627.usagebasedbillingjava.service.PlanService;
import com.github.feng111627.usagebasedbillingjava.service.ReconciliationService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景三：收款失败 -> 重试 -> 宽限 -> 暂停 -> 补缴恢复。
 * 含并发 collect 不重复扣款、永久失败转 OVERDUE、守恒校验。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class PaymentDunningFlowTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentDunningFlowTest.class);

    @Autowired PlanService planService;
    @Autowired SubscriptionService subscriptionService;
    @Autowired BillingService billingService;
    @Autowired PaymentService paymentService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired LocalMockPaymentGateway gateway;
    @Autowired BillingClock clock;

    private String setupSubscription(String subId, long basePrice) {
        planService.createPlan("plan-" + subId, "P", Money.of(basePrice),
                Map.of("api", new MetricDef("api", 0, Money.of(1), "call")));
        Instant anchor = clock.now();
        subscriptionService.subscribe(subId, "cust", "plan-" + subId, anchor);
        return billingService.issueBill(subId, 0).getId();
    }

    @Test
    void failTwiceThenRetrySucceeds() {
        String billId = setupSubscription("sub-retry", 1000);
        gateway.scriptFailFirst("charge:" + billId, 2, "insufficient funds");

        PaymentAttempt a1 = paymentService.collect(billId);
        PaymentAttempt a2 = paymentService.collect(billId);
        assertEquals(PaymentResult.FAILURE, a1.result());
        assertEquals(PaymentResult.FAILURE, a2.result());
        assertEquals(BillStatus.RETRYING, billingService.getBill(billId).getStatus());

        PaymentAttempt a3 = paymentService.collect(billId);
        log.info("[case] charge failed twice then retry #3 -> {}; bill={}, attempts recorded={}; "
                        + "basis: failures do not occupy idempotency key, exactly one success charge exists",
                a3.result(), billingService.getBill(billId).getStatus(),
                paymentService.attemptsOf(billId).size());
        assertEquals(PaymentResult.SUCCESS, a3.result());
        assertEquals(BillStatus.PAID, billingService.getBill(billId).getStatus());
        long successCharges = paymentService.attemptsOf(billId).stream()
                .filter(a -> !a.refund() && a.result() == PaymentResult.SUCCESS).count();
        assertEquals(1, successCharges);

        // 已付再收 -> 明确分类 BILL_ALREADY_PAID
        BillingException ex = assertThrows(BillingException.class, () -> paymentService.collect(billId));
        log.info("[case] collect on PAID bill classified as {} (basis: terminal state guard)",
                ex.getCode());
        assertEquals(ErrorCode.BILL_ALREADY_PAID, ex.getCode());

        reconciliationService.verifyBill(billId);
        reconciliationService.verifyConservation();
    }

    @Test
    void concurrentCollectChargesExactlyOnce() throws Exception {
        String billId = setupSubscription("sub-cc", 1000);
        gateway.scriptFailFirst("charge:" + billId, 1, "temporary");
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    PaymentAttempt a = paymentService.collect(billId);
                    if (a.result() == PaymentResult.SUCCESS) {
                        success.incrementAndGet();
                    } else {
                        failure.incrementAndGet();
                    }
                } catch (BillingException e) {
                    if (e.getCode() == ErrorCode.BILL_ALREADY_PAID) {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("unexpected", e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.info("[case] 12 concurrent collect: success={} failure={} rejected-as-paid={} "
                        + "(basis: per-bill lock + fixed gateway reference replay)",
                success.get(), failure.get(), rejected.get());
        assertEquals(1, success.get());
        assertEquals(1, failure.get());
        assertEquals(n - 2, rejected.get());
        reconciliationService.verifyConservation();
    }

    @Test
    void permanentFailureGoesOverdueSuspendsThenPayOverdueResumes() {
        String billId = setupSubscription("sub-dunning", 1000);
        gateway.scriptAlwaysFail("charge:" + billId, "card expired");

        paymentService.collect(billId);
        // 宽限期内催收：不动作
        Bill before = paymentService.evaluateDunning(billId, clock.now().plusSeconds(86400));
        assertEquals(BillStatus.RETRYING, before.getStatus());

        // 过宽限期（7 天）：OVERDUE + 暂停订阅
        Bill overdue = paymentService.evaluateDunning(billId, clock.now().plusSeconds(8L * 86400));
        log.info("[case] after grace: bill={} subscription={} (basis: dueAt elapsed -> OVERDUE + SUSPENDED)",
                overdue.getStatus(),
                Timeline.statusAt(subscriptionService.get("sub-dunning"), clock.now().plusSeconds(8L * 86400)));
        assertEquals(BillStatus.OVERDUE, overdue.getStatus());
        assertEquals(SubscriptionStatus.SUSPENDED,
                Timeline.statusAt(subscriptionService.get("sub-dunning"), clock.now().plusSeconds(8L * 86400)));

        // 换卡成功（清除脚本），补缴 -> PAID + 恢复
        gateway.reset();
        // reset 会清掉成功缓存；原失败未缓存，因此同引用重试现在成功
        PaymentAttempt paid = paymentService.payOverdue(billId);
        assertEquals(PaymentResult.SUCCESS, paid.result());
        assertEquals(BillStatus.PAID, billingService.getBill(billId).getStatus());
        assertEquals(SubscriptionStatus.ACTIVE, Timeline.currentStatus(subscriptionService.get("sub-dunning")));
        log.info("[case] payOverdue success -> bill PAID, subscription ACTIVE; basis: no unpaid bills remain");

        reconciliationService.verifyBill(billId);
        reconciliationService.verifyConservation();
    }
}

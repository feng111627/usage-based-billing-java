package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.repository.Repositories;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LedgerService;
import com.github.feng111627.usagebasedbillingjava.service.LocalPaymentGateway;
import com.github.feng111627.usagebasedbillingjava.service.PaymentService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 收款失败全链路：
 * closePeriod 扣款失败 -> FAILED + 订阅 PAST_DUE；
 * 3 天重试窗口内 tick -> 再次扣款（仍失败）；
 * 超过 3 天 tick -> PAST_DUE 宽限（7 天）；
 * 超过宽限 tick -> SUSPENDED；
 * 通道恢复后补缴 -> PAID、订阅 ACTIVE、周期滚动；全程仅一次成功扣款，三方守恒。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentFailureRetryTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentFailureRetryTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired UsageService usage;
    @Autowired BillingService billing;
    @Autowired PaymentService payments;
    @Autowired ClockService clock;
    @Autowired LocalPaymentGateway gateway;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;
    @Autowired Repositories.PaymentRepository paymentRepository;

    @Test
    void retryGraceSuspendThenRepayKeepsOneChargeAndConservation() {
        gateway.reset();
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-f1", "cust-f", "BASIC", 1);
        usage.report("f1-ev1", "sub-f1", "api_calls", 100, Instant.parse("2026-09-10T00:00:00Z"));

        // 周期末：让通道连续失败 3 次（出账 1 + 窗口内重试 1 + 宽限期内 1），
        // 到期进入暂停；之后通道恢复，补缴成功
        gateway.failNext(3);
        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice inv;
        {
            BillingException ex = assertThrows(BillingException.class, () -> billing.closePeriod("sub-f1"));
            assertEquals(ErrorCode.GATEWAY_REJECTED, ex.getCode());
            inv = billing.listInvoices("sub-f1").stream()
                    .filter(Invoice::isCycle).reduce((a, b) -> b).orElseThrow();
        }
        log.info("[stage] 出账扣款失败 invoice={} status={} attempts={} 订阅={}",
                inv.getInvoiceId(), inv.getStatus(), inv.getAttemptCount(),
                subscriptions.getSubscription("sub-f1").getStatus());
        assertEquals(InvoiceStatus.FAILED, inv.getStatus());
        assertEquals(SubscriptionStatus.PAST_DUE, subscriptions.getSubscription("sub-f1").getStatus());

        // 重试窗口内 tick：自动重试（第 2 次尝试），失败不外抛，账单保持 FAILED 由状态机继续推进
        Invoice retried = billing.runBillingTick("sub-f1");
        assertEquals(InvoiceStatus.FAILED, retried.getStatus());
        assertEquals(2, retried.getAttemptCount());
        log.info("[stage] 3 天内自动重试仍失败 attempts={} 账单保持 FAILED", retried.getAttemptCount());

        // 超过 3 天重试窗口：进入宽限，不再立即扣款
        clock.advanceSeconds(Duration.ofDays(3).plusSeconds(1).getSeconds());
        Invoice pastDue = billing.runBillingTick("sub-f1");
        assertEquals(InvoiceStatus.PAST_DUE, pastDue.getStatus());
        assertNotNull(pastDue.getDueAt());
        log.info("[stage] 超过重试窗口 -> PAST_DUE 宽限截止={}", pastDue.getDueAt());

        // 宽限期内 tick（第 3 次尝试，仍失败），账单保持 PAST_DUE
        clock.advanceSeconds(Duration.ofDays(2).getSeconds());
        Invoice graceRetried = billing.runBillingTick("sub-f1");
        assertEquals(InvoiceStatus.PAST_DUE, graceRetried.getStatus());
        assertEquals(3, graceRetried.getAttemptCount());
        log.info("[stage] 宽限期内扣款仍失败 attempts={}", graceRetried.getAttemptCount());

        // 超过宽限截止：暂停
        clock.advanceSeconds(Duration.ofDays(6).getSeconds());
        Invoice suspendedInv = billing.runBillingTick("sub-f1");
        assertEquals(SubscriptionStatus.SUSPENDED, subscriptions.getSubscription("sub-f1").getStatus());
        log.info("[stage] 超过宽限 -> SUSPENDED，账单状态={}", suspendedInv.getStatus());

        // 暂停期间变更应被分类拒绝
        BillingException blocked = assertThrows(BillingException.class,
                () -> subscriptions.changePlan("sub-f1", "PRO", "x", null));
        assertEquals(ErrorCode.SUBSCRIPTION_SUSPENDED, blocked.getCode());
        log.info("[stage] 暂停期变更被拒绝 code={}", blocked.getCode());

        // 通道恢复（不再注入失败），补缴：成功，仅一次成功扣款
        payments.repay(inv.getInvoiceId(), "repay-f1-1");
        assertEquals(InvoiceStatus.PAID, billing.getInvoice(inv.getInvoiceId()).getStatus());
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscription("sub-f1").getStatus());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"),
                subscriptions.getSubscription("sub-f1").getCurrentPeriodStart());

        long successCharges = paymentRepository.findByInvoice(inv.getInvoiceId()).stream()
                .filter(a -> !a.isRefund() && a.isSuccess()).count();
        long totalAttempts = paymentRepository.findByInvoice(inv.getInvoiceId()).stream()
                .filter(a -> !a.isRefund()).count();
        log.info("[stage] 补缴成功：总尝试={} 成功扣款={}（必须恰好 1，不重复扣款）", totalAttempts, successCharges);
        assertEquals(1, successCharges, "全程只能有一次成功扣款");
        assertTrue(totalAttempts >= 3, "应至少经历 3 次尝试（出账+重试+宽限）");

        // 幂等补缴：同键重放不重复扣款
        var replay = payments.repay(inv.getInvoiceId(), "repay-f1-1");
        assertTrue(replay.isSuccess());
        assertEquals(1, paymentRepository.findByInvoice(inv.getInvoiceId()).stream()
                .filter(a -> !a.isRefund() && a.isSuccess()).count());

        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "失败重试全链路后对账必须平: " + issues);
        assertEquals(0, ledger.conservationDelta().signum(),
                "补缴后三方守恒: " + ledger.balances());
        log.info("[stage] 判定依据：对账问题={} 余额={} 守恒差={}",
                issues, ledger.balances(), ledger.conservationDelta());
    }

}

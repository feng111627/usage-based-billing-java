package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus;
import com.github.feng111627.usagebasedbillingjava.model.LineKind;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LocalPaymentGateway;
import com.github.feng111627.usagebasedbillingjava.service.PaymentService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计量缺失边界：
 * - 周期内一条 api_calls 都没上报 -> 出账 ANOMALY，不收款、不滚周期，收款尝试被明确拒绝；
 * - 显式上报 quantity=0 属于已计量，不算缺失；
 * - 人工 confirm-zero 留痕后才按零处理并预收基础费 -> PENDING -> 收款 -> 滚动。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MissingMeteringTest {

    private static final Logger log = LoggerFactory.getLogger(MissingMeteringTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired BillingService billing;
    @Autowired PaymentService payments;
    @Autowired UsageService usage;
    @Autowired ClockService clock;
    @Autowired LocalPaymentGateway gateway;

    @Test
    void missingMeteringIsAnomalyUntilHumanConfirmed() {
        gateway.reset();
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-m1", "cust-m", "BASIC", 1);
        // 不上报任何 api_calls
        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));

        Invoice inv = billing.closePeriod("sub-m1");
        log.info("[missing] 输入：周期内零上报；判定依据：{} -> status={}", inv.getAnomalyReason(), inv.getStatus());
        assertEquals(InvoiceStatus.ANOMALY, inv.getStatus());
        assertEquals(0, inv.getAmount().signum());
        assertNotNull(inv.getAnomalyReason());
        // 订阅不滚动、状态仍是 ACTIVE（异常账单挂起）
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"),
                subscriptions.getSubscription("sub-m1").getCurrentPeriodStart());

        // 异常账单直接收款必须被分类拒绝，错误原因可区分
        BillingException ex = assertThrows(BillingException.class,
                () -> payments.charge(inv, "should-fail"));
        assertEquals(ErrorCode.INVOICE_ANOMALY, ex.getCode());
        log.info("[missing] 异常账单收款被拒绝 code={} message={}", ex.getCode(), ex.getMessage());

        // 人工确认零用量（必须留 operator），随后按零超量 + 30 基础费收款
        Invoice confirmed = billing.confirmZeroUsage(inv.getInvoiceId(), "ops-alice");
        assertEquals(InvoiceStatus.PAID, confirmed.getStatus(), "确认后通道默认成功应直接 PAID");
        assertEquals(0, new BigDecimal("30.00").compareTo(confirmed.getAmount()));
        boolean hasTrace = confirmed.getLines().stream()
                .anyMatch(l -> l.getKind() == LineKind.ADJUSTMENT
                        && l.getBasis() != null && l.getBasis().contains("ops-alice"));
        assertTrue(hasTrace, "必须保留人工确认零用量的留痕行");
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscription("sub-m1").getStatus());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"),
                subscriptions.getSubscription("sub-m1").getCurrentPeriodStart());
        log.info("[missing] 人工确认后：amount={} 状态=PAID，周期滚动，留痕行存在", confirmed.getAmount());
    }

    @Test
    void explicitZeroReportIsNotMissing() {
        gateway.reset();
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-m2", "cust-m2", "BASIC", 1);
        // 显式零用量上报：代表计量数据存在，不应判缺失
        usage.report("zero-1", "sub-m2", "api_calls", 0, Instant.parse("2026-09-15T00:00:00Z"));
        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice inv = billing.closePeriod("sub-m2");
        log.info("[zero] 显式 0 上报 -> status={} amount={}（无超量+预收基础费30）", inv.getStatus(), inv.getAmount());
        assertNotEquals(InvoiceStatus.ANOMALY, inv.getStatus());
        assertEquals(0, new BigDecimal("30.00").compareTo(inv.getAmount()));
    }
}

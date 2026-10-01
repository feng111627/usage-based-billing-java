package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus;
import com.github.feng111627.usagebasedbillingjava.model.Subscription;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 正常全流程：开通 -> 上报（含超配额）-> 出账收款 -> 滚动周期 -> 对账守恒。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HappyFlowIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HappyFlowIntegrationTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired UsageService usage;
    @Autowired BillingService billing;
    @Autowired ClockService clock;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;

    @Test
    void subscribeReportCloseAndPay() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        log.info("[case] 输入: 开通 sub-h1, plan=BASIC(30元/1000配额/0.05超量), anchor=1, at={}", clock.now());
        Subscription s = subscriptions.subscribe("sub-h1", "cust-1", "BASIC", 1);
        assertEquals(SubscriptionStatus.ACTIVE, s.getStatus());

        log.info("[case] 输入: 2026-09-10 上报 api_calls 1200 -> 超配额 200，超量费 200*0.05=10.00");
        usage.report("ev-h1-1", "sub-h1", "api_calls", 1200, Instant.parse("2026-09-10T08:00:00Z"));

        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice inv = billing.closePeriod("sub-h1");
        log.info("[case] 判定: 出账 invoice={} status={} amount={} 明细依据={}",
                inv.getInvoiceId(), inv.getStatus(), inv.getAmount(), inv.getLines());

        assertEquals(InvoiceStatus.PAID, inv.getStatus(), "通道默认成功，出账即应支付完成");
        // 超配额费 10.00 + 下一周期基础费 30.00
        assertEquals(0, new BigDecimal("40.00").compareTo(inv.getAmount()));

        Subscription after = subscriptions.getSubscription("sub-h1");
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), after.getCurrentPeriodStart());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), after.getCurrentPeriodEnd());

        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "正常流程对账应无问题: " + issues);
        assertEquals(0, ledger.conservationDelta().signum(),
                "三方金额必须守恒: " + ledger.balances());
        log.info("[case] 判定依据：对账问题={} 三方余额={} 守恒差={}",
                issues, ledger.balances(), ledger.conservationDelta());
    }
}

package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus;
import com.github.feng111627.usagebasedbillingjava.model.LineKind;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LedgerService;
import com.github.feng111627.usagebasedbillingjava.service.LocalPaymentGateway;
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
 * 迟到用量：账单已 PAID 后才收到 9 月周期事件，不丢量——
 * 按该周期套餐重算超配额费，另立 ADJUSTMENT 账单即时补收；
 * 重复追加被拒绝；最终账单口径与用量汇总一致、三方守恒。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LateUsageAdjustmentTest {

    private static final Logger log = LoggerFactory.getLogger(LateUsageAdjustmentTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired UsageService usage;
    @Autowired BillingService billing;
    @Autowired ClockService clock;
    @Autowired LocalPaymentGateway gateway;
    @Autowired ReconciliationService reconciliation;
    @Autowired LedgerService ledger;

    @Test
    void lateEventAfterPaidCreatesAdjustmentInvoice() {
        gateway.reset();
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-l1", "cust-l", "BASIC", 1);
        // 周期内用了 1000（恰好配额内），正常出账：无超量 + 预收 30
        usage.report("l1-ev1", "sub-l1", "api_calls", 1000, Instant.parse("2026-09-12T00:00:00Z"));
        clock.advanceTo(Instant.parse("2026-10-01T00:00:00Z"));
        Invoice cycle = billing.closePeriod("sub-l1");
        assertEquals(InvoiceStatus.PAID, cycle.getStatus());
        log.info("[late] 周期账单已付 amount={}", cycle.getAmount());

        // 10 月 3 日才收到 9 月 20 日的迟到事件 300：总用量变 1300，超 300，超量费 300*0.05=15
        clock.advanceTo(Instant.parse("2026-10-03T00:00:00Z"));
        usage.report("l1-late-1", "sub-l1", "api_calls", 300, Instant.parse("2026-09-20T00:00:00Z"));
        Invoice adj = billing.appendLateUsage(cycle.getInvoiceId(), "l1-late-1");
        log.info("[late] 迟到补计调整账单 id={} amount={} status={} 依据={}",
                adj.getInvoiceId(), adj.getAmount(), adj.getStatus(), adj.getLines());
        assertEquals(0, new BigDecimal("15.00").compareTo(adj.getAmount()));
        assertTrue(adj.getLines().stream().anyMatch(l -> l.getKind() == LineKind.ADJUSTMENT));
        assertEquals(InvoiceStatus.PAID, adj.getStatus(), "调整账单应即时收款成功");

        // 重复追加同一事件必须被拒绝（不重复计费）
        var dup = assertThrows(com.github.feng111627.usagebasedbillingjava.error.BillingException.class,
                () -> billing.appendLateUsage(cycle.getInvoiceId(), "l1-late-1"));
        assertEquals(com.github.feng111627.usagebasedbillingjava.error.ErrorCode.DUPLICATE_REQUEST,
                dup.getCode());

        // 原周期账单金额不被篡改；对账仍平；三方守恒
        Invoice cycleReloaded = billing.getInvoice(cycle.getInvoiceId());
        assertEquals(0, new BigDecimal("30.00").compareTo(cycleReloaded.getAmount()));
        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "迟到补计后对账必须平: " + issues);
        assertEquals(0, ledger.conservationDelta().signum(),
                "补收后三方守恒: " + ledger.balances());
        log.info("[late] 判定依据：原账单保持 {}，调整 15.00，对账问题={} 余额={} 守恒差={}",
                cycleReloaded.getAmount(), issues, ledger.balances(), ledger.conservationDelta());
    }
}

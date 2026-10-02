package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.PauseRecord;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.repository.Repositories;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LedgerService;
import com.github.feng111627.usagebasedbillingjava.service.PauseService;
import com.github.feng111627.usagebasedbillingjava.service.PaymentService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 退款守恒护栏：任何一笔成功退款累计不得超过该订阅已经成功收到的钱。
 * 暂停按比例退款、升降配差价退款、手工退款共用同一上限；超额直接 REFUND_LIMIT_EXCEEDED 拦截。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PauseRefundGuardTest {

    private static final Logger log = LoggerFactory.getLogger(PauseRefundGuardTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired PauseService pauseService;
    @Autowired PaymentService payments;
    @Autowired ClockService clock;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;
    @Autowired Repositories.InvoiceRepository invoiceRepository;

    @Test
    void refundBeyondAmountPaidIsRejectedAndPauseResumeNetConserves() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-g1", "cust-g", "BASIC", 1); // 首期收款 30.00

        // 暂停按比例退款（剩 15 天：30*15/30=15），合法
        PauseRecord pr = pauseService.pause("sub-g1", "pause-g1", Instant.parse("2026-09-16T00:00:00Z"));
        assertEquals(0, new BigDecimal("-15.00").compareTo(pr.getAmount()));
        assertEquals(SubscriptionStatus.PAUSED, subscriptions.getSubscription("sub-g1").getStatus());

        // 试图通过任意账单手工再退一大笔，超过净收款（30-15=15）必须被拦
        com.github.feng111627.usagebasedbillingjava.model.Invoice any =
                invoiceRepository.findBySubscription("sub-g1").get(0);
        BillingException ex = assertThrows(BillingException.class,
                () -> payments.refund(any.getInvoiceId(), new BigDecimal("100"), "manual-over-refund"));
        assertEquals(ErrorCode.REFUND_LIMIT_EXCEEDED, ex.getCode());
        log.info("[guard] 超额退款被拦截 code={} reason={}", ex.getCode(), ex.getMessage());

        // 合法的恢复补收（剩 5 天：30*5/30=5）成功
        PauseRecord rs = pauseService.resume("sub-g1", "resume-g1", Instant.parse("2026-09-26T00:00:00Z"));
        assertEquals(0, new BigDecimal("5.00").compareTo(rs.getAmount()));
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.getSubscription("sub-g1").getStatus());

        // 三方守恒、对账平（护栏拦截的那次退款没有任何资金/状态副作用）
        Map<String, List<String>> issues = reconciliation.reconcileAll();
        assertTrue(issues.isEmpty(), "暂停-退款-恢复净额后对账必须平: " + issues);
        assertEquals(0, ledger.conservationDelta().signum(), "三方守恒: " + ledger.balances());
        log.info("[guard] 判定依据：收款30 退款15 恢复补收5；对账问题={} 余额={} 守恒差={}",
                issues, ledger.balances(), ledger.conservationDelta());
    }
}

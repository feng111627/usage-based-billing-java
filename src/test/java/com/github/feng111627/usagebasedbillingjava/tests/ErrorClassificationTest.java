package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LocalPaymentGateway;
import com.github.feng111627.usagebasedbillingjava.service.PauseService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
import com.github.feng111627.usagebasedbillingjava.service.UsageService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同类操作的不同失败原因必须被分类为不同 ErrorCode，且错误信息可区分。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ErrorClassificationTest {

    private static final Logger log = LoggerFactory.getLogger(ErrorClassificationTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired PauseService pauseService;
    @Autowired UsageService usage;
    @Autowired ClockService clock;
    @Autowired LocalPaymentGateway gateway;

    @Test
    void distinctFailuresHaveDistinctCodes() {
        gateway.reset();
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));

        BillingException notFound = assertThrows(BillingException.class,
                () -> subscriptions.subscribe("sub-e1", "c", "NO_PLAN", 1));
        assertEquals(ErrorCode.PLAN_NOT_FOUND, notFound.getCode());

        subscriptions.subscribe("sub-e1", "c", "BASIC", 1);
        BillingException duplicate = assertThrows(BillingException.class,
                () -> subscriptions.subscribe("sub-e1", "c", "PRO", 1));
        assertEquals(ErrorCode.DUPLICATE_REQUEST, duplicate.getCode());

        BillingException badAnchor = assertThrows(BillingException.class,
                () -> subscriptions.subscribe("sub-e2", "c", "BASIC", 31));
        assertEquals(ErrorCode.VALIDATION, badAnchor.getCode());

        subscriptions.cancel("sub-e1", "cancel-e1", Instant.parse("2026-09-10T00:00:00Z"));
        BillingException inactive = assertThrows(BillingException.class,
                () -> subscriptions.changePlan("sub-e1", "PRO", "k1", Instant.parse("2026-09-11T00:00:00Z")));
        assertEquals(ErrorCode.SUBSCRIPTION_INACTIVE, inactive.getCode());

        log.info("[error-code] 不同失败原因被区分：PLAN_NOT_FOUND / DUPLICATE_REQUEST / VALIDATION / SUBSCRIPTION_INACTIVE");
        log.info("[error-code] 依据示例：{} | {} | {} | {}",
                notFound.getMessage(), duplicate.getMessage(), badAnchor.getMessage(), inactive.getMessage());
        assertNotEquals(notFound.getCode(), duplicate.getCode());
        assertNotEquals(duplicate.getCode(), badAnchor.getCode());
        assertNotEquals(badAnchor.getCode(), inactive.getCode());

        // 客户主动暂停(PAUSED) 与 收款失败停用(SUSPENDED) 必须是两类可区分原因；
        // 暂停账段内上报用量也必须有独立错误码，不能悄悄当零。
        subscriptions.subscribe("sub-e3", "c", "BASIC", 1);
        pauseService.pause("sub-e3", "pause-e3", Instant.parse("2026-09-05T00:00:00Z"));
        BillingException paused = assertThrows(BillingException.class,
                () -> subscriptions.changePlan("sub-e3", "PRO", "k2", Instant.parse("2026-09-06T00:00:00Z")));
        BillingException usageDuringPause = assertThrows(BillingException.class,
                () -> usage.report("e3-bad", "sub-e3", "api_calls", 1,
                        Instant.parse("2026-09-07T00:00:00Z")));
        assertEquals(ErrorCode.SUBSCRIPTION_PAUSED_BY_CUSTOMER, paused.getCode());
        assertEquals(ErrorCode.USAGE_DURING_PAUSE, usageDuringPause.getCode());
        assertNotEquals(ErrorCode.SUBSCRIPTION_SUSPENDED, paused.getCode(),
                "客户主动暂停不能与收款失败停用混为一谈");
        assertNotEquals(paused.getCode(), usageDuringPause.getCode());
        log.info("[error-code] 客户暂停 SUBSCRIPTION_PAUSED_BY_CUSTOMER 与收款失败 {} 与暂停期上报 {} 三者可区分",
                ErrorCode.SUBSCRIPTION_SUSPENDED, usageDuringPause.getCode());
    }
}

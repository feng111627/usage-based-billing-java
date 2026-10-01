package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LocalPaymentGateway;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
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
    }
}

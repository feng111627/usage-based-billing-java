package com.github.feng111627.usagebasedbillingjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全流程 HTTP 冒烟：建套餐 -> 开通 -> 上报（含重复）-> 出账 -> 收款 -> 对账 -> 守恒。
 * 使用随机端口，不依赖任何外部服务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(HttpEndToEndTest.class);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("unchecked")
    @Test
    void endToEndOverHttp() {
        // 1) 套餐
        String planBody = """
                {"id":"http-plan","name":"HTTP Plan","basePriceCents":2000,
                 "metrics":[{"key":"api","includedUnits":100,"overagePriceCents":3,"unit":"call"}]}
                """;
        rest.put(url("/api/plans/http-plan"), new HttpEntity<>(planBody, json()));

        // 2) 开通
        Instant anchor = Instant.parse("2026-09-01T00:00:00Z");
        String subBody = "{\"subscriptionId\":\"http-sub\",\"customerId\":\"c1\","
                + "\"planId\":\"http-plan\",\"effectiveAt\":\"" + anchor + "\"}";
        ResponseEntity<Map> subResp = rest.postForEntity(url("/api/subscriptions"),
                new HttpEntity<>(subBody, json()), Map.class);
        assertEquals(200, subResp.getStatusCode().value());
        log.info("[http] subscription created: {}", subResp.getBody().get("id"));

        // 3) 上报两次（第二个是重复幂等键）
        Instant wEnd = anchor.plusSeconds(86400);
        String usageBody = "{\"idempotencyKey\":\"h1\",\"metricKey\":\"api\",\"quantity\":150,"
                + "\"windowStart\":\"" + anchor + "\",\"windowEnd\":\"" + wEnd + "\","
                + "\"meteringComplete\":true}";
        ResponseEntity<Map> u1 = rest.postForEntity(url("/api/subscriptions/http-sub/usage"),
                new HttpEntity<>(usageBody, json()), Map.class);
        ResponseEntity<Map> u2 = rest.postForEntity(url("/api/subscriptions/http-sub/usage"),
                new HttpEntity<>(usageBody, json()), Map.class);
        assertEquals(Boolean.FALSE, u1.getBody().get("duplicate"));
        assertEquals(Boolean.TRUE, u2.getBody().get("duplicate"));
        log.info("[http] duplicate report flagged: u1.duplicate={} u2.duplicate={}",
                u1.getBody().get("duplicate"), u2.getBody().get("duplicate"));

        // 4) 出账：基础费 2000；超 50 单位 ×3 = 150 -> 2150
        ResponseEntity<Map> bill = rest.postForEntity(
                url("/api/subscriptions/http-sub/bills"),
                new HttpEntity<>("{\"periodIndex\":0}", json()), Map.class);
        assertEquals(200, bill.getStatusCode().value());
        Object amount = bill.getBody().get("amountCents");
        assertEquals(2150, ((Number) amount).intValue());
        String billId = (String) bill.getBody().get("id");
        log.info("[http] bill issued id={} amount={}", billId, amount);

        // 5) 收款
        ResponseEntity<Map> paid = rest.postForEntity(
                url("/api/subscriptions/http-sub/bills/" + billId + "/collect"),
                HttpEntity.EMPTY, Map.class);
        assertEquals("SUCCESS", paid.getBody().get("result"));

        // 6) 账单对账 + 全局守恒
        ResponseEntity<Map> verify = rest.postForEntity(
                url("/api/subscriptions/http-sub/bills/" + billId + "/verify"),
                HttpEntity.EMPTY, Map.class);
        assertEquals(200, verify.getStatusCode().value());
        assertEquals(true, verify.getBody().get("balanced"));

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> conservation = rest.postForEntity(
                url("/api/admin/verify-conservation"), HttpEntity.EMPTY, Map.class);
        Map body = conservation.getBody();
        log.info("[http] conservation: {}", body);
        // cash 为负（贷-借口径），-cash == platform + fees
        long cash = ((Number) body.get("CASH_CLEARING")).longValue();
        long platform = ((Number) body.get("PLATFORM_REVENUE")).longValue();
        long fees = ((Number) body.get("GATEWAY_FEES")).longValue();
        assertEquals(-cash, platform + fees);

        // 7) 错误分类：不存在账单 404 + 错误码
        ResponseEntity<Map> notFound = rest.postForEntity(
                url("/api/subscriptions/http-sub/bills/bill-nope/collect"),
                HttpEntity.EMPTY, Map.class);
        assertEquals(404, notFound.getStatusCode().value());
        assertEquals("BILL_NOT_FOUND", notFound.getBody().get("code"));
        assertNotNull(notFound.getBody().get("message"));
        log.info("[http] error classification works: code={} message={}",
                notFound.getBody().get("code"), notFound.getBody().get("message"));
    }
}

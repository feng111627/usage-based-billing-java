package com.github.feng111627.usagebasedbillingjava.tests;

import com.github.feng111627.usagebasedbillingjava.model.UsageAccumulation;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
import com.github.feng111627.usagebasedbillingjava.service.UsageService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发上报：
 * 1) 同一 eventId 被多线程重复提交 -> 只计一次，不重复计费；
 * 2) 不同 eventId 并发上报 -> 总量等于各事件量之和，累计一致不丢量；
 * 3) 迟到/乱序（usageAt 早于接收顺序）-> 归到正确周期，不丢。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConcurrentUsageReportTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentUsageReportTest.class);

    @Autowired SubscriptionService subscriptions;
    @Autowired UsageService usage;
    @Autowired ClockService clock;

    @Test
    void duplicateAndConcurrentReportsAreConsistent() throws Exception {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-c1", "cust-c", "BASIC", 1);

        int threads = 16;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger duplicateReplays = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        // 场景 A：同一个 eventId "dup-1" 被所有线程同时重复提交，仅允许计一次 10
        // 场景 B：每线程各自提交 perThread 个不同 eventId，每个 qty=2
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 不同事件并发
                        usage.report("ev-" + tid + "-" + i, "sub-c1", "api_calls", 2,
                                Instant.parse("2026-09-05T00:00:00Z"));
                    }
                    // 同 eventId 重复：除首个外都应返回且不增加总量（返回结果即判定依据）
                    UsageAccumulation acc = usage.report("dup-1", "sub-c1", "api_calls", 10,
                            Instant.parse("2026-09-06T00:00:00Z"));
                    if (acc.getVersion() < 1) {
                        errors.add(new AssertionError("累计版本异常"));
                    }
                } catch (Exception e) {
                    // 同 eventId 并发下不应有异常（首个落库，其余视为重复）
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(errors.isEmpty(), "并发上报不应产生异常: " + errors);

        long expectedUnique = (long) threads * perThread * 2 + 10;
        long actualTotal = usage.listEvents("sub-c1").stream()
                .mapToLong(e -> e.getQuantity()).sum();
        log.info("[concurrent] 输入：{} 线程 × {} 事件(qty=2) + 共同重复 eventId=dup-1(qty=10)；期望总量={} 实际事件量和={}",
                threads, perThread, expectedUnique, actualTotal);
        assertEquals(expectedUnique, actualTotal, "事件存储总量必须与输入一致（不丢量、不重复）");

        // 服务端去重：重复 eventId 在事件表里只有一条
        long dupStored = usage.listEvents("sub-c1").stream().filter(e -> e.getEventId().equals("dup-1")).count();
        assertEquals(1, dupStored, "重复 eventId 只能存储一条");

        // 累计行：同一周期同一计量项只有一个，且总量正确
        UsageAccumulation sep = usage.report("probe-readonly", "sub-c1", "api_calls", 0,
                Instant.parse("2026-09-20T00:00:00Z"));
        log.info("[concurrent] 判定依据：9月周期累计 total={} version={}（探针 0 量事件外）",
                sep.getTotal(), sep.getVersion());
        // 累计 = 400*2? 计算: threads*perThread=400 个事件 *2 = 800；dup-1=10；探针=0
        assertEquals(810, sep.getTotal(), "并发累计结果必须确定且一致（800 独立 + 10 去重重播）");
        assertFalse(sep.isMissing());
    }

    @Test
    void lateAndOutOfOrderEventsAreNotLostOrDoubleCounted() {
        clock.advanceTo(Instant.parse("2026-09-01T00:00:00Z"));
        subscriptions.subscribe("sub-c2", "cust-c2", "BASIC", 1);

        // 先收到 9 月较晚的事件，再“迟到”收到 9 月初事件（乱序到达）
        usage.report("ord-2", "sub-c2", "api_calls", 7, Instant.parse("2026-09-20T00:00:00Z"));
        usage.report("ord-1", "sub-c2", "api_calls", 3, Instant.parse("2026-09-02T00:00:00Z"));
        // 完全重复
        usage.report("ord-1", "sub-c2", "api_calls", 3, Instant.parse("2026-09-02T00:00:00Z"));

        long total = usage.listEvents("sub-c2").stream().mapToLong(e -> e.getQuantity()).sum();
        log.info("[ordering] 输入：乱序 ord-1=3, ord-2=7, ord-1 重复；判定依据：事件量和={}（期望 10，不丢不重）", total);
        assertEquals(10, total);
    }
}

package com.github.feng111627.usagebasedbillingjava.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 按订阅维度分段加锁：同一订阅的开通后变更、用量上报、出账、收款串行执行，
 * 不同订阅互不阻塞。配合 seq 全序，保证同一时刻多次变更的扣费结论确定、无中间态残留。
 */
@Component
public class SubscriptionLocks {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public Object lockFor(String subscriptionId) {
        return locks.computeIfAbsent(subscriptionId, k -> new Object());
    }

    public <T> T withLock(String subscriptionId, Supplier<T> action) {
        synchronized (lockFor(subscriptionId)) {
            return action.get();
        }
    }

    public void withLock(String subscriptionId, Runnable action) {
        synchronized (lockFor(subscriptionId)) {
            action.run();
        }
    }
}

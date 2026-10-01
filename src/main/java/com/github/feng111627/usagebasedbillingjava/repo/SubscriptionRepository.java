package com.github.feng111627.usagebasedbillingjava.repo;

import com.github.feng111627.usagebasedbillingjava.domain.Subscription;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订阅内存仓储。每个订阅一把锁：同一订阅的事件追加、用量汇总、出账在服务层持锁串行化，
 * 不同订阅互不阻塞。
 */
@Repository
public class SubscriptionRepository {

    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public Subscription save(Subscription subscription) {
        subscriptions.put(subscription.getId(), subscription);
        locks.computeIfAbsent(subscription.getId(), k -> new Object());
        return subscription;
    }

    public Optional<Subscription> findById(String id) {
        return Optional.ofNullable(subscriptions.get(id));
    }

    public List<Subscription> findAll() {
        return List.copyOf(subscriptions.values());
    }

    /** 返回订阅级互斥锁（不存在也返回一个锁对象，方便创建前同步）。 */
    public Object lockOf(String subscriptionId) {
        return locks.computeIfAbsent(subscriptionId, k -> new Object());
    }
}

package com.github.feng111627.usagebasedbillingjava.repository;

import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.LedgerTransaction;
import com.github.feng111627.usagebasedbillingjava.model.PaymentAttempt;
import com.github.feng111627.usagebasedbillingjava.model.Plan;
import com.github.feng111627.usagebasedbillingjava.model.PlanChange;
import com.github.feng111627.usagebasedbillingjava.model.Subscription;
import com.github.feng111627.usagebasedbillingjava.model.UsageAccumulation;
import com.github.feng111627.usagebasedbillingjava.model.UsageEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全部基于 {@link ConcurrentHashMap} 的内存仓储。
 * 保存即存对象引用本身（单进程内可变实体），聚合更新的原子性由服务层按订阅加锁保证。
 */
@Configuration
public class InMemoryRepositories {

    @Bean
    public Repositories.PlanRepository planRepository() {
        return new Repositories.PlanRepository() {
            private final Map<String, Plan> store = new ConcurrentHashMap<>();

            @Override
            public Plan save(Plan plan) {
                store.put(plan.getPlanId(), plan);
                return plan;
            }

            @Override
            public Optional<Plan> findById(String planId) {
                return Optional.ofNullable(store.get(planId));
            }

            @Override
            public List<Plan> findAll() {
                return new ArrayList<>(store.values());
            }
        };
    }

    @Bean
    public Repositories.SubscriptionRepository subscriptionRepository() {
        return new Repositories.SubscriptionRepository() {
            private final Map<String, Subscription> store = new ConcurrentHashMap<>();

            @Override
            public Subscription save(Subscription subscription) {
                store.put(subscription.getSubscriptionId(), subscription);
                return subscription;
            }

            @Override
            public Optional<Subscription> findById(String subscriptionId) {
                return Optional.ofNullable(store.get(subscriptionId));
            }

            @Override
            public List<Subscription> findAll() {
                return new ArrayList<>(store.values());
            }
        };
    }

    @Bean
    public Repositories.ChangeRepository changeRepository() {
        return new Repositories.ChangeRepository() {
            private final Map<String, PlanChange> byId = new ConcurrentHashMap<>();
            private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();

            @Override
            public synchronized PlanChange save(PlanChange change) {
                byId.put(change.getSubscriptionId() + "#" + change.getSeq(), change);
                if (change.getIdempotencyKey() != null) {
                    idempotencyIndex.put(change.getIdempotencyKey(),
                            change.getSubscriptionId() + "#" + change.getSeq());
                }
                return change;
            }

            @Override
            public synchronized List<PlanChange> findBySubscription(String subscriptionId) {
                return byId.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(subscriptionId + "#"))
                        .map(Map.Entry::getValue)
                        .sorted((a, b) -> Long.compare(a.getSeq(), b.getSeq()))
                        .toList();
            }

            @Override
            public synchronized Optional<PlanChange> findByIdempotencyKey(String key) {
                return Optional.ofNullable(idempotencyIndex.get(key)).map(byId::get);
            }

            @Override
            public synchronized List<PlanChange> findAll() {
                return byId.values().stream()
                        .sorted((a, b) -> Long.compare(a.getSeq(), b.getSeq()))
                        .toList();
            }
        };
    }

    @Bean
    public Repositories.UsageEventRepository usageEventRepository() {
        return new Repositories.UsageEventRepository() {
            private final Map<String, UsageEvent> store = new ConcurrentHashMap<>();

            @Override
            public UsageEvent save(UsageEvent event) {
                store.put(event.getEventId(), event);
                return event;
            }

            @Override
            public Optional<UsageEvent> findByEventId(String eventId) {
                return Optional.ofNullable(store.get(eventId));
            }

            @Override
            public List<UsageEvent> findBySubscription(String subscriptionId) {
                return store.values().stream()
                        .filter(e -> e.getSubscriptionId().equals(subscriptionId))
                        .toList();
            }

            @Override
            public List<UsageEvent> findAll() {
                return new ArrayList<>(store.values());
            }
        };
    }

    @Bean
    public Repositories.UsageAccumulationRepository usageAccumulationRepository() {
        return new Repositories.UsageAccumulationRepository() {
            private final Map<String, UsageAccumulation> store = new ConcurrentHashMap<>();

            private static String key(String subscriptionId, String periodKey, String metric) {
                return subscriptionId + "|" + periodKey + "|" + metric;
            }

            @Override
            public Optional<UsageAccumulation> find(String subscriptionId, String periodKey, String metric) {
                return Optional.ofNullable(store.get(key(subscriptionId, periodKey, metric)));
            }

            @Override
            public UsageAccumulation save(UsageAccumulation accumulation) {
                store.put(key(accumulation.getSubscriptionId(), accumulation.getPeriodKey(),
                        accumulation.getMetric()), accumulation);
                return accumulation;
            }

            @Override
            public List<UsageAccumulation> findBySubscriptionAndPeriod(String subscriptionId, String periodKey) {
                return store.values().stream()
                        .filter(a -> a.getSubscriptionId().equals(subscriptionId)
                                && a.getPeriodKey().equals(periodKey))
                        .toList();
            }

            @Override
            public List<UsageAccumulation> findAll() {
                return new ArrayList<>(store.values());
            }
        };
    }

    @Bean
    public Repositories.InvoiceRepository invoiceRepository() {
        return new Repositories.InvoiceRepository() {
            private final Map<String, Invoice> store = new ConcurrentHashMap<>();

            @Override
            public Invoice save(Invoice invoice) {
                store.put(invoice.getInvoiceId(), invoice);
                return invoice;
            }

            @Override
            public Optional<Invoice> findById(String invoiceId) {
                return Optional.ofNullable(store.get(invoiceId));
            }

            @Override
            public List<Invoice> findBySubscription(String subscriptionId) {
                return store.values().stream()
                        .filter(i -> i.getSubscriptionId().equals(subscriptionId))
                        .sorted((a, b) -> a.getPeriodStart().compareTo(b.getPeriodStart()))
                        .toList();
            }

            @Override
            public Optional<Invoice> findBySubscriptionAndPeriod(String subscriptionId, String periodKey) {
                return store.values().stream()
                        .filter(i -> i.getSubscriptionId().equals(subscriptionId)
                                && i.getPeriodKey().equals(periodKey))
                        .findFirst();
            }

            @Override
            public List<Invoice> findAll() {
                return new ArrayList<>(store.values());
            }
        };
    }

    @Bean
    public Repositories.PaymentRepository paymentRepository() {
        return new Repositories.PaymentRepository() {
            private final Map<String, PaymentAttempt> store = new ConcurrentHashMap<>();

            @Override
            public PaymentAttempt save(PaymentAttempt attempt) {
                store.put(attempt.getId(), attempt);
                return attempt;
            }

            @Override
            public List<PaymentAttempt> findByInvoice(String invoiceId) {
                return store.values().stream()
                        .filter(a -> a.getInvoiceId().equals(invoiceId))
                        .sorted((a, b) -> a.getAttemptedAt().compareTo(b.getAttemptedAt()))
                        .toList();
            }

            @Override
            public List<PaymentAttempt> findAll() {
                return new ArrayList<>(store.values());
            }
        };
    }

    @Bean
    public Repositories.LedgerRepository ledgerRepository() {
        return new Repositories.LedgerRepository() {
            private final Map<String, LedgerTransaction> store = new ConcurrentHashMap<>();

            @Override
            public LedgerTransaction save(LedgerTransaction tx) {
                store.put(tx.getTxId(), tx);
                return tx;
            }

            @Override
            public Optional<LedgerTransaction> findByIdempotencyKey(String key) {
                return store.values().stream()
                        .filter(t -> key.equals(t.getIdempotencyKey()))
                        .findFirst();
            }

            @Override
            public List<LedgerTransaction> findAll() {
                return new ArrayList<>(store.values());
            }
        };
    }
}

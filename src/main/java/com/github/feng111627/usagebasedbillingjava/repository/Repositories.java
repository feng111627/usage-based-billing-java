package com.github.feng111627.usagebasedbillingjava.repository;

import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.LedgerTransaction;
import com.github.feng111627.usagebasedbillingjava.model.PaymentAttempt;
import com.github.feng111627.usagebasedbillingjava.model.Plan;
import com.github.feng111627.usagebasedbillingjava.model.PlanChange;
import com.github.feng111627.usagebasedbillingjava.model.Subscription;
import com.github.feng111627.usagebasedbillingjava.model.UsageAccumulation;
import com.github.feng111627.usagebasedbillingjava.model.UsageEvent;

import java.util.List;
import java.util.Optional;

/**
 * 内存仓储集合：本地验证无需外部数据库。
 */
public final class Repositories {

    private Repositories() {
    }

    public interface PlanRepository {
        Plan save(Plan plan);
        Optional<Plan> findById(String planId);
        List<Plan> findAll();
    }

    public interface SubscriptionRepository {
        Subscription save(Subscription subscription);
        Optional<Subscription> findById(String subscriptionId);
        List<Subscription> findAll();
    }

    public interface ChangeRepository {
        PlanChange save(PlanChange change);
        List<PlanChange> findBySubscription(String subscriptionId);
        Optional<PlanChange> findByIdempotencyKey(String key);
        List<PlanChange> findAll();
    }

    public interface UsageEventRepository {
        UsageEvent save(UsageEvent event);
        Optional<UsageEvent> findByEventId(String eventId);
        List<UsageEvent> findBySubscription(String subscriptionId);
        List<UsageEvent> findAll();
    }

    public interface UsageAccumulationRepository {
        Optional<UsageAccumulation> find(String subscriptionId, String periodKey, String metric);
        UsageAccumulation save(UsageAccumulation accumulation);
        List<UsageAccumulation> findBySubscriptionAndPeriod(String subscriptionId, String periodKey);
        List<UsageAccumulation> findAll();
    }

    public interface InvoiceRepository {
        Invoice save(Invoice invoice);
        Optional<Invoice> findById(String invoiceId);
        List<Invoice> findBySubscription(String subscriptionId);
        Optional<Invoice> findBySubscriptionAndPeriod(String subscriptionId, String periodKey);
        List<Invoice> findAll();
    }

    public interface PaymentRepository {
        PaymentAttempt save(PaymentAttempt attempt);
        List<PaymentAttempt> findByInvoice(String invoiceId);
        List<PaymentAttempt> findAll();
    }

    public interface LedgerRepository {
        LedgerTransaction save(LedgerTransaction tx);
        Optional<LedgerTransaction> findByIdempotencyKey(String key);
        List<LedgerTransaction> findAll();
    }
}

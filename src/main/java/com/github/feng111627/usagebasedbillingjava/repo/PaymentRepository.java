package com.github.feng111627.usagebasedbillingjava.repo;

import com.github.feng111627.usagebasedbillingjava.domain.PaymentAttempt;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 支付尝试内存仓储（只追加）。
 */
@Repository
public class PaymentRepository {

    private final CopyOnWriteArrayList<PaymentAttempt> attempts = new CopyOnWriteArrayList<>();

    public PaymentAttempt save(PaymentAttempt attempt) {
        attempts.add(attempt);
        return attempt;
    }

    public List<PaymentAttempt> findByBill(String billId) {
        return attempts.stream()
                .filter(a -> a.billId().equals(billId))
                .sorted(Comparator.comparing(PaymentAttempt::attemptedAt)
                        .thenComparing(PaymentAttempt::reference))
                .toList();
    }

    public List<PaymentAttempt> findAll() {
        return List.copyOf(attempts);
    }
}

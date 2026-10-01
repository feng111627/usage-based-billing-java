package com.github.feng111627.usagebasedbillingjava.repo;

import com.github.feng111627.usagebasedbillingjava.domain.Bill;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账单内存仓储。每张账单一把锁：收款重试/宽限判定/退款串行化，杜绝并发重复扣款。
 */
@Repository
public class BillRepository {

    private final ConcurrentHashMap<String, Bill> bills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public Bill save(Bill bill) {
        bills.put(bill.getId(), bill);
        locks.computeIfAbsent(bill.getId(), k -> new Object());
        return bill;
    }

    public Optional<Bill> findById(String id) {
        return Optional.ofNullable(bills.get(id));
    }

    public List<Bill> findBySubscription(String subscriptionId) {
        return bills.values().stream()
                .filter(b -> b.getSubscriptionId().equals(subscriptionId))
                .sorted(Comparator.comparing(Bill::getIssuedAt).thenComparing(Bill::getId))
                .toList();
    }

    public List<Bill> findByInvoiceGroup(String invoiceGroupId) {
        return bills.values().stream()
                .filter(b -> b.getInvoiceGroupId().equals(invoiceGroupId))
                .sorted(Comparator.comparing(Bill::getIssuedAt).thenComparing(Bill::getId))
                .toList();
    }

    public List<Bill> findAll() {
        return List.copyOf(bills.values());
    }

    public Object lockOf(String billId) {
        return locks.computeIfAbsent(billId, k -> new Object());
    }
}

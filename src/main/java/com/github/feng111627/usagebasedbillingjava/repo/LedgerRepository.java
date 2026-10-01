package com.github.feng111627.usagebasedbillingjava.repo;

import com.github.feng111627.usagebasedbillingjava.domain.AccountType;
import com.github.feng111627.usagebasedbillingjava.domain.LedgerAccount;
import com.github.feng111627.usagebasedbillingjava.domain.LedgerEntry;
import org.springframework.stereotype.Repository;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 分类账内存仓储：分录只追加，余额由全部分录投影。
 */
@Repository
public class LedgerRepository {

    private final CopyOnWriteArrayList<LedgerEntry> entries = new CopyOnWriteArrayList<>();

    public void post(LedgerEntry entry) {
        entries.add(entry);
    }

    public List<LedgerEntry> findAll() {
        return List.copyOf(entries);
    }

    public List<LedgerEntry> findByBill(String billId) {
        return entries.stream().filter(e -> e.billId().equals(billId)).toList();
    }

    /**
     * 各账户余额 = Σ(贷 - 借)。
     * 记账约定：SUBSCRIBER_PAID 的贷方为订阅方已付资金来源；PLATFORM_REVENUE /
     * GATEWAY_FEES 的贷方为收入归属。守恒等式：
     * SUBSCRIBER_PAID(贷-借) == PLATFORM_REVENUE(贷-借) + GATEWAY_FEES(贷-借)。
     */
    public Map<AccountType, Long> balances() {
        EnumMap<AccountType, Long> balances = new EnumMap<>(AccountType.class);
        for (AccountType t : AccountType.values()) {
            balances.put(t, 0L);
        }
        for (LedgerEntry e : entries) {
            balances.merge(e.account(), e.creditCents() - e.debitCents(), Long::sum);
        }
        return balances;
    }

    public List<LedgerAccount> accounts() {
        return balances().entrySet().stream()
                .map(en -> new LedgerAccount(en.getKey(), en.getValue()))
                .toList();
    }
}

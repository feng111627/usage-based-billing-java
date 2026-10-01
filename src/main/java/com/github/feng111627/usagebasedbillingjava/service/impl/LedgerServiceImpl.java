package com.github.feng111627.usagebasedbillingjava.service.impl;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.Account;
import com.github.feng111627.usagebasedbillingjava.model.LedgerTransaction;
import com.github.feng111627.usagebasedbillingjava.repository.Repositories;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LedgerService;
import com.github.feng111627.usagebasedbillingjava.service.Money;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 复式记账实现。约定：
 * SUBSCRIBER 为客户现金账户（充值为正、付款为负、退款为正）；
 * PLATFORM 为平台收入账户（收款为正、退款为负）；
 * CHANNEL 为通道手续费账户。
 * 每笔事务所有分录之和必须为 0 —— 这是“三方守恒”的记账侧保证；
 * 幂等键去重保证同一笔收款/退款绝不重复入账。
 */
@Service
public class LedgerServiceImpl implements LedgerService {

    private final Repositories.LedgerRepository ledgerRepository;
    private final ClockService clock;

    public LedgerServiceImpl(Repositories.LedgerRepository ledgerRepository, ClockService clock) {
        this.ledgerRepository = ledgerRepository;
        this.clock = clock;
    }

    @Override
    public synchronized LedgerTransaction post(String refType, String refId,
                                               Map<Account, BigDecimal> entries, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        if (entries == null || entries.isEmpty()) {
            throw new BillingException(ErrorCode.VALIDATION, "记账分录不能为空");
        }
        BigDecimal sum = BigDecimal.ZERO;
        Map<Account, BigDecimal> normalized = new EnumMap<>(Account.class);
        for (Map.Entry<Account, BigDecimal> e : entries.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() == 0) {
                continue;
            }
            BigDecimal v = Money.of(e.getValue());
            normalized.put(e.getKey(), v);
            sum = sum.add(v);
        }
        if (sum.signum() != 0) {
            throw new BillingException(ErrorCode.VALIDATION,
                    "复式记账不平衡，分录和=" + sum + "（借贷必须相等），ref=" + refType + ":" + refId);
        }
        LedgerTransaction tx = new LedgerTransaction();
        tx.setTxId(UUID.randomUUID().toString());
        tx.setRefType(refType);
        tx.setRefId(refId);
        tx.setCreatedAt(clock.now());
        tx.setIdempotencyKey(idempotencyKey);
        tx.getEntries().putAll(normalized);
        return ledgerRepository.save(tx);
    }

    @Override
    public synchronized BigDecimal conservationDelta() {
        Map<Account, BigDecimal> b = balances();
        return Money.of(b.getOrDefault(Account.SUBSCRIBER, BigDecimal.ZERO)
                .add(b.getOrDefault(Account.PLATFORM, BigDecimal.ZERO))
                .add(b.getOrDefault(Account.CHANNEL, BigDecimal.ZERO)));
    }

    @Override
    public synchronized Map<Account, BigDecimal> balances() {
        Map<Account, BigDecimal> balances = new EnumMap<>(Account.class);
        for (Account a : Account.values()) {
            balances.put(a, BigDecimal.ZERO);
        }
        for (LedgerTransaction tx : ledgerRepository.findAll()) {
            tx.getEntries().forEach((a, v) -> balances.merge(a, v, BigDecimal::add));
        }
        balances.replaceAll((a, v) -> Money.of(v));
        return balances;
    }

    @Override
    public List<LedgerTransaction> all() {
        return ledgerRepository.findAll();
    }
}

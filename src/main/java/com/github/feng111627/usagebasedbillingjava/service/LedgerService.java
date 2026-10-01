package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.model.Account;
import com.github.feng111627.usagebasedbillingjava.model.LedgerTransaction;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 复式记账：所有资金流动必须借贷平衡；按幂等键去重，杜绝重复扣款/漏扣。
 */
public interface LedgerService {

    /** 记一笔平衡事务（entries 之和必须为 0），返回事务；重复幂等键返回原事务。 */
    LedgerTransaction post(String refType, String refId, Map<Account, BigDecimal> entries, String idempotencyKey);

    /** 校验三方账户总余额守恒：SUBSCRIBER + PLATFORM + CHANNEL == 0。 */
    BigDecimal conservationDelta();

    Map<Account, BigDecimal> balances();

    java.util.List<LedgerTransaction> all();
}

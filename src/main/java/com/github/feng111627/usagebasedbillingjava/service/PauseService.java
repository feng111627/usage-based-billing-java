package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.model.PauseRecord;

import java.time.Instant;
import java.util.List;

/**
 * 客户主动暂停/恢复订阅（区别于收款失败导致的 SUSPENDED）。
 */
public interface PauseService {

    /**
     * 暂停：从 effectiveAt 起不再产生基础费，按暂停时刻到周期末剩余比例退回已预付未消耗的基础费。
     * 幂等键重复提交返回首次结果，不重复退款。
     */
    PauseRecord pause(String subscriptionId, String idempotencyKey, Instant effectiveAt);

    /**
     * 恢复：从 effectiveAt 起到周期末按比例补收基础费，周期配额按真正活跃时长等比例折算。
     * 幂等键重复提交返回首次结果，不重复扣款。
     */
    PauseRecord resume(String subscriptionId, String idempotencyKey, Instant effectiveAt);

    /** 列出某订阅全部暂停/恢复记录（按全序 seq 升序）。 */
    List<PauseRecord> list(String subscriptionId);
}

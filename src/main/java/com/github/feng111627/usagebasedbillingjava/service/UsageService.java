package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.model.UsageAccumulation;
import com.github.feng111627.usagebasedbillingjava.model.UsageEvent;

import java.time.Instant;
import java.util.List;

public interface UsageService {

    /**
     * 上报用量。eventId 幂等：重复上报返回既有累计、不重复计费；
     * 迟到（usageAt 落在已关账周期）不丢量，转入调整项；
     * 并发上报按 eventId 去重 + 行级原子累加，累计结果一致。
     */
    UsageAccumulation report(String eventId, String subscriptionId, String metric, long quantity, Instant usageAt);

    /** 标记某订阅当前/指定周期某计量项缺失（出账前可由调用方或出账逻辑调用）。 */
    void markMissing(String subscriptionId, String periodKey, String metric, boolean missing);

    List<UsageEvent> listEvents(String subscriptionId);
}

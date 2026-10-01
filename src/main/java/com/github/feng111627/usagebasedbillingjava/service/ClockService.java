package com.github.feng111627.usagebasedbillingjava.service;

import java.time.Instant;

/**
 * 可被测试拨动的时钟：所有时间判断只允许通过本服务获取，保证本地可重复验证。
 */
public interface ClockService {
    Instant now();

    /** 测试/演示用：把时钟拨到指定时刻。 */
    void advanceTo(Instant instant);

    /** 相对当前前进。 */
    void advanceSeconds(long seconds);
}

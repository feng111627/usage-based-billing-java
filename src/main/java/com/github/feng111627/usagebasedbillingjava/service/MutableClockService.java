package com.github.feng111627.usagebasedbillingjava.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可被测试/演示拨动的时钟。默认固定在一个确定性的起点（2026-09-01T00:00:00Z），
 * 所有计费时间判断都经由此服务，避免依赖真实墙钟导致测试不可重复。
 */
@Service
public class MutableClockService implements ClockService {

    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    @Override
    public Instant now() {
        return now.get();
    }

    @Override
    public void advanceTo(Instant instant) {
        now.set(instant);
    }

    @Override
    public void advanceSeconds(long seconds) {
        now.updateAndGet(t -> t.plusSeconds(seconds));
    }
}

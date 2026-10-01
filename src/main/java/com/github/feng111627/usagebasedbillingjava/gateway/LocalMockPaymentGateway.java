package com.github.feng111627.usagebasedbillingjava.gateway;

import com.github.feng111627.usagebasedbillingjava.domain.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地模拟支付通道（无外部依赖）：
 * <ul>
 *   <li>默认成功；可脚本化某引用前缀前 N 次失败（临时故障 -> 重试成功）或永久失败
 *       （卡失效 -> 宽限 -> 暂停）；失败不占用幂等键，同引用后续重试可以成功；</li>
 *   <li>成功扣款按引用缓存重放，杜绝同一笔钱重复扣；</li>
 *   <li>退款用独立引用，支持对同一原扣款多次部分退款，本地模拟恒成功；</li>
 *   <li>手续费：每笔成功扣款收 {@value #FEE_CENTS} 分，退款不退手续费，
 *       全局守恒等式为“订阅方已付 = 平台收入 + 通道手续费”。</li>
 * </ul>
 */
@Component
public class LocalMockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(LocalMockPaymentGateway.class);

    public static final long FEE_CENTS = 10L;

    private final Map<String, PaymentResult> succeeded = new ConcurrentHashMap<>();
    private final Map<String, ScriptRule> rules = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failCounters = new ConcurrentHashMap<>();

    private record ScriptRule(boolean permanent, int failTimes, String reason) {
    }

    /** 匹配前缀的扣款前 times 次失败，之后成功。 */
    public void scriptFailFirst(String referencePrefix, int times, String reason) {
        rules.put(referencePrefix, new ScriptRule(false, times, reason));
    }

    /** 匹配前缀的扣款永久失败。 */
    public void scriptAlwaysFail(String referencePrefix, String reason) {
        rules.put(referencePrefix, new ScriptRule(true, Integer.MAX_VALUE, reason));
    }

    public void reset() {
        succeeded.clear();
        rules.clear();
        failCounters.clear();
    }

    @Override
    public PaymentResult charge(String reference, long amountCents) {
        PaymentResult cached = succeeded.get(reference);
        if (cached != null) {
            log.info("[gateway] replay charge ref={} amount={} -> SUCCESS (idempotent)", reference, amountCents);
            return cached;
        }
        ScriptRule rule = matchRule(reference);
        if (rule != null) {
            int seen = failCounters.computeIfAbsent(reference, k -> new AtomicInteger()).incrementAndGet();
            if (rule.permanent() || seen <= rule.failTimes()) {
                log.warn("[gateway] charge ref={} amount={} attempt#{} -> FAILURE reason='{}'",
                        reference, amountCents, seen, rule.reason());
                // 失败不写入 succeeded：同引用重试不受阻
                return PaymentResult.FAILURE;
            }
        }
        log.info("[gateway] charge ref={} amount={} fee={} -> SUCCESS", reference, amountCents, FEE_CENTS);
        succeeded.put(reference, PaymentResult.SUCCESS);
        return PaymentResult.SUCCESS;
    }

    @Override
    public PaymentResult refund(String reference, String originalReference, long amountCents) {
        PaymentResult cached = succeeded.get(reference);
        if (cached != null) {
            log.info("[gateway] replay refund ref={} orig={} amount={} -> SUCCESS",
                    reference, originalReference, amountCents);
        } else {
            log.info("[gateway] refund ref={} orig={} amount={} -> SUCCESS",
                    reference, originalReference, amountCents);
            succeeded.put(reference, PaymentResult.SUCCESS);
        }
        return PaymentResult.SUCCESS;
    }

    private ScriptRule matchRule(String reference) {
        ScriptRule matched = null;
        int matchedLen = -1;
        for (Map.Entry<String, ScriptRule> en : rules.entrySet()) {
            if (reference.startsWith(en.getKey()) && en.getKey().length() > matchedLen) {
                matched = en.getValue();
                matchedLen = en.getKey().length();
            }
        }
        return matched;
    }

    public String failureReasonOf(String reference) {
        ScriptRule rule = matchRule(reference);
        return rule == null ? "unknown" : rule.reason();
    }
}

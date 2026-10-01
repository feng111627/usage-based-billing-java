package com.github.feng111627.usagebasedbillingjava.service.impl;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.Account;
import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.model.InvoiceStatus;
import com.github.feng111627.usagebasedbillingjava.model.PaymentAttempt;
import com.github.feng111627.usagebasedbillingjava.model.Subscription;
import com.github.feng111627.usagebasedbillingjava.model.SubscriptionStatus;
import com.github.feng111627.usagebasedbillingjava.repository.Repositories;
import com.github.feng111627.usagebasedbillingjava.service.BillingCalendar;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LedgerService;
import com.github.feng111627.usagebasedbillingjava.service.LocalPaymentGateway;
import com.github.feng111627.usagebasedbillingjava.service.Money;
import com.github.feng111627.usagebasedbillingjava.service.PaymentService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 支付编排：本地模拟通道 + 复式记账。
 *
 * <p>守恒规则（费率 {@link #FEE_RATE}）：收款时分录
 * SUBSCRIBER -amount、PLATFORM +(amount-fee)、CHANNEL +fee，三者和为 0；
 * 退款用同一费率反向冲回。任何金额流动只经此入口，保证不重复扣款/不漏扣。
 * 幂等键贯穿“通道调用 + 记账”，重复请求返回原始流水。
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
    private static final BigDecimal FEE_RATE = new BigDecimal("0.01");

    private final LocalPaymentGateway gateway;
    private final LedgerService ledger;
    private final Repositories.PaymentRepository paymentRepository;
    private final Repositories.InvoiceRepository invoiceRepository;
    private final Repositories.SubscriptionRepository subscriptionRepository;
    private final BillingCalendar calendar;
    private final ClockService clock;
    private final SubscriptionLocks locks;

    public PaymentServiceImpl(LocalPaymentGateway gateway, LedgerService ledger,
                              Repositories.PaymentRepository paymentRepository,
                              Repositories.InvoiceRepository invoiceRepository,
                              Repositories.SubscriptionRepository subscriptionRepository,
                              BillingCalendar calendar,
                              ClockService clock, SubscriptionLocks locks) {
        this.gateway = gateway;
        this.ledger = ledger;
        this.paymentRepository = paymentRepository;
        this.invoiceRepository = invoiceRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.calendar = calendar;
        this.clock = clock;
        this.locks = locks;
    }

    @Override
    public PaymentAttempt charge(Invoice invoice, String idempotencyKey) {
        return locks.withLock(invoice.getSubscriptionId(),
                () -> doCharge(invoice, idempotencyKey, false));
    }

    @Override
    public PaymentAttempt repay(String invoiceId, String idempotencyKey) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BillingException(ErrorCode.INVOICE_NOT_FOUND, "账单不存在: " + invoiceId));
        return locks.withLock(invoice.getSubscriptionId(),
                () -> doCharge(invoice, idempotencyKey, true));
    }

    private PaymentAttempt doCharge(Invoice invoice, String key, boolean manualRepay) {
        if (key == null || key.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "支付必须携带 idempotencyKey");
        }

        // 幂等重放最先判定：即使账单已 PAID，同键重放也返回首次流水，绝不重复扣款、不抛错
        var replay = paymentRepository.findByInvoice(invoice.getInvoiceId()).stream()
                .filter(a -> !a.isRefund() && key.equals(a.getIdempotencyKey()))
                .findFirst();
        if (replay.isPresent()) {
            log.info("[charge] 幂等重放 key={} invoice={} -> 返回首次流水(success={})，不重复扣款",
                    key, invoice.getInvoiceId(), replay.get().isSuccess());
            return replay.get();
        }

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new BillingException(ErrorCode.INVOICE_ALREADY_PAID,
                    "账单已支付，禁止重复扣款: " + invoice.getInvoiceId());
        }
        if (invoice.getStatus() == InvoiceStatus.ANOMALY) {
            throw new BillingException(ErrorCode.INVOICE_ANOMALY,
                    "账单存在计量异常，需先处理（确认零用量/补报）才能收款: " + invoice.getAnomalyReason());
        }
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw new BillingException(ErrorCode.INVOICE_NOT_PAYABLE, "账单已作废: " + invoice.getInvoiceId());
        }
        BigDecimal amount = Money.of(invoice.getAmount());
        if (amount.signum() <= 0) {
            throw new BillingException(ErrorCode.INVALID_AMOUNT,
                    "账单金额非正（" + amount + "），无需扣款；零元账单应直接标记支付");
        }

        int attemptNo = invoice.getAttemptCount() + 1;
        PaymentAttempt attempt = newAttempt(invoice, attemptNo, amount, key, false);
        LocalPaymentGateway.GatewayResult result = gateway.charge(invoice.getInvoiceId(), amount);
        attempt.setSuccess(result.success());
        attempt.setChannelReference(result.channelReference());
        attempt.setFailureReason(result.failureReason());
        paymentRepository.save(attempt);

        invoice.setAttemptCount(attemptNo);
        invoice.setLastAttemptAt(clock.now());

        if (!result.success()) {
            // 仅首次（PENDING）失败落 FAILED；宽限期(PAST_DUE)内的重试失败保持 PAST_DUE，由 BillingService 决定流转
            if (invoice.getStatus() == InvoiceStatus.PENDING) {
                invoice.setStatus(InvoiceStatus.FAILED);
            }
            if (invoice.getFailedAt() == null) {
                invoice.setFailedAt(clock.now());
            }
            invoiceRepository.save(invoice);
            log.warn("[charge] 输入 invoice={} amount={} attemptNo={} key={}；通道判定失败 reason={} -> 账单置 FAILED 待重试",
                    invoice.getInvoiceId(), amount, attemptNo, key, result.failureReason());
            throw new BillingException(ErrorCode.GATEWAY_REJECTED,
                    "支付通道第 " + attemptNo + " 次扣款失败: " + result.failureReason());
        }

        BigDecimal fee = Money.prorate(amount, FEE_RATE);
        Map<Account, BigDecimal> entries = new EnumMap<>(Account.class);
        entries.put(Account.SUBSCRIBER, amount.negate());
        entries.put(Account.PLATFORM, amount.subtract(fee));
        entries.put(Account.CHANNEL, fee);
        ledger.post(manualRepay ? "REPAY" : "CHARGE", invoice.getInvoiceId(), entries, "ledger:" + key);

        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(clock.now());
        invoiceRepository.save(invoice);
        activateAndRollover(invoice);
        log.info("[charge] 输入 invoice={} amount={} attemptNo={} key={}；判定依据：通道成功 ref={} 手续费率={} fee={} -> PAID",
                invoice.getInvoiceId(), amount, attemptNo, key, result.channelReference(), FEE_RATE, fee);
        return attempt;
    }

    @Override
    public PaymentAttempt refund(String invoiceId, BigDecimal rawAmount, String key) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BillingException(ErrorCode.INVOICE_NOT_FOUND, "账单不存在: " + invoiceId));
        return locks.withLock(invoice.getSubscriptionId(), () -> doRefund(invoice, rawAmount, key));
    }

    private PaymentAttempt doRefund(Invoice invoice, BigDecimal rawAmount, String key) {
        if (key == null || key.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "退款必须携带 idempotencyKey");
        }
        if (key.equals(invoice.getRefundIdempotencyKey())) {
            List<PaymentAttempt> prev = paymentRepository.findByInvoice(invoice.getInvoiceId());
            PaymentAttempt hit = prev.stream()
                    .filter(a -> a.isRefund() && key.equals(a.getIdempotencyKey()))
                    .findFirst().orElseThrow();
            log.info("[refund] 幂等重放 key={} -> 返回原退款流水，不重复退款", key);
            return hit;
        }
        BigDecimal amount = Money.of(rawAmount);
        if (amount.signum() <= 0) {
            throw new BillingException(ErrorCode.INVALID_AMOUNT, "退款金额必须为正: " + rawAmount);
        }

        PaymentAttempt attempt = newAttempt(invoice, 0, amount, key, true);
        LocalPaymentGateway.GatewayResult result = gateway.refund(invoice.getInvoiceId(), amount);
        attempt.setSuccess(result.success());
        attempt.setChannelReference(result.channelReference());
        attempt.setFailureReason(result.failureReason());
        paymentRepository.save(attempt);
        if (!result.success()) {
            log.warn("[refund] 通道退款失败 invoice={} reason={}（未记账，可重试）", invoice.getInvoiceId(), result.failureReason());
            throw new BillingException(ErrorCode.GATEWAY_REJECTED, "退款通道失败: " + result.failureReason());
        }

        BigDecimal fee = Money.prorate(amount, FEE_RATE);
        Map<Account, BigDecimal> entries = new EnumMap<>(Account.class);
        entries.put(Account.SUBSCRIBER, amount);
        entries.put(Account.PLATFORM, amount.subtract(fee).negate());
        entries.put(Account.CHANNEL, fee.negate());
        ledger.post("REFUND", invoice.getInvoiceId(), entries, "ledger:" + key);

        invoice.setRefundIdempotencyKey(key);
        // 负额账单（退订/降级差价）本质即退款账单，通道退款成功后标记 REFUNDED
        if (invoice.getAmount().signum() < 0) {
            invoice.setStatus(InvoiceStatus.REFUNDED);
        }
        invoiceRepository.save(invoice);
        log.info("[refund] 输入 invoice={} amount={} key={}；判定依据：通道成功 ref={} 反向冲回 fee={}",
                invoice.getInvoiceId(), amount, key, result.channelReference(), fee);
        return attempt;
    }

    /**
     * 收款成功后的订阅对齐（补缴恢复 + 周期滚动）。
     * 仅对周期账单、且账单周期终点恰为订阅当前周期起点时滚动，重复调用无副作用，
     * 与 BillingService 的同名判断保持一致。
     */
    private void activateAndRollover(Invoice invoice) {
        if (!invoice.isCycle()) {
            return;
        }
        Subscription sub = subscriptionRepository.findById(invoice.getSubscriptionId()).orElse(null);
        if (sub == null) {
            return;
        }
        if (sub.getStatus() == SubscriptionStatus.PAST_DUE || sub.getStatus() == SubscriptionStatus.SUSPENDED) {
            sub.setStatus(SubscriptionStatus.ACTIVE);
        }
        if (invoice.getPeriodStart() != null && invoice.getPeriodStart().equals(sub.getCurrentPeriodStart())) {
            sub.setCurrentPeriodStart(invoice.getPeriodEnd());
            sub.setCurrentPeriodEnd(calendar.nextPeriodStart(invoice.getPeriodEnd(), sub.getAnchorDay()));
            sub.setStatus(SubscriptionStatus.ACTIVE);
            log.info("[post-pay] sub={} 周期账单收讫 -> 恢复 ACTIVE 并滚动周期至 {} ~ {}",
                    sub.getSubscriptionId(), sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd());
        }
        subscriptionRepository.save(sub);
    }

    private PaymentAttempt newAttempt(Invoice invoice, int attemptNo, BigDecimal amount, String key, boolean refund) {
        PaymentAttempt a = new PaymentAttempt();
        a.setId(UUID.randomUUID().toString());
        a.setInvoiceId(invoice.getInvoiceId());
        a.setSubscriptionId(invoice.getSubscriptionId());
        a.setAttemptNo(attemptNo);
        a.setAmount(amount);
        a.setAttemptedAt(clock.now());
        a.setIdempotencyKey(key);
        a.setRefund(refund);
        return a;
    }
}

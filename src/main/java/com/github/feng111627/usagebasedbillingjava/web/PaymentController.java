package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.model.PaymentAttempt;
import com.github.feng111627.usagebasedbillingjava.service.PaymentService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/invoices/{invoiceId}/repay")
    public PaymentAttempt repay(@PathVariable String invoiceId, @RequestBody(required = false) Map<String, Object> req) {
        String key = SubscriptionController.str(req, "idempotencyKey");
        return paymentService.repay(invoiceId, key);
    }

    @PostMapping("/invoices/{invoiceId}/refund")
    public PaymentAttempt refund(@PathVariable String invoiceId, @RequestBody Map<String, Object> req) {
        String key = SubscriptionController.str(req, "idempotencyKey");
        BigDecimal amount = new BigDecimal(SubscriptionController.str(req, "amount"));
        return paymentService.refund(invoiceId, amount, key);
    }
}

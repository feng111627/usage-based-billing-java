package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.model.Invoice;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @PostMapping("/subscriptions/{id}/close-period")
    public Invoice closePeriod(@PathVariable String id) {
        return billingService.closePeriod(id);
    }

    @PostMapping("/subscriptions/{id}/tick")
    public Invoice tick(@PathVariable String id) {
        return billingService.runBillingTick(id);
    }

    @GetMapping("/invoices/{invoiceId}")
    public Invoice getInvoice(@PathVariable String invoiceId) {
        return billingService.getInvoice(invoiceId);
    }

    @GetMapping("/subscriptions/{id}/invoices")
    public Map<String, Object> listInvoices(@PathVariable String id) {
        List<Invoice> invoices = billingService.listInvoices(id);
        return Map.of("subscriptionId", id, "invoices", invoices);
    }

    @PostMapping("/invoices/{invoiceId}/confirm-zero")
    public Invoice confirmZero(@PathVariable String invoiceId, @RequestBody(required = false) Map<String, Object> req) {
        String operator = req == null ? "anonymous" : String.valueOf(req.getOrDefault("operator", "anonymous"));
        return billingService.confirmZeroUsage(invoiceId, operator);
    }

    @PostMapping("/invoices/{invoiceId}/late-usage/{eventId}")
    public Invoice appendLate(@PathVariable String invoiceId, @PathVariable String eventId) {
        return billingService.appendLateUsage(invoiceId, eventId);
    }
}

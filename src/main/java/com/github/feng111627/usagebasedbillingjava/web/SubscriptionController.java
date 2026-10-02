package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.PlanChange;
import com.github.feng111627.usagebasedbillingjava.model.Subscription;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final ClockService clock;

    public SubscriptionController(SubscriptionService subscriptionService, ClockService clock) {
        this.subscriptionService = subscriptionService;
        this.clock = clock;
    }

    @PostMapping
    public Map<String, Object> subscribe(@RequestBody Map<String, Object> req) {
        String id = str(req, "subscriptionId");
        String customer = str(req, "customerId");
        String plan = str(req, "planId");
        int anchor = integer(req, "anchorDay", 1);
        Subscription s = subscriptionService.subscribe(id, customer, plan, anchor);
        return toView(s);
    }

    @PostMapping("/{id}/change")
    public Map<String, Object> change(@PathVariable String id, @RequestBody Map<String, Object> req) {
        PlanChange pc = subscriptionService.changePlan(id, str(req, "targetPlanId"),
                str(req, "idempotencyKey"), instant(req, "effectiveAt", clock.now()));
        return changeView(pc);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestBody Map<String, Object> req) {
        PlanChange pc = subscriptionService.cancel(id, str(req, "idempotencyKey"),
                instant(req, "effectiveAt", clock.now()));
        return changeView(pc);
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable String id, @RequestBody Map<String, Object> req) {
        PlanChange pc = subscriptionService.pause(id, str(req, "idempotencyKey"),
                instant(req, "effectiveAt", clock.now()));
        return changeView(pc);
    }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id, @RequestBody Map<String, Object> req) {
        PlanChange pc = subscriptionService.resume(id, str(req, "idempotencyKey"),
                instant(req, "effectiveAt", clock.now()));
        return changeView(pc);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return toView(subscriptionService.getSubscription(id));
    }

    static Map<String, Object> toView(Subscription s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subscriptionId", s.getSubscriptionId());
        m.put("customerId", s.getCustomerId());
        m.put("currentPlanId", s.getCurrentPlanId());
        m.put("status", s.getStatus());
        m.put("anchorDay", s.getAnchorDay());
        m.put("currentPeriodStart", s.getCurrentPeriodStart());
        m.put("currentPeriodEnd", s.getCurrentPeriodEnd());
        m.put("lastChangeSeq", s.getLastChangeSeq());
        return m;
    }

    static Map<String, Object> changeView(PlanChange pc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", pc.getSeq());
        m.put("subscriptionId", pc.getSubscriptionId());
        m.put("type", pc.getType());
        m.put("fromPlanId", pc.getFromPlanId());
        m.put("toPlanId", pc.getToPlanId());
        m.put("effectiveAt", pc.getEffectiveAt());
        m.put("proration", pc.getProration());
        m.put("invoiceId", pc.getInvoiceId());
        return m;
    }

    static String str(Map<String, Object> req, String key) {
        Object v = req == null ? null : req.get(key);
        if (v == null) {
            throw new BillingException(ErrorCode.VALIDATION, "缺少必填参数: " + key);
        }
        return String.valueOf(v);
    }

    static int integer(Map<String, Object> req, String key, int dflt) {
        Object v = req == null ? null : req.get(key);
        return v == null ? dflt : Integer.parseInt(String.valueOf(v));
    }

    static Instant instant(Map<String, Object> req, String key, Instant dflt) {
        Object v = req == null ? null : req.get(key);
        return v == null ? dflt : Instant.parse(String.valueOf(v));
    }
}

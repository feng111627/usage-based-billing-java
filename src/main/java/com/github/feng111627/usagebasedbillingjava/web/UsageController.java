package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.model.UsageAccumulation;
import com.github.feng111627.usagebasedbillingjava.model.UsageEvent;
import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.UsageService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usageService;
    private final ClockService clock;

    public UsageController(UsageService usageService, ClockService clock) {
        this.usageService = usageService;
        this.clock = clock;
    }

    @PostMapping
    public Map<String, Object> report(@RequestBody Map<String, Object> req) {
        String eventId = SubscriptionController.str(req, "eventId");
        String subscriptionId = SubscriptionController.str(req, "subscriptionId");
        String metric = SubscriptionController.str(req, "metric");
        long qty = Long.parseLong(SubscriptionController.str(req, "quantity"));
        Instant usageAt = req.get("usageAt") == null ? clock.now()
                : Instant.parse(String.valueOf(req.get("usageAt")));
        UsageAccumulation acc = usageService.report(eventId, subscriptionId, metric, qty, usageAt);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subscriptionId", acc.getSubscriptionId());
        m.put("periodKey", acc.getPeriodKey());
        m.put("metric", acc.getMetric());
        m.put("total", acc.getTotal());
        m.put("version", acc.getVersion());
        m.put("missing", acc.isMissing());
        m.put("lateEventIds", acc.getLateEventIds());
        return m;
    }

    @GetMapping("/{subscriptionId}")
    public Map<String, Object> list(@PathVariable String subscriptionId) {
        List<UsageEvent> events = usageService.listEvents(subscriptionId);
        return Map.of("subscriptionId", subscriptionId, "count", events.size(), "events", events);
    }
}

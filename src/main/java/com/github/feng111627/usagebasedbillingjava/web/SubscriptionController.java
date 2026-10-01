package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.service.SubscriptionService;
import com.github.feng111627.usagebasedbillingjava.web.dto.ChangePlanDto;
import com.github.feng111627.usagebasedbillingjava.web.dto.SubscribeDto;
import com.github.feng111627.usagebasedbillingjava.web.dto.SubscriptionView;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public SubscriptionView subscribe(@RequestBody SubscribeDto dto) {
        return Views.of(subscriptionService.subscribe(
                dto.subscriptionId(), dto.customerId(), dto.planId(), dto.effectiveAt()));
    }

    @PostMapping("/{id}/change-plan")
    public SubscriptionView changePlan(@PathVariable String id, @RequestBody ChangePlanDto dto) {
        return Views.of(subscriptionService.changePlan(id, dto.newPlanId(), dto.effectiveAt()));
    }

    @PostMapping("/{id}/cancel")
    public SubscriptionView cancel(@PathVariable String id, @RequestBody(required = false) ChangePlanDto dto) {
        return Views.of(subscriptionService.cancel(id, dto == null ? null : dto.effectiveAt()));
    }

    @GetMapping("/{id}")
    public SubscriptionView get(@PathVariable String id) {
        return Views.of(subscriptionService.get(id));
    }
}

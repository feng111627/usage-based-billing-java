package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.service.ClockService;
import com.github.feng111627.usagebasedbillingjava.service.LedgerService;
import com.github.feng111627.usagebasedbillingjava.service.LocalPaymentGateway;
import com.github.feng111627.usagebasedbillingjava.service.PlanService;
import com.github.feng111627.usagebasedbillingjava.service.ReconciliationService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地演示/测试辅助：拨时钟、注入通道失败、建套餐、对账、查总账。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ClockService clock;
    private final LocalPaymentGateway gateway;
    private final PlanService planService;
    private final ReconciliationService reconciliationService;
    private final LedgerService ledgerService;

    public AdminController(ClockService clock, LocalPaymentGateway gateway, PlanService planService,
                           ReconciliationService reconciliationService, LedgerService ledgerService) {
        this.clock = clock;
        this.gateway = gateway;
        this.planService = planService;
        this.reconciliationService = reconciliationService;
        this.ledgerService = ledgerService;
    }

    @PostMapping("/clock/advance")
    public Map<String, Object> advance(@RequestBody Map<String, Object> req) {
        if (req.get("to") != null) {
            clock.advanceTo(Instant.parse(String.valueOf(req.get("to"))));
        } else {
            clock.advanceSeconds(Long.parseLong(String.valueOf(req.getOrDefault("seconds", 0))));
        }
        return Map.of("now", clock.now());
    }

    @PostMapping("/gateway/fail-next")
    public Map<String, Object> failNext(@RequestBody Map<String, Object> req) {
        int times = Integer.parseInt(String.valueOf(req.getOrDefault("times", 1)));
        gateway.failNext(times);
        return Map.of("failNext", times);
    }

    @GetMapping("/plans")
    public Object plans() {
        return planService.listPlans();
    }

    @PostMapping("/plans")
    public Object createPlan(@RequestBody Map<String, Object> req) {
        return planService.createPlan(
                String.valueOf(req.get("planId")),
                String.valueOf(req.getOrDefault("name", "")),
                new BigDecimal(String.valueOf(req.get("basePrice"))),
                Long.parseLong(String.valueOf(req.getOrDefault("includedUnits", 0))),
                new BigDecimal(String.valueOf(req.getOrDefault("overagePrice", "0"))));
    }

    @GetMapping("/reconcile")
    public Map<String, Object> reconcile() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issues", reconciliationService.reconcileAll());
        m.put("conservationDelta", ledgerService.conservationDelta());
        m.put("balances", ledgerService.balances());
        return m;
    }

    @GetMapping("/ledger")
    public Map<String, Object> ledger() {
        return Map.of("balances", ledgerService.balances(),
                "conservationDelta", ledgerService.conservationDelta(),
                "transactions", ledgerService.all());
    }
}

package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.common.Money;
import com.github.feng111627.usagebasedbillingjava.domain.MetricDef;
import com.github.feng111627.usagebasedbillingjava.domain.Plan;
import com.github.feng111627.usagebasedbillingjava.service.PlanService;
import com.github.feng111627.usagebasedbillingjava.web.dto.MetricDefDto;
import com.github.feng111627.usagebasedbillingjava.web.dto.PlanDto;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @PutMapping("/{id}")
    public PlanDto put(@PathVariable String id, @RequestBody PlanDto dto) {
        Map<String, MetricDef> metrics = new LinkedHashMap<>();
        if (dto.metrics() != null) {
            for (MetricDefDto m : dto.metrics()) {
                metrics.put(m.key(), new MetricDef(m.key(), m.includedUnits(),
                        Money.of(m.overagePriceCents()), m.unit()));
            }
        }
        Plan plan = planService.createPlan(id, dto.name(), Money.of(dto.basePriceCents()), metrics);
        return toDto(plan);
    }

    @GetMapping
    public List<PlanDto> list() {
        return planService.listPlans().stream().map(PlanController::toDto).toList();
    }

    static PlanDto toDto(Plan p) {
        return new PlanDto(p.id(), p.name(), p.basePrice().cents(),
                p.metrics().values().stream()
                        .map(d -> new MetricDefDto(d.key(), d.includedUnits(),
                                d.overagePrice().cents(), d.unit()))
                        .toList());
    }
}

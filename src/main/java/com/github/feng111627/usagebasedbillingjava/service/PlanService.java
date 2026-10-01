package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.common.BillingException;
import com.github.feng111627.usagebasedbillingjava.common.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.common.Money;
import com.github.feng111627.usagebasedbillingjava.domain.MetricDef;
import com.github.feng111627.usagebasedbillingjava.domain.Plan;
import com.github.feng111627.usagebasedbillingjava.repo.PlanRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 套餐管理。
 */
@Service
public class PlanService {

    private final PlanRepository planRepository;

    public PlanService(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public Plan createPlan(String id, String name, Money basePrice, Map<String, MetricDef> metrics) {
        if (id == null || id.isBlank()) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT, "plan id is required");
        }
        if (basePrice == null || basePrice.isNegative()) {
            throw new BillingException(ErrorCode.INVALID_ARGUMENT, "base price must be non-negative");
        }
        Map<String, MetricDef> safe = new LinkedHashMap<>();
        for (MetricDef def : metrics.values()) {
            if (def.includedUnits() < 0) {
                throw new BillingException(ErrorCode.INVALID_ARGUMENT,
                        "included units must be >= 0 for metric " + def.key());
            }
            if (def.overagePrice().isNegative()) {
                throw new BillingException(ErrorCode.INVALID_ARGUMENT,
                        "overage price must be >= 0 for metric " + def.key());
            }
            safe.put(def.key(), def);
        }
        return planRepository.save(new Plan(id, name, basePrice, safe));
    }

    public Plan getPlan(String id) {
        return planRepository.findById(id)
                .orElseThrow(() -> new BillingException(ErrorCode.PLAN_NOT_FOUND,
                        "plan not found: " + id));
    }

    public List<Plan> listPlans() {
        return planRepository.findAll();
    }
}

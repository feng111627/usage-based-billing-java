package com.github.feng111627.usagebasedbillingjava.service.impl;

import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.model.Plan;
import com.github.feng111627.usagebasedbillingjava.repository.Repositories;
import com.github.feng111627.usagebasedbillingjava.service.PlanService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class PlanServiceImpl implements PlanService {

    private final Repositories.PlanRepository planRepository;

    public PlanServiceImpl(Repositories.PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    @Override
    public Plan createPlan(String planId, String name, BigDecimal basePrice, long includedUnits,
                           BigDecimal overagePrice) {
        if (planId == null || planId.isBlank()) {
            throw new BillingException(ErrorCode.VALIDATION, "planId 不能为空");
        }
        if (basePrice == null || basePrice.signum() < 0) {
            throw new BillingException(ErrorCode.VALIDATION, "basePrice 不能为空或负数");
        }
        if (includedUnits < 0) {
            throw new BillingException(ErrorCode.VALIDATION, "includedUnits 不能为负");
        }
        if (overagePrice == null || overagePrice.signum() < 0) {
            throw new BillingException(ErrorCode.VALIDATION, "overagePrice 不能为空或负数");
        }
        if (planRepository.findById(planId).isPresent()) {
            throw new BillingException(ErrorCode.DUPLICATE_REQUEST, "套餐已存在: " + planId);
        }
        return planRepository.save(new Plan(planId, name, basePrice.stripTrailingZeros(),
                includedUnits, overagePrice.stripTrailingZeros()));
    }

    @Override
    public Plan getPlan(String planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new BillingException(ErrorCode.PLAN_NOT_FOUND, "套餐不存在: " + planId));
    }

    @Override
    public List<Plan> listPlans() {
        return planRepository.findAll();
    }
}

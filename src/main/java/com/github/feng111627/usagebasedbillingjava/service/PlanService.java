package com.github.feng111627.usagebasedbillingjava.service;

import com.github.feng111627.usagebasedbillingjava.model.Plan;

import java.math.BigDecimal;
import java.util.List;

public interface PlanService {
    Plan createPlan(String planId, String name, BigDecimal basePrice, long includedUnits, BigDecimal overagePrice);
    Plan getPlan(String planId);
    List<Plan> listPlans();
}

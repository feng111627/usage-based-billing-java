package com.github.feng111627.usagebasedbillingjava.repo;

import com.github.feng111627.usagebasedbillingjava.domain.Plan;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 套餐内存仓储（线程安全）。
 */
@Repository
public class PlanRepository {

    private final ConcurrentHashMap<String, Plan> plans = new ConcurrentHashMap<>();

    public Plan save(Plan plan) {
        plans.put(plan.id(), plan);
        return plan;
    }

    public Optional<Plan> findById(String id) {
        return Optional.ofNullable(plans.get(id));
    }

    public List<Plan> findAll() {
        return plans.values().stream().toList();
    }
}

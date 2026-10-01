package com.github.feng111627.usagebasedbillingjava.model;

import java.math.BigDecimal;

/**
 * 套餐：周期基础价 basePrice，配额 includedUnits（超出部分 overagePrice/单位）。
 */
public class Plan {
    private String planId;
    private String name;
    private BigDecimal basePrice;
    private long includedUnits;
    private BigDecimal overagePrice;

    public Plan() {
    }

    public Plan(String planId, String name, BigDecimal basePrice, long includedUnits, BigDecimal overagePrice) {
        this.planId = planId;
        this.name = name;
        this.basePrice = basePrice;
        this.includedUnits = includedUnits;
        this.overagePrice = overagePrice;
    }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getBasePrice() { return basePrice; }
    public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }
    public long getIncludedUnits() { return includedUnits; }
    public void setIncludedUnits(long includedUnits) { this.includedUnits = includedUnits; }
    public BigDecimal getOveragePrice() { return overagePrice; }
    public void setOveragePrice(BigDecimal overagePrice) { this.overagePrice = overagePrice; }
}

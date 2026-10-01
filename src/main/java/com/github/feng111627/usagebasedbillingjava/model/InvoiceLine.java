package com.github.feng111627.usagebasedbillingjava.model;

import java.math.BigDecimal;

/**
 * 账单明细行。basis 记录判定依据（用量、配额、比例等），保证结论可解释。
 */
public class InvoiceLine {
    private LineKind kind;
    private String description;
    private BigDecimal amount;
    private String basis;

    public InvoiceLine() {
    }

    public InvoiceLine(LineKind kind, String description, BigDecimal amount, String basis) {
        this.kind = kind;
        this.description = description;
        this.amount = amount;
        this.basis = basis;
    }

    public LineKind getKind() { return kind; }
    public void setKind(LineKind kind) { this.kind = kind; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getBasis() { return basis; }
    public void setBasis(String basis) { this.basis = basis; }
}

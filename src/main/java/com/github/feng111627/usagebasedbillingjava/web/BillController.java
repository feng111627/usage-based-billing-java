package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.domain.MetricUsage;
import com.github.feng111627.usagebasedbillingjava.service.BillingService;
import com.github.feng111627.usagebasedbillingjava.service.PaymentService;
import com.github.feng111627.usagebasedbillingjava.service.ReconciliationService;
import com.github.feng111627.usagebasedbillingjava.web.dto.AdjustmentDto;
import com.github.feng111627.usagebasedbillingjava.web.dto.BillView;
import com.github.feng111627.usagebasedbillingjava.web.dto.DunningDto;
import com.github.feng111627.usagebasedbillingjava.web.dto.IssueBillDto;
import com.github.feng111627.usagebasedbillingjava.web.dto.MetricUsageView;
import com.github.feng111627.usagebasedbillingjava.web.dto.PaymentAttemptView;
import com.github.feng111627.usagebasedbillingjava.web.dto.RefundDto;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions/{id}/bills")
public class BillController {

    private final BillingService billingService;
    private final PaymentService paymentService;
    private final ReconciliationService reconciliationService;

    public BillController(BillingService billingService,
                          PaymentService paymentService,
                          ReconciliationService reconciliationService) {
        this.billingService = billingService;
        this.paymentService = paymentService;
        this.reconciliationService = reconciliationService;
    }

    @PostMapping
    public BillView issue(@PathVariable String id, @RequestBody(required = false) IssueBillDto dto) {
        int periodIndex = dto == null || dto.periodIndex() == null ? 0 : dto.periodIndex();
        return Views.of(billingService.issueBill(id, periodIndex));
    }

    /** 账期用量汇总（对账用）。 */
    @GetMapping("/usage-summary")
    public Map<String, MetricUsageView> usageSummary(@PathVariable String id,
                                                     @RequestParam(defaultValue = "0") int periodIndex) {
        Map<String, MetricUsageView> out = new LinkedHashMap<>();
        for (Map.Entry<String, MetricUsage> en : billingService.summarizeUsage(id, periodIndex).entrySet()) {
            out.put(en.getKey(), Views.of(en.getKey(), en.getValue()));
        }
        return out;
    }

    /** 关闭账期后的迟到数据调账（幂等）。 */
    @PostMapping("/adjustments")
    public BillView adjust(@PathVariable String id,
                           @RequestParam(defaultValue = "0") int periodIndex,
                           @RequestBody AdjustmentDto dto) {
        return Views.of(billingService.applyLateAdjustment(id, periodIndex, dto.adjustmentKey()));
    }

    @GetMapping
    public List<BillView> list(@PathVariable String id) {
        return billingService.listBills(id).stream().map(Views::of).toList();
    }

    @GetMapping("/{billId}")
    public BillView get(@PathVariable String id, @PathVariable String billId) {
        return Views.of(billingService.getBill(billId));
    }

    @PostMapping("/{billId}/collect")
    public PaymentAttemptView collect(@PathVariable String id, @PathVariable String billId) {
        return Views.of(paymentService.collect(billId));
    }

    @PostMapping("/{billId}/evaluate-dunning")
    public BillView evaluateDunning(@PathVariable String id, @PathVariable String billId,
                                    @RequestBody(required = false) DunningDto dto) {
        return Views.of(paymentService.evaluateDunning(billId, dto == null ? null : dto.at()));
    }

    @PostMapping("/{billId}/pay-overdue")
    public PaymentAttemptView payOverdue(@PathVariable String id, @PathVariable String billId) {
        return Views.of(paymentService.payOverdue(billId));
    }

    @PostMapping("/{billId}/refund")
    public BillView refund(@PathVariable String id, @PathVariable String billId, @RequestBody RefundDto dto) {
        return Views.of(paymentService.refund(billId, dto.amountCents(), dto.reason()));
    }

    @GetMapping("/{billId}/attempts")
    public List<PaymentAttemptView> attempts(@PathVariable String id, @PathVariable String billId) {
        return paymentService.attemptsOf(billId).stream().map(Views::of).toList();
    }

    @PostMapping("/{billId}/verify")
    public ReconciliationService.BillReconciliation verify(@PathVariable String id,
                                                            @PathVariable String billId) {
        return reconciliationService.verifyBill(billId);
    }
}

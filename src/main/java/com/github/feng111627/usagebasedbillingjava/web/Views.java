package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.domain.Bill;
import com.github.feng111627.usagebasedbillingjava.domain.BillLine;
import com.github.feng111627.usagebasedbillingjava.domain.MetricUsage;
import com.github.feng111627.usagebasedbillingjava.domain.PaymentAttempt;
import com.github.feng111627.usagebasedbillingjava.domain.Subscription;
import com.github.feng111627.usagebasedbillingjava.domain.SubscriptionEvent;
import com.github.feng111627.usagebasedbillingjava.domain.UsageReport;
import com.github.feng111627.usagebasedbillingjava.engine.Timeline;
import com.github.feng111627.usagebasedbillingjava.web.dto.BillLineView;
import com.github.feng111627.usagebasedbillingjava.web.dto.BillView;
import com.github.feng111627.usagebasedbillingjava.web.dto.EventView;
import com.github.feng111627.usagebasedbillingjava.web.dto.MetricUsageView;
import com.github.feng111627.usagebasedbillingjava.web.dto.PaymentAttemptView;
import com.github.feng111627.usagebasedbillingjava.web.dto.SubscriptionView;
import com.github.feng111627.usagebasedbillingjava.web.dto.UsageView;

import java.util.List;

/** 领域对象 -> API 视图的纯函数映射。 */
final class Views {

    private Views() {
    }

    static SubscriptionView of(Subscription s) {
        List<EventView> events = s.getEvents().stream()
                .map(e -> new EventView(e.seq(), e.type().name(), e.planId(),
                        e.effectiveAt(), e.recordedAt()))
                .toList();
        var status = Timeline.currentStatus(s);
        var plan = s.getEvents().isEmpty() ? null
                : Timeline.planIdAt(s, s.orderedEvents().get(s.orderedEvents().size() - 1).effectiveAt());
        return new SubscriptionView(s.getId(), s.getCustomerId(),
                status == null ? "NEW" : status.name(), plan, s.getAnchoredAt(), events);
    }

    static UsageView of(UsageReport r, boolean duplicate) {
        return new UsageView(r.idempotencyKey(), r.metricKey(), r.quantity(),
                r.windowStart(), r.windowEnd(), r.meteringComplete(), r.recordedAt(), duplicate);
    }

    static BillView of(Bill b) {
        List<BillLineView> lines = b.getLines().stream()
                .map(Views::of).toList();
        return new BillView(b.getId(), b.getPeriodKey(), b.getStatus().name(), b.getAmount().cents(),
                b.getIssuedAt(), b.getDueAt(), b.getAttemptCount(),
                b.getInvoiceGroupId(), b.getAdjustmentKey(), lines);
    }

    static BillLineView of(BillLine l) {
        return new BillLineView(l.kind().name(), l.metricKey(), l.description(), l.amount());
    }

    static MetricUsageView of(String metric, MetricUsage u) {
        return new MetricUsageView(metric, u.totalQuantity(), u.includedUnits(),
                u.overageUnits(), u.overageAmount(), u.missingMetering(), u.overQuota());
    }

    static PaymentAttemptView of(PaymentAttempt a) {
        return new PaymentAttemptView(a.reference(), a.billId(), a.amountCents(), a.refund(),
                a.result().name(), a.failureReason(), a.attemptedAt());
    }
}

package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.domain.UsageReport;
import com.github.feng111627.usagebasedbillingjava.service.UsageService;
import com.github.feng111627.usagebasedbillingjava.web.dto.UsageReportDto;
import com.github.feng111627.usagebasedbillingjava.web.dto.UsageView;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/subscriptions/{id}/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    @PostMapping
    public UsageView report(@PathVariable String id, @RequestBody UsageReportDto dto) {
        UsageService.ReportResult result = usageService.report(dto.idempotencyKey(), id, dto.metricKey(),
                dto.quantity(), dto.windowStart(), dto.windowEnd(), dto.meteringComplete());
        return Views.of(result.report(), result.duplicate());
    }

    @GetMapping
    public List<UsageView> list(@PathVariable String id) {
        return usageService.listReports(id).stream().map(r -> Views.of(r, false)).toList();
    }
}

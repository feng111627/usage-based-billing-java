package com.github.feng111627.usagebasedbillingjava.web;

import com.github.feng111627.usagebasedbillingjava.domain.LedgerAccount;
import com.github.feng111627.usagebasedbillingjava.repo.LedgerRepository;
import com.github.feng111627.usagebasedbillingjava.service.ReconciliationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 平台级运维接口：全局守恒校验、分类账余额查看。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ReconciliationService reconciliationService;
    private final LedgerRepository ledgerRepository;

    public AdminController(ReconciliationService reconciliationService,
                           LedgerRepository ledgerRepository) {
        this.reconciliationService = reconciliationService;
        this.ledgerRepository = ledgerRepository;
    }

    @PostMapping("/verify-conservation")
    public Map<String, Long> verifyConservation() {
        return reconciliationService.verifyConservation();
    }

    @GetMapping("/ledger/accounts")
    public List<LedgerAccount> accounts() {
        return ledgerRepository.accounts();
    }
}

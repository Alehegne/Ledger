package com.example.Ledger.reconciliation.controller;

import com.example.Ledger.reconciliation.dto.ReconciliationHistoryPageResponse;
import com.example.Ledger.reconciliation.dto.ReconciliationRunResponse;
import com.example.Ledger.reconciliation.service.FinancialReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private final FinancialReconciliationService reconciliationService;

    public ReconciliationController(FinancialReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReconciliationRunResponse> runReconciliation() {
        return ResponseEntity.ok(reconciliationService.runReconciliation());
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReconciliationHistoryPageResponse> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(reconciliationService.getHistory(page, size));
    }

    @GetMapping("/{runId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReconciliationRunResponse> getRun(@PathVariable Long runId) {
        return ResponseEntity.ok(reconciliationService.getRun(runId));
    }
}

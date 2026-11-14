package com.example.Ledger.reconciliation.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReconciliationCheckResult(
        String code,
        String name,
        boolean passed,
        boolean blocking,
        BigDecimal variance,
        String summary,
        List<ReconciliationDiscrepancy> discrepancies
) {
}

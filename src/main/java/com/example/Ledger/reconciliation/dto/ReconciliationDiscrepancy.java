package com.example.Ledger.reconciliation.dto;

import java.math.BigDecimal;

public record ReconciliationDiscrepancy(
        String entityType,
        String entityId,
        String reference,
        BigDecimal expectedAmount,
        BigDecimal actualAmount,
        BigDecimal variance,
        String detail
) {
}

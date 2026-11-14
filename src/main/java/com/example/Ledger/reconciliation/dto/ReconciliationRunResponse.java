package com.example.Ledger.reconciliation.dto;

import com.example.Ledger.reconciliation.model.ReconciliationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReconciliationRunResponse(
        Long id,
        Long organizationId,
        ReconciliationStatus status,
        BigDecimal totalPostedDebits,
        BigDecimal totalPostedCredits,
        BigDecimal globalVariance,
        long postedEntryCount,
        long accountCount,
        int checksPassed,
        int checksFailed,
        Long triggeredByUserId,
        String triggeredByName,
        List<ReconciliationCheckResult> checks,
        Instant createdAt
) {
}

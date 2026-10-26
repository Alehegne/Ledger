package com.example.Ledger.dashboard.dto;

import com.example.Ledger.journal.dto.JournalEntryResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardOverviewResponse(
        LocalDate asOfDate,
        BigDecimal totalAssets,
        BigDecimal totalRevenue,
        BigDecimal totalExpenses,
        int draftsCount,
        int postedCount,
        List<JournalEntryResponse> recentEntries
) {
}

package com.example.Ledger.report.controller;

import com.example.Ledger.report.dto.GeneralLedgerAccountResponse;
import com.example.Ledger.report.dto.TrialBalanceResponse;
import com.example.Ledger.report.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST controller for accounting reports.
 * Exposes General Ledger and Trial Balance endpoints with filtering and pagination.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * GET /api/reports/general-ledger
     *
     * Returns a General Ledger report for a specific account with running balances.
     * Supports date filtering and pagination.
     *
     * Query Parameters:
     * - accountId (required): ID of the account to report on
     * - fromDate (optional): Start date for filtering (inclusive), format: YYYY-MM-DD
     * - toDate (optional): End date for filtering (inclusive), format: YYYY-MM-DD
     * - page (optional, default: 0): Page number for pagination (0-indexed)
     * - size (optional, default: 20): Page size (1-100)
     *
     * @param accountId The account to filter on (required)
     * @param fromDate Optional start date (inclusive)
     * @param toDate Optional end date (inclusive)
     * @param page Page number (0-indexed)
     * @param size Page size
     * @return General Ledger report with running balances
     */
    @GetMapping("/general-ledger")
    @PreAuthorize("hasAnyRole('ADMIN', 'ACCOUNTANT', 'VIEWER')")
    public ResponseEntity<GeneralLedgerAccountResponse> getGeneralLedger(
            @RequestParam(required = true) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        GeneralLedgerAccountResponse response = reportService.getGeneralLedger(accountId, fromDate, toDate, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/reports/trial-balance
     *
     * Returns a Trial Balance report as of a specific date.
     * Includes all accounts in the organization, even those with zero balances.
     * Verifies debit and credit totals are equal.
     *
     * Query Parameters:
     * - asOfDate (optional, default: today): As-of date for the report, format: YYYY-MM-DD
     *
     * @param asOfDate Optional as-of date; defaults to today if not provided
     * @return Trial Balance report with account balances and totals
     */
    @GetMapping("/trial-balance")
    @PreAuthorize("hasAnyRole('ADMIN', 'ACCOUNTANT', 'VIEWER')")
    public ResponseEntity<TrialBalanceResponse> getTrialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate
    ) {
        TrialBalanceResponse response = reportService.getTrialBalance(asOfDate);
        return ResponseEntity.ok(response);
    }
}

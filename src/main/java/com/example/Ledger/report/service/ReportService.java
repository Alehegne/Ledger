package com.example.Ledger.report.service;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.EntrySide;
import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.journal.model.JournalLine;
import com.example.Ledger.journal.repository.JournalLineRepository;
import com.example.Ledger.journal.repository.JournalLineRepository.PostedAccountTotals;
import com.example.Ledger.report.dto.GeneralLedgerAccountResponse;
import com.example.Ledger.report.dto.GeneralLedgerLineResponse;
import com.example.Ledger.report.dto.TrialBalanceAccountLine;
import com.example.Ledger.report.dto.TrialBalanceResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for generating accounting reports: General Ledger and Trial Balance.
 * Handles running balance calculations, date filtering, pagination, and account aggregation.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReportService {

    private static final LocalDate MIN_REPORT_DATE = LocalDate.of(1, 1, 1);
    private static final LocalDate MAX_REPORT_DATE = LocalDate.of(9999, 12, 31);

    private final AccountRepository accountRepository;
    private final JournalLineRepository journalLineRepository;
    private final CurrentUserProvider currentUserProvider;

    public ReportService(
            AccountRepository accountRepository,
            JournalLineRepository journalLineRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.accountRepository = accountRepository;
        this.journalLineRepository = journalLineRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Generates a General Ledger report for a specific account with optional date filtering and pagination.
     *
     * @param accountId Optional account ID. If null, returns an error.
     * @param fromDate  Optional start date (inclusive)
     * @param toDate    Optional end date (inclusive)
     * @param page      Page number (0-indexed)
     * @param size      Page size
     * @return General Ledger report for the account
     */
    public GeneralLedgerAccountResponse getGeneralLedger(
            Long accountId,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size
    ) {
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        // Account filtering is required for now per SRS implied behavior
        if (accountId == null) {
            throw new BusinessException("ACCOUNT_ID_REQUIRED", "Account ID is required for general ledger");
        }

        // Validate date range
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new BusinessException("INVALID_DATE_RANGE", "fromDate cannot be after toDate");
        }

        // Load and verify account belongs to current organization
        Account account = accountRepository.findByIdAndOrganizationId(accountId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        // Normalize pagination
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);

        // Fetch paginated lines for the date range
        Pageable pageable = PageRequest.of(normalizedPage, normalizedSize);

        LocalDate effectiveFromDate = fromDate != null ? fromDate : MIN_REPORT_DATE;
        LocalDate effectiveToDate = toDate != null ? toDate : MAX_REPORT_DATE;

        Page<JournalLine> pageResult = journalLineRepository.findPostedLinesForAccountInDateRange(
                organizationId,
                accountId,
            effectiveFromDate,
            effectiveToDate,
                pageable
        );

        // Balance through the day before fromDate (or zero when no fromDate filter)
        BigDecimal openingBalance = calculateOpeningBalance(
                account,
                organizationId,
                accountId,
                fromDate
        );

        // Final balance through the full filtered window
        PostedAccountTotals filteredTotals = journalLineRepository.sumPostedDebitsAndCreditsForAccountInDateRange(
            organizationId,
            accountId,
            effectiveFromDate,
            effectiveToDate
        );
        BigDecimal closingBalance = applyTotals(openingBalance, filteredTotals, account.getAccountType());

        // Compute page-start running balance by removing all newer rows than the page anchor.
        BigDecimal pageRunningStart = closingBalance;
        if (!pageResult.getContent().isEmpty()) {
            JournalLine anchor = pageResult.getContent().getFirst();
            PostedAccountTotals newerTotals = journalLineRepository.sumPostedDebitsAndCreditsForAccountNewerThanLine(
                organizationId,
                accountId,
                effectiveFromDate,
                effectiveToDate,
                anchor.getJournalEntry().getTransactionDate(),
                anchor.getJournalEntry().getId(),
                anchor.getId()
            );
            pageRunningStart = pageRunningStart.subtract(signedDelta(newerTotals, account.getAccountType()));
        }

        // Convert to response DTOs with running balance
        List<GeneralLedgerLineResponse> lineResponses = new ArrayList<>();
        BigDecimal runningBalance = pageRunningStart;

        for (JournalLine line : pageResult.getContent()) {
            lineResponses.add(new GeneralLedgerLineResponse(
                    line.getJournalEntry().getId(),
                    line.getJournalEntry().getTransactionDate(),
                    line.getJournalEntry().getDescription(),
                    line.getJournalEntry().getReference(),
                    line.getSide(),
                    line.getAmount(),
                    runningBalance
            ));
            runningBalance = reverseBalance(runningBalance, line, account.getAccountType());
        }

        int effectiveTotalPages = Math.max(pageResult.getTotalPages(), 1);

        return new GeneralLedgerAccountResponse(
                account.getId(),
                account.getAccountCode(),
                account.getName(),
                account.getAccountType(),
                lineResponses,
                normalizedPage,
                normalizedSize,
                pageResult.getTotalElements(),
                effectiveTotalPages,
                openingBalance,
                closingBalance
        );
    }

    /**
     * Generates a Trial Balance report as of a specific date (or current if not specified).
     * Includes all accounts for the organization, even those with zero balances.
     *
     * @param asOfDate Optional as-of date; defaults to today
     * @return Trial Balance report with all accounts and totals
     */
    public TrialBalanceResponse getTrialBalance(LocalDate asOfDate) {
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        if (asOfDate == null) {
            asOfDate = LocalDate.now();
        }

        // Fetch all accounts in the organization
        List<Account> accounts = accountRepository.findByOrganizationIdOrderByAccountCodeAsc(organizationId);

        List<TrialBalanceAccountLine> lines = new ArrayList<>();
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        for (Account account : accounts) {
            // Get posted totals through the as-of date
            PostedAccountTotals totals = journalLineRepository.sumPostedDebitsAndCreditsForAccountThroughDate(
                    organizationId,
                    account.getId(),
                    asOfDate
            );

            BigDecimal accountDebits = totals != null ? nullSafeDecimal(totals.totalDebits()) : BigDecimal.ZERO;
            BigDecimal accountCredits = totals != null ? nullSafeDecimal(totals.totalCredits()) : BigDecimal.ZERO;

            // Determine which column the balance appears in based on account type
            BigDecimal debitBalance = BigDecimal.ZERO;
            BigDecimal creditBalance = BigDecimal.ZERO;

            switch (account.getAccountType()) {
                case ASSET, EXPENSE -> {
                    if (accountDebits.compareTo(accountCredits) >= 0) {
                        debitBalance = accountDebits.subtract(accountCredits);
                    } else {
                        creditBalance = accountCredits.subtract(accountDebits);
                    }
                }
                case LIABILITY, EQUITY, REVENUE -> {
                    if (accountCredits.compareTo(accountDebits) >= 0) {
                        creditBalance = accountCredits.subtract(accountDebits);
                    } else {
                        debitBalance = accountDebits.subtract(accountCredits);
                    }
                }
            }

            lines.add(new TrialBalanceAccountLine(
                    account.getId(),
                    account.getAccountCode(),
                    account.getName(),
                    account.getAccountType(),
                    debitBalance,
                    creditBalance
            ));

            totalDebits = totalDebits.add(debitBalance);
            totalCredits = totalCredits.add(creditBalance);
        }

        // Check if balanced (with tolerance for rounding errors in comparison)
        boolean isBalanced = totalDebits.compareTo(totalCredits) == 0;

        return new TrialBalanceResponse(
                asOfDate,
                lines,
                totalDebits,
                totalCredits,
                isBalanced
        );
    }

    /**
     * Calculates the opening balance for an account at the start of a date range.
     * If no date range is specified, opening balance is 0.
     * If from date is specified, opening balance is the total through the day before.
     */
    private BigDecimal calculateOpeningBalance(Account account, Long organizationId, Long accountId, LocalDate fromDate) {
        if (fromDate == null) {
            return BigDecimal.ZERO;
        }

        LocalDate dayBefore = fromDate.minusDays(1);
        PostedAccountTotals totals = journalLineRepository.sumPostedDebitsAndCreditsForAccountThroughDate(
                organizationId,
                accountId,
                dayBefore
        );

        BigDecimal totalDebits = totals != null ? nullSafeDecimal(totals.totalDebits()) : BigDecimal.ZERO;
        BigDecimal totalCredits = totals != null ? nullSafeDecimal(totals.totalCredits()) : BigDecimal.ZERO;

        return switch (account.getAccountType()) {
            case ASSET, EXPENSE -> totalDebits.subtract(totalCredits);
            case LIABILITY, EQUITY, REVENUE -> totalCredits.subtract(totalDebits);
        };
    }

    private BigDecimal reverseBalance(BigDecimal runningBalance, JournalLine line, AccountType accountType) {
        BigDecimal amount = line.getAmount();

        return switch (accountType) {
            case ASSET, EXPENSE -> {
                if (line.getSide() == EntrySide.DEBIT) {
                    yield runningBalance.subtract(amount);
                } else {
                    yield runningBalance.add(amount);
                }
            }
            case LIABILITY, EQUITY, REVENUE -> {
                if (line.getSide() == EntrySide.CREDIT) {
                    yield runningBalance.subtract(amount);
                } else {
                    yield runningBalance.add(amount);
                }
            }
        };
    }

    private BigDecimal applyTotals(BigDecimal startBalance, PostedAccountTotals totals, AccountType accountType) {
        return startBalance.add(signedDelta(totals, accountType));
    }

    private BigDecimal signedDelta(PostedAccountTotals totals, AccountType accountType) {
        BigDecimal debits = totals == null ? BigDecimal.ZERO : nullSafeDecimal(totals.totalDebits());
        BigDecimal credits = totals == null ? BigDecimal.ZERO : nullSafeDecimal(totals.totalCredits());

        return switch (accountType) {
            case ASSET, EXPENSE -> debits.subtract(credits);
            case LIABILITY, EQUITY, REVENUE -> credits.subtract(debits);
        };
    }

    /**
     * Safely converts a value to BigDecimal, handling nulls.
     */
    private BigDecimal nullSafeDecimal(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}

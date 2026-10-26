package com.example.Ledger.dashboard.service;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.dashboard.dto.DashboardOverviewResponse;
import com.example.Ledger.journal.dto.JournalEntryResponse;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.journal.repository.JournalLineRepository;
import com.example.Ledger.journal.repository.JournalLineRepository.PostedAccountTotals;
import com.example.Ledger.journal.service.JournalEntryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DashboardOverviewService {

    private static final int RECENT_ENTRIES_LIMIT = 5;

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final JournalEntryService journalEntryService;
    private final CurrentUserProvider currentUserProvider;

    public DashboardOverviewService(
            AccountRepository accountRepository,
            JournalEntryRepository journalEntryRepository,
            JournalLineRepository journalLineRepository,
            JournalEntryService journalEntryService,
            CurrentUserProvider currentUserProvider
    ) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.journalEntryService = journalEntryService;
        this.currentUserProvider = currentUserProvider;
    }

    public DashboardOverviewResponse getOverview() {
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        LocalDate asOfDate = LocalDate.now();

        List<Account> accounts = accountRepository.findByOrganizationIdOrderByAccountCodeAsc(organizationId);

        BigDecimal totalAssets = accounts.stream()
                .filter(account -> account.getAccountType() == AccountType.ASSET)
                .map(account -> balanceForAccount(organizationId, account))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalRevenue = accounts.stream()
                .filter(account -> account.getAccountType() == AccountType.REVENUE)
                .map(account -> balanceForAccount(organizationId, account))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalExpenses = accounts.stream()
                .filter(account -> account.getAccountType() == AccountType.EXPENSE)
                .map(account -> balanceForAccount(organizationId, account).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int draftsCount = (int) journalEntryRepository.countByOrganizationIdAndStatus(organizationId, JournalEntryStatus.DRAFT);
        int postedCount = (int) journalEntryRepository.countByOrganizationIdAndStatus(organizationId, JournalEntryStatus.POSTED);

        List<JournalEntryResponse> recentEntries = journalEntryRepository
                .findByOrganizationIdOrderByTransactionDateDescIdDesc(organizationId, PageRequest.of(0, RECENT_ENTRIES_LIMIT))
                .stream()
                .map(journalEntryService::mapEntry)
                .toList();

        return new DashboardOverviewResponse(asOfDate, totalAssets, totalRevenue, totalExpenses, draftsCount, postedCount, recentEntries);
    }

    private BigDecimal balanceForAccount(Long organizationId, Account account) {
        PostedAccountTotals totals = journalLineRepository.sumPostedDebitsAndCreditsForAccount(organizationId, account.getId());
        BigDecimal debits = totals == null ? BigDecimal.ZERO : totals.totalDebits();
        BigDecimal credits = totals == null ? BigDecimal.ZERO : totals.totalCredits();

        return switch (account.getAccountType()) {
            case ASSET, EXPENSE -> debits.subtract(credits);
            case LIABILITY, EQUITY, REVENUE -> credits.subtract(debits);
        };
    }
}

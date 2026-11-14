package com.example.Ledger.reconciliation.service;

import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.audit.service.AuditLogService;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.AuditLogAction;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.exception.ForbiddenException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.common.observability.LedgerMetricsService;
import com.example.Ledger.common.security.CurrentUserProvider;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.reconciliation.dto.ReconciliationCheckResult;
import com.example.Ledger.reconciliation.dto.ReconciliationDiscrepancy;
import com.example.Ledger.reconciliation.dto.ReconciliationHistoryPageResponse;
import com.example.Ledger.reconciliation.dto.ReconciliationRunResponse;
import com.example.Ledger.reconciliation.model.ReconciliationRun;
import com.example.Ledger.reconciliation.model.ReconciliationStatus;
import com.example.Ledger.reconciliation.repository.ReconciliationRunRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class FinancialReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(FinancialReconciliationService.class);

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final TypeReference<List<ReconciliationCheckResult>> CHECKS_TYPE = new TypeReference<>() {};

    private final ReconciliationRunRepository reconciliationRunRepository;
    private final OrganizationRepository organizationRepository;
    private final LedgerUserRepository userRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AuditLogService auditLogService;
    private final LedgerMetricsService ledgerMetricsService;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public FinancialReconciliationService(
            ReconciliationRunRepository reconciliationRunRepository,
            OrganizationRepository organizationRepository,
            LedgerUserRepository userRepository,
            AccountRepository accountRepository,
            JournalEntryRepository journalEntryRepository,
            AuditLogService auditLogService,
            LedgerMetricsService ledgerMetricsService,
            CurrentUserProvider currentUserProvider,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.reconciliationRunRepository = reconciliationRunRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.auditLogService = auditLogService;
        this.ledgerMetricsService = ledgerMetricsService;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ReconciliationRunResponse runReconciliation() {
        Timer.Sample sample = ledgerMetricsService.startTransactionTimer();
        try {
            currentUserProvider.requireRole(Role.ADMIN);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        Long userId = currentUserProvider.getCurrentUserId();

        Organization organization = requireTenantOrganization(organizationId);
        LedgerUser triggeredBy = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        long postedEntryCount = journalEntryRepository.countByOrganizationIdAndStatus(organizationId, JournalEntryStatus.POSTED);
        long accountCount = accountRepository.countByOrganizationId(organizationId);

        PostedTotals globalTotals = fetchGlobalPostedTotals(organizationId);
        BigDecimal totalPostedDebits = scale4(globalTotals.totalDebits());
        BigDecimal totalPostedCredits = scale4(globalTotals.totalCredits());
        BigDecimal globalVariance = scale4(totalPostedDebits.subtract(totalPostedCredits).abs());

        ReconciliationCheckResult globalCheck = evaluateGlobalDebitCreditBalance(
                organization,
                postedEntryCount,
                totalPostedDebits,
                totalPostedCredits,
                globalVariance
        );
        ReconciliationCheckResult perEntryCheck = evaluatePerEntryBalance(organizationId, postedEntryCount);

        Map<Long, AccountSnapshot> accountsById = fetchAccountSnapshots(organizationId);
        Map<Long, PostedTotals> postedTotalsByAccount = fetchPostedTotalsByAccount(organizationId);

        ReconciliationCheckResult trialBalanceAndGlCheck = evaluateTrialBalanceAndGlParity(
                organizationId,
                accountsById,
                postedTotalsByAccount,
                totalPostedDebits,
                totalPostedCredits
        );
        ReconciliationCheckResult hierarchyCheck = evaluateAccountHierarchyIntegrity(
                organizationId,
                accountsById,
                postedTotalsByAccount
        );

        List<ReconciliationCheckResult> checks = List.of(
                globalCheck,
                perEntryCheck,
                trialBalanceAndGlCheck,
                hierarchyCheck
        );

        int checksPassed = (int) checks.stream().filter(ReconciliationCheckResult::passed).count();
        int checksFailed = checks.size() - checksPassed;
        ReconciliationStatus status = checksFailed == 0 ? ReconciliationStatus.PASSED : ReconciliationStatus.FAILED;

        ReconciliationRun run = new ReconciliationRun();
        run.setOrganization(organization);
        run.setTriggeredByUser(triggeredBy);
        run.setStatus(status);
        run.setTotalPostedDebits(totalPostedDebits);
        run.setTotalPostedCredits(totalPostedCredits);
        run.setGlobalVariance(globalVariance);
        run.setPostedEntryCount(postedEntryCount);
        run.setAccountCount(accountCount);
        run.setChecksPassed(checksPassed);
        run.setChecksFailed(checksFailed);
        run.setChecksJson(serializeChecks(checks));

        ReconciliationRun saved = reconciliationRunRepository.save(run);

        auditLogService.recordAudit(
                organization,
                triggeredBy,
                AuditLogAction.RECONCILIATION_RUN,
                "RECONCILIATION_RUN",
                saved.getId().toString(),
                Map.of(
                        "status", status.name(),
                        "globalVariance", globalVariance.toPlainString(),
                        "postedEntryCount", postedEntryCount,
                        "accountCount", accountCount,
                        "checksPassed", checksPassed,
                        "checksFailed", checksFailed
                )
        );

        ledgerMetricsService.recordReconciliationStatusAfterCommit(status);
        log.info("Completed reconciliation run id={} status={} globalVariance={} checksPassed={} checksFailed={}",
                saved.getId(), status, globalVariance, checksPassed, checksFailed);

        return toResponse(saved, checks);
        } finally {
            ledgerMetricsService.stopTransactionTimer(sample);
        }
    }

    @Transactional(readOnly = true)
    public ReconciliationHistoryPageResponse getHistory(int page, int size) {
        currentUserProvider.requireRole(Role.ADMIN);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        requireTenantOrganization(organizationId);

        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(normalizedPage, normalizedSize);

        Page<ReconciliationRun> runsPage = reconciliationRunRepository
                .findByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId, pageable);

        List<ReconciliationRunResponse> items = runsPage.getContent().stream()
                .map(run -> toResponse(run, deserializeChecks(run.getChecksJson())))
                .toList();

        return new ReconciliationHistoryPageResponse(
                normalizedPage,
                normalizedSize,
                runsPage.getTotalElements(),
                Math.max(runsPage.getTotalPages(), 1),
                items
        );
    }

    @Transactional(readOnly = true)
    public ReconciliationRunResponse getRun(Long runId) {
        currentUserProvider.requireRole(Role.ADMIN);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        requireTenantOrganization(organizationId);

        ReconciliationRun run = reconciliationRunRepository.findByIdAndOrganizationId(runId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reconciliation run not found"));

        return toResponse(run, deserializeChecks(run.getChecksJson()));
    }

    private Organization requireTenantOrganization(Long organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        if (organization.getKind() == OrganizationKind.PLATFORM) {
            throw new ForbiddenException("Platform organization is not accessible through tenant endpoints");
        }
        return organization;
    }

    private PostedTotals fetchGlobalPostedTotals(Long organizationId) {
        PostedTotals totals = jdbcTemplate.queryForObject(
                """
                SELECT
                    COALESCE(SUM(CASE WHEN jl.side = 'DEBIT' THEN jl.amount ELSE 0 END), 0) AS total_debits,
                    COALESCE(SUM(CASE WHEN jl.side = 'CREDIT' THEN jl.amount ELSE 0 END), 0) AS total_credits
                FROM journal_line jl
                JOIN journal_entry je ON je.id = jl.journal_entry_id
                WHERE je.organization_id = ?
                  AND je.status = 'POSTED'
                """,
                (rs, rowNum) -> new PostedTotals(
                        scale4(rs.getBigDecimal("total_debits")),
                        scale4(rs.getBigDecimal("total_credits"))
                ),
                organizationId
        );
        return totals != null ? totals : new PostedTotals(ZERO_MONEY, ZERO_MONEY);
    }

    private ReconciliationCheckResult evaluateGlobalDebitCreditBalance(
            Organization organization,
            long postedEntryCount,
            BigDecimal totalPostedDebits,
            BigDecimal totalPostedCredits,
            BigDecimal globalVariance
    ) {
        boolean passed = globalVariance.compareTo(BigDecimal.ZERO) == 0;
        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        if (!passed) {
            discrepancies.add(new ReconciliationDiscrepancy(
                    "ORGANIZATION",
                    organization.getId().toString(),
                    organization.getName(),
                    totalPostedDebits,
                    totalPostedCredits,
                    globalVariance,
                    "Global posted debits (%s) do not equal global posted credits (%s)."
                            .formatted(totalPostedDebits.toPlainString(), totalPostedCredits.toPlainString())
            ));
        }

        String summary = passed
                ? "All %d posted journal entries balance globally (Debits = Credits = %s)."
                        .formatted(postedEntryCount, totalPostedDebits.toPlainString())
                : "Global ledger out of balance by %s across %d posted journal entries."
                        .formatted(globalVariance.toPlainString(), postedEntryCount);

        return new ReconciliationCheckResult(
                "GLOBAL_DEBIT_CREDIT_BALANCE",
                "Global Posted Debit/Credit Balance",
                passed,
                true,
                globalVariance,
                summary,
                discrepancies
        );
    }

    private ReconciliationCheckResult evaluatePerEntryBalance(Long organizationId, long postedEntryCount) {
        List<ReconciliationDiscrepancy> discrepancies = jdbcTemplate.query(
                """
                SELECT
                    je.id,
                    je.reference,
                    je.description,
                    COALESCE(SUM(CASE WHEN jl.side = 'DEBIT' THEN jl.amount ELSE 0 END), 0) AS total_debits,
                    COALESCE(SUM(CASE WHEN jl.side = 'CREDIT' THEN jl.amount ELSE 0 END), 0) AS total_credits,
                    COUNT(jl.id) AS line_count,
                    COALESCE(SUM(CASE WHEN jl.side = 'DEBIT' THEN 1 ELSE 0 END), 0) AS debit_lines,
                    COALESCE(SUM(CASE WHEN jl.side = 'CREDIT' THEN 1 ELSE 0 END), 0) AS credit_lines
                FROM journal_entry je
                LEFT JOIN journal_line jl ON jl.journal_entry_id = je.id
                WHERE je.organization_id = ?
                  AND je.status = 'POSTED'
                GROUP BY je.id, je.reference, je.description
                HAVING COALESCE(SUM(CASE WHEN jl.side = 'DEBIT' THEN jl.amount ELSE 0 END), 0)
                    <> COALESCE(SUM(CASE WHEN jl.side = 'CREDIT' THEN jl.amount ELSE 0 END), 0)
                    OR COUNT(jl.id) < 2
                    OR COALESCE(SUM(CASE WHEN jl.side = 'DEBIT' THEN 1 ELSE 0 END), 0) = 0
                    OR COALESCE(SUM(CASE WHEN jl.side = 'CREDIT' THEN 1 ELSE 0 END), 0) = 0
                ORDER BY je.id ASC
                """,
                (rs, rowNum) -> {
                    Long entryId = rs.getLong("id");
                    String reference = rs.getString("reference");
                    String description = rs.getString("description");
                    BigDecimal debits = scale4(rs.getBigDecimal("total_debits"));
                    BigDecimal credits = scale4(rs.getBigDecimal("total_credits"));
                    long lineCount = rs.getLong("line_count");
                    long debitLines = rs.getLong("debit_lines");
                    long creditLines = rs.getLong("credit_lines");
                    BigDecimal variance = scale4(debits.subtract(credits).abs());

                    String displayRef = (reference != null && !reference.isBlank())
                            ? reference
                            : (description != null ? description : "JE-" + entryId);

                    List<String> reasons = new ArrayList<>();
                    if (debits.compareTo(credits) != 0) {
                        reasons.add("debits=%s != credits=%s".formatted(debits.toPlainString(), credits.toPlainString()));
                    }
                    if (lineCount < 2 || debitLines == 0 || creditLines == 0) {
                        reasons.add("invalid double-entry structure (lines=%d, debitLines=%d, creditLines=%d)"
                                .formatted(lineCount, debitLines, creditLines));
                    }

                    return new ReconciliationDiscrepancy(
                            "JOURNAL_ENTRY",
                            entryId.toString(),
                            displayRef,
                            debits,
                            credits,
                            variance,
                            "Posted journal entry #%d violates double-entry invariant: %s."
                                    .formatted(entryId, String.join("; ", reasons))
                    );
                },
                organizationId
        );

        BigDecimal totalEntryVariance = scale4(discrepancies.stream()
                .map(ReconciliationDiscrepancy::variance)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        boolean passed = discrepancies.isEmpty();
        String summary = passed
                ? "Verified all %d posted journal entries individually maintain Debits = Credits with >= 2 opposing lines."
                        .formatted(postedEntryCount)
                : "Detected %d posted journal %s violating per-entry double-entry balance (combined variance %s)."
                        .formatted(
                                discrepancies.size(),
                                discrepancies.size() == 1 ? "entry" : "entries",
                                totalEntryVariance.toPlainString()
                        );

        return new ReconciliationCheckResult(
                "PER_ENTRY_BALANCE",
                "Per-Entry Double-Entry Balance",
                passed,
                true,
                totalEntryVariance,
                summary,
                discrepancies
        );
    }

    private Map<Long, AccountSnapshot> fetchAccountSnapshots(Long organizationId) {
        List<AccountSnapshot> rows = jdbcTemplate.query(
                """
                SELECT id, organization_id, parent_account_id, account_code, name, account_type
                FROM account
                WHERE organization_id = ?
                ORDER BY account_code ASC, id ASC
                """,
                (rs, rowNum) -> new AccountSnapshot(
                        rs.getLong("id"),
                        rs.getLong("organization_id"),
                        rs.getObject("parent_account_id", Long.class),
                        rs.getString("account_code"),
                        rs.getString("name"),
                        AccountType.valueOf(rs.getString("account_type"))
                ),
                organizationId
        );

        Map<Long, AccountSnapshot> map = new LinkedHashMap<>();
        for (AccountSnapshot row : rows) {
            map.put(row.id(), row);
        }
        return map;
    }

    private Map<Long, PostedTotals> fetchPostedTotalsByAccount(Long organizationId) {
        List<Map.Entry<Long, PostedTotals>> rows = jdbcTemplate.query(
                """
                SELECT
                    jl.account_id,
                    COALESCE(SUM(CASE WHEN jl.side = 'DEBIT' THEN jl.amount ELSE 0 END), 0) AS total_debits,
                    COALESCE(SUM(CASE WHEN jl.side = 'CREDIT' THEN jl.amount ELSE 0 END), 0) AS total_credits
                FROM journal_line jl
                JOIN journal_entry je ON je.id = jl.journal_entry_id
                WHERE je.organization_id = ?
                  AND je.status = 'POSTED'
                GROUP BY jl.account_id
                """,
                (rs, rowNum) -> Map.entry(
                        rs.getLong("account_id"),
                        new PostedTotals(
                                scale4(rs.getBigDecimal("total_debits")),
                                scale4(rs.getBigDecimal("total_credits"))
                        )
                ),
                organizationId
        );

        Map<Long, PostedTotals> totalsMap = new HashMap<>();
        for (Map.Entry<Long, PostedTotals> entry : rows) {
            totalsMap.put(entry.getKey(), entry.getValue());
        }
        return totalsMap;
    }

    private ReconciliationCheckResult evaluateTrialBalanceAndGlParity(
            Long organizationId,
            Map<Long, AccountSnapshot> accountsById,
            Map<Long, PostedTotals> postedTotalsByAccount,
            BigDecimal globalPostedDebits,
            BigDecimal globalPostedCredits
    ) {
        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();

        // 1. Verify no journal line in this organization references a missing or cross-organization account
        List<ReconciliationDiscrepancy> crossOrgOrMissingLines = jdbcTemplate.query(
                """
                SELECT
                    jl.id AS line_id,
                    jl.journal_entry_id,
                    jl.account_id,
                    jl.amount,
                    a.organization_id AS account_org_id
                FROM journal_line jl
                JOIN journal_entry je ON je.id = jl.journal_entry_id
                LEFT JOIN account a ON a.id = jl.account_id
                WHERE je.organization_id = ?
                  AND (a.id IS NULL OR a.organization_id <> je.organization_id)
                ORDER BY jl.id ASC
                """,
                (rs, rowNum) -> {
                    Long lineId = rs.getLong("line_id");
                    Long entryId = rs.getLong("journal_entry_id");
                    Long accountId = rs.getLong("account_id");
                    BigDecimal amount = scale4(rs.getBigDecimal("amount"));
                    Long accountOrgId = rs.getObject("account_org_id", Long.class);
                    return new ReconciliationDiscrepancy(
                            "JOURNAL_LINE",
                            lineId.toString(),
                            "JE-" + entryId,
                            ZERO_MONEY,
                            amount,
                            amount,
                            accountOrgId == null
                                    ? "Journal line #%d references missing account #%d.".formatted(lineId, accountId)
                                    : "Journal line #%d in org #%d references cross-tenant account #%d (org #%d)."
                                            .formatted(lineId, organizationId, accountId, accountOrgId)
                    );
                },
                organizationId
        );
        discrepancies.addAll(crossOrgOrMissingLines);

        // 2. Verify no journal line in this organization has a non-positive amount
        List<ReconciliationDiscrepancy> invalidAmountLines = jdbcTemplate.query(
                """
                SELECT jl.id AS line_id, jl.journal_entry_id, jl.amount
                FROM journal_line jl
                JOIN journal_entry je ON je.id = jl.journal_entry_id
                WHERE je.organization_id = ?
                  AND jl.amount <= 0
                ORDER BY jl.id ASC
                """,
                (rs, rowNum) -> {
                    Long lineId = rs.getLong("line_id");
                    Long entryId = rs.getLong("journal_entry_id");
                    BigDecimal amount = scale4(rs.getBigDecimal("amount"));
                    return new ReconciliationDiscrepancy(
                            "JOURNAL_LINE",
                            lineId.toString(),
                            "JE-" + entryId,
                            ZERO_MONEY,
                            amount,
                            amount.abs(),
                            "Journal line #%d has non-positive amount (%s).".formatted(lineId, amount.toPlainString())
                    );
                },
                organizationId
        );
        discrepancies.addAll(invalidAmountLines);

        // 3. Recompute Trial Balance totals from per-account GL sums and verify net parity against global posted ledger
        BigDecimal tbTotalDebits = ZERO_MONEY;
        BigDecimal tbTotalCredits = ZERO_MONEY;
        BigDecimal glAggregatedNetDebitMinusCredit = ZERO_MONEY;

        for (AccountSnapshot account : accountsById.values()) {
            PostedTotals totals = postedTotalsByAccount.getOrDefault(account.id(), new PostedTotals(ZERO_MONEY, ZERO_MONEY));
            BigDecimal accountDebits = totals.totalDebits();
            BigDecimal accountCredits = totals.totalCredits();

            BigDecimal debitBalance = ZERO_MONEY;
            BigDecimal creditBalance = ZERO_MONEY;

            switch (account.accountType()) {
                case ASSET, EXPENSE -> {
                    if (accountDebits.compareTo(accountCredits) >= 0) {
                        debitBalance = scale4(accountDebits.subtract(accountCredits));
                    } else {
                        creditBalance = scale4(accountCredits.subtract(accountDebits));
                    }
                }
                case LIABILITY, EQUITY, REVENUE -> {
                    if (accountCredits.compareTo(accountDebits) >= 0) {
                        creditBalance = scale4(accountCredits.subtract(accountDebits));
                    } else {
                        debitBalance = scale4(accountDebits.subtract(accountCredits));
                    }
                }
            }

            // Parity check between Trial Balance net position and independent GL signed delta
            BigDecimal tbAccountNet = scale4(debitBalance.subtract(creditBalance));
            BigDecimal glAccountNet = scale4(accountDebits.subtract(accountCredits));
            if (tbAccountNet.compareTo(glAccountNet) != 0) {
                BigDecimal accountVariance = scale4(tbAccountNet.subtract(glAccountNet).abs());
                discrepancies.add(new ReconciliationDiscrepancy(
                        "ACCOUNT",
                        account.id().toString(),
                        account.accountCode(),
                        glAccountNet,
                        tbAccountNet,
                        accountVariance,
                        "Trial Balance net (%s) diverges from GL closing net (%s) for account %s."
                                .formatted(tbAccountNet.toPlainString(), glAccountNet.toPlainString(), account.accountCode())
                ));
            }

            tbTotalDebits = scale4(tbTotalDebits.add(debitBalance));
            tbTotalCredits = scale4(tbTotalCredits.add(creditBalance));
            glAggregatedNetDebitMinusCredit = scale4(glAggregatedNetDebitMinusCredit.add(glAccountNet));
        }

        BigDecimal tbNetVariance = scale4(tbTotalDebits.subtract(tbTotalCredits).abs());
        BigDecimal globalNet = scale4(globalPostedDebits.subtract(globalPostedCredits));
        BigDecimal glVsGlobalVariance = scale4(glAggregatedNetDebitMinusCredit.subtract(globalNet).abs());

        if (tbNetVariance.compareTo(BigDecimal.ZERO) != 0) {
            discrepancies.add(new ReconciliationDiscrepancy(
                    "TRIAL_BALANCE",
                    organizationId.toString(),
                    "TRIAL_BALANCE",
                    tbTotalDebits,
                    tbTotalCredits,
                    tbNetVariance,
                    "Trial Balance total debits (%s) do not equal total credits (%s)."
                            .formatted(tbTotalDebits.toPlainString(), tbTotalCredits.toPlainString())
            ));
        }

        if (glVsGlobalVariance.compareTo(BigDecimal.ZERO) != 0) {
            discrepancies.add(new ReconciliationDiscrepancy(
                    "GENERAL_LEDGER",
                    organizationId.toString(),
                    "GL_TOTALS",
                    globalNet,
                    glAggregatedNetDebitMinusCredit,
                    glVsGlobalVariance,
                    "Account-level GL aggregation (%s) differs from global posted journal net (%s)."
                            .formatted(glAggregatedNetDebitMinusCredit.toPlainString(), globalNet.toPlainString())
            ));
        }

        BigDecimal maxVariance = discrepancies.stream()
                .map(ReconciliationDiscrepancy::variance)
                .max(BigDecimal::compareTo)
                .map(FinancialReconciliationService::scale4)
                .orElse(ZERO_MONEY);

        boolean passed = discrepancies.isEmpty();
        String summary = passed
                ? "Trial Balance (%s DR / %s CR across %d accounts) ties out identically to General Ledger totals with zero cross-tenant or negative lines."
                        .formatted(tbTotalDebits.toPlainString(), tbTotalCredits.toPlainString(), accountsById.size())
                : "Detected %d Trial Balance / GL integrity %s (max variance %s)."
                        .formatted(
                                discrepancies.size(),
                                discrepancies.size() == 1 ? "discrepancy" : "discrepancies",
                                maxVariance.toPlainString()
                        );

        return new ReconciliationCheckResult(
                "TRIAL_BALANCE_AND_GL_PARITY",
                "Trial Balance & GL Integrity Parity",
                passed,
                true,
                maxVariance,
                summary,
                discrepancies
        );
    }

    private ReconciliationCheckResult evaluateAccountHierarchyIntegrity(
            Long organizationId,
            Map<Long, AccountSnapshot> accountsById,
            Map<Long, PostedTotals> postedTotalsByAccount
    ) {
        List<ReconciliationDiscrepancy> structuralErrors = new ArrayList<>();
        Map<Long, List<AccountSnapshot>> childrenByParentId = new LinkedHashMap<>();

        for (AccountSnapshot account : accountsById.values()) {
            Long parentId = account.parentAccountId();
            if (parentId == null) {
                continue;
            }

            if (parentId.equals(account.id())) {
                structuralErrors.add(new ReconciliationDiscrepancy(
                        "ACCOUNT",
                        account.id().toString(),
                        account.accountCode(),
                        ZERO_MONEY,
                        ZERO_MONEY,
                        BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP),
                        "Account %s (%s) references itself as parent_account_id."
                                .formatted(account.accountCode(), account.name())
                ));
                continue;
            }

            if (!accountsById.containsKey(parentId)) {
                Long externalOrgId = findAccountOrganizationId(parentId);
                String reason = externalOrgId == null
                        ? "Account %s references non-existent parent account #%d.".formatted(account.accountCode(), parentId)
                        : "Account %s in org #%d references cross-tenant parent account #%d (org #%d)."
                                .formatted(account.accountCode(), organizationId, parentId, externalOrgId);
                structuralErrors.add(new ReconciliationDiscrepancy(
                        "ACCOUNT",
                        account.id().toString(),
                        account.accountCode(),
                        ZERO_MONEY,
                        ZERO_MONEY,
                        BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP),
                        reason
                ));
                continue;
            }

            childrenByParentId.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(account);

            // Detect cycles starting from this account
            Set<Long> visited = new HashSet<>();
            visited.add(account.id());
            Long cursor = parentId;
            while (cursor != null) {
                if (!visited.add(cursor)) {
                    structuralErrors.add(new ReconciliationDiscrepancy(
                            "ACCOUNT",
                            account.id().toString(),
                            account.accountCode(),
                            ZERO_MONEY,
                            ZERO_MONEY,
                            BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP),
                            "Circular parent-account hierarchy detected involving account %s (#%d)."
                                    .formatted(account.accountCode(), account.id())
                    ));
                    break;
                }
                AccountSnapshot parentAccount = accountsById.get(cursor);
                cursor = parentAccount != null ? parentAccount.parentAccountId() : null;
            }
        }

        boolean passed = structuralErrors.isEmpty();
        if (!passed) {
            BigDecimal errorCountVariance = scale4(BigDecimal.valueOf(structuralErrors.size()));
            return new ReconciliationCheckResult(
                    "ACCOUNT_HIERARCHY_INTEGRITY",
                    "Account Hierarchy & Rollup Integrity",
                    false,
                    true,
                    errorCountVariance,
                    "Detected %d structural hierarchy error(s) (cycles, self-links, or cross-tenant parent references)."
                            .formatted(structuralErrors.size()),
                    structuralErrors
            );
        }

        // Compute informative parent vs direct children rollup report without failing valid CoAs
        List<String> rollupNotes = new ArrayList<>();
        int parentGroupsWithDirectPostings = 0;

        for (Map.Entry<Long, List<AccountSnapshot>> entry : childrenByParentId.entrySet()) {
            AccountSnapshot parent = accountsById.get(entry.getKey());
            BigDecimal parentDirectBalance = computeNormalBalance(parent, postedTotalsByAccount.get(parent.id()));
            BigDecimal childrenRollupBalance = ZERO_MONEY;
            for (AccountSnapshot child : entry.getValue()) {
                childrenRollupBalance = scale4(childrenRollupBalance.add(
                        computeNormalBalance(child, postedTotalsByAccount.get(child.id()))
                ));
            }
            if (parentDirectBalance.compareTo(BigDecimal.ZERO) != 0) {
                parentGroupsWithDirectPostings++;
            }
            rollupNotes.add("%s (%s): direct=%s, children(%d)=%s"
                    .formatted(
                            parent.accountCode(),
                            parent.name(),
                            parentDirectBalance.toPlainString(),
                            entry.getValue().size(),
                            childrenRollupBalance.toPlainString()
                    ));
        }

        String summary;
        if (childrenByParentId.isEmpty()) {
            summary = "Evaluated %d accounts with flat hierarchy; no cycles or invalid parent links detected."
                    .formatted(accountsById.size());
        } else {
            summary = "Evaluated %d accounts across %d parent group(s)%s. Informative rollups: %s."
                    .formatted(
                            accountsById.size(),
                            childrenByParentId.size(),
                            parentGroupsWithDirectPostings > 0
                                    ? " (%d with direct parent postings permitted)".formatted(parentGroupsWithDirectPostings)
                                    : "",
                            String.join("; ", rollupNotes)
                    );
        }

        return new ReconciliationCheckResult(
                "ACCOUNT_HIERARCHY_INTEGRITY",
                "Account Hierarchy & Rollup Integrity",
                true,
                false,
                ZERO_MONEY,
                summary,
                List.of()
        );
    }

    private Long findAccountOrganizationId(Long accountId) {
        List<Long> orgIds = jdbcTemplate.query(
                "SELECT organization_id FROM account WHERE id = ?",
                (rs, rowNum) -> rs.getLong("organization_id"),
                accountId
        );
        return orgIds.isEmpty() ? null : orgIds.getFirst();
    }

    private BigDecimal computeNormalBalance(AccountSnapshot account, PostedTotals totals) {
        if (totals == null) {
            return ZERO_MONEY;
        }
        return switch (account.accountType()) {
            case ASSET, EXPENSE -> scale4(totals.totalDebits().subtract(totals.totalCredits()));
            case LIABILITY, EQUITY, REVENUE -> scale4(totals.totalCredits().subtract(totals.totalDebits()));
        };
    }

    private String serializeChecks(List<ReconciliationCheckResult> checks) {
        try {
            return objectMapper.writeValueAsString(checks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reconciliation check results", e);
        }
    }

    private List<ReconciliationCheckResult> deserializeChecks(String checksJson) {
        if (checksJson == null || checksJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(checksJson, CHECKS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize reconciliation check results", e);
        }
    }

    private ReconciliationRunResponse toResponse(ReconciliationRun run, List<ReconciliationCheckResult> checks) {
        LedgerUser triggeredBy = run.getTriggeredByUser();
        return new ReconciliationRunResponse(
                run.getId(),
                run.getOrganization().getId(),
                run.getStatus(),
                scale4(run.getTotalPostedDebits()),
                scale4(run.getTotalPostedCredits()),
                scale4(run.getGlobalVariance()),
                run.getPostedEntryCount(),
                run.getAccountCount(),
                run.getChecksPassed(),
                run.getChecksFailed(),
                triggeredBy != null ? triggeredBy.getId() : null,
                triggeredBy != null ? triggeredBy.getFullName() : null,
                checks,
                run.getCreatedAt()
        );
    }

    private static BigDecimal scale4(BigDecimal value) {
        return value == null ? ZERO_MONEY : value.setScale(4, RoundingMode.HALF_UP);
    }

    private record PostedTotals(BigDecimal totalDebits, BigDecimal totalCredits) {
    }

    private record AccountSnapshot(
            Long id,
            Long organizationId,
            Long parentAccountId,
            String accountCode,
            String name,
            AccountType accountType
    ) {
    }
}

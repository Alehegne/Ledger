package com.example.Ledger.journal.service;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.audit.service.AuditLogService;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AuditLogAction;
import com.example.Ledger.common.enums.EntrySide;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.common.observability.LedgerMetricsService;
import com.example.Ledger.common.security.CurrentUserProvider;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.Ledger.journal.dto.JournalEntryPageResponse;
import com.example.Ledger.journal.dto.JournalEntryRequest;
import com.example.Ledger.journal.dto.JournalEntryResponse;
import com.example.Ledger.journal.dto.JournalEntryUpdateRequest;
import com.example.Ledger.journal.dto.JournalLineRequest;
import com.example.Ledger.journal.dto.JournalLineResponse;
import com.example.Ledger.journal.model.JournalEntry;
import com.example.Ledger.journal.model.JournalLine;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.outbox.service.OutboxEventService;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class JournalEntryService {

    private static final Logger log = LoggerFactory.getLogger(JournalEntryService.class);

    private final JournalEntryRepository journalEntryRepository;
    private final AccountRepository accountRepository;
    private final OrganizationRepository organizationRepository;
    private final LedgerUserRepository userRepository;
    private final AuditLogService auditLogService;
    private final OutboxEventService outboxEventService;
    private final LedgerMetricsService ledgerMetricsService;
    private final CurrentUserProvider currentUserProvider;

    public JournalEntryService(
            JournalEntryRepository journalEntryRepository,
            AccountRepository accountRepository,
            OrganizationRepository organizationRepository,
            LedgerUserRepository userRepository,
            AuditLogService auditLogService,
            OutboxEventService outboxEventService,
            LedgerMetricsService ledgerMetricsService,
            CurrentUserProvider currentUserProvider
    ) {
        this.journalEntryRepository = journalEntryRepository;
        this.accountRepository = accountRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.outboxEventService = outboxEventService;
        this.ledgerMetricsService = ledgerMetricsService;
        this.currentUserProvider = currentUserProvider;
    }

    public JournalEntryResponse createDraft(JournalEntryRequest request) {
        currentUserProvider.requireRole(Role.ADMIN, Role.ACCOUNTANT);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        validateDateRange(request.transactionDate(), request.transactionDate());

        JournalEntry entry = new JournalEntry();
        entry.setOrganization(loadOrganization(organizationId));
        entry.setCreatedByUser(loadCurrentUser());
        entry.setTransactionDate(request.transactionDate());
        entry.setDescription(cleanDescription(request.description()));
        entry.setReference(cleanReference(request.reference()));
        entry.setStatus(JournalEntryStatus.DRAFT);

        List<JournalLineRequest> lines = request.lines();
        validateLines(lines);
        Map<Long, Account> accounts = loadAccounts(lines, organizationId);
        applyLines(entry, lines, accounts);

        JournalEntry saved = journalEntryRepository.save(entry);
        auditLogService.recordAudit(
                AuditLogAction.JOURNAL_CREATED,
                "JOURNAL_ENTRY",
                String.valueOf(saved.getId()),
                Map.of("status", saved.getStatus().name())
        );
        return mapEntry(saved);
    }

    @Transactional(readOnly = true)
    public JournalEntryPageResponse listEntries(
            JournalEntryStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            Long accountId,
            int page,
            int size
    ) {
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        validateDateRange(fromDate, toDate);

        final JournalEntryStatus effectiveStatus = currentUserProvider.getCurrentUser().getRole() == Role.VIEWER
                ? JournalEntryStatus.POSTED
                : status;

        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);

        Specification<JournalEntry> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organization").get("id"), organizationId));

            if (effectiveStatus != null) {
                predicates.add(cb.equal(root.get("status"), effectiveStatus));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("transactionDate"), toDate));
            }
            if (accountId != null) {
                Join<Object, Object> linesJoin = root.join("lines", JoinType.INNER);
                predicates.add(cb.equal(linesJoin.get("account").get("id"), accountId));
                query.distinct(true);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(
                normalizedPage,
                normalizedSize,
                Sort.by(Sort.Order.desc("transactionDate"), Sort.Order.desc("id"))
        );

        Page<JournalEntry> result = journalEntryRepository.findAll(spec, pageable);
        List<JournalEntryResponse> items = result.getContent().stream()
                .map(this::mapEntry)
                .toList();

        return new JournalEntryPageResponse(
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                items
        );
    }

    @Transactional(readOnly = true)
    public JournalEntryResponse getEntry(Long entryId) {
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        JournalEntry entry = loadEntry(entryId, organizationId);

        if (currentUserProvider.getCurrentUser().getRole() == Role.VIEWER
                && entry.getStatus() == JournalEntryStatus.DRAFT) {
            throw new ResourceNotFoundException("Journal entry not found");
        }

        return mapEntry(entry);
    }

    public JournalEntryResponse updateDraft(Long entryId, JournalEntryUpdateRequest request) {
        currentUserProvider.requireRole(Role.ADMIN, Role.ACCOUNTANT);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        JournalEntry entry = loadEntry(entryId, organizationId);
        ensureDraft(entry);

        if (request.transactionDate() != null) {
            entry.setTransactionDate(request.transactionDate());
        }
        if (request.description() != null) {
            entry.setDescription(cleanDescription(request.description()));
        }
        if (request.reference() != null) {
            entry.setReference(cleanReference(request.reference()));
        }
        if (request.lines() != null) {
            validateLines(request.lines());
            Map<Long, Account> accounts = loadAccounts(request.lines(), organizationId);
            applyLines(entry, request.lines(), accounts);
        }

        JournalEntry saved = journalEntryRepository.save(entry);
        auditLogService.recordAudit(
                AuditLogAction.JOURNAL_UPDATED,
                "JOURNAL_ENTRY",
                String.valueOf(saved.getId()),
                Map.of("status", saved.getStatus().name())
        );
        return mapEntry(saved);
    }

    public void deleteDraft(Long entryId) {
        currentUserProvider.requireRole(Role.ADMIN, Role.ACCOUNTANT);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        JournalEntry entry = loadEntry(entryId, organizationId);
        ensureDraft(entry);
        auditLogService.recordAudit(
                "JOURNAL_ENTRY_DELETED",
                "JOURNAL_ENTRY",
                String.valueOf(entry.getId()),
                Map.of("status", entry.getStatus().name())
        );
        journalEntryRepository.delete(entry);
    }

    public JournalEntryResponse postEntry(Long entryId) {
        Timer.Sample sample = ledgerMetricsService.startTransactionTimer();
        try {
            currentUserProvider.requireRole(Role.ADMIN, Role.ACCOUNTANT);

            Long organizationId = currentUserProvider.getCurrentOrganizationId();
            JournalEntry entry = journalEntryRepository.findByIdAndOrganizationIdForUpdate(entryId, organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found"));

            ensureDraft(entry);
            validatePostedEntry(entry, organizationId);

            entry.setStatus(JournalEntryStatus.POSTED);
            entry.setPostedByUser(loadCurrentUser());
            entry.setPostedAt(Instant.now());

            JournalEntry saved = journalEntryRepository.save(entry);
            auditLogService.recordAudit(
                    AuditLogAction.JOURNAL_POSTED,
                    "JOURNAL_ENTRY",
                    String.valueOf(saved.getId()),
                    Map.of("status", saved.getStatus().name())
            );

            BigDecimal totalDebits = sumBySide(saved, EntrySide.DEBIT);
            BigDecimal totalCredits = sumBySide(saved, EntrySide.CREDIT);

            Map<String, Object> outboxPayload = new LinkedHashMap<>();
            outboxPayload.put("entryId", saved.getId());
            outboxPayload.put("organizationId", saved.getOrganization().getId());
            outboxPayload.put("transactionDate", saved.getTransactionDate().toString());
            outboxPayload.put("reference", saved.getReference());
            outboxPayload.put("description", saved.getDescription());
            outboxPayload.put("totalDebits", totalDebits);
            outboxPayload.put("totalCredits", totalCredits);
            outboxPayload.put("postedByUserId", saved.getPostedByUser() == null ? null : saved.getPostedByUser().getId());
            outboxPayload.put("postedAt", saved.getPostedAt().toString());
            outboxPayload.put("lineCount", saved.getLines().size());
            outboxPayload.put("isReversal", false);

            outboxEventService.recordEvent(
                    saved.getOrganization(),
                    "JOURNAL_POSTED",
                    "JOURNAL_ENTRY",
                    String.valueOf(saved.getId()),
                    outboxPayload
            );
            ledgerMetricsService.recordJournalPostedAfterCommit();
            log.info("Posted journal entry id={} with totalDebits={} lines={}",
                    saved.getId(), totalDebits, saved.getLines().size());
            return mapEntry(saved);
        } finally {
            ledgerMetricsService.stopTransactionTimer(sample);
        }
    }

    private JournalEntry loadEntry(Long entryId, Long organizationId) {
        return journalEntryRepository.findByIdAndOrganizationId(entryId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found"));
    }

    private Organization loadOrganization(Long organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }

    private LedgerUser loadCurrentUser() {
        return userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void ensureDraft(JournalEntry entry) {
        if (entry.getStatus() != JournalEntryStatus.DRAFT) {
            throw new BusinessException("JOURNAL_ENTRY_NOT_DRAFT", "Only draft journal entries can be modified");
        }
    }

    private void validateDateRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new BusinessException("INVALID_DATE_RANGE", "fromDate cannot be after toDate");
        }
    }

    private String cleanDescription(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException("INVALID_JOURNAL_DESCRIPTION", "Description is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 500) {
            throw new BusinessException("INVALID_JOURNAL_DESCRIPTION", "Description must be at most 500 characters");
        }
        return trimmed;
    }

    private String cleanReference(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 120) {
            throw new BusinessException("INVALID_JOURNAL_REFERENCE", "Reference must be at most 120 characters");
        }
        return trimmed;
    }

    private void validateLines(List<JournalLineRequest> lines) {
        if (lines == null || lines.size() < 2) {
            throw new BusinessException("JOURNAL_ENTRY_MIN_LINES", "Journal entry must contain at least two lines");
        }

        boolean hasDebit = false;
        boolean hasCredit = false;

        for (JournalLineRequest line : lines) {
            if (line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("INVALID_JOURNAL_LINE_AMOUNT", "Journal line amount must be greater than zero");
            }
            if (line.side() == EntrySide.DEBIT) {
                hasDebit = true;
            }
            if (line.side() == EntrySide.CREDIT) {
                hasCredit = true;
            }
        }

        if (!hasDebit || !hasCredit) {
            throw new BusinessException("JOURNAL_ENTRY_MISSING_SIDE", "Journal entry must contain at least one debit and one credit line");
        }
    }

    private Map<Long, Account> loadAccounts(List<JournalLineRequest> lines, Long organizationId) {
        Set<Long> accountIds = lines.stream()
                .map(JournalLineRequest::accountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Account> orgAccounts = accountRepository.findAllByIdInAndOrganizationId(accountIds, organizationId);
        Map<Long, Account> accountById = orgAccounts.stream()
                .collect(Collectors.toMap(Account::getId, account -> account, (left, right) -> left, LinkedHashMap::new));

        for (Long accountId : accountIds) {
            Account account = accountById.get(accountId);
            if (account != null) {
                if (account.getStatus() != AccountStatus.ACTIVE) {
                    throw new BusinessException("ACCOUNT_INACTIVE", "Journal lines cannot reference inactive accounts");
                }
                continue;
            }

            Account existing = accountRepository.findById(accountId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
            if (!organizationId.equals(existing.getOrganization().getId())) {
                throw new BusinessException("CROSS_ORGANIZATION_ACCOUNT", "Journal lines must use accounts in the current organization");
            }

            throw new BusinessException("ACCOUNT_INACTIVE", "Journal lines cannot reference inactive accounts");
        }

        return accountById;
    }

    private void applyLines(JournalEntry entry, List<JournalLineRequest> lines, Map<Long, Account> accounts) {
        entry.getLines().clear();

        for (JournalLineRequest lineRequest : lines) {
            JournalLine line = new JournalLine();
            line.setAccount(accounts.get(lineRequest.accountId()));
            line.setSide(lineRequest.side());
            line.setAmount(lineRequest.amount());
            entry.addLine(line);
        }
    }

    private void validatePostedEntry(JournalEntry entry, Long organizationId) {
        List<JournalLine> lines = entry.getLines();
        if (lines == null || lines.size() < 2) {
            throw new BusinessException("JOURNAL_ENTRY_MIN_LINES", "Journal entry must contain at least two lines");
        }

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        boolean hasDebit = false;
        boolean hasCredit = false;

        for (JournalLine line : lines) {
            if (line.getAmount() == null || line.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("INVALID_JOURNAL_LINE_AMOUNT", "Journal line amount must be greater than zero");
            }

            Account account = line.getAccount();
            if (account == null) {
                throw new BusinessException("INVALID_JOURNAL_LINE_ACCOUNT", "Journal line account is required");
            }

            if (!organizationId.equals(account.getOrganization().getId())) {
                throw new BusinessException("CROSS_ORGANIZATION_ACCOUNT", "Journal lines must use accounts in the current organization");
            }

            if (account.getStatus() != AccountStatus.ACTIVE) {
                throw new BusinessException("ACCOUNT_INACTIVE", "Journal lines cannot reference inactive accounts");
            }

            if (line.getSide() == EntrySide.DEBIT) {
                hasDebit = true;
                totalDebits = totalDebits.add(line.getAmount());
            } else if (line.getSide() == EntrySide.CREDIT) {
                hasCredit = true;
                totalCredits = totalCredits.add(line.getAmount());
            }
        }

        if (!hasDebit || !hasCredit) {
            throw new BusinessException("JOURNAL_ENTRY_MISSING_SIDE", "Journal entry must contain at least one debit and one credit line");
        }

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new BusinessException("JOURNAL_ENTRY_UNBALANCED", "Journal entry debits and credits must be equal");
        }
    }

    public JournalEntryResponse mapEntry(JournalEntry entry) {
        List<JournalLineResponse> lines = entry.getLines().stream()
                .map(line -> new JournalLineResponse(
                        line.getId(),
                        line.getAccount().getId(),
                        line.getAccount().getAccountCode(),
                        line.getAccount().getName(),
                        line.getAccount().getAccountType(),
                        line.getSide(),
                        line.getAmount()
                ))
                .toList();

        BigDecimal totalDebits = sumBySide(entry, EntrySide.DEBIT);
        BigDecimal totalCredits = sumBySide(entry, EntrySide.CREDIT);

        return new JournalEntryResponse(
                entry.getId(),
                entry.getOrganization().getId(),
                entry.getTransactionDate(),
                entry.getDescription(),
                entry.getReference(),
                entry.getStatus(),
                entry.getCreatedByUser() == null ? null : entry.getCreatedByUser().getId(),
                entry.getPostedByUser() == null ? null : entry.getPostedByUser().getId(),
                entry.getPostedAt(),
                totalDebits,
                totalCredits,
                lines,
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                entry.getReversedByEntry() == null ? null : entry.getReversedByEntry().getId(),
                entry.getReversesEntry() == null ? null : entry.getReversesEntry().getId(),
                entry.getReversalReason()
        );
    }

    private BigDecimal sumBySide(JournalEntry entry, EntrySide side) {
        return entry.getLines().stream()
                .filter(line -> line.getSide() == side)
                .map(JournalLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

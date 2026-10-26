package com.example.Ledger.journal.service;

import com.example.Ledger.audit.service.AuditLogService;
import com.example.Ledger.common.enums.AuditLogAction;
import com.example.Ledger.common.enums.EntrySide;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.journal.dto.JournalEntryResponse;
import com.example.Ledger.journal.model.JournalEntry;
import com.example.Ledger.journal.model.JournalLine;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Service for reversing posted journal entries.
 * Ensures atomic creation of reversal entries and proper relationship tracking.
 */
@Service
@Transactional
public class JournalReversalService {

    private final JournalEntryRepository journalEntryRepository;
    private final JournalEntryService journalEntryService;
    private final AuditLogService auditLogService;
    private final LedgerUserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;

    public JournalReversalService(
            JournalEntryRepository journalEntryRepository,
            JournalEntryService journalEntryService,
            AuditLogService auditLogService,
            LedgerUserRepository userRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.journalEntryRepository = journalEntryRepository;
        this.journalEntryService = journalEntryService;
        this.auditLogService = auditLogService;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Reverses a posted journal entry by creating a new entry with opposite debit/credit sides.
     * The original entry remains unchanged. The reversal is automatically posted.
     *
     * @param originalEntryId The ID of the posted entry to reverse
     * @return Response for the newly created reversal entry
     * @throws BusinessException if the entry is not posted, already reversed, or on concurrent reversal attempt
     */
    public JournalEntryResponse reverseEntry(Long originalEntryId) {
        currentUserProvider.requireRole(
                com.example.Ledger.common.enums.Role.ADMIN,
                com.example.Ledger.common.enums.Role.ACCOUNTANT
        );

        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        // Load the original entry with pessimistic lock to prevent concurrent reversals
        JournalEntry originalEntry = journalEntryRepository.findByIdAndOrganizationIdForUpdate(originalEntryId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found"));

        // Validate the entry can be reversed
        validateReversalEligibility(originalEntry);

        // Create the reversal entry
        JournalEntry reversalEntry = createReversalEntry(originalEntry, organizationId);

        // Save the reversal
        JournalEntry savedReversal = journalEntryRepository.save(reversalEntry);

        // Mark the original entry as reversed (update the relationship)
        originalEntry.setReversedByEntry(savedReversal);
        journalEntryRepository.save(originalEntry);

        // Post the reversal entry automatically
        savedReversal.setStatus(JournalEntryStatus.POSTED);
        savedReversal.setPostedByUser(loadCurrentUser());
        savedReversal.setPostedAt(Instant.now());
        journalEntryRepository.save(savedReversal);

        // Record audit events
        auditLogService.recordAudit(
                AuditLogAction.JOURNAL_REVERSED,
                "JOURNAL_ENTRY",
                originalEntry.getId().toString(),
                Map.of("reversedByEntryId", savedReversal.getId())
        );
        auditLogService.recordAudit(
                AuditLogAction.JOURNAL_POSTED,
                "JOURNAL_ENTRY",
                savedReversal.getId().toString(),
                Map.of("status", JournalEntryStatus.POSTED.name(), "isReversal", true)
        );

        return journalEntryService.mapEntry(savedReversal);
    }

    /**
     * Validates that a journal entry is eligible for reversal.
     * - Must be POSTED
     * - Must not already be reversed
     * - Must not be a reversal itself (to prevent recursive reversals)
     */
    private void validateReversalEligibility(JournalEntry entry) {
        if (entry.getStatus() != JournalEntryStatus.POSTED) {
            throw new BusinessException(
                    "JOURNAL_ENTRY_NOT_POSTED",
                    "Only posted journal entries can be reversed"
            );
        }

        if (entry.getReversedByEntry() != null) {
            throw new BusinessException(
                    "JOURNAL_ENTRY_ALREADY_REVERSED",
                    "This journal entry has already been reversed"
            );
        }
    }

    /**
     * Creates a new journal entry that reverses the original.
     * The reversal entry has opposite debit/credit sides for each line.
     * The reversal is created as a DRAFT initially; caller will post it.
     */
    private JournalEntry createReversalEntry(JournalEntry original, Long organizationId) {
        JournalEntry reversal = new JournalEntry();
        reversal.setOrganization(original.getOrganization());
        reversal.setCreatedByUser(loadCurrentUser());
        reversal.setTransactionDate(LocalDate.now());
        reversal.setDescription("Reversal of: " + original.getDescription());
        reversal.setReference(original.getReference() != null ? "REV-" + original.getReference() : null);
        reversal.setStatus(JournalEntryStatus.DRAFT);

        // Create reversed lines
        for (JournalLine originalLine : original.getLines()) {
            JournalLine reversalLine = new JournalLine();
            reversalLine.setAccount(originalLine.getAccount());
            reversalLine.setAmount(originalLine.getAmount());
            // Reverse the side: DEBIT becomes CREDIT, CREDIT becomes DEBIT
            reversalLine.setSide(originalLine.getSide() == EntrySide.DEBIT ? EntrySide.CREDIT : EntrySide.DEBIT);
            reversal.addLine(reversalLine);
        }

        return reversal;
    }

    /**
     * Loads the current authenticated user.
     */
    private LedgerUser loadCurrentUser() {
        return userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}

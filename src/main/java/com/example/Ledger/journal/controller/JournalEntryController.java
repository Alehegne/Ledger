package com.example.Ledger.journal.controller;

import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.idempotency.Idempotent;
import com.example.Ledger.journal.dto.JournalEntryPageResponse;
import com.example.Ledger.journal.dto.JournalEntryRequest;
import com.example.Ledger.journal.dto.JournalEntryResponse;
import com.example.Ledger.journal.dto.JournalEntryUpdateRequest;
import com.example.Ledger.journal.service.JournalEntryService;
import com.example.Ledger.journal.service.JournalReversalService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class JournalEntryController {

    private final JournalEntryService journalEntryService;
    private final JournalReversalService journalReversalService;

    public JournalEntryController(
            JournalEntryService journalEntryService,
            JournalReversalService journalReversalService
    ) {
        this.journalEntryService = journalEntryService;
        this.journalReversalService = journalReversalService;
    }

    @PostMapping("/journal-entries")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public JournalEntryResponse create(@Valid @RequestBody JournalEntryRequest request) {
        return journalEntryService.createDraft(request);
    }

    @GetMapping("/journal-entries")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','VIEWER')")
    public JournalEntryPageResponse list(
            @RequestParam(required = false) JournalEntryStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return journalEntryService.listEntries(status, fromDate, toDate, accountId, page, size);
    }

    @GetMapping("/journal-entries/{entryId}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','VIEWER')")
    public JournalEntryResponse get(@PathVariable Long entryId) {
        return journalEntryService.getEntry(entryId);
    }

    @PatchMapping("/journal-entries/{entryId}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public JournalEntryResponse update(@PathVariable Long entryId, @Valid @RequestBody JournalEntryUpdateRequest request) {
        return journalEntryService.updateDraft(entryId, request);
    }

    @DeleteMapping("/journal-entries/{entryId}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public void delete(@PathVariable Long entryId) {
        journalEntryService.deleteDraft(entryId);
    }

    @Idempotent
    @PostMapping("/journal-entries/{entryId}/post")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public JournalEntryResponse post(@PathVariable Long entryId) {
        return journalEntryService.postEntry(entryId);
    }

    @Idempotent
    @PostMapping("/journal-entries/{entryId}/reverse")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public JournalEntryResponse reverse(
            @PathVariable Long entryId,
            @Valid @RequestBody(required = false) com.example.Ledger.journal.dto.JournalReversalRequest request
    ) {
        String reason = request != null ? request.reason() : null;
        return journalReversalService.reverseEntry(entryId, reason);
    }
}

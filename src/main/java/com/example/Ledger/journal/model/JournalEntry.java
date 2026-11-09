package com.example.Ledger.journal.model;

import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.persistence.BaseEntity;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.user.model.LedgerUser;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "journal_entry")
public class JournalEntry extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotNull
    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @NotBlank
    @Size(max = 500)
    @Column(nullable = false, length = 500)
    private String description;

    @Size(max = 120)
    @Column(length = 120)
    private String reference;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JournalEntryStatus status;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private LedgerUser createdByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "posted_by_user_id")
    private LedgerUser postedByUser;

    @Column(name = "posted_at")
    private Instant postedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversed_by_journal_entry_id")
    private JournalEntry reversedByEntry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reverses_journal_entry_id")
    private JournalEntry reversesEntry;

    @Size(max = 500)
    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<JournalLine> lines = new ArrayList<>();

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(LocalDate transactionDate) {
        this.transactionDate = transactionDate;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public JournalEntryStatus getStatus() {
        return status;
    }

    public void setStatus(JournalEntryStatus status) {
        this.status = status;
    }

    public LedgerUser getCreatedByUser() {
        return createdByUser;
    }

    public void setCreatedByUser(LedgerUser createdByUser) {
        this.createdByUser = createdByUser;
    }

    public LedgerUser getPostedByUser() {
        return postedByUser;
    }

    public void setPostedByUser(LedgerUser postedByUser) {
        this.postedByUser = postedByUser;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }

    public JournalEntry getReversedByEntry() {
        return reversedByEntry;
    }

    public void setReversedByEntry(JournalEntry reversedByEntry) {
        this.reversedByEntry = reversedByEntry;
    }

    public JournalEntry getReversesEntry() {
        return reversesEntry;
    }

    public void setReversesEntry(JournalEntry reversesEntry) {
        this.reversesEntry = reversesEntry;
    }

    public String getReversalReason() {
        return reversalReason;
    }

    public void setReversalReason(String reversalReason) {
        this.reversalReason = reversalReason;
    }

    public List<JournalLine> getLines() {
        return lines;
    }

    public void addLine(JournalLine line) {
        line.setJournalEntry(this);
        this.lines.add(line);
    }
}
package com.example.Ledger.reconciliation.model;

import com.example.Ledger.common.persistence.BaseEntity;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.user.model.LedgerUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRun extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triggered_by_user_id")
    private LedgerUser triggeredByUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationStatus status;

    @Column(name = "total_posted_debits", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalPostedDebits;

    @Column(name = "total_posted_credits", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalPostedCredits;

    @Column(name = "global_variance", nullable = false, precision = 19, scale = 4)
    private BigDecimal globalVariance;

    @Column(name = "posted_entry_count", nullable = false)
    private long postedEntryCount;

    @Column(name = "account_count", nullable = false)
    private long accountCount;

    @Column(name = "checks_passed", nullable = false)
    private int checksPassed;

    @Column(name = "checks_failed", nullable = false)
    private int checksFailed;

    @Column(name = "checks_json", nullable = false, columnDefinition = "TEXT")
    private String checksJson;

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
    }

    public LedgerUser getTriggeredByUser() {
        return triggeredByUser;
    }

    public void setTriggeredByUser(LedgerUser triggeredByUser) {
        this.triggeredByUser = triggeredByUser;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public void setStatus(ReconciliationStatus status) {
        this.status = status;
    }

    public BigDecimal getTotalPostedDebits() {
        return totalPostedDebits;
    }

    public void setTotalPostedDebits(BigDecimal totalPostedDebits) {
        this.totalPostedDebits = totalPostedDebits;
    }

    public BigDecimal getTotalPostedCredits() {
        return totalPostedCredits;
    }

    public void setTotalPostedCredits(BigDecimal totalPostedCredits) {
        this.totalPostedCredits = totalPostedCredits;
    }

    public BigDecimal getGlobalVariance() {
        return globalVariance;
    }

    public void setGlobalVariance(BigDecimal globalVariance) {
        this.globalVariance = globalVariance;
    }

    public long getPostedEntryCount() {
        return postedEntryCount;
    }

    public void setPostedEntryCount(long postedEntryCount) {
        this.postedEntryCount = postedEntryCount;
    }

    public long getAccountCount() {
        return accountCount;
    }

    public void setAccountCount(long accountCount) {
        this.accountCount = accountCount;
    }

    public int getChecksPassed() {
        return checksPassed;
    }

    public void setChecksPassed(int checksPassed) {
        this.checksPassed = checksPassed;
    }

    public int getChecksFailed() {
        return checksFailed;
    }

    public void setChecksFailed(int checksFailed) {
        this.checksFailed = checksFailed;
    }

    public String getChecksJson() {
        return checksJson;
    }

    public void setChecksJson(String checksJson) {
        this.checksJson = checksJson;
    }
}

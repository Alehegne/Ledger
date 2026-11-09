package com.example.Ledger.admin.model;

import com.example.Ledger.common.persistence.BaseEntity;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.user.model.LedgerUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "platform_audit_log")
public class PlatformAuditLog extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private LedgerUser actorUser;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String action;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_organization_id")
    private Organization targetOrganization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private LedgerUser targetUser;

    @Lob
    @Column(name = "metadata_json")
    private String metadataJson;

    public LedgerUser getActorUser() {
        return actorUser;
    }

    public void setActorUser(LedgerUser actorUser) {
        this.actorUser = actorUser;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Organization getTargetOrganization() {
        return targetOrganization;
    }

    public void setTargetOrganization(Organization targetOrganization) {
        this.targetOrganization = targetOrganization;
    }

    public LedgerUser getTargetUser() {
        return targetUser;
    }

    public void setTargetUser(LedgerUser targetUser) {
        this.targetUser = targetUser;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }
}

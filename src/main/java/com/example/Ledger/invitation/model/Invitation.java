package com.example.Ledger.invitation.model;

import com.example.Ledger.common.enums.InvitationPurpose;
import com.example.Ledger.common.enums.Role;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Entity
@Table(
        name = "invitation",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_invitation_token", columnNames = {"invitation_token"})
        }
)
public class Invitation extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_user_id", nullable = false)
    private LedgerUser invitedByUser;

    @Email
    @NotBlank
    @Size(max = 255)
    @Column(name = "invited_email", nullable = false, length = 255)
    private String invitedEmail;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InvitationPurpose purpose = InvitationPurpose.TEAM_INVITE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private LedgerUser targetUser;

    @NotBlank
    @Size(max = 255)
    @Column(name = "invitation_token", nullable = false, length = 255)
    private String invitationToken;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
    }

    public LedgerUser getInvitedByUser() {
        return invitedByUser;
    }

    public void setInvitedByUser(LedgerUser invitedByUser) {
        this.invitedByUser = invitedByUser;
    }

    public String getInvitedEmail() {
        return invitedEmail;
    }

    public void setInvitedEmail(String invitedEmail) {
        this.invitedEmail = invitedEmail;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public InvitationPurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(InvitationPurpose purpose) {
        this.purpose = purpose;
    }

    public LedgerUser getTargetUser() {
        return targetUser;
    }

    public void setTargetUser(LedgerUser targetUser) {
        this.targetUser = targetUser;
    }

    public String getInvitationToken() {
        return invitationToken;
    }

    public void setInvitationToken(String invitationToken) {
        this.invitationToken = invitationToken;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public void setSupersededAt(Instant supersededAt) {
        this.supersededAt = supersededAt;
    }
}

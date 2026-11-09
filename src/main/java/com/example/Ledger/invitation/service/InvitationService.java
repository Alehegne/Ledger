package com.example.Ledger.invitation.service;

import com.example.Ledger.audit.service.AuditLogService;
import com.example.Ledger.auth.service.AuthService;
import com.example.Ledger.common.enums.AuditLogAction;
import com.example.Ledger.common.enums.InvitationPurpose;
import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.enums.UserStatus;
import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.exception.ForbiddenException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.invitation.dto.AcceptInvitationRequest;
import com.example.Ledger.invitation.dto.InvitationRequest;
import com.example.Ledger.invitation.dto.InvitationResponse;
import com.example.Ledger.invitation.model.Invitation;
import com.example.Ledger.invitation.repository.InvitationRepository;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class InvitationService {

    private final InvitationRepository invitationRepository;
    private final OrganizationRepository organizationRepository;
    private final LedgerUserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public InvitationService(
            InvitationRepository invitationRepository,
            OrganizationRepository organizationRepository,
            LedgerUserRepository userRepository,
            CurrentUserProvider currentUserProvider,
            PasswordEncoder passwordEncoder,
            AuditLogService auditLogService
    ) {
        this.invitationRepository = invitationRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    public InvitationResponse createInvitation(InvitationRequest request) {
        currentUserProvider.requireRole(Role.ADMIN);
        if (request.role() == null || !request.role().isTenantAssignable()) {
            throw new ForbiddenException("Cannot invite users with platform role " + request.role());
        }

        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        String email = AuthService.normalizeEmail(request.email());

        Organization organization = organizationRepository.findLockedById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        if (organization.getKind() != OrganizationKind.TENANT) {
            throw new ForbiddenException("Platform organization cannot issue tenant team invitations");
        }
        if (organization.getStatus() == OrganizationStatus.SUSPENDED) {
            throw new BusinessException("ORGANIZATION_SUSPENDED", "Cannot create invitations while organization is suspended");
        }
        if (organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw new BusinessException("ORGANIZATION_PENDING", "Organization onboarding has not been completed");
        }

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException("DUPLICATE_EMAIL", "A user with this email already exists");
        }

        LedgerUser invitedBy = userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Instant now = Instant.now();
        invitationRepository.supersedeOpenByOrganizationIdAndEmail(organizationId, email, now);

        // BCrypt accepts at most 72 bytes; keep raw invitation token below that limit.
        String rawToken = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        Invitation invitation = new Invitation();
        invitation.setOrganization(organization);
        invitation.setInvitedByUser(invitedBy);
        invitation.setInvitedEmail(email);
        invitation.setRole(request.role());
        invitation.setPurpose(InvitationPurpose.TEAM_INVITE);
        invitation.setTargetUser(null);
        invitation.setInvitationToken(passwordEncoder.encode(rawToken));
        invitation.setExpiresAt(now.plusSeconds(7L * 24 * 60 * 60));
        invitation = invitationRepository.save(invitation);

        auditLogService.recordAudit(
                AuditLogAction.USER_INVITED,
                "INVITATION",
                invitation.getId().toString(),
                Map.of(
                        "email", invitation.getInvitedEmail(),
                        "role", invitation.getRole().name(),
                        "purpose", invitation.getPurpose().name()
                )
        );

        return new InvitationResponse(
                invitation.getId(),
                organization.getId(),
                invitation.getInvitedEmail(),
                invitation.getRole(),
                rawToken,
                invitation.getExpiresAt(),
                invitation.getCreatedAt()
        );
    }

    public String acceptInvitation(AcceptInvitationRequest request) {
        String email = AuthService.normalizeEmail(request.email());
        List<Invitation> candidates = invitationRepository.findByInvitedEmailIgnoreCase(email);

        Invitation matched = candidates.stream()
                .filter(candidate -> passwordEncoder.matches(request.token(), candidate.getInvitationToken()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("INVALID_INVITATION", "Invitation is invalid or expired"));

        Invitation invitation = invitationRepository.findLockedById(matched.getId())
                .orElseThrow(() -> new BusinessException("INVALID_INVITATION", "Invitation is invalid or expired"));

        Instant now = Instant.now();

        if (!invitation.getInvitedEmail().equalsIgnoreCase(email)) {
            throw new BusinessException("INVALID_INVITATION", "Invitation email does not match");
        }
        if (invitation.getSupersededAt() != null) {
            throw new BusinessException("INVITATION_SUPERSEDED", "Invitation has been superseded");
        }
        if (invitation.getAcceptedAt() != null) {
            throw new BusinessException("INVITATION_ALREADY_USED", "Invitation has already been accepted");
        }
        if (invitation.getExpiresAt().isBefore(now)) {
            throw new BusinessException("INVITATION_EXPIRED", "Invitation has expired");
        }

        Organization organization = organizationRepository.findLockedById(invitation.getOrganization().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        if (organization.getKind() != OrganizationKind.TENANT) {
            throw new BusinessException("INVALID_INVITATION", "Platform organization cannot accept tenant invitations");
        }
        if (!invitation.getRole().isTenantAssignable()) {
            throw new BusinessException("INVALID_INVITATION", "Invitation role is not valid for a tenant user");
        }

        LedgerUser user;
        boolean transitionedToActive = false;

        switch (invitation.getPurpose()) {
            case ONBOARDING -> {
                if (organization.getStatus() == OrganizationStatus.SUSPENDED) {
                    throw new BusinessException("ORGANIZATION_SUSPENDED", "Organization is suspended");
                }
                if (organization.getStatus() != OrganizationStatus.PENDING) {
                    throw new BusinessException("ORGANIZATION_ALREADY_ONBOARDED", "Organization onboarding has already been completed");
                }
                if (userRepository.existsByEmailIgnoreCase(email)) {
                    throw new BusinessException("DUPLICATE_EMAIL", "A user with this email already exists");
                }
                user = createActiveTenantUser(organization, email, request, invitation.getRole());
                organization.setStatus(OrganizationStatus.ACTIVE);
                organizationRepository.save(organization);
                transitionedToActive = true;
            }
            case TEAM_INVITE -> {
                if (organization.getStatus() == OrganizationStatus.SUSPENDED) {
                    throw new BusinessException("ORGANIZATION_SUSPENDED", "Organization is suspended");
                }
                if (organization.getStatus() != OrganizationStatus.ACTIVE) {
                    throw new BusinessException("ORGANIZATION_PENDING", "Organization onboarding has not been completed");
                }
                if (userRepository.existsByEmailIgnoreCase(email)) {
                    throw new BusinessException("DUPLICATE_EMAIL", "A user with this email already exists");
                }
                user = createActiveTenantUser(organization, email, request, invitation.getRole());
            }
            case ADMIN_RECOVERY -> {
                if (invitation.getRole() != Role.ADMIN) {
                    throw new BusinessException("INVALID_RECOVERY_INVITATION", "Recovery invitation must grant ADMIN role");
                }
                if (invitation.getTargetUser() != null) {
                    LedgerUser targetUser = userRepository.findLockedWithOrganizationById(invitation.getTargetUser().getId())
                            .orElseThrow(() -> new BusinessException("INVALID_RECOVERY_INVITATION", "Recovery target user no longer exists"));
                    if (targetUser.getOrganization() == null
                            || !targetUser.getOrganization().getId().equals(organization.getId())) {
                        throw new BusinessException("INVALID_RECOVERY_INVITATION", "Recovery target user does not belong to the invitation organization");
                    }
                    if (!targetUser.getEmail().equalsIgnoreCase(email)
                            || !targetUser.getEmail().equalsIgnoreCase(invitation.getInvitedEmail())) {
                        throw new BusinessException("INVALID_RECOVERY_INVITATION", "Recovery invitation email does not match target user");
                    }
                    if (targetUser.getRole() != Role.ADMIN) {
                        throw new BusinessException("INVALID_RECOVERY_INVITATION", "Recovery target user does not hold ADMIN role");
                    }
                    if (targetUser.getStatus() != UserStatus.DISABLED) {
                        throw new BusinessException("USER_ALREADY_ACTIVE", "Target user is already active");
                    }

                    targetUser.setFullName(request.fullName().trim());
                    targetUser.setPasswordHash(passwordEncoder.encode(request.password()));
                    targetUser.setRole(Role.ADMIN);
                    targetUser.setStatus(UserStatus.ACTIVE);
                    user = userRepository.save(targetUser);
                } else {
                    if (userRepository.existsByEmailIgnoreCase(email)) {
                        throw new BusinessException("DUPLICATE_EMAIL", "A user with this email already exists");
                    }
                    user = createActiveTenantUser(organization, email, request, Role.ADMIN);
                }

                // If organization had never completed initial onboarding (PENDING), recovery completes onboarding.
                // If organization is SUSPENDED, keep it SUSPENDED until SUPER_ADMIN explicitly reactivates it.
                if (organization.getStatus() == OrganizationStatus.PENDING) {
                    organization.setStatus(OrganizationStatus.ACTIVE);
                    organizationRepository.save(organization);
                    transitionedToActive = true;
                }
            }
            default -> throw new BusinessException("INVALID_INVITATION", "Unsupported invitation purpose");
        }

        invitation.setAcceptedAt(now);
        invitationRepository.save(invitation);

        auditLogService.recordAudit(
                organization,
                user,
                AuditLogAction.INVITATION_ACCEPTED,
                "INVITATION",
                invitation.getId().toString(),
                Map.of(
                        "userId", user.getId().toString(),
                        "email", user.getEmail(),
                        "role", user.getRole().name(),
                        "purpose", invitation.getPurpose().name()
                )
        );

        if (transitionedToActive) {
            auditLogService.recordAudit(
                    organization,
                    user,
                    AuditLogAction.ORGANIZATION_ACTIVATED,
                    "ORGANIZATION",
                    organization.getId().toString(),
                    Map.of(
                            "organizationName", organization.getName(),
                            "baseCurrency", organization.getBaseCurrency(),
                            "adminEmail", user.getEmail()
                    )
            );
        }

        return "Invitation accepted";
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> listPendingInvitations() {
        currentUserProvider.requireRole(Role.ADMIN);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        return invitationRepository.findByOrganizationIdAndAcceptedAtIsNullAndSupersededAtIsNullOrderByCreatedAtDesc(organizationId)
                .stream()
                .map(inv -> new InvitationResponse(
                        inv.getId(),
                        organizationId,
                        inv.getInvitedEmail(),
                        inv.getRole(),
                        null,
                        inv.getExpiresAt(),
                        inv.getCreatedAt()
                ))
                .toList();
    }

    public void revokeInvitation(Long invitationId) {
        currentUserProvider.requireRole(Role.ADMIN);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        Invitation invitation = invitationRepository.findByIdAndOrganizationId(invitationId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));

        if (invitation.getPurpose() != InvitationPurpose.TEAM_INVITE) {
            throw new ForbiddenException("Platform-managed invitations cannot be revoked from tenant settings");
        }
        if (invitation.getAcceptedAt() != null) {
            throw new BusinessException("INVITATION_ALREADY_USED", "Cannot revoke an already accepted invitation");
        }
        if (invitation.getSupersededAt() != null) {
            throw new BusinessException("INVITATION_SUPERSEDED", "Cannot revoke an already superseded invitation");
        }

        auditLogService.recordAudit(
                AuditLogAction.INVITATION_REVOKED,
                "INVITATION",
                invitation.getId().toString(),
                Map.of(
                        "email", invitation.getInvitedEmail(),
                        "role", invitation.getRole().name()
                )
        );

        invitationRepository.delete(invitation);
    }

    private LedgerUser createActiveTenantUser(
            Organization organization,
            String email,
            AcceptInvitationRequest request,
            Role role
    ) {
        LedgerUser user = new LedgerUser();
        user.setOrganization(organization);
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }
}

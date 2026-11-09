package com.example.Ledger.invitation.service;

import com.example.Ledger.audit.service.AuditLogService;
import com.example.Ledger.auth.service.AuthService;
import com.example.Ledger.common.enums.AuditLogAction;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.exception.BusinessException;
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
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        String email = AuthService.normalizeEmail(request.email());

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException("DUPLICATE_EMAIL", "A user with this email already exists");
        }

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        LedgerUser invitedBy = userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // BCrypt accepts at most 72 bytes; keep raw invitation token below that limit.
        String rawToken = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
        Invitation invitation = new Invitation();
        invitation.setOrganization(organization);
        invitation.setInvitedByUser(invitedBy);
        invitation.setInvitedEmail(email);
        invitation.setRole(request.role());
        invitation.setInvitationToken(passwordEncoder.encode(rawToken));
        invitation.setExpiresAt(Instant.now().plusSeconds(7L * 24 * 60 * 60));
        invitation = invitationRepository.save(invitation);

        auditLogService.recordAudit(
                AuditLogAction.USER_INVITED,
                "INVITATION",
                invitation.getId().toString(),
                Map.of(
                        "email", invitation.getInvitedEmail(),
                        "role", invitation.getRole().name()
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

        Invitation invitation = candidates.stream()
                .filter(candidate -> passwordEncoder.matches(request.token(), candidate.getInvitationToken()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("INVALID_INVITATION", "Invitation is invalid or expired"));

        if (invitation.getAcceptedAt() != null) {
            throw new BusinessException("INVITATION_ALREADY_USED", "Invitation has already been accepted");
        }

        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("INVITATION_EXPIRED", "Invitation has expired");
        }

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException("DUPLICATE_EMAIL", "A user with this email already exists");
        }

        LedgerUser user = new LedgerUser();
        user.setOrganization(invitation.getOrganization());
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(invitation.getRole());
        user = userRepository.save(user);
        invitation.setAcceptedAt(Instant.now());
        invitationRepository.save(invitation);

        auditLogService.recordAudit(
                invitation.getOrganization(),
                user,
                AuditLogAction.INVITATION_ACCEPTED,
                "INVITATION",
                invitation.getId().toString(),
                Map.of(
                        "userId", user.getId().toString(),
                        "email", user.getEmail(),
                        "role", user.getRole().name()
                )
        );

        return "Invitation accepted";
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> listPendingInvitations() {
        currentUserProvider.requireRole(Role.ADMIN);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        return invitationRepository.findByOrganizationIdAndAcceptedAtIsNullOrderByCreatedAtDesc(organizationId)
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

        if (invitation.getAcceptedAt() != null) {
            throw new BusinessException("INVITATION_ALREADY_USED", "Cannot revoke an already accepted invitation");
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
}

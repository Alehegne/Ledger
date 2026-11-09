package com.example.Ledger.admin.service;

import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.admin.dto.PlatformOrganizationDetailResponse;
import com.example.Ledger.admin.dto.PlatformOrganizationPageResponse;
import com.example.Ledger.admin.dto.PlatformOrganizationSummaryResponse;
import com.example.Ledger.admin.dto.PlatformOrganizationUserResponse;
import com.example.Ledger.admin.dto.PlatformOverviewResponse;
import com.example.Ledger.admin.dto.PlatformPendingInvitationSummary;
import com.example.Ledger.admin.dto.RecoveryInvitationRequest;
import com.example.Ledger.admin.dto.RecoveryInvitationResponse;
import com.example.Ledger.admin.dto.UpdateAdminOrganizationRequest;
import com.example.Ledger.admin.dto.UpdateOrganizationStatusRequest;
import com.example.Ledger.admin.dto.UpdateUserStatusRequest;
import com.example.Ledger.admin.repository.PlatformAuditLogRepository;
import com.example.Ledger.auth.repository.RefreshTokenRepository;
import com.example.Ledger.auth.service.AuthService;
import com.example.Ledger.common.enums.InvitationPurpose;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.enums.PlatformAuditAction;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.enums.UserStatus;
import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.exception.ForbiddenException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.invitation.model.Invitation;
import com.example.Ledger.invitation.repository.InvitationRepository;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.journal.repository.JournalLineRepository;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class PlatformAdminService {

    private static final String RESERVED_PLATFORM_ORG_NAME = "Ledger Platform";

    private final OrganizationRepository organizationRepository;
    private final LedgerUserRepository userRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final InvitationRepository invitationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PlatformAuditLogRepository platformAuditLogRepository;
    private final PlatformAuditService platformAuditService;
    private final PasswordEncoder passwordEncoder;

    public PlatformAdminService(
            OrganizationRepository organizationRepository,
            LedgerUserRepository userRepository,
            AccountRepository accountRepository,
            JournalEntryRepository journalEntryRepository,
            JournalLineRepository journalLineRepository,
            InvitationRepository invitationRepository,
            RefreshTokenRepository refreshTokenRepository,
            PlatformAuditLogRepository platformAuditLogRepository,
            PlatformAuditService platformAuditService,
            PasswordEncoder passwordEncoder
    ) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.invitationRepository = invitationRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.platformAuditLogRepository = platformAuditLogRepository;
        this.platformAuditService = platformAuditService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public PlatformOverviewResponse getOverview() {
        long totalTenantOrgs = organizationRepository.countByKind(OrganizationKind.TENANT);
        long activeTenantOrgs = organizationRepository.countByKindAndStatus(OrganizationKind.TENANT, OrganizationStatus.ACTIVE);
        long pendingTenantOrgs = organizationRepository.countByKindAndStatus(OrganizationKind.TENANT, OrganizationStatus.PENDING);
        long suspendedTenantOrgs = organizationRepository.countByKindAndStatus(OrganizationKind.TENANT, OrganizationStatus.SUSPENDED);

        long totalTenantUsers = userRepository.countByOrganizationKind(OrganizationKind.TENANT);
        long disabledTenantUsers = userRepository.countByOrganizationKindAndStatus(OrganizationKind.TENANT, UserStatus.DISABLED);
        long totalTenantAccounts = accountRepository.countByOrganizationKind(OrganizationKind.TENANT);
        long totalPostedJournalEntries = journalEntryRepository.countByOrganizationKindAndStatus(
                OrganizationKind.TENANT,
                JournalEntryStatus.POSTED
        );
        long unbalancedTenantOrgs = journalLineRepository.findUnbalancedTenantOrganizationIds().size();
        Instant lastPlatformActionAt = platformAuditLogRepository.findLatestCreatedAt().orElse(null);

        return new PlatformOverviewResponse(
                totalTenantOrgs,
                activeTenantOrgs,
                pendingTenantOrgs,
                suspendedTenantOrgs,
                totalTenantUsers,
                disabledTenantUsers,
                totalTenantAccounts,
                totalPostedJournalEntries,
                unbalancedTenantOrgs,
                lastPlatformActionAt
        );
    }

    @Transactional(readOnly = true)
    public PlatformOrganizationPageResponse listOrganizations(
            String search,
            OrganizationStatus status,
            int page,
            int size
    ) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);

        Specification<Organization> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("kind"), OrganizationKind.TENANT));

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (search != null && !search.trim().isEmpty()) {
                String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("name")), pattern));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(
                normalizedPage,
                normalizedSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );

        Page<Organization> result = organizationRepository.findAll(spec, pageable);
        List<Organization> orgs = result.getContent();
        if (orgs.isEmpty()) {
            return new PlatformOrganizationPageResponse(
                    result.getNumber(),
                    result.getSize(),
                    result.getTotalElements(),
                    result.getTotalPages(),
                    List.of()
            );
        }

        List<Long> orgIds = orgs.stream().map(Organization::getId).toList();

        Map<Long, UserAggregate> userAggs = new HashMap<>();
        for (Object[] row : userRepository.summarizeUsersByOrganizationIds(orgIds)) {
            Long orgId = ((Number) row[0]).longValue();
            userAggs.put(orgId, new UserAggregate(
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue(),
                    (Instant) row[4]
            ));
        }

        Map<Long, AccountAggregate> accountAggs = new HashMap<>();
        for (Object[] row : accountRepository.summarizeAccountsByOrganizationIds(orgIds)) {
            Long orgId = ((Number) row[0]).longValue();
            accountAggs.put(orgId, new AccountAggregate(
                    ((Number) row[1]).longValue(),
                    (Instant) row[2]
            ));
        }

        Map<Long, EntryAggregate> entryAggs = new HashMap<>();
        for (Object[] row : journalEntryRepository.summarizeEntriesByOrganizationIds(orgIds)) {
            Long orgId = ((Number) row[0]).longValue();
            entryAggs.put(orgId, new EntryAggregate(
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    (Instant) row[3]
            ));
        }

        Map<Long, Long> openInvitationCounts = new HashMap<>();
        for (Object[] row : invitationRepository.summarizeOpenInvitationsByOrganizationIds(orgIds)) {
            Long orgId = ((Number) row[0]).longValue();
            openInvitationCounts.put(orgId, ((Number) row[1]).longValue());
        }

        List<PlatformOrganizationSummaryResponse> items = orgs.stream()
                .map(org -> {
                    UserAggregate ua = userAggs.getOrDefault(org.getId(), UserAggregate.EMPTY);
                    AccountAggregate aa = accountAggs.getOrDefault(org.getId(), AccountAggregate.EMPTY);
                    EntryAggregate ea = entryAggs.getOrDefault(org.getId(), EntryAggregate.EMPTY);
                    long openInvites = openInvitationCounts.getOrDefault(org.getId(), 0L);

                    Instant lastActivity = maxInstant(
                            org.getUpdatedAt(),
                            ua.lastUpdatedAt(),
                            aa.lastUpdatedAt(),
                            ea.lastUpdatedAt()
                    );

                    return new PlatformOrganizationSummaryResponse(
                            org.getId(),
                            org.getName(),
                            org.getBaseCurrency(),
                            org.getStatus(),
                            ua.userCount(),
                            ua.activeAdminCount(),
                            ua.disabledUserCount(),
                            aa.accountCount(),
                            ea.postedEntryCount(),
                            ea.draftEntryCount(),
                            openInvites,
                            lastActivity,
                            org.getCreatedAt(),
                            org.getSuspendedAt(),
                            org.getSuspendedReason()
                    );
                })
                .toList();

        return new PlatformOrganizationPageResponse(
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                items
        );
    }

    @Transactional(readOnly = true)
    public PlatformOrganizationDetailResponse getOrganizationDetail(Long organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        if (organization.getKind() == OrganizationKind.PLATFORM) {
            throw new ResourceNotFoundException("Organization not found");
        }
        return buildDetailResponse(organization);
    }

    public PlatformOrganizationDetailResponse updateOrganization(
            Long organizationId,
            UpdateAdminOrganizationRequest request,
            Long actorUserId
    ) {
        LedgerUser actor = requireSuperAdminActor(actorUserId);
        Organization organization = loadLockedMutableTenantOrganization(organizationId);

        if (request == null) {
            return buildDetailResponse(organization);
        }

        String previousName = organization.getName();
        String previousCurrency = organization.getBaseCurrency();

        String nextName = request.name() == null ? null : request.name().trim();
        if (nextName != null && nextName.isBlank()) {
            throw new BusinessException("ORGANIZATION_NAME_REQUIRED", "Organization name is required");
        }
        if (nextName != null && RESERVED_PLATFORM_ORG_NAME.equalsIgnoreCase(nextName)) {
            throw new BusinessException("RESERVED_ORGANIZATION_NAME", "Organization name 'Ledger Platform' is reserved");
        }
        final Long targetOrganizationId = organization.getId();
        if (nextName != null && !nextName.equalsIgnoreCase(organization.getName())
                && organizationRepository.findByNameIgnoreCase(nextName)
                        .filter(existing -> !existing.getId().equals(targetOrganizationId))
                        .isPresent()) {
            throw new BusinessException("ORGANIZATION_NAME_EXISTS", "Organization name already exists");
        }

        String nextCurrency = request.baseCurrency() == null ? null : request.baseCurrency().trim().toUpperCase(Locale.ROOT);
        if (nextCurrency != null && !nextCurrency.matches("^[A-Z]{3}$")) {
            throw new BusinessException("INVALID_CURRENCY", "Currency must be a 3-letter ISO code");
        }
        if (nextCurrency != null && !nextCurrency.equalsIgnoreCase(organization.getBaseCurrency())
                && journalEntryRepository.existsByOrganizationId(organization.getId())) {
            throw new BusinessException("BASE_CURRENCY_LOCKED", "Base currency cannot be changed after journal transactions have been created");
        }

        boolean changed = false;
        if (nextName != null && !nextName.equals(organization.getName())) {
            organization.setName(nextName);
            changed = true;
        }
        if (nextCurrency != null && !nextCurrency.equals(organization.getBaseCurrency())) {
            organization.setBaseCurrency(nextCurrency);
            changed = true;
        }

        if (changed) {
            try {
                organization = organizationRepository.saveAndFlush(organization);
            } catch (DataIntegrityViolationException ex) {
                throw new BusinessException("ORGANIZATION_NAME_EXISTS", "Organization name already exists");
            }

            platformAuditService.record(
                    actor,
                    PlatformAuditAction.ORGANIZATION_UPDATED,
                    organization,
                    null,
                    Map.of(
                            "previousName", previousName,
                            "newName", organization.getName(),
                            "previousBaseCurrency", previousCurrency,
                            "newBaseCurrency", organization.getBaseCurrency()
                    )
            );
        }

        return buildDetailResponse(organization);
    }

    public PlatformOrganizationDetailResponse updateOrganizationStatus(
            Long organizationId,
            UpdateOrganizationStatusRequest request,
            Long actorUserId
    ) {
        LedgerUser actor = requireSuperAdminActor(actorUserId);
        Organization organization = loadLockedMutableTenantOrganization(organizationId);

        OrganizationStatus requestedStatus = request.status();
        if (requestedStatus == OrganizationStatus.PENDING) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "Organization status cannot be manually set to PENDING");
        }

        Instant now = Instant.now();
        String trimmedReason = request.reason() == null ? null : request.reason().trim();

        if (requestedStatus == OrganizationStatus.SUSPENDED) {
            if (trimmedReason == null || trimmedReason.isBlank()) {
                throw new BusinessException("SUSPENSION_REASON_REQUIRED", "A reason is required when suspending an organization");
            }
            if (organization.getStatus() == OrganizationStatus.SUSPENDED) {
                throw new BusinessException("ORGANIZATION_ALREADY_SUSPENDED", "Organization is already suspended");
            }

            OrganizationStatus previousStatus = organization.getStatus();
            organization.setStatus(OrganizationStatus.SUSPENDED);
            organization.setSuspendedAt(now);
            organization.setSuspendedReason(trimmedReason);
            organization = organizationRepository.save(organization);

            refreshTokenRepository.revokeActiveByOrganizationId(organization.getId(), now);

            platformAuditService.record(
                    actor,
                    PlatformAuditAction.ORGANIZATION_SUSPENDED,
                    organization,
                    null,
                    Map.of(
                            "previousStatus", previousStatus.name(),
                            "reason", trimmedReason
                    )
            );

            return buildDetailResponse(organization);
        }

        // requestedStatus == OrganizationStatus.ACTIVE (reactivation request)
        if (organization.getStatus() == OrganizationStatus.PENDING) {
            throw new BusinessException("ORGANIZATION_PENDING", "Pending organization cannot be manually activated before onboarding completion");
        }
        if (organization.getStatus() == OrganizationStatus.ACTIVE) {
            throw new BusinessException("ORGANIZATION_ALREADY_ACTIVE", "Organization is already active");
        }

        // Clarification #2: Reactivation from SUSPENDED
        long totalUsers = userRepository.countByOrganizationId(organization.getId());
        long activeAdmins = userRepository.countByOrganizationIdAndRoleAndStatus(
                organization.getId(),
                Role.ADMIN,
                UserStatus.ACTIVE
        );

        OrganizationStatus restoredStatus;
        if (totalUsers == 0) {
            // Initial onboarding was never completed; restore to PENDING rather than bypassing onboarding.
            restoredStatus = OrganizationStatus.PENDING;
        } else if (activeAdmins >= 1) {
            restoredStatus = OrganizationStatus.ACTIVE;
        } else {
            throw new BusinessException(
                    "NO_ACTIVE_ADMIN",
                    "Cannot reactivate organization without at least one active administrator. Enable a disabled administrator or complete a recovery invitation first."
            );
        }

        organization.setStatus(restoredStatus);
        organization.setSuspendedAt(null);
        organization.setSuspendedReason(null);
        organization = organizationRepository.save(organization);

        platformAuditService.record(
                actor,
                PlatformAuditAction.ORGANIZATION_REACTIVATED,
                organization,
                null,
                Map.of(
                        "previousStatus", OrganizationStatus.SUSPENDED.name(),
                        "restoredStatus", restoredStatus.name(),
                        "totalUsers", totalUsers,
                        "activeAdmins", activeAdmins,
                        "note", trimmedReason == null ? "" : trimmedReason
                )
        );

        return buildDetailResponse(organization);
    }

    @Transactional(readOnly = true)
    public List<PlatformOrganizationUserResponse> listOrganizationUsers(Long organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        if (organization.getKind() == OrganizationKind.PLATFORM) {
            throw new ResourceNotFoundException("Organization not found");
        }

        return userRepository.findByOrganizationIdOrderByFullNameAscEmailAsc(organizationId)
                .stream()
                .map(this::toUserResponse)
                .toList();
    }

    public PlatformOrganizationUserResponse updateOrganizationUserStatus(
            Long organizationId,
            Long userId,
            UpdateUserStatusRequest request,
            Long actorUserId
    ) {
        LedgerUser actor = requireSuperAdminActor(actorUserId);
        Organization organization = loadLockedMutableTenantOrganization(organizationId);

        LedgerUser targetUser = userRepository.findLockedWithOrganizationById(userId)
                .filter(u -> u.getOrganization() != null && u.getOrganization().getId().equals(organization.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("User not found in organization"));

        if (targetUser.getRole() == Role.SUPER_ADMIN) {
            throw new ForbiddenException("Cannot modify platform administrator user status");
        }

        UserStatus previousStatus = targetUser.getStatus();
        UserStatus nextStatus = request.status();
        Instant now = Instant.now();

        targetUser.setStatus(nextStatus);
        targetUser = userRepository.save(targetUser);

        if (nextStatus == UserStatus.DISABLED) {
            refreshTokenRepository.revokeActiveByUserId(targetUser.getId(), now);
            if (previousStatus != UserStatus.DISABLED) {
                platformAuditService.record(
                        actor,
                        PlatformAuditAction.TENANT_USER_DISABLED,
                        organization,
                        targetUser,
                        Map.of(
                                "email", targetUser.getEmail(),
                                "role", targetUser.getRole().name()
                        )
                );
            }
        } else {
            // If this user was waiting on a recovery invite and SUPER_ADMIN manually re-enabled them,
            // supersede any stale open recovery invitations targeting them.
            invitationRepository.supersedeOpenByOrganizationIdAndTargetUserId(organization.getId(), targetUser.getId(), now);
            if (previousStatus != UserStatus.ACTIVE) {
                platformAuditService.record(
                        actor,
                        PlatformAuditAction.TENANT_USER_ENABLED,
                        organization,
                        targetUser,
                        Map.of(
                                "email", targetUser.getEmail(),
                                "role", targetUser.getRole().name()
                        )
                );
            }
        }

        return toUserResponse(targetUser);
    }

    public RecoveryInvitationResponse createRecoveryInvitation(
            Long organizationId,
            RecoveryInvitationRequest request,
            Long actorUserId
    ) {
        LedgerUser actor = requireSuperAdminActor(actorUserId);
        Organization organization = loadLockedMutableTenantOrganization(organizationId);

        String normalizedEmail = AuthService.normalizeEmail(request.adminEmail());
        List<LedgerUser> existingUsers = userRepository.findAllWithOrganizationByEmailIgnoreCase(normalizedEmail);

        LedgerUser targetUser = null;
        if (!existingUsers.isEmpty()) {
            LedgerUser existing = existingUsers.getFirst();
            if (existing.getOrganization() == null || !existing.getOrganization().getId().equals(organization.getId())) {
                throw new BusinessException("DUPLICATE_EMAIL", "A user with this email already exists in another organization");
            }
            if (existing.getStatus() == UserStatus.ACTIVE) {
                throw new BusinessException("USER_ALREADY_ACTIVE", "A user with this email is already active in this organization");
            }
            if (existing.getRole() != Role.ADMIN) {
                throw new BusinessException("TARGET_USER_NOT_ADMIN", "Existing disabled user does not hold ADMIN role");
            }
            targetUser = existing;
        }

        Instant now = Instant.now();
        invitationRepository.supersedeOpenByOrganizationIdAndEmail(organization.getId(), normalizedEmail, now);
        if (targetUser != null) {
            invitationRepository.supersedeOpenByOrganizationIdAndTargetUserId(organization.getId(), targetUser.getId(), now);
        }
        if (organization.getStatus() == OrganizationStatus.PENDING) {
            // Replace stale initial onboarding ADMIN invitations when issuing recovery for an un-onboarded org.
            invitationRepository.supersedeOpenAdminInvitesByOrganizationId(organization.getId(), now);
        }

        // BCrypt accepts at most 72 bytes; keep raw invitation token below that limit.
        String rawToken = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");

        Invitation invitation = new Invitation();
        invitation.setOrganization(organization);
        invitation.setInvitedByUser(actor);
        invitation.setInvitedEmail(normalizedEmail);
        invitation.setRole(Role.ADMIN);
        invitation.setPurpose(InvitationPurpose.ADMIN_RECOVERY);
        invitation.setTargetUser(targetUser);
        invitation.setInvitationToken(passwordEncoder.encode(rawToken));
        invitation.setExpiresAt(now.plusSeconds(7L * 24 * 60 * 60));
        invitation = invitationRepository.save(invitation);

        platformAuditService.record(
                actor,
                PlatformAuditAction.ADMIN_RECOVERY_INVITED,
                organization,
                targetUser,
                Map.of(
                        "invitedEmail", normalizedEmail,
                        "targetMode", targetUser != null ? "EXISTING_DISABLED_ADMIN" : "NEW_ADMIN_EMAIL",
                        "invitationId", invitation.getId().toString()
                )
        );

        return new RecoveryInvitationResponse(
                invitation.getId(),
                organization.getId(),
                invitation.getInvitedEmail(),
                invitation.getRole(),
                invitation.getPurpose(),
                targetUser != null ? targetUser.getId() : null,
                rawToken,
                invitation.getExpiresAt(),
                invitation.getCreatedAt()
        );
    }

    private LedgerUser requireSuperAdminActor(Long actorUserId) {
        LedgerUser actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Actor user not found"));
        if (actor.getRole() != Role.SUPER_ADMIN) {
            throw new ForbiddenException("Only SUPER_ADMIN can perform platform operations");
        }
        return actor;
    }

    private Organization loadLockedMutableTenantOrganization(Long organizationId) {
        Organization organization = organizationRepository.findLockedById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        if (organization.getKind() == OrganizationKind.PLATFORM) {
            throw new BusinessException(
                    "PLATFORM_ORGANIZATION_IMMUTABLE",
                    "Platform anchor organization cannot be modified or suspended"
            );
        }
        return organization;
    }

    private PlatformOrganizationDetailResponse buildDetailResponse(Organization organization) {
        long orgId = organization.getId();
        long userCount = userRepository.countByOrganizationId(orgId);
        long activeUserCount = userRepository.countByOrganizationIdAndStatus(orgId, UserStatus.ACTIVE);
        long disabledUserCount = userRepository.countByOrganizationIdAndStatus(orgId, UserStatus.DISABLED);
        long activeAdminCount = userRepository.countByOrganizationIdAndRoleAndStatus(orgId, Role.ADMIN, UserStatus.ACTIVE);
        long accountCount = accountRepository.countByOrganizationId(orgId);
        long postedEntryCount = journalEntryRepository.countByOrganizationIdAndStatus(orgId, JournalEntryStatus.POSTED);
        long draftEntryCount = journalEntryRepository.countByOrganizationIdAndStatus(orgId, JournalEntryStatus.DRAFT);
        boolean hasTransactions = journalEntryRepository.existsByOrganizationId(orgId);

        JournalLineRepository.PostedAccountTotals totals = journalLineRepository.sumPostedDebitsAndCreditsForOrganization(orgId);
        boolean trialBalanceBalanced = totals == null
                || totals.totalDebits().compareTo(totals.totalCredits()) == 0;

        List<PlatformPendingInvitationSummary> pendingInvitations = invitationRepository
                .findByOrganizationIdAndAcceptedAtIsNullAndSupersededAtIsNullOrderByCreatedAtDesc(orgId)
                .stream()
                .map(inv -> new PlatformPendingInvitationSummary(
                        inv.getId(),
                        inv.getInvitedEmail(),
                        inv.getRole(),
                        inv.getPurpose(),
                        inv.getExpiresAt(),
                        inv.getCreatedAt()
                ))
                .toList();

        return new PlatformOrganizationDetailResponse(
                organization.getId(),
                organization.getName(),
                organization.getBaseCurrency(),
                organization.getKind(),
                organization.getStatus(),
                organization.getSuspendedAt(),
                organization.getSuspendedReason(),
                userCount,
                activeUserCount,
                disabledUserCount,
                activeAdminCount,
                accountCount,
                postedEntryCount,
                draftEntryCount,
                hasTransactions,
                trialBalanceBalanced,
                pendingInvitations,
                organization.getCreatedAt(),
                organization.getUpdatedAt()
        );
    }

    private PlatformOrganizationUserResponse toUserResponse(LedgerUser user) {
        return new PlatformOrganizationUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }

    private Instant maxInstant(Instant... candidates) {
        Instant max = null;
        for (Instant candidate : candidates) {
            if (candidate != null && (max == null || candidate.isAfter(max))) {
                max = candidate;
            }
        }
        return max;
    }

    private record UserAggregate(long userCount, long activeAdminCount, long disabledUserCount, Instant lastUpdatedAt) {
        static final UserAggregate EMPTY = new UserAggregate(0L, 0L, 0L, null);
    }

    private record AccountAggregate(long accountCount, Instant lastUpdatedAt) {
        static final AccountAggregate EMPTY = new AccountAggregate(0L, null);
    }

    private record EntryAggregate(long postedEntryCount, long draftEntryCount, Instant lastUpdatedAt) {
        static final EntryAggregate EMPTY = new EntryAggregate(0L, 0L, null);
    }
}

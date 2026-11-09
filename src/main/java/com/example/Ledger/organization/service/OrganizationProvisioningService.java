package com.example.Ledger.organization.service;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.admin.dto.AdminCreateOrganizationRequest;
import com.example.Ledger.admin.dto.AdminCreateOrganizationResponse;
import com.example.Ledger.admin.service.PlatformAuditService;
import com.example.Ledger.auth.service.AuthService;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.InvitationPurpose;
import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.enums.PlatformAuditAction;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.exception.ForbiddenException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.invitation.model.Invitation;
import com.example.Ledger.invitation.repository.InvitationRepository;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class OrganizationProvisioningService {

    private static final String RESERVED_PLATFORM_ORG_NAME = "Ledger Platform";

    private final OrganizationRepository organizationRepository;
    private final LedgerUserRepository userRepository;
    private final AccountRepository accountRepository;
    private final InvitationRepository invitationRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformAuditService platformAuditService;

    public OrganizationProvisioningService(
            OrganizationRepository organizationRepository,
            LedgerUserRepository userRepository,
            AccountRepository accountRepository,
            InvitationRepository invitationRepository,
            PasswordEncoder passwordEncoder,
            PlatformAuditService platformAuditService
    ) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.invitationRepository = invitationRepository;
        this.passwordEncoder = passwordEncoder;
        this.platformAuditService = platformAuditService;
    }

    public AdminCreateOrganizationResponse provisionTenantOrganization(
            AdminCreateOrganizationRequest request,
            Long actorUserId
    ) {
        LedgerUser actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Actor user not found"));
        if (actor.getRole() != Role.SUPER_ADMIN) {
            throw new ForbiddenException("Only SUPER_ADMIN may provision organizations");
        }

        String orgName = request.organizationName().trim();
        if (orgName.isBlank()) {
            throw new BusinessException("ORGANIZATION_NAME_REQUIRED", "Organization name is required");
        }
        if (RESERVED_PLATFORM_ORG_NAME.equalsIgnoreCase(orgName)) {
            throw new BusinessException("RESERVED_ORGANIZATION_NAME", "Organization name 'Ledger Platform' is reserved");
        }
        if (organizationRepository.findByNameIgnoreCase(orgName).isPresent()) {
            throw new BusinessException("ORGANIZATION_NAME_EXISTS", "Organization name already exists");
        }

        String normalizedEmail = AuthService.normalizeEmail(request.adminEmail());
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BusinessException("DUPLICATE_EMAIL", "A user with this email already exists");
        }

        String currency = request.baseCurrency().trim().toUpperCase(Locale.ROOT);

        Organization organization = new Organization();
        organization.setName(orgName);
        organization.setBaseCurrency(currency);
        organization.setKind(OrganizationKind.TENANT);
        organization.setStatus(OrganizationStatus.PENDING);

        try {
            organization = organizationRepository.saveAndFlush(organization);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("ORGANIZATION_NAME_EXISTS", "Organization name already exists");
        }

        seedDefaultChartOfAccounts(organization);

        Instant now = Instant.now();
        // BCrypt accepts at most 72 bytes; keep raw invitation token below that limit.
        String rawToken = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");

        Invitation invitation = new Invitation();
        invitation.setOrganization(organization);
        invitation.setInvitedByUser(actor);
        invitation.setInvitedEmail(normalizedEmail);
        invitation.setRole(Role.ADMIN);
        invitation.setPurpose(InvitationPurpose.ONBOARDING);
        invitation.setTargetUser(null);
        invitation.setInvitationToken(passwordEncoder.encode(rawToken));
        invitation.setExpiresAt(now.plusSeconds(7L * 24 * 60 * 60));
        invitation = invitationRepository.save(invitation);

        platformAuditService.record(
                actor,
                PlatformAuditAction.ORGANIZATION_CREATED,
                organization,
                null,
                Map.of(
                        "organizationName", organization.getName(),
                        "baseCurrency", organization.getBaseCurrency(),
                        "status", organization.getStatus().name(),
                        "invitedAdminEmail", normalizedEmail,
                        "invitationId", invitation.getId().toString()
                )
        );

        return new AdminCreateOrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.getBaseCurrency(),
                organization.getStatus(),
                invitation.getId(),
                invitation.getInvitedEmail(),
                rawToken,
                invitation.getExpiresAt(),
                organization.getCreatedAt()
        );
    }

    private void seedDefaultChartOfAccounts(Organization organization) {
        List<String[]> seeds = List.of(
                new String[] {"1000", "Cash", "ASSET"},
                new String[] {"1100", "Accounts Receivable", "ASSET"},
                new String[] {"2000", "Accounts Payable", "LIABILITY"},
                new String[] {"3000", "Owner Equity", "EQUITY"},
                new String[] {"4000", "Revenue", "REVENUE"},
                new String[] {"5000", "Operating Expenses", "EXPENSE"}
        );

        for (String[] seed : seeds) {
            Account account = new Account();
            account.setOrganization(organization);
            account.setAccountCode(seed[0]);
            account.setName(seed[1]);
            account.setAccountType(AccountType.valueOf(seed[2]));
            account.setStatus(AccountStatus.ACTIVE);
            accountRepository.save(account);
        }
    }
}

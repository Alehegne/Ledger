package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.enums.UserStatus;
import com.example.Ledger.common.security.JwtService;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformAdminIntegrationTest extends PostgresIntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private LedgerUserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Organization platformOrg;
    private LedgerUser superAdmin;
    private String superAdminToken;

    @BeforeEach
    void resetDatabaseWithPlatformAnchor() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE platform_audit_log, refresh_token, journal_line, journal_entry, invitation, audit_log, app_user, account, organization CASCADE"
        );

        platformOrg = new Organization();
        platformOrg.setName("Ledger Platform");
        platformOrg.setBaseCurrency("USD");
        platformOrg.setKind(OrganizationKind.PLATFORM);
        platformOrg.setStatus(OrganizationStatus.ACTIVE);
        platformOrg = organizationRepository.save(platformOrg);

        superAdmin = new LedgerUser();
        superAdmin.setOrganization(platformOrg);
        superAdmin.setEmail("superadmin." + UUID.randomUUID().toString().substring(0, 6) + "@platform.test");
        superAdmin.setFullName("Platform Super Admin");
        superAdmin.setPasswordHash(passwordEncoder.encode("SuperAdminPass123!"));
        superAdmin.setRole(Role.SUPER_ADMIN);
        superAdmin.setStatus(UserStatus.ACTIVE);
        superAdmin = userRepository.save(superAdmin);

        superAdminToken = jwtService.generateToken(superAdmin);
    }

    @Test
    void platformAnchorIsExcludedAndImmutableWhileSuperAdminIsBlockedFromTenantDataPlane() throws Exception {
        HttpResponse<String> overviewRes = send("GET", "/admin/overview", superAdminToken, null);
        assertThat(overviewRes.statusCode()).isEqualTo(200);
        JsonNode overview = objectMapper.readTree(overviewRes.body());
        assertThat(overview.get("totalTenantOrganizations").asLong()).isZero();
        assertThat(overview.get("totalTenantUsers").asLong()).isZero();

        HttpResponse<String> listRes = send("GET", "/admin/organizations", superAdminToken, null);
        assertThat(listRes.statusCode()).isEqualTo(200);
        JsonNode listBody = objectMapper.readTree(listRes.body());
        assertThat(listBody.get("totalElements").asLong()).isZero();
        assertThat(listBody.get("items")).isEmpty();

        assertThat(send("GET", "/admin/organizations/" + platformOrg.getId(), superAdminToken, null).statusCode())
                .isEqualTo(404);
        assertThat(send("GET", "/admin/organizations/" + platformOrg.getId() + "/users", superAdminToken, null).statusCode())
                .isEqualTo(404);

        HttpResponse<String> patchPlatformRes = send(
                "PATCH",
                "/admin/organizations/" + platformOrg.getId(),
                superAdminToken,
                Map.of("name", "Renamed Platform")
        );
        assertThat(patchPlatformRes.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(patchPlatformRes.body()).get("code").asText())
                .isEqualTo("PLATFORM_ORGANIZATION_IMMUTABLE");

        HttpResponse<String> suspendPlatformRes = send(
                "PATCH",
                "/admin/organizations/" + platformOrg.getId() + "/status",
                superAdminToken,
                Map.of("status", "SUSPENDED", "reason", "Should fail")
        );
        assertThat(suspendPlatformRes.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(suspendPlatformRes.body()).get("code").asText())
                .isEqualTo("PLATFORM_ORGANIZATION_IMMUTABLE");

        HttpResponse<String> recoveryPlatformRes = send(
                "POST",
                "/admin/organizations/" + platformOrg.getId() + "/recovery-invitation",
                superAdminToken,
                Map.of("adminEmail", "recover@platform.test")
        );
        assertThat(recoveryPlatformRes.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(recoveryPlatformRes.body()).get("code").asText())
                .isEqualTo("PLATFORM_ORGANIZATION_IMMUTABLE");

        // SUPER_ADMIN is strictly blocked from customer accounting endpoints
        assertThat(send("GET", "/accounts", superAdminToken, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/journal-entries", superAdminToken, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/reports/trial-balance", superAdminToken, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/dashboard/overview", superAdminToken, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/audit-logs", superAdminToken, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/organizations/me", superAdminToken, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/users", superAdminToken, null).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/users/invite", superAdminToken, Map.of("email", "x@y.test", "role", "VIEWER")).statusCode())
                .isEqualTo(403);
    }

    @Test
    void tenantUsersCannotAccessAdminOrEscalateRolesAndRetiredRegistrationEndpointsAreGone() throws Exception {
        Organization tenantOrg = createTenantOrganization("Tenant Guard Co", "USD", OrganizationStatus.ACTIVE);
        LedgerUser tenantAdmin = createUser(tenantOrg, "admin@tenantguard.test", "Tenant Admin", Role.ADMIN, UserStatus.ACTIVE);
        LedgerUser tenantMember = createUser(tenantOrg, "viewer@tenantguard.test", "Tenant Viewer", Role.VIEWER, UserStatus.ACTIVE);
        String tenantAdminToken = jwtService.generateToken(tenantAdmin);

        assertThat(send("GET", "/admin/overview", tenantAdminToken, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/admin/organizations", tenantAdminToken, null).statusCode()).isEqualTo(403);
        assertThat(send(
                "POST",
                "/admin/organizations",
                tenantAdminToken,
                Map.of("organizationName", "Blocked Org", "baseCurrency", "USD", "adminEmail", "b@b.test")
        ).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/admin/audit-logs", tenantAdminToken, null).statusCode()).isEqualTo(403);

        HttpResponse<String> inviteSuperAdmin = send(
                "POST",
                "/users/invite",
                tenantAdminToken,
                Map.of("email", "escalate@tenantguard.test", "role", "SUPER_ADMIN")
        );
        assertThat(inviteSuperAdmin.statusCode()).isEqualTo(403);

        HttpResponse<String> promoteSuperAdmin = send(
                "PATCH",
                "/users/" + tenantMember.getId() + "/role",
                tenantAdminToken,
                Map.of("role", "SUPER_ADMIN")
        );
        assertThat(promoteSuperAdmin.statusCode()).isEqualTo(403);

        // Retired self-registration & legacy org creation endpoints must not exist
        assertThat(send(
                "POST",
                "/auth/register-organization",
                null,
                Map.of("organizationName", "Self Reg", "baseCurrency", "USD", "fullName", "A", "email", "a@a.test", "password", "Pass1234!")
        ).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/organizations", null, Map.of("name", "Legacy")).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/auth/register-organization", tenantAdminToken, Map.of()).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/organizations", tenantAdminToken, Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    void inviteFirstProvisioningValidationPendingRestrictionsAndOnboardingLifecycle() throws Exception {
        // Reserved name rejection
        HttpResponse<String> reservedRes = send(
                "POST",
                "/admin/organizations",
                superAdminToken,
                Map.of("organizationName", "ledger platform", "baseCurrency", "USD", "adminEmail", "new@acme.test")
        );
        assertThat(reservedRes.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(reservedRes.body()).get("code").asText()).isEqualTo("RESERVED_ORGANIZATION_NAME");

        // Provision new org -> PENDING, 6 CoA accounts, 0 users
        HttpResponse<String> createRes = send(
                "POST",
                "/admin/organizations",
                superAdminToken,
                Map.of("organizationName", "Northwind Advisory", "baseCurrency", "eur", "adminEmail", "Founder@Northwind.TEST")
        );
        assertThat(createRes.statusCode()).isEqualTo(201);
        JsonNode created = objectMapper.readTree(createRes.body());
        long orgId = created.get("organizationId").asLong();
        long invitationId = created.get("invitationId").asLong();
        String onboardingToken = created.get("invitationToken").asText();
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("baseCurrency").asText()).isEqualTo("EUR");
        assertThat(created.get("invitedAdminEmail").asText()).isEqualTo("founder@northwind.test");
        assertThat(onboardingToken).isNotBlank();
        assertThat(userRepository.countByOrganizationId(orgId)).isZero();
        assertThat(accountRepository.countByOrganizationId(orgId)).isEqualTo(6);

        // Case-insensitive duplicate org name rejected with 409 ORGANIZATION_NAME_EXISTS
        HttpResponse<String> dupOrgRes = send(
                "POST",
                "/admin/organizations",
                superAdminToken,
                Map.of("organizationName", "northwind advisory", "baseCurrency", "USD", "adminEmail", "other@northwind.test")
        );
        assertThat(dupOrgRes.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(dupOrgRes.body()).get("code").asText()).isEqualTo("ORGANIZATION_NAME_EXISTS");

        // Platform detail view shows pending invitation metadata without exposing token hash or plaintext token
        HttpResponse<String> detailRes = send("GET", "/admin/organizations/" + orgId, superAdminToken, null);
        assertThat(detailRes.statusCode()).isEqualTo(200);
        assertThat(detailRes.body()).doesNotContain(onboardingToken);
        JsonNode detail = objectMapper.readTree(detailRes.body());
        assertThat(detail.get("status").asText()).isEqualTo("PENDING");
        assertThat(detail.get("accountCount").asLong()).isEqualTo(6);
        assertThat(detail.get("pendingInvitations")).hasSize(1);
        assertThat(detail.get("pendingInvitations").get(0).get("purpose").asText()).isEqualTo("ONBOARDING");

        // Overview counts PENDING org cleanly
        JsonNode overviewPending = objectMapper.readTree(send("GET", "/admin/overview", superAdminToken, null).body());
        assertThat(overviewPending.get("totalTenantOrganizations").asLong()).isEqualTo(1);
        assertThat(overviewPending.get("pendingTenantOrganizations").asLong()).isEqualTo(1);
        assertThat(overviewPending.get("activeTenantOrganizations").asLong()).isZero();
        assertThat(overviewPending.get("totalTenantAccounts").asLong()).isEqualTo(6);

        // Complete onboarding -> transitions org to ACTIVE and allows admin login
        HttpResponse<String> acceptRes = send(
                "POST",
                "/invitations/accept",
                null,
                Map.of(
                        "token", onboardingToken,
                        "email", "founder@northwind.test",
                        "fullName", "Nadia Founder",
                        "password", "FounderPass123!"
                )
        );
        assertThat(acceptRes.statusCode()).isEqualTo(200);

        Organization activatedOrg = organizationRepository.findById(orgId).orElseThrow();
        assertThat(activatedOrg.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);

        JsonNode overviewActive = objectMapper.readTree(send("GET", "/admin/overview", superAdminToken, null).body());
        assertThat(overviewActive.get("activeTenantOrganizations").asLong()).isEqualTo(1);
        assertThat(overviewActive.get("pendingTenantOrganizations").asLong()).isZero();

        JsonNode loginNode = login("founder@northwind.test", "FounderPass123!");
        String founderAccessToken = loginNode.get("token").asText();
        assertThat(loginNode.get("user").get("role").asText()).isEqualTo("ADMIN");

        // Check a separate PENDING org user cannot log in or use pre-issued JWT (ORGANIZATION_PENDING 403)
        Organization otherPendingOrg = createTenantOrganization("Still Pending Co", "USD", OrganizationStatus.PENDING);
        LedgerUser stuckUser = createUser(otherPendingOrg, "stuck@pending.test", "Stuck User", Role.ADMIN, UserStatus.ACTIVE);
        String stuckJwt = jwtService.generateToken(stuckUser);
        HttpResponse<String> pendingJwtRes = send("GET", "/accounts", stuckJwt, null);
        assertThat(pendingJwtRes.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(pendingJwtRes.body()).get("code").asText()).isEqualTo("ORGANIZATION_PENDING");
        HttpResponse<String> pendingLoginRes = send(
                "POST",
                "/auth/login",
                null,
                Map.of("email", "stuck@pending.test", "password", "TestPass123!")
        );
        assertThat(pendingLoginRes.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(pendingLoginRes.body()).get("code").asText()).isEqualTo("ORGANIZATION_PENDING");

        // Tenant ADMIN cannot revoke a platform-managed ADMIN_RECOVERY invitation (even if unaccepted on their org)
        LedgerUser disabledAdmin = createUser(activatedOrg, "disabled@northwind.test", "Disabled Admin", Role.ADMIN, UserStatus.DISABLED);
        HttpResponse<String> recoveryRes = send(
                "POST",
                "/admin/organizations/" + orgId + "/recovery-invitation",
                superAdminToken,
                Map.of("adminEmail", disabledAdmin.getEmail())
        );
        assertThat(recoveryRes.statusCode()).isEqualTo(201);
        long recoveryInviteId = objectMapper.readTree(recoveryRes.body()).get("invitationId").asLong();

        HttpResponse<String> revokeRecoveryRes = send("DELETE", "/invitations/" + recoveryInviteId, founderAccessToken, null);
        assertThat(revokeRecoveryRes.statusCode()).isEqualTo(403);
    }

    @Test
    void suspensionRevokesTokensBlocksTenantAccessAndReactivationFollowsClarificationTwo() throws Exception {
        // Scenario A: PENDING org with 0 users -> suspend -> reactivate restores PENDING
        HttpResponse<String> pendingCreateRes = send(
                "POST",
                "/admin/organizations",
                superAdminToken,
                Map.of("organizationName", "Pending Reactivate Co", "baseCurrency", "USD", "adminEmail", "init@pendingco.test")
        );
        assertThat(pendingCreateRes.statusCode()).isEqualTo(201);
        long pendingOrgId = objectMapper.readTree(pendingCreateRes.body()).get("organizationId").asLong();

        assertThat(send(
                "PATCH",
                "/admin/organizations/" + pendingOrgId + "/status",
                superAdminToken,
                Map.of("status", "SUSPENDED", "reason", "Compliance hold")
        ).statusCode()).isEqualTo(200);

        HttpResponse<String> pendingReactivateRes = send(
                "PATCH",
                "/admin/organizations/" + pendingOrgId + "/status",
                superAdminToken,
                Map.of("status", "ACTIVE", "reason", "Hold cleared")
        );
        assertThat(pendingReactivateRes.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(pendingReactivateRes.body()).get("status").asText()).isEqualTo("PENDING");

        // Scenario B: ACTIVE org with 1 admin -> suspend requires reason, revokes refresh tokens, blocks JWT & login
        Organization org = createTenantOrganization("Suspend Lifecycle Co", "USD", OrganizationStatus.ACTIVE);
        LedgerUser admin = createUser(org, "soleadmin@suspendco.test", "Sole Admin", Role.ADMIN, UserStatus.ACTIVE);
        JsonNode loginNode = login(admin.getEmail(), "TestPass123!");
        String accessToken = loginNode.get("token").asText();
        String refreshToken = loginNode.get("refreshToken").asText();

        HttpResponse<String> missingReasonRes = send(
                "PATCH",
                "/admin/organizations/" + org.getId() + "/status",
                superAdminToken,
                Map.of("status", "SUSPENDED", "reason", "   ")
        );
        assertThat(missingReasonRes.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(missingReasonRes.body()).get("code").asText())
                .isEqualTo("SUSPENSION_REASON_REQUIRED");

        HttpResponse<String> suspendRes = send(
                "PATCH",
                "/admin/organizations/" + org.getId() + "/status",
                superAdminToken,
                Map.of("status", "SUSPENDED", "reason", "Delinquent billing")
        );
        assertThat(suspendRes.statusCode()).isEqualTo(200);
        JsonNode suspendedBody = objectMapper.readTree(suspendRes.body());
        assertThat(suspendedBody.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(suspendedBody.get("suspendedReason").asText()).isEqualTo("Delinquent billing");

        // Refresh token was revoked on suspension; active JWT and login both blocked with 403 ORGANIZATION_SUSPENDED
        assertThat(send("POST", "/auth/refresh", null, Map.of("refreshToken", refreshToken)).statusCode()).isEqualTo(401);
        HttpResponse<String> suspendedJwtRes = send("GET", "/accounts", accessToken, null);
        assertThat(suspendedJwtRes.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(suspendedJwtRes.body()).get("code").asText()).isEqualTo("ORGANIZATION_SUSPENDED");
        HttpResponse<String> suspendedLoginRes = send(
                "POST",
                "/auth/login",
                null,
                Map.of("email", admin.getEmail(), "password", "TestPass123!")
        );
        assertThat(suspendedLoginRes.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(suspendedLoginRes.body()).get("code").asText()).isEqualTo("ORGANIZATION_SUSPENDED");

        // Disable sole admin -> reactivation must reject with 409 NO_ACTIVE_ADMIN
        assertThat(send(
                "PATCH",
                "/admin/organizations/" + org.getId() + "/users/" + admin.getId() + "/status",
                superAdminToken,
                Map.of("status", "DISABLED")
        ).statusCode()).isEqualTo(200);

        HttpResponse<String> reactivateNoAdminRes = send(
                "PATCH",
                "/admin/organizations/" + org.getId() + "/status",
                superAdminToken,
                Map.of("status", "ACTIVE")
        );
        assertThat(reactivateNoAdminRes.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(reactivateNoAdminRes.body()).get("code").asText()).isEqualTo("NO_ACTIVE_ADMIN");
        assertThat(organizationRepository.findById(org.getId()).orElseThrow().getStatus())
                .isEqualTo(OrganizationStatus.SUSPENDED);

        // Re-enable sole admin -> reactivation succeeds and restores access
        assertThat(send(
                "PATCH",
                "/admin/organizations/" + org.getId() + "/users/" + admin.getId() + "/status",
                superAdminToken,
                Map.of("status", "ACTIVE")
        ).statusCode()).isEqualTo(200);

        HttpResponse<String> reactivateSuccessRes = send(
                "PATCH",
                "/admin/organizations/" + org.getId() + "/status",
                superAdminToken,
                Map.of("status", "ACTIVE", "reason", "Invoice settled")
        );
        assertThat(reactivateSuccessRes.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(reactivateSuccessRes.body()).get("status").asText()).isEqualTo("ACTIVE");

        JsonNode reLogin = login(admin.getEmail(), "TestPass123!");
        assertThat(send("GET", "/organizations/me", reLogin.get("token").asText(), null).statusCode()).isEqualTo(200);
    }

    @Test
    void userLifecycleAndRecoveryInvitationEnforceClarificationThreeSafetyRules() throws Exception {
        Organization org = createTenantOrganization("Recovery Safety Co", "USD", OrganizationStatus.ACTIVE);
        Organization otherOrg = createTenantOrganization("Foreign Recovery Co", "USD", OrganizationStatus.ACTIVE);

        LedgerUser primaryAdmin = createUser(org, "locked.admin@recovery.test", "Locked Admin", Role.ADMIN, UserStatus.ACTIVE);
        LedgerUser accountant = createUser(org, "staff@recovery.test", "Staff Accountant", Role.ACCOUNTANT, UserStatus.DISABLED);
        LedgerUser foreignUser = createUser(otherOrg, "elsewhere@foreign.test", "Elsewhere User", Role.ADMIN, UserStatus.ACTIVE);

        // Log in primaryAdmin to capture active tokens, then disable them
        JsonNode initialLogin = login(primaryAdmin.getEmail(), "TestPass123!");
        String accessToken = initialLogin.get("token").asText();
        String refreshToken = initialLogin.get("refreshToken").asText();

        HttpResponse<String> disableRes = send(
                "PATCH",
                "/admin/organizations/" + org.getId() + "/users/" + primaryAdmin.getId() + "/status",
                superAdminToken,
                Map.of("status", "DISABLED")
        );
        assertThat(disableRes.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(disableRes.body()).get("status").asText()).isEqualTo("DISABLED");

        assertThat(send("POST", "/auth/refresh", null, Map.of("refreshToken", refreshToken)).statusCode()).isEqualTo(401);
        HttpResponse<String> disabledJwtRes = send("GET", "/organizations/me", accessToken, null);
        assertThat(disabledJwtRes.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(disabledJwtRes.body()).get("code").asText()).isEqualTo("USER_DISABLED");
        HttpResponse<String> disabledLoginRes = send(
                "POST",
                "/auth/login",
                null,
                Map.of("email", primaryAdmin.getEmail(), "password", "TestPass123!")
        );
        assertThat(disabledLoginRes.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(disabledLoginRes.body()).get("code").asText()).isEqualTo("USER_DISABLED");

        // Validation guards on recovery invitation creation
        LedgerUser secondActiveAdmin = createUser(org, "active.admin@recovery.test", "Active Admin", Role.ADMIN, UserStatus.ACTIVE);
        assertThat(objectMapper.readTree(send(
                "POST",
                "/admin/organizations/" + org.getId() + "/recovery-invitation",
                superAdminToken,
                Map.of("adminEmail", foreignUser.getEmail())
        ).body()).get("code").asText()).isEqualTo("DUPLICATE_EMAIL");

        assertThat(objectMapper.readTree(send(
                "POST",
                "/admin/organizations/" + org.getId() + "/recovery-invitation",
                superAdminToken,
                Map.of("adminEmail", secondActiveAdmin.getEmail())
        ).body()).get("code").asText()).isEqualTo("USER_ALREADY_ACTIVE");

        assertThat(objectMapper.readTree(send(
                "POST",
                "/admin/organizations/" + org.getId() + "/recovery-invitation",
                superAdminToken,
                Map.of("adminEmail", accountant.getEmail())
        ).body()).get("code").asText()).isEqualTo("TARGET_USER_NOT_ADMIN");

        // Issue 1st recovery invite for disabled primaryAdmin, then supersede with 2nd
        HttpResponse<String> firstInviteRes = send(
                "POST",
                "/admin/organizations/" + org.getId() + "/recovery-invitation",
                superAdminToken,
                Map.of("adminEmail", primaryAdmin.getEmail())
        );
        assertThat(firstInviteRes.statusCode()).isEqualTo(201);
        JsonNode firstInvite = objectMapper.readTree(firstInviteRes.body());
        assertThat(firstInvite.get("targetUserId").asLong()).isEqualTo(primaryAdmin.getId());
        String firstToken = firstInvite.get("invitationToken").asText();

        HttpResponse<String> secondInviteRes = send(
                "POST",
                "/admin/organizations/" + org.getId() + "/recovery-invitation",
                superAdminToken,
                Map.of("adminEmail", primaryAdmin.getEmail())
        );
        assertThat(secondInviteRes.statusCode()).isEqualTo(201);
        String secondToken = objectMapper.readTree(secondInviteRes.body()).get("invitationToken").asText();

        // Superseded token must fail
        HttpResponse<String> supersededAcceptRes = send(
                "POST",
                "/invitations/accept",
                null,
                Map.of(
                        "token", firstToken,
                        "email", primaryAdmin.getEmail(),
                        "fullName", "Recovered Admin",
                        "password", "NewRecoveredPass123!"
                )
        );
        assertThat(supersededAcceptRes.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(supersededAcceptRes.body()).get("code").asText())
                .isEqualTo("INVITATION_SUPERSEDED");

        // Wrong email on valid token must fail
        HttpResponse<String> wrongEmailRes = send(
                "POST",
                "/invitations/accept",
                null,
                Map.of(
                        "token", secondToken,
                        "email", "someone.else@recovery.test",
                        "fullName", "Recovered Admin",
                        "password", "NewRecoveredPass123!"
                )
        );
        assertThat(wrongEmailRes.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(wrongEmailRes.body()).get("code").asText())
                .isEqualTo("INVALID_INVITATION");

        long userCountBefore = userRepository.countByOrganizationId(org.getId());

        // Valid acceptance updates the EXACT disabled target user in place without creating duplicates
        HttpResponse<String> validAcceptRes = send(
                "POST",
                "/invitations/accept",
                null,
                Map.of(
                        "token", secondToken,
                        "email", primaryAdmin.getEmail(),
                        "fullName", "Recovered Primary Admin",
                        "password", "NewRecoveredPass123!"
                )
        );
        assertThat(validAcceptRes.statusCode()).isEqualTo(200);
        assertThat(userRepository.countByOrganizationId(org.getId())).isEqualTo(userCountBefore);

        LedgerUser reloaded = userRepository.findById(primaryAdmin.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(reloaded.getFullName()).isEqualTo("Recovered Primary Admin");
        assertThat(reloaded.getRole()).isEqualTo(Role.ADMIN);

        // Old password rejected; new password accepted
        assertThat(send("POST", "/auth/login", null, Map.of("email", primaryAdmin.getEmail(), "password", "TestPass123!")).statusCode())
                .isEqualTo(401);
        login(primaryAdmin.getEmail(), "NewRecoveredPass123!");

        // Replay of used recovery token fails
        HttpResponse<String> replayRes = send(
                "POST",
                "/invitations/accept",
                null,
                Map.of(
                        "token", secondToken,
                        "email", primaryAdmin.getEmail(),
                        "fullName", "Replay Attack",
                        "password", "ReplayPass123!"
                )
        );
        assertThat(replayRes.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(replayRes.body()).get("code").asText()).isEqualTo("INVITATION_ALREADY_USED");

        // Manual re-enable by SUPER_ADMIN supersedes any open recovery invite for that user
        send("PATCH", "/admin/organizations/" + org.getId() + "/users/" + primaryAdmin.getId() + "/status",
                superAdminToken, Map.of("status", "DISABLED"));
        String thirdToken = objectMapper.readTree(send(
                "POST",
                "/admin/organizations/" + org.getId() + "/recovery-invitation",
                superAdminToken,
                Map.of("adminEmail", primaryAdmin.getEmail())
        ).body()).get("invitationToken").asText();
        send("PATCH", "/admin/organizations/" + org.getId() + "/users/" + primaryAdmin.getId() + "/status",
                superAdminToken, Map.of("status", "ACTIVE"));
        HttpResponse<String> postReenableAccept = send(
                "POST",
                "/invitations/accept",
                null,
                Map.of(
                        "token", thirdToken,
                        "email", primaryAdmin.getEmail(),
                        "fullName", "Stale Accept",
                        "password", "StalePass123!"
                )
        );
        assertThat(postReenableAccept.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(postReenableAccept.body()).get("code").asText())
                .isEqualTo("INVITATION_SUPERSEDED");

        // Recovery with NEW email on a SUSPENDED org keeps org SUSPENDED until explicit reactivation
        Organization suspendedOrg = createTenantOrganization("Suspended Recovery Co", "USD", OrganizationStatus.SUSPENDED);
        suspendedOrg.setSuspendedReason("Admin left company");
        suspendedOrg = organizationRepository.save(suspendedOrg);
        createUser(suspendedOrg, "departed@suspendedco.test", "Departed Admin", Role.ADMIN, UserStatus.DISABLED);

        assertThat(objectMapper.readTree(send(
                "PATCH",
                "/admin/organizations/" + suspendedOrg.getId() + "/status",
                superAdminToken,
                Map.of("status", "ACTIVE")
        ).body()).get("code").asText()).isEqualTo("NO_ACTIVE_ADMIN");

        HttpResponse<String> newEmailRecoveryRes = send(
                "POST",
                "/admin/organizations/" + suspendedOrg.getId() + "/recovery-invitation",
                superAdminToken,
                Map.of("adminEmail", "replacement@suspendedco.test")
        );
        assertThat(newEmailRecoveryRes.statusCode()).isEqualTo(201);
        JsonNode newEmailRecovery = objectMapper.readTree(newEmailRecoveryRes.body());
        assertThat(newEmailRecovery.get("targetUserId").isNull()).isTrue();
        String newAdminToken = newEmailRecovery.get("invitationToken").asText();

        assertThat(send(
                "POST",
                "/invitations/accept",
                null,
                Map.of(
                        "token", newAdminToken,
                        "email", "replacement@suspendedco.test",
                        "fullName", "Replacement Admin",
                        "password", "ReplacementPass123!"
                )
        ).statusCode()).isEqualTo(200);

        // Organization remains SUSPENDED after recovery acceptance until explicit SUPER_ADMIN reactivation
        assertThat(organizationRepository.findById(suspendedOrg.getId()).orElseThrow().getStatus())
                .isEqualTo(OrganizationStatus.SUSPENDED);

        HttpResponse<String> explicitReactivate = send(
                "PATCH",
                "/admin/organizations/" + suspendedOrg.getId() + "/status",
                superAdminToken,
                Map.of("status", "ACTIVE", "reason", "Replacement admin verified")
        );
        assertThat(explicitReactivate.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(explicitReactivate.body()).get("status").asText()).isEqualTo("ACTIVE");
        login("replacement@suspendedco.test", "ReplacementPass123!");
    }

    @Test
    void organizationMetadataEditCurrencyLockAndPlatformAuditLogHygiene() throws Exception {
        HttpResponse<String> createRes = send(
                "POST",
                "/admin/organizations",
                superAdminToken,
                Map.of("organizationName", "Audit Filter Org", "baseCurrency", "USD", "adminEmail", "owner@auditfilter.test")
        );
        assertThat(createRes.statusCode()).isEqualTo(201);
        JsonNode created = objectMapper.readTree(createRes.body());
        long orgId = created.get("organizationId").asLong();
        String onboardingToken = created.get("invitationToken").asText();

        // Accept onboarding invite
        assertThat(send(
                "POST",
                "/invitations/accept",
                null,
                Map.of(
                        "token", onboardingToken,
                        "email", "owner@auditfilter.test",
                        "fullName", "Owner Filter",
                        "password", "OwnerPass123!"
                )
        ).statusCode()).isEqualTo(200);

        // Before journal entries exist: baseCurrency can be changed
        HttpResponse<String> preTxUpdate = send(
                "PATCH",
                "/admin/organizations/" + orgId,
                superAdminToken,
                Map.of("name", "Audit Filter Org V2", "baseCurrency", "gbp")
        );
        assertThat(preTxUpdate.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(preTxUpdate.body()).get("baseCurrency").asText()).isEqualTo("GBP");

        // Create and post a balanced journal entry via HTTP in the tenant organization -> locks baseCurrency
        String ownerToken = login("owner@auditfilter.test", "OwnerPass123!").get("token").asText();
        LedgerUser owner = userRepository.findAllByEmailIgnoreCase("owner@auditfilter.test").getFirst();
        List<Account> accounts = accountRepository.findByOrganizationIdOrderByAccountCodeAsc(orgId);
        Account debitAcc = accounts.get(0);
        Account creditAcc = accounts.get(4);

        HttpResponse<String> draftRes = send(
                "POST",
                "/journal-entries",
                ownerToken,
                Map.of(
                        "transactionDate", LocalDate.now().toString(),
                        "description", "Initial transaction",
                        "reference", "TX-1",
                        "lines", List.of(
                                Map.of("accountId", debitAcc.getId(), "side", "DEBIT", "amount", new BigDecimal("100.00")),
                                Map.of("accountId", creditAcc.getId(), "side", "CREDIT", "amount", new BigDecimal("100.00"))
                        )
                )
        );
        assertThat(draftRes.statusCode()).isEqualTo(200);
        long entryId = objectMapper.readTree(draftRes.body()).get("id").asLong();
        assertThat(send("POST", "/journal-entries/" + entryId + "/post", ownerToken, null).statusCode())
                .isEqualTo(200);

        HttpResponse<String> lockedCurrencyRes = send(
                "PATCH",
                "/admin/organizations/" + orgId,
                superAdminToken,
                Map.of("baseCurrency", "EUR")
        );
        assertThat(lockedCurrencyRes.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(lockedCurrencyRes.body()).get("code").asText()).isEqualTo("BASE_CURRENCY_LOCKED");

        // Exercise user disable/enable, recovery invite, suspend, reactivate to cover all 7 PlatformAuditAction types
        send("PATCH", "/admin/organizations/" + orgId + "/users/" + owner.getId() + "/status",
                superAdminToken, Map.of("status", "DISABLED"));
        String recoveryToken = objectMapper.readTree(send(
                "POST",
                "/admin/organizations/" + orgId + "/recovery-invitation",
                superAdminToken,
                Map.of("adminEmail", owner.getEmail())
        ).body()).get("invitationToken").asText();
        send("PATCH", "/admin/organizations/" + orgId + "/users/" + owner.getId() + "/status",
                superAdminToken, Map.of("status", "ACTIVE"));
        send("PATCH", "/admin/organizations/" + orgId + "/status",
                superAdminToken, Map.of("status", "SUSPENDED", "reason", "Routine audit"));
        send("PATCH", "/admin/organizations/" + orgId + "/status",
                superAdminToken, Map.of("status", "ACTIVE", "reason", "Audit complete"));

        String today = LocalDate.now().toString();
        HttpResponse<String> auditLogsRes = send(
                "GET",
                "/admin/audit-logs?targetOrganizationId=" + orgId + "&from=" + today + "&to=" + today + "&size=50",
                superAdminToken,
                null
        );
        assertThat(auditLogsRes.statusCode()).isEqualTo(200);
        JsonNode auditBody = objectMapper.readTree(auditLogsRes.body());
        Set<String> seenActions = new HashSet<>();
        for (JsonNode item : auditBody.get("items")) {
            seenActions.add(item.get("action").asText());
            assertThat(item.get("actor").asText()).contains(superAdmin.getEmail());
        }
        assertThat(seenActions).containsExactlyInAnyOrder(
                "ORGANIZATION_CREATED",
                "ORGANIZATION_UPDATED",
                "TENANT_USER_DISABLED",
                "ADMIN_RECOVERY_INVITED",
                "TENANT_USER_ENABLED",
                "ORGANIZATION_SUSPENDED",
                "ORGANIZATION_REACTIVATED"
        );

        // Zero raw tokens or password secrets in DB audit logs (platform or tenant)
        List<String> platformMetadata = jdbcTemplate.queryForList(
                "SELECT COALESCE(metadata_json, '') FROM platform_audit_log", String.class);
        for (String meta : platformMetadata) {
            assertThat(meta).doesNotContain(onboardingToken);
            assertThat(meta).doesNotContain(recoveryToken);
            assertThat(meta).doesNotContain("OwnerPass123!");
        }
        List<String> tenantMetadata = jdbcTemplate.queryForList(
                "SELECT COALESCE(metadata_json, '') FROM audit_log", String.class);
        for (String meta : tenantMetadata) {
            assertThat(meta).doesNotContain(superAdmin.getEmail());
            assertThat(meta).doesNotContain(onboardingToken);
            assertThat(meta).doesNotContain(recoveryToken);
        }

        // Action filter query parameter returns only matching action
        HttpResponse<String> filteredActionRes = send(
                "GET",
                "/admin/audit-logs?action=ORGANIZATION_SUSPENDED&targetOrganizationId=" + orgId,
                superAdminToken,
                null
        );
        assertThat(filteredActionRes.statusCode()).isEqualTo(200);
        JsonNode filteredItems = objectMapper.readTree(filteredActionRes.body()).get("items");
        assertThat(filteredItems).hasSize(1);
        assertThat(filteredItems.get(0).get("action").asText()).isEqualTo("ORGANIZATION_SUSPENDED");
    }

    private Organization createTenantOrganization(String name, String currency, OrganizationStatus status) {
        Organization org = new Organization();
        org.setName(name + " " + UUID.randomUUID().toString().substring(0, 6));
        org.setBaseCurrency(currency);
        org.setKind(OrganizationKind.TENANT);
        org.setStatus(status);
        return organizationRepository.save(org);
    }

    private LedgerUser createUser(Organization org, String email, String fullName, Role role, UserStatus status) {
        LedgerUser user = new LedgerUser();
        user.setOrganization(org);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode("TestPass123!"));
        user.setRole(role);
        user.setStatus(status);
        return userRepository.save(user);
    }

    private JsonNode login(String email, String password) throws Exception {
        HttpResponse<String> res = send(
                "POST",
                "/auth/login",
                null,
                Map.of("email", email, "password", password)
        );
        assertThat(res.statusCode()).isEqualTo(200);
        return objectMapper.readTree(res.body());
    }

    private HttpResponse<String> send(String method, String path, String bearerToken, Object body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            String json = objectMapper.writeValueAsString(body);
            builder.method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

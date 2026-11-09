package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.audit.repository.AuditLogRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.security.JwtService;
import com.example.Ledger.invitation.repository.InvitationRepository;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditAndUserGovernanceIntegrationTest extends PostgresIntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private LedgerUserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private Organization createOrganization(String name, String currency) {
        Organization organization = new Organization();
        organization.setName(name);
        organization.setBaseCurrency(currency);
        return organizationRepository.save(organization);
    }

    private LedgerUser createUser(Organization org, String email, String fullName, Role role) {
        LedgerUser user = new LedgerUser();
        user.setOrganization(org);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode("TestPass123!"));
        user.setRole(role);
        return userRepository.save(user);
    }

    @Test
    void auditEventsEmittedAcrossAllEntities() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrganization("Audit Test Org " + suffix, "USD");
        LedgerUser admin = createUser(org, "admin." + suffix + "@audit.test", "Admin User", Role.ADMIN);
        String adminToken = jwtService.generateToken(admin);

        // 1. Create account
        HttpResponse<String> createAccountRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/accounts"))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                                "accountCode", "1010",
                                "name", "Operating Bank Account",
                                "accountType", "ASSET",
                                "status", "ACTIVE"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(createAccountRes.statusCode()).isEqualTo(200);
        JsonNode createdAccountNode = objectMapper.readTree(createAccountRes.body());
        long accountId = createdAccountNode.get("id").asLong();

        // 2. Update account
        HttpResponse<String> updateAccountRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/accounts/" + accountId))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                                "name", "Operating Checking Account"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(updateAccountRes.statusCode()).isEqualTo(200);

        // 3. Deactivate account
        HttpResponse<String> deactRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/accounts/" + accountId + "/deactivate"))
                        .header("Authorization", "Bearer " + adminToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(deactRes.statusCode()).isEqualTo(200);

        // 4. Invite user
        HttpResponse<String> inviteRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/users/invite"))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                                "email", "invitee." + suffix + "@audit.test",
                                "role", "ACCOUNTANT"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(inviteRes.statusCode()).isEqualTo(200);
        JsonNode inviteNode = objectMapper.readTree(inviteRes.body());
        long inviteId = inviteNode.get("id").asLong();

        // 5. Revoke invitation
        HttpResponse<String> revokeRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/invitations/" + inviteId))
                        .header("Authorization", "Bearer " + adminToken)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(revokeRes.statusCode()).isEqualTo(204);

        // 6. Update organization settings
        HttpResponse<String> orgSettingsRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/organizations/settings"))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                                "name", "Audit Test Org Updated " + suffix
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(orgSettingsRes.statusCode()).isEqualTo(200);

        // 7. Query audit logs via GET /api/audit-logs
        HttpResponse<String> auditLogsRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/api/audit-logs?size=50"))
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(auditLogsRes.statusCode()).isEqualTo(200);
        JsonNode pageNode = objectMapper.readTree(auditLogsRes.body());
        JsonNode items = pageNode.get("items");
        assertThat(items.isArray()).isTrue();

        // Verify actions present in audit log
        boolean foundAccountCreated = false;
        boolean foundAccountUpdated = false;
        boolean foundAccountDeactivated = false;
        boolean foundUserInvited = false;
        boolean foundInvitationRevoked = false;
        boolean foundOrgUpdated = false;

        for (JsonNode item : items) {
            String action = item.get("action").asText();
            if ("ACCOUNT_CREATED".equals(action)) foundAccountCreated = true;
            if ("ACCOUNT_UPDATED".equals(action)) foundAccountUpdated = true;
            if ("ACCOUNT_DEACTIVATED".equals(action)) foundAccountDeactivated = true;
            if ("USER_INVITED".equals(action)) foundUserInvited = true;
            if ("INVITATION_REVOKED".equals(action)) foundInvitationRevoked = true;
            if ("ORGANIZATION_SETTINGS_UPDATED".equals(action)) foundOrgUpdated = true;
        }

        assertThat(foundAccountCreated).isTrue();
        assertThat(foundAccountUpdated).isTrue();
        assertThat(foundAccountDeactivated).isTrue();
        assertThat(foundUserInvited).isTrue();
        assertThat(foundInvitationRevoked).isTrue();
        assertThat(foundOrgUpdated).isTrue();
    }

    @Test
    void userRoleManagementAndLastAdminProtection() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization org = createOrganization("Role Governance Org " + suffix, "USD");
        LedgerUser admin1 = createUser(org, "admin1." + suffix + "@gov.test", "Primary Admin", Role.ADMIN);
        LedgerUser member = createUser(org, "member." + suffix + "@gov.test", "Staff Member", Role.ACCOUNTANT);
        String adminToken = jwtService.generateToken(admin1);

        // 1. Attempt to demote the sole admin -> must be blocked with 409 LAST_ADMIN_PROTECTION
        HttpResponse<String> demoteSoleAdminRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/users/" + admin1.getId() + "/role"))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                                "role", "ACCOUNTANT"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(demoteSoleAdminRes.statusCode()).isEqualTo(409);
        JsonNode demoteError = objectMapper.readTree(demoteSoleAdminRes.body());
        assertThat(demoteError.get("code").asText()).isEqualTo("LAST_ADMIN_PROTECTION");

        // 2. Promote member to ADMIN -> succeeds
        HttpResponse<String> promoteRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/users/" + member.getId() + "/role"))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                                "role", "ADMIN"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(promoteRes.statusCode()).isEqualTo(200);
        JsonNode updatedMemberNode = objectMapper.readTree(promoteRes.body());
        assertThat(updatedMemberNode.get("role").asText()).isEqualTo("ADMIN");

        // 3. Now that 2 admins exist, demote primary admin -> succeeds
        HttpResponse<String> demotePrimaryRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/users/" + admin1.getId() + "/role"))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                                "role", "ACCOUNTANT"
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(demotePrimaryRes.statusCode()).isEqualTo(200);
    }

    @Test
    void auditLogAuthorizationGuards() throws Exception {
        // Unauthenticated access to audit logs receives 401 Unauthorized
        HttpResponse<String> auditRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/api/audit-logs"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(auditRes.statusCode()).isEqualTo(401);
    }
}

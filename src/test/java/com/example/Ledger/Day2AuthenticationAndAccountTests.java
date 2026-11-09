package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
import com.example.Ledger.common.security.JwtService;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
class Day2AuthenticationAndAccountTests extends PostgresIntegrationTestSupport {

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private Organization organization;
    private String organizationName;
    private LedgerUser admin;
    private LedgerUser accountant;
    private LedgerUser viewer;
    private String adminEmail;
    private String accountantEmail;
    private String viewerEmail;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organizationName = "Acme Org " + suffix;

        organization = new Organization();
        organization.setName(organizationName);
        organization.setBaseCurrency("ETB");
        organization = organizationRepository.save(organization);

        adminEmail = "admin." + suffix + "@acme.test";
        accountantEmail = "accountant." + suffix + "@acme.test";
        viewerEmail = "viewer." + suffix + "@acme.test";

        admin = new LedgerUser();
        admin.setOrganization(organization);
        admin.setEmail(adminEmail);
        admin.setFullName("Acme Admin");
        admin.setPasswordHash(passwordEncoder.encode("Secret123!"));
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);

        accountant = new LedgerUser();
        accountant.setOrganization(organization);
        accountant.setEmail(accountantEmail);
        accountant.setFullName("Charlie Brown");
        accountant.setPasswordHash(passwordEncoder.encode("Secret123!"));
        accountant.setRole(Role.ACCOUNTANT);
        accountant = userRepository.save(accountant);

        viewer = new LedgerUser();
        viewer.setOrganization(organization);
        viewer.setEmail(viewerEmail);
        viewer.setFullName("Acme Viewer");
        viewer.setPasswordHash(passwordEncoder.encode("Secret123!"));
        viewer.setRole(Role.VIEWER);
        viewer = userRepository.save(viewer);
    }

    @Test
    void loginWithValidCredentialsReturnsJwt() throws Exception {
        HttpResponse<String> response = postJson("/auth/login", Map.of(
            "email", adminEmail,
                "password", "Secret123!"
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("token").asText()).isNotBlank();
        assertThat(body.get("user").get("email").asText()).isEqualTo(adminEmail);
    }

    @Test
    void browserOriginCanPreflightLoginRequest() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + port + "/auth/login"))
                                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                                .header("Origin", "http://localhost:5174")
                                .header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "Authorization,Content-Type")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
                .isEqualTo("http://localhost:5174");
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElse(""))
                .contains("POST");
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse(""))
                .contains("Authorization");
    }

    @Test
    void loginWithInvalidPasswordFails() throws Exception {
        HttpResponse<String> response = postJson("/auth/login", Map.of(
            "email", adminEmail,
                "password", "wrong-password"
        ));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void loginWithUnknownIdentityFails() throws Exception {
        HttpResponse<String> response = postJson("/auth/login", Map.of(
                "email", "missing@acme.test",
                "password", "Secret123!"
        ));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void protectedEndpointWithoutTokenIsUnauthorized() throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/accounts")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void viewerCannotCreateAccount() throws Exception {
        String token = loginAs(viewerEmail, "Secret123!");
        HttpResponse<String> response = postJsonWithToken("/accounts", token, Map.of(
                "accountCode", "1000",
                "name", "Cash",
                "accountType", "ASSET",
                "status", "ACTIVE"
        ));

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void accountantCannotCreateAccount() throws Exception {
        String token = loginAs(accountantEmail, "Secret123!");
        HttpResponse<String> response = postJsonWithToken("/accounts", token, Map.of(
                "accountCode", "1000",
                "name", "Cash",
                "accountType", "ASSET",
                "status", "ACTIVE"
        ));

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void accountantCannotUpdateAccount() throws Exception {
        String adminToken = loginAs(adminEmail, "Secret123!");
        HttpResponse<String> createResponse = postJsonWithToken("/accounts", adminToken, Map.of(
                "accountCode", "1010",
                "name", "Petty Cash",
                "accountType", "ASSET",
                "status", "ACTIVE"
        ));
        Long accountId = objectMapper.readTree(createResponse.body()).get("id").asLong();

        String accountantToken = loginAs(accountantEmail, "Secret123!");
        HttpResponse<String> patchResponse = patchJsonWithToken("/accounts/" + accountId, accountantToken, Map.of(
                "name", "Modified Name"
        ));
        assertThat(patchResponse.statusCode()).isEqualTo(403);
    }

    @Test
    void accountantCannotDeactivateAccount() throws Exception {
        String adminToken = loginAs(adminEmail, "Secret123!");
        HttpResponse<String> createResponse = postJsonWithToken("/accounts", adminToken, Map.of(
                "accountCode", "1020",
                "name", "Supplies",
                "accountType", "ASSET",
                "status", "ACTIVE"
        ));
        Long accountId = objectMapper.readTree(createResponse.body()).get("id").asLong();

        String accountantToken = loginAs(accountantEmail, "Secret123!");
        HttpResponse<String> deactResponse = postJsonWithToken("/accounts/" + accountId + "/deactivate", accountantToken, Map.of());
        assertThat(deactResponse.statusCode()).isEqualTo(403);
    }

    @Test
    void adminCanCreateAccount() throws Exception {
        String token = loginAs(adminEmail, "Secret123!");
        var claims = jwtService.parseClaims(token);
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(admin.getId()));
        assertThat(claims.get("organizationId", Number.class).longValue()).isEqualTo(organization.getId());

        HttpResponse<String> response = postJsonWithToken("/accounts", token, Map.of(
                "accountCode", "1000",
                "name", "Cash",
                "accountType", "ASSET",
                "status", "ACTIVE"
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).get("accountCode").asText()).isEqualTo("1000");
    }

    @Test
    void duplicateEmailAcrossOrganizationsIsRejectedAtInvitation() throws Exception {
        Organization org2 = new Organization();
        org2.setName("Second Org " + UUID.randomUUID().toString().substring(0, 8));
        org2.setBaseCurrency("USD");
        org2 = organizationRepository.save(org2);

        LedgerUser org2Admin = new LedgerUser();
        org2Admin.setOrganization(org2);
        org2Admin.setEmail("admin2." + UUID.randomUUID().toString().substring(0, 6) + "@acme.test");
        org2Admin.setFullName("Org2 Admin");
        org2Admin.setPasswordHash(passwordEncoder.encode("Secret123!"));
        org2Admin.setRole(Role.ADMIN);
        org2Admin = userRepository.save(org2Admin);

        String org2Token = loginAs(org2Admin.getEmail(), "Secret123!");
        HttpResponse<String> response = postJsonWithToken("/users/invite", org2Token, Map.of(
                "email", admin.getEmail(),
                "role", "VIEWER"
        ));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(response.body()).get("code").asText()).isEqualTo("DUPLICATE_EMAIL");
    }

    @Test
    void adminCanReadCurrentOrganizationSettings() throws Exception {
        String token = loginAs(adminEmail, "Secret123!");
        HttpResponse<String> response = getWithToken("/organizations/me", token);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("id").asLong()).isEqualTo(organization.getId());
        assertThat(body.get("name").asText()).isEqualTo(organizationName);
        assertThat(body.get("baseCurrency").asText()).isEqualTo("ETB");
        assertThat(body.has("createdAt")).isTrue();
        assertThat(body.toString()).doesNotContain("passwordHash");
        assertThat(body.toString()).doesNotContain("organizationId");
    }

    @Test
    void adminCanUpdateCurrentOrganizationSettingsWithPartialValues() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String updatedName = "Northstar Co. " + uniqueSuffix;
        String secondUpdatedName = "Acme Org Updated " + uniqueSuffix;

        String token = loginAs(adminEmail, "Secret123!");
        HttpResponse<String> response = patchJsonWithToken("/organizations/me", token, Map.of(
                "name", updatedName,
                "baseCurrency", " usd "
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("name").asText()).isEqualTo(updatedName);
        assertThat(body.get("baseCurrency").asText()).isEqualTo("USD");

        Organization reloaded = organizationRepository.findById(organization.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo(updatedName);
        assertThat(reloaded.getBaseCurrency()).isEqualTo("USD");

        HttpResponse<String> secondResponse = patchJsonWithToken("/organizations/me", token, Map.of(
                "name", secondUpdatedName
        ));
        assertThat(secondResponse.statusCode()).isEqualTo(200);
        JsonNode secondBody = objectMapper.readTree(secondResponse.body());
        assertThat(secondBody.get("baseCurrency").asText()).isEqualTo("USD");
        assertThat(secondBody.get("name").asText()).isEqualTo(secondUpdatedName);
    }

    @Test
    void invalidOrganizationSettingsValuesAreRejected() throws Exception {
        String token = loginAs(adminEmail, "Secret123!");
        HttpResponse<String> response = patchJsonWithToken("/organizations/me", token, Map.of(
                "baseCurrency", "us"
        ));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void accountantAndViewerCannotUpdateOrganizationSettings() throws Exception {
        String accountantToken = loginAs(accountantEmail, "Secret123!");
        String viewerToken = loginAs(viewerEmail, "Secret123!");

        HttpResponse<String> accountantResponse = patchJsonWithToken("/organizations/me", accountantToken, Map.of(
                "name", "Not Allowed"
        ));
        HttpResponse<String> viewerResponse = patchJsonWithToken("/organizations/me", viewerToken, Map.of(
                "name", "Not Allowed"
        ));

        assertThat(accountantResponse.statusCode()).isEqualTo(403);
        assertThat(viewerResponse.statusCode()).isEqualTo(403);
    }

    @Test
    void organizationSettingsAreScopedToTheCurrentOrganization() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String org2Name = "Second Org " + uniqueSuffix;
        String renamedOrg2Name = "Other Org Renamed " + uniqueSuffix;

        Organization org2 = new Organization();
        org2.setName(org2Name);
        org2.setBaseCurrency("USD");
        org2 = organizationRepository.save(org2);

        LedgerUser org2Admin = new LedgerUser();
        org2Admin.setOrganization(org2);
        org2Admin.setEmail("org2.admin." + UUID.randomUUID().toString().substring(0, 6) + "@acme.test");
        org2Admin.setFullName("Org2 Admin");
        org2Admin.setPasswordHash(passwordEncoder.encode("Secret123!"));
        org2Admin.setRole(Role.ADMIN);
        org2Admin = userRepository.save(org2Admin);

        String token = loginAs(org2Admin.getEmail(), "Secret123!");
        HttpResponse<String> readResponse = getWithToken("/organizations/me", token);
        assertThat(readResponse.statusCode()).isEqualTo(200);
        JsonNode readBody = objectMapper.readTree(readResponse.body());
        assertThat(readBody.get("name").asText()).isEqualTo(org2Name);
        assertThat(readBody.get("baseCurrency").asText()).isEqualTo("USD");

        HttpResponse<String> updateResponse = patchJsonWithToken("/organizations/me", token, Map.of(
                "name", renamedOrg2Name
        ));
        assertThat(updateResponse.statusCode()).isEqualTo(200);
        JsonNode updateBody = objectMapper.readTree(updateResponse.body());
        assertThat(updateBody.get("name").asText()).isEqualTo(renamedOrg2Name);

        assertThat(organizationRepository.findById(organization.getId()).orElseThrow().getName()).isEqualTo(organizationName);
        assertThat(organizationRepository.findById(org2.getId()).orElseThrow().getName()).isEqualTo(renamedOrg2Name);
    }

    @Test
    void adminCanListOnlyUsersFromCurrentOrganizationInDeterministicOrder() throws Exception {
        LedgerUser otherOrgUser = new LedgerUser();
        Organization org2 = new Organization();
        org2.setName("Outside Org " + UUID.randomUUID().toString().substring(0, 8));
        org2.setBaseCurrency("USD");
        org2 = organizationRepository.save(org2);
        otherOrgUser.setOrganization(org2);
        otherOrgUser.setEmail("outsider." + UUID.randomUUID().toString().substring(0, 6) + "@acme.test");
        otherOrgUser.setFullName("Zoe Evil");
        otherOrgUser.setPasswordHash(passwordEncoder.encode("Secret123!"));
        otherOrgUser.setRole(Role.ADMIN);
        userRepository.save(otherOrgUser);

        String token = loginAs(adminEmail, "Secret123!");
        HttpResponse<String> response = getWithToken("/users", token);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(3);
        assertThat(body.get(0).get("email").asText()).isEqualTo(adminEmail);
        assertThat(body.get(0).get("fullName").asText()).isEqualTo("Acme Admin");
        assertThat(body.get(0).get("role").asText()).isEqualTo("ADMIN");
        assertThat(body.get(1).get("email").asText()).isEqualTo(viewerEmail);
        assertThat(body.get(1).get("fullName").asText()).isEqualTo("Acme Viewer");
        assertThat(body.get(1).get("role").asText()).isEqualTo("VIEWER");
        assertThat(body.get(2).get("email").asText()).isEqualTo(accountantEmail);
        assertThat(body.get(2).get("fullName").asText()).isEqualTo("Charlie Brown");
        assertThat(body.get(2).get("role").asText()).isEqualTo("ACCOUNTANT");
        assertThat(body.get(0).get("invitationStatus").asText()).isEqualTo("ACTIVE");
        assertThat(body.toString()).doesNotContain("passwordHash");
        assertThat(body.toString()).doesNotContain("invitationToken");
        assertThat(body.toString()).doesNotContain("jwt");
        assertThat(body.toString()).doesNotContain("token");
        assertThat(body.toString()).doesNotContain(otherOrgUser.getEmail());
    }

    @Test
    void accountantAndViewerCannotListOrganizationUsers() throws Exception {
        String accountantToken = loginAs(accountantEmail, "Secret123!");
        String viewerToken = loginAs(viewerEmail, "Secret123!");

        HttpResponse<String> accountantResponse = getWithToken("/users", accountantToken);
        HttpResponse<String> viewerResponse = getWithToken("/users", viewerToken);

        assertThat(accountantResponse.statusCode()).isEqualTo(403);
        assertThat(viewerResponse.statusCode()).isEqualTo(403);
    }

    @Test
    void organizationDirectoryIncludesCurrentAdminEvenWithoutAdditionalMembers() throws Exception {
        Organization emptyOrg = new Organization();
        emptyOrg.setName("Empty Org" + UUID.randomUUID().toString().substring(0, 6));
        emptyOrg.setBaseCurrency("USD");
        emptyOrg = organizationRepository.save(emptyOrg);

        LedgerUser emptyAdmin = new LedgerUser();
        emptyAdmin.setOrganization(emptyOrg);
        emptyAdmin.setEmail("emptyadmin." + UUID.randomUUID().toString().substring(0, 6) + "@acme.test");
        emptyAdmin.setFullName("Empty Admin");
        emptyAdmin.setPasswordHash(passwordEncoder.encode("Secret123!"));
        emptyAdmin.setRole(Role.ADMIN);
        emptyAdmin = userRepository.save(emptyAdmin);

        String token = loginAs(emptyAdmin.getEmail(), "Secret123!");
        HttpResponse<String> response = getWithToken("/users", token);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("email").asText()).isEqualTo(emptyAdmin.getEmail());
        assertThat(body.get(0).get("fullName").asText()).isEqualTo("Empty Admin");
        assertThat(body.get(0).get("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void invitedUserBecomesVisibleInOrganizationDirectoryAfterAcceptance() throws Exception {
        String token = loginAs(adminEmail, "Secret123!");
        HttpResponse<String> inviteResponse = postJsonWithToken("/users/invite", token, Map.of(
                "email", "newviewer." + UUID.randomUUID().toString().substring(0, 6) + "@acme.test",
                "role", "VIEWER"
        ));
        assertThat(inviteResponse.statusCode()).isEqualTo(200);
        String rawToken = objectMapper.readTree(inviteResponse.body()).get("invitationToken").asText();

        String invitedEmail = objectMapper.readTree(inviteResponse.body()).get("invitedEmail").asText();
        HttpResponse<String> acceptResponse = postJson("/invitations/accept", Map.of(
                "token", rawToken,
                "email", invitedEmail,
                "fullName", "New Viewer",
                "password", "Secret123!"
        ));
        assertThat(acceptResponse.statusCode()).isEqualTo(200);

        HttpResponse<String> listResponse = getWithToken("/users", token);
        assertThat(listResponse.statusCode()).isEqualTo(200);
        JsonNode users = objectMapper.readTree(listResponse.body());
        assertThat(users).anySatisfy(node -> {
            assertThat(node.get("email").asText()).isEqualTo(invitedEmail);
            assertThat(node.get("fullName").asText()).isEqualTo("New Viewer");
            assertThat(node.get("role").asText()).isEqualTo("VIEWER");
            assertThat(node.get("invitationStatus").asText()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void jwtCannotReadAnotherOrganizationsAccountById() throws Exception {
        Organization org2 = new Organization();
        org2.setName("Another Org " + UUID.randomUUID().toString().substring(0, 8));
        org2.setBaseCurrency("ETB");
        org2 = organizationRepository.save(org2);

        Account foreignAccount = new Account();
        foreignAccount.setOrganization(org2);
        foreignAccount.setAccountCode("2000");
        foreignAccount.setName("Foreign Cash");
        foreignAccount.setAccountType(AccountType.ASSET);
        foreignAccount.setStatus(AccountStatus.ACTIVE);
        foreignAccount = accountRepository.save(foreignAccount);

        String token = loginAs(adminEmail, "Secret123!");
        HttpResponse<String> response = getWithToken("/accounts/" + foreignAccount.getId(), token);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void duplicateAccountCodeWithinOrganizationIsRejected() throws Exception {
        String token = loginAs(adminEmail, "Secret123!");
        accountRepository.save(createAccount("1000", "Cash", AccountType.ASSET, AccountStatus.ACTIVE));

        HttpResponse<String> response = postJsonWithToken("/accounts", token, Map.of(
                "accountCode", "1000",
                "name", "Bank Cash",
                "accountType", "ASSET",
                "status", "ACTIVE"
        ));

        assertThat(response.statusCode()).isEqualTo(409);
    }

    private String loginAs(String email, String password) throws Exception {
        HttpResponse<String> response = postJson("/auth/login", Map.of(
                "email", email,
                "password", password
        ));
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).get("token").asText();
    }

    private HttpResponse<String> postJson(String path, Map<String, String> payload) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJsonWithToken(String path, String token, Map<String, ?> payload) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJsonWithToken(String path, String token, Map<String, ?> payload) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithToken(String path, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Account createAccount(String code, String name, AccountType type, AccountStatus status) {
        Account account = new Account();
        account.setOrganization(organization);
        account.setAccountCode(code);
        account.setName(name);
        account.setAccountType(type);
        account.setStatus(status);
        return account;
    }
}

package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.Role;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardOverviewTests {

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

    private Organization organization;
    private Organization otherOrganization;
    private LedgerUser admin;
    private LedgerUser accountant;
    private LedgerUser viewer;
    private LedgerUser otherAdmin;

    private static final String PASSWORD = "Secret123!";

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        organization = new Organization();
        organization.setName("Overview-Org-" + suffix);
        organization.setBaseCurrency("ETB");
        organization = organizationRepository.save(organization);

        otherOrganization = new Organization();
        otherOrganization.setName("Overview-Other-" + suffix);
        otherOrganization.setBaseCurrency("ETB");
        otherOrganization = organizationRepository.save(otherOrganization);

        admin = createUser(organization, "admin." + suffix + "@overview.test", Role.ADMIN);
        accountant = createUser(organization, "accountant." + suffix + "@overview.test", Role.ACCOUNTANT);
        viewer = createUser(organization, "viewer." + suffix + "@overview.test", Role.VIEWER);
        otherAdmin = createUser(otherOrganization, "other-admin." + suffix + "@overview.test", Role.ADMIN);
    }

    @Test
    void permittedRolesCanReadOverview() throws Exception {
        for (String token : List.of(loginAs(admin.getEmail(), PASSWORD), loginAs(accountant.getEmail(), PASSWORD), loginAs(viewer.getEmail(), PASSWORD))) {
            HttpResponse<String> response = getWithToken("/api/dashboard/overview", token);
            assertThat(response.statusCode()).isEqualTo(200);

            JsonNode body = objectMapper.readTree(response.body());
            assertThat(body.get("asOfDate").asText()).isNotBlank();
            assertThat(body.get("totalAssets").decimalValue()).isNotNull();
            assertThat(body.get("totalRevenue").decimalValue()).isNotNull();
            assertThat(body.get("totalExpenses").decimalValue()).isNotNull();
            assertThat(body.get("draftsCount").asInt()).isGreaterThanOrEqualTo(0);
            assertThat(body.get("postedCount").asInt()).isGreaterThanOrEqualTo(0);
            assertThat(body.get("recentEntries")).isNotNull();
        }
    }

    @Test
    void overviewShowsCurrentOrganizationTotalsAndRecentActivity() throws Exception {
        Account cash = createAccount(organization, "1000", "Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        Account expense = createAccount(organization, "5000", "Rent", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account revenue = createAccount(organization, "4000", "Sales", AccountType.REVENUE, AccountStatus.ACTIVE);

        String token = loginAs(accountant.getEmail(), PASSWORD);

        Long postedEntryId = createAndPostEntry(token, cash, expense, LocalDate.of(2026, 1, 8), new BigDecimal("1000.00"), "Posted expense");
        Long revenueEntryId = createAndPostEntry(token, cash, revenue, LocalDate.of(2026, 1, 9), new BigDecimal("250.00"), "Posted revenue");
        Long draftEntryId = createDraftEntry(token, cash, expense, LocalDate.of(2026, 1, 10), new BigDecimal("300.00"), "Draft entry");

        createAndPostEntry(loginAs(otherAdmin.getEmail(), PASSWORD), createAccount(otherOrganization, "1001", "Other Cash", AccountType.ASSET, AccountStatus.ACTIVE),
                createAccount(otherOrganization, "5001", "Foreign Expense", AccountType.EXPENSE, AccountStatus.ACTIVE),
                LocalDate.of(2026, 1, 11), new BigDecimal("500.00"), "Other org");

        HttpResponse<String> response = getWithToken("/api/dashboard/overview", token);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("totalAssets").decimalValue()).isEqualByComparingTo(new BigDecimal("1250.00"));
        assertThat(body.get("totalRevenue").decimalValue()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(body.get("totalExpenses").decimalValue()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(body.get("draftsCount").asInt()).isEqualTo(1);
        assertThat(body.get("postedCount").asInt()).isEqualTo(2);

        JsonNode recentEntries = body.get("recentEntries");
        assertThat(recentEntries).hasSize(3);
        assertThat(recentEntries.toString()).contains("Draft entry");
        assertThat(recentEntries.toString()).contains(String.valueOf(draftEntryId));
        assertThat(recentEntries.toString()).contains("Posted revenue");
        assertThat(recentEntries.toString()).contains("Posted expense");
        assertThat(recentEntries.toString()).doesNotContain("Other org");
        assertThat(recentEntries.toString()).contains(String.valueOf(revenueEntryId));
        assertThat(recentEntries.toString()).contains(String.valueOf(postedEntryId));
    }

    @Test
    void emptyOrganizationReturnsZeroMetrics() throws Exception {
        String token = loginAs(admin.getEmail(), PASSWORD);

        HttpResponse<String> response = getWithToken("/api/dashboard/overview", token);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("totalAssets").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.get("totalRevenue").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.get("totalExpenses").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.get("draftsCount").asInt()).isZero();
        assertThat(body.get("postedCount").asInt()).isZero();
        assertThat(body.get("recentEntries")).hasSize(0);
    }

    @Test
    void overviewIsOrganizationScopedAndUnauthorizedRequestsAreDenied() throws Exception {
        Account cash = createAccount(organization, "1000", "Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        Account expense = createAccount(organization, "5000", "Rent", AccountType.EXPENSE, AccountStatus.ACTIVE);
        String token = loginAs(accountant.getEmail(), PASSWORD);
        createAndPostEntry(token, cash, expense, LocalDate.of(2026, 2, 3), new BigDecimal("50.00"), "Scoped entry");

        HttpResponse<String> response = getWithToken("/api/dashboard/overview", token);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("postedCount").asInt()).isEqualTo(1);

        HttpResponse<String> unauthenticated = getWithoutToken("/api/dashboard/overview");
        assertThat(unauthenticated.statusCode()).isEqualTo(401);

        HttpResponse<String> noPermission = getWithoutToken("/api/dashboard/overview");
        assertThat(noPermission.statusCode()).isEqualTo(401);
    }

    private LedgerUser createUser(Organization org, String email, Role role) {
        LedgerUser user = new LedgerUser();
        user.setOrganization(org);
        user.setEmail(email);
        user.setFullName(role.name());
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        return userRepository.save(user);
    }

    private Account createAccount(Organization org, String code, String name, AccountType type, AccountStatus status) {
        Account account = new Account();
        account.setOrganization(org);
        account.setAccountCode(code);
        account.setName(name);
        account.setAccountType(type);
        account.setStatus(status);
        return accountRepository.save(account);
    }

    private String loginAs(String email, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "email", email,
                        "password", password
                ))))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).get("token").asText();
    }

    private HttpResponse<String> getWithToken(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithoutToken(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJsonWithToken(String path, String token, Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");

        if (body == null) {
            return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }

        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Long createAndPostEntry(String token, Account debitAccount, Account creditAccount, LocalDate transactionDate, BigDecimal amount, String description) throws Exception {
        HttpResponse<String> create = postJsonWithToken("/journal-entries", token, Map.of(
                "transactionDate", transactionDate.toString(),
                "description", description,
                "reference", "REF-" + UUID.randomUUID().toString().substring(0, 8),
                "lines", List.of(
                        Map.of("accountId", debitAccount.getId(), "side", "DEBIT", "amount", amount),
                        Map.of("accountId", creditAccount.getId(), "side", "CREDIT", "amount", amount)
                )
        ));
        assertThat(create.statusCode()).isEqualTo(200);
        Long entryId = objectMapper.readTree(create.body()).get("id").asLong();

        HttpResponse<String> post = postJsonWithToken("/journal-entries/" + entryId + "/post", token, null);
        assertThat(post.statusCode()).isEqualTo(200);
        return entryId;
    }

    private Long createDraftEntry(String token, Account debitAccount, Account creditAccount, LocalDate transactionDate, BigDecimal amount, String description) throws Exception {
        HttpResponse<String> create = postJsonWithToken("/journal-entries", token, Map.of(
                "transactionDate", transactionDate.toString(),
                "description", description,
                "reference", "REF-" + UUID.randomUUID().toString().substring(0, 8),
                "lines", List.of(
                        Map.of("accountId", debitAccount.getId(), "side", "DEBIT", "amount", amount),
                        Map.of("accountId", creditAccount.getId(), "side", "CREDIT", "amount", amount)
                )
        ));
        assertThat(create.statusCode()).isEqualTo(200);
        return objectMapper.readTree(create.body()).get("id").asLong();
    }
}

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

import java.io.IOException;
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
class Day3JournalEntryTests {

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
        organization.setName("Org-" + suffix);
        organization.setBaseCurrency("ETB");
        organization = organizationRepository.save(organization);

        otherOrganization = new Organization();
        otherOrganization.setName("Other-" + suffix);
        otherOrganization.setBaseCurrency("ETB");
        otherOrganization = organizationRepository.save(otherOrganization);

        admin = createUser(organization, "admin." + suffix + "@acme.test", Role.ADMIN);
        accountant = createUser(organization, "accountant." + suffix + "@acme.test", Role.ACCOUNTANT);
        viewer = createUser(organization, "viewer." + suffix + "@acme.test", Role.VIEWER);
        otherAdmin = createUser(otherOrganization, "other-admin." + suffix + "@acme.test", Role.ADMIN);
    }

    @Test
    void accountantCanCreateAndPostEntryAndBalanceUsesPostedOnly() throws Exception {
        Account rent = createAccount(organization, "5100", "Rent Expense", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account cash = createAccount(organization, "1100", "Cash", AccountType.ASSET, AccountStatus.ACTIVE);

        String token = loginAs(accountant.getEmail(), PASSWORD);

        HttpResponse<String> createResponse = postJsonWithToken("/journal-entries", token, Map.of(
                "transactionDate", LocalDate.now().toString(),
                "description", "October rent",
                "reference", "JE-RENT-1",
                "lines", List.of(
                        Map.of("accountId", rent.getId(), "side", "DEBIT", "amount", new BigDecimal("1000.00")),
                        Map.of("accountId", cash.getId(), "side", "CREDIT", "amount", new BigDecimal("1000.00"))
                )
        ));

        assertThat(createResponse.statusCode()).isEqualTo(200);
        JsonNode createdBody = objectMapper.readTree(createResponse.body());
        Long entryId = createdBody.get("id").asLong();
        assertThat(createdBody.get("status").asText()).isEqualTo("DRAFT");

        HttpResponse<String> prePostBalance = getWithToken("/accounts/" + rent.getId() + "/balance", token);
        assertThat(prePostBalance.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(prePostBalance.body()).get("balance").decimalValue())
                .isEqualByComparingTo("0");

        HttpResponse<String> postResponse = postJsonWithToken("/journal-entries/" + entryId + "/post", token, null);
        assertThat(postResponse.statusCode()).isEqualTo(200);
        JsonNode postBody = objectMapper.readTree(postResponse.body());
        assertThat(postBody.get("status").asText()).isEqualTo("POSTED");

        HttpResponse<String> duplicatePost = postJsonWithToken("/journal-entries/" + entryId + "/post", token, null);
        assertThat(duplicatePost.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(duplicatePost.body()).get("code").asText()).isEqualTo("JOURNAL_ENTRY_NOT_DRAFT");

        HttpResponse<String> postBalance = getWithToken("/accounts/" + rent.getId() + "/balance", token);
        assertThat(postBalance.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(postBalance.body()).get("balance").decimalValue())
                .isEqualByComparingTo("1000.0000");
    }

    @Test
    void postingUnbalancedEntryIsRejected() throws Exception {
        Account expense = createAccount(organization, "5200", "Utilities", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account cash = createAccount(organization, "1110", "Bank Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        String token = loginAs(accountant.getEmail(), PASSWORD);

        HttpResponse<String> createResponse = postJsonWithToken("/journal-entries", token, Map.of(
                "transactionDate", LocalDate.now().toString(),
                "description", "Unbalanced draft",
                "reference", "JE-UNBAL",
                "lines", List.of(
                        Map.of("accountId", expense.getId(), "side", "DEBIT", "amount", new BigDecimal("100.00")),
                        Map.of("accountId", cash.getId(), "side", "CREDIT", "amount", new BigDecimal("90.00"))
                )
        ));
        assertThat(createResponse.statusCode()).isEqualTo(200);

        Long entryId = objectMapper.readTree(createResponse.body()).get("id").asLong();
        HttpResponse<String> postResponse = postJsonWithToken("/journal-entries/" + entryId + "/post", token, null);

        assertThat(postResponse.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(postResponse.body()).get("code").asText()).isEqualTo("JOURNAL_ENTRY_UNBALANCED");
    }

    @Test
    void viewerCannotCreateJournalEntries() throws Exception {
        Account expense = createAccount(organization, "5300", "Salaries", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account cash = createAccount(organization, "1120", "Main Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        String token = loginAs(viewer.getEmail(), PASSWORD);

        HttpResponse<String> response = postJsonWithToken("/journal-entries", token, Map.of(
                "transactionDate", LocalDate.now().toString(),
                "description", "Viewer attempt",
                "reference", "JE-VIEW",
                "lines", List.of(
                        Map.of("accountId", expense.getId(), "side", "DEBIT", "amount", new BigDecimal("10.00")),
                        Map.of("accountId", cash.getId(), "side", "CREDIT", "amount", new BigDecimal("10.00"))
                )
        ));

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void viewerListsOnlyPostedJournalEntriesWithoutStatusFilter() throws Exception {
        Account expense = createAccount(organization, "5301", "Insurance", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account cash = createAccount(organization, "1121", "Insurance Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        String accountantToken = loginAs(accountant.getEmail(), PASSWORD);
        String viewerToken = loginAs(viewer.getEmail(), PASSWORD);

        Long draftEntryId = createDraftEntry(accountantToken, expense, cash, "Draft for viewer", new BigDecimal("20.00"));
        Long postedEntryId = createDraftEntry(accountantToken, expense, cash, "Posted for viewer", new BigDecimal("25.00"));
        postJsonWithToken("/journal-entries/" + postedEntryId + "/post", accountantToken, null);

        HttpResponse<String> response = getWithToken("/journal-entries", viewerToken);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(response.body());
        for (JsonNode item : body.get("items")) {
            assertThat(item.get("status").asText()).isEqualTo("POSTED");
        }
        assertThat(body.get("items").toString()).doesNotContain(draftEntryId.toString());
    }

    @Test
    void viewerDraftStatusFilterDoesNotExposeDrafts() throws Exception {
        Account expense = createAccount(organization, "5302", "Travel", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account cash = createAccount(organization, "1122", "Travel Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        String accountantToken = loginAs(accountant.getEmail(), PASSWORD);
        String viewerToken = loginAs(viewer.getEmail(), PASSWORD);

        Long draftEntryId = createDraftEntry(accountantToken, expense, cash, "Hidden draft", new BigDecimal("18.00"));
        Long postedEntryId = createDraftEntry(accountantToken, expense, cash, "Visible posted", new BigDecimal("22.00"));
        postJsonWithToken("/journal-entries/" + postedEntryId + "/post", accountantToken, null);

        HttpResponse<String> response = getWithToken("/journal-entries?status=DRAFT", viewerToken);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("items").size()).isGreaterThanOrEqualTo(0);
        for (JsonNode item : body.get("items")) {
            assertThat(item.get("status").asText()).isNotEqualTo("DRAFT");
        }
        assertThat(body.get("items").toString()).doesNotContain(draftEntryId.toString());
    }

    @Test
    void viewerCanReadPostedEntryButNotDraftEntryById() throws Exception {
        Account expense = createAccount(organization, "5303", "Utilities", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account cash = createAccount(organization, "1123", "Utilities Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        String accountantToken = loginAs(accountant.getEmail(), PASSWORD);
        String viewerToken = loginAs(viewer.getEmail(), PASSWORD);

        Long postedEntryId = createDraftEntry(accountantToken, expense, cash, "Visible posted detail", new BigDecimal("40.00"));
        postJsonWithToken("/journal-entries/" + postedEntryId + "/post", accountantToken, null);

        HttpResponse<String> postedResponse = getWithToken("/journal-entries/" + postedEntryId, viewerToken);
        assertThat(postedResponse.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(postedResponse.body()).get("status").asText()).isEqualTo("POSTED");

        Long draftEntryId = createDraftEntry(accountantToken, expense, cash, "Hidden draft detail", new BigDecimal("55.00"));
        HttpResponse<String> draftResponse = getWithToken("/journal-entries/" + draftEntryId, viewerToken);
        assertThat(draftResponse.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(draftResponse.body()).get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void viewerCannotAccessCrossOrganizationDrafts() throws Exception {
        Account expense = createAccount(otherOrganization, "6600", "Other Org Expense", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account cash = createAccount(otherOrganization, "1180", "Other Org Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        String otherAdminToken = loginAs(otherAdmin.getEmail(), PASSWORD);
        String viewerToken = loginAs(viewer.getEmail(), PASSWORD);

        Long otherOrgEntryId = createDraftEntry(otherAdminToken, expense, cash, "Foreign org entry", new BigDecimal("12.00"));

        HttpResponse<String> response = getWithToken("/journal-entries/" + otherOrgEntryId, viewerToken);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(response.body()).get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void postedEntryIsImmutableForPatchAndDelete() throws Exception {
        Account expense = createAccount(organization, "5400", "Supplies", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account cash = createAccount(organization, "1130", "Drawer Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        String token = loginAs(admin.getEmail(), PASSWORD);

        Long entryId = createDraftEntry(token, expense, cash, "Immutable check", new BigDecimal("30.00"));

        HttpResponse<String> postResponse = postJsonWithToken("/journal-entries/" + entryId + "/post", token, null);
        assertThat(postResponse.statusCode()).isEqualTo(200);

        HttpResponse<String> patchResponse = patchJsonWithToken("/journal-entries/" + entryId, token, Map.of(
                "description", "Should fail"
        ));
        assertThat(patchResponse.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(patchResponse.body()).get("code").asText()).isEqualTo("JOURNAL_ENTRY_NOT_DRAFT");

        HttpResponse<String> deleteResponse = deleteWithToken("/journal-entries/" + entryId, token);
        assertThat(deleteResponse.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(deleteResponse.body()).get("code").asText()).isEqualTo("JOURNAL_ENTRY_NOT_DRAFT");
    }

    @Test
    void inactiveAccountCannotBeUsedInNewEntry() throws Exception {
        Account inactiveExpense = createAccount(organization, "5500", "Inactive Expense", AccountType.EXPENSE, AccountStatus.INACTIVE);
        Account cash = createAccount(organization, "1140", "Petty Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        String token = loginAs(accountant.getEmail(), PASSWORD);

        HttpResponse<String> response = postJsonWithToken("/journal-entries", token, Map.of(
                "transactionDate", LocalDate.now().toString(),
                "description", "Inactive account",
                "reference", "JE-INACTIVE",
                "lines", List.of(
                        Map.of("accountId", inactiveExpense.getId(), "side", "DEBIT", "amount", new BigDecimal("5.00")),
                        Map.of("accountId", cash.getId(), "side", "CREDIT", "amount", new BigDecimal("5.00"))
                )
        ));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(response.body()).get("code").asText()).isEqualTo("ACCOUNT_INACTIVE");
    }

    @Test
    void crossOrganizationAccountUseIsRejected() throws Exception {
        Account localExpense = createAccount(organization, "5600", "Local Expense", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account foreignCash = createAccount(otherOrganization, "9999", "Foreign Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        String token = loginAs(admin.getEmail(), PASSWORD);

        HttpResponse<String> response = postJsonWithToken("/journal-entries", token, Map.of(
                "transactionDate", LocalDate.now().toString(),
                "description", "Cross org check",
                "reference", "JE-XORG",
                "lines", List.of(
                        Map.of("accountId", localExpense.getId(), "side", "DEBIT", "amount", new BigDecimal("25.00")),
                        Map.of("accountId", foreignCash.getId(), "side", "CREDIT", "amount", new BigDecimal("25.00"))
                )
        ));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(response.body()).get("code").asText()).isEqualTo("CROSS_ORGANIZATION_ACCOUNT");
    }

    @Test
    void journalEntryListSupportsStatusFilterAndPagination() throws Exception {
        Account expense = createAccount(organization, "5700", "Travel Expense", AccountType.EXPENSE, AccountStatus.ACTIVE);
        Account cash = createAccount(organization, "1150", "Cash Drawer", AccountType.ASSET, AccountStatus.ACTIVE);
        String token = loginAs(accountant.getEmail(), PASSWORD);

        Long entryOne = createDraftEntry(token, expense, cash, "Travel one", new BigDecimal("12.00"));
        Long entryTwo = createDraftEntry(token, expense, cash, "Travel two", new BigDecimal("15.00"));
        Long entryThree = createDraftEntry(token, expense, cash, "Travel three", new BigDecimal("18.00"));

        HttpResponse<String> postResponse = postJsonWithToken("/journal-entries/" + entryOne + "/post", token, null);
        assertThat(postResponse.statusCode()).isEqualTo(200);

        HttpResponse<String> pageResponse = getWithToken("/journal-entries?status=DRAFT&page=0&size=2", token);
        assertThat(pageResponse.statusCode()).isEqualTo(200);

        JsonNode pageBody = objectMapper.readTree(pageResponse.body());
        assertThat(pageBody.get("size").asInt()).isEqualTo(2);
        assertThat(pageBody.get("items").size()).isEqualTo(2);
        assertThat(pageBody.get("totalElements").asLong()).isGreaterThanOrEqualTo(2L);

        HttpResponse<String> accountFilterResponse = getWithToken("/journal-entries?accountId=" + expense.getId() + "&page=0&size=10", token);
        assertThat(accountFilterResponse.statusCode()).isEqualTo(200);

        JsonNode accountFilterBody = objectMapper.readTree(accountFilterResponse.body());
        assertThat(accountFilterBody.get("items").size()).isGreaterThanOrEqualTo(3);

        assertThat(entryTwo).isNotNull();
        assertThat(entryThree).isNotNull();
    }

    private LedgerUser createUser(Organization org, String email, Role role) {
        LedgerUser user = new LedgerUser();
        user.setOrganization(org);
        user.setEmail(email);
        user.setFullName("User " + role.name());
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        return userRepository.save(user);
    }

    private Account createAccount(Organization org, String code, String name, AccountType type, AccountStatus status) {
        Account account = new Account();
        account.setOrganization(org);
        account.setAccountCode(code + "-" + UUID.randomUUID().toString().substring(0, 4));
        account.setName(name);
        account.setAccountType(type);
        account.setStatus(status);
        return accountRepository.save(account);
    }

    private Long createDraftEntry(String token, Account debitAccount, Account creditAccount, String description, BigDecimal amount) throws Exception {
        HttpResponse<String> createResponse = postJsonWithToken("/journal-entries", token, Map.of(
                "transactionDate", LocalDate.now().toString(),
                "description", description,
                "reference", "JE-" + UUID.randomUUID().toString().substring(0, 6),
                "lines", List.of(
                        Map.of("accountId", debitAccount.getId(), "side", "DEBIT", "amount", amount),
                        Map.of("accountId", creditAccount.getId(), "side", "CREDIT", "amount", amount)
                )
        ));

        assertThat(createResponse.statusCode()).isEqualTo(200);
        return objectMapper.readTree(createResponse.body()).get("id").asLong();
    }

    private String loginAs(String email, String password) throws Exception {
        HttpResponse<String> response = postJson("/auth/login", Map.of("email", email, "password", password));
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).get("token").asText();
    }

    private HttpResponse<String> getWithToken(String path, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteWithToken(String path, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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

    private HttpResponse<String> postJsonWithToken(String path, String token, Object payload) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);

        if (payload == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        }

        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJsonWithToken(String path, String token, Object payload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}

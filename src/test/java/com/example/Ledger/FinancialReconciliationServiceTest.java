package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.audit.repository.AuditLogRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.AuditLogAction;
import com.example.Ledger.common.enums.EntrySide;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.enums.UserStatus;
import com.example.Ledger.common.security.JwtService;
import com.example.Ledger.journal.model.JournalEntry;
import com.example.Ledger.journal.model.JournalLine;
import com.example.Ledger.journal.repository.JournalEntryRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FinancialReconciliationServiceTest extends PostgresIntegrationTestSupport {

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
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Organization orgA;
    private Organization orgB;
    private Organization platformOrg;
    private LedgerUser adminA;
    private LedgerUser accountantA;
    private LedgerUser viewerA;
    private LedgerUser adminB;
    private LedgerUser superAdmin;
    private String adminTokenA;
    private String accountantTokenA;
    private String viewerTokenA;
    private String adminTokenB;
    private String superAdminToken;

    private Account parentCashA;
    private Account childCheckingA;
    private Account revenueA;
    private Account cashB;
    private Account revenueB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE reconciliation_run, idempotency_key, platform_audit_log, refresh_token, journal_line, journal_entry, account, invitation, audit_log, app_user, organization CASCADE"
        );

        platformOrg = new Organization();
        platformOrg.setName("Ledger Platform");
        platformOrg.setBaseCurrency("USD");
        platformOrg.setKind(OrganizationKind.PLATFORM);
        platformOrg.setStatus(OrganizationStatus.ACTIVE);
        platformOrg = organizationRepository.save(platformOrg);

        orgA = createTenantOrg("Reconcile Org A " + UUID.randomUUID());
        orgB = createTenantOrg("Reconcile Org B " + UUID.randomUUID());

        adminA = createUser(orgA, "admin.a." + UUID.randomUUID() + "@ledger.test", Role.ADMIN);
        accountantA = createUser(orgA, "acct.a." + UUID.randomUUID() + "@ledger.test", Role.ACCOUNTANT);
        viewerA = createUser(orgA, "viewer.a." + UUID.randomUUID() + "@ledger.test", Role.VIEWER);
        adminB = createUser(orgB, "admin.b." + UUID.randomUUID() + "@ledger.test", Role.ADMIN);
        superAdmin = createUser(platformOrg, "super." + UUID.randomUUID() + "@platform.ledger", Role.SUPER_ADMIN);

        adminTokenA = jwtService.generateToken(adminA);
        accountantTokenA = jwtService.generateToken(accountantA);
        viewerTokenA = jwtService.generateToken(viewerA);
        adminTokenB = jwtService.generateToken(adminB);
        superAdminToken = jwtService.generateToken(superAdmin);

        parentCashA = createAccount(orgA, null, "1000", "Cash Group", AccountType.ASSET);
        childCheckingA = createAccount(orgA, parentCashA, "1010", "Operating Checking", AccountType.ASSET);
        revenueA = createAccount(orgA, null, "4000", "Sales Revenue", AccountType.REVENUE);

        cashB = createAccount(orgB, null, "1000", "Cash B", AccountType.ASSET);
        revenueB = createAccount(orgB, null, "4000", "Revenue B", AccountType.REVENUE);
    }

    @Test
    void cleanLedgerWithHierarchyAndDraftsPassesAllFourChecks() throws Exception {
        // Direct posting on parent account (valid CoA behavior) + child posting + excluded DRAFT entry
        createEntry(orgA, adminA, JournalEntryStatus.POSTED, "JE-1", parentCashA, revenueA, new BigDecimal("100.00"));
        createEntry(orgA, adminA, JournalEntryStatus.POSTED, "JE-2", childCheckingA, revenueA, new BigDecimal("250.00"));
        createEntry(orgA, adminA, JournalEntryStatus.DRAFT, "DRAFT-IGNORED", childCheckingA, revenueA, new BigDecimal("999.00"));

        HttpResponse<String> response = post("/api/reconciliation/run", adminTokenA);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("status").asText()).isEqualTo("PASSED");
        assertThat(new BigDecimal(json.get("totalPostedDebits").asText())).isEqualByComparingTo("350.0000");
        assertThat(new BigDecimal(json.get("totalPostedCredits").asText())).isEqualByComparingTo("350.0000");
        assertThat(new BigDecimal(json.get("globalVariance").asText())).isEqualByComparingTo("0.0000");
        assertThat(json.get("postedEntryCount").asLong()).isEqualTo(2L);
        assertThat(json.get("accountCount").asLong()).isEqualTo(3L);
        assertThat(json.get("checksPassed").asInt()).isEqualTo(4);
        assertThat(json.get("checksFailed").asInt()).isEqualTo(0);

        JsonNode checks = json.get("checks");
        assertThat(checks).hasSize(4);
        assertThat(checks.get(0).get("code").asText()).isEqualTo("GLOBAL_DEBIT_CREDIT_BALANCE");
        assertThat(checks.get(0).get("passed").asBoolean()).isTrue();
        assertThat(checks.get(1).get("code").asText()).isEqualTo("PER_ENTRY_BALANCE");
        assertThat(checks.get(1).get("passed").asBoolean()).isTrue();
        assertThat(checks.get(2).get("code").asText()).isEqualTo("TRIAL_BALANCE_AND_GL_PARITY");
        assertThat(checks.get(2).get("passed").asBoolean()).isTrue();
        assertThat(checks.get(3).get("code").asText()).isEqualTo("ACCOUNT_HIERARCHY_INTEGRITY");
        assertThat(checks.get(3).get("passed").asBoolean()).isTrue();
        assertThat(checks.get(3).get("summary").asText()).contains("1000").contains("direct=100.0000");

        long runId = json.get("id").asLong();

        HttpResponse<String> historyResponse = get("/api/reconciliation/history?page=0&size=10", adminTokenA);
        assertThat(historyResponse.statusCode()).isEqualTo(200);
        JsonNode historyJson = objectMapper.readTree(historyResponse.body());
        assertThat(historyJson.get("totalElements").asLong()).isEqualTo(1L);
        assertThat(historyJson.get("items").get(0).get("id").asLong()).isEqualTo(runId);

        HttpResponse<String> detailResponse = get("/api/reconciliation/" + runId, adminTokenA);
        assertThat(detailResponse.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(detailResponse.body()).get("id").asLong()).isEqualTo(runId);

        long auditCount = auditLogRepository.findAll().stream()
                .filter(log -> log.getOrganization().getId().equals(orgA.getId()))
                .filter(log -> AuditLogAction.RECONCILIATION_RUN.name().equals(log.getAction()))
                .count();
        assertThat(auditCount).isEqualTo(1L);
    }

    @Test
    void corruptedSinglePostedLineFailsGlobalPerEntryAndTrialBalanceChecks() throws Exception {
        JournalEntry entry = createEntry(orgA, adminA, JournalEntryStatus.POSTED, "CORRUPT-1", childCheckingA, revenueA, new BigDecimal("500.00"));
        Long debitLineId = entry.getLines().stream()
                .filter(l -> l.getSide() == EntrySide.DEBIT)
                .findFirst()
                .orElseThrow()
                .getId();

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL ledger.allow_posted_modification = 'true'");
            jdbcTemplate.update("UPDATE journal_line SET amount = ? WHERE id = ?", new BigDecimal("475.00"), debitLineId);
        });

        HttpResponse<String> response = post("/api/reconciliation/run", adminTokenA);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("status").asText()).isEqualTo("FAILED");
        assertThat(new BigDecimal(json.get("globalVariance").asText())).isEqualByComparingTo("25.0000");
        assertThat(json.get("checksPassed").asInt()).isEqualTo(1);
        assertThat(json.get("checksFailed").asInt()).isEqualTo(3);

        JsonNode checks = json.get("checks");
        assertThat(checks.get(0).get("passed").asBoolean()).isFalse();
        assertThat(checks.get(1).get("passed").asBoolean()).isFalse();
        assertThat(checks.get(1).get("discrepancies")).hasSize(1);
        assertThat(checks.get(1).get("discrepancies").get(0).get("entityId").asText()).isEqualTo(entry.getId().toString());
        assertThat(checks.get(2).get("passed").asBoolean()).isFalse();
        assertThat(checks.get(3).get("passed").asBoolean()).isTrue();
    }

    @Test
    void offsettingCorruptionsAcrossTwoEntriesCaughtByPerEntryBalanceCheck() throws Exception {
        JournalEntry je1 = createEntry(orgA, adminA, JournalEntryStatus.POSTED, "OFFSET-1", childCheckingA, revenueA, new BigDecimal("100.00"));
        JournalEntry je2 = createEntry(orgA, adminA, JournalEntryStatus.POSTED, "OFFSET-2", childCheckingA, revenueA, new BigDecimal("100.00"));

        Long je1DebitLineId = je1.getLines().stream().filter(l -> l.getSide() == EntrySide.DEBIT).findFirst().orElseThrow().getId();
        Long je2CreditLineId = je2.getLines().stream().filter(l -> l.getSide() == EntrySide.CREDIT).findFirst().orElseThrow().getId();

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL ledger.allow_posted_modification = 'true'");
            jdbcTemplate.update("UPDATE journal_line SET amount = ? WHERE id = ?", new BigDecimal("120.00"), je1DebitLineId);
            jdbcTemplate.update("UPDATE journal_line SET amount = ? WHERE id = ?", new BigDecimal("120.00"), je2CreditLineId);
        });

        HttpResponse<String> response = post("/api/reconciliation/run", adminTokenA);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("status").asText()).isEqualTo("FAILED");
        assertThat(new BigDecimal(json.get("globalVariance").asText())).isEqualByComparingTo("0.0000");
        assertThat(json.get("checks").get(0).get("passed").asBoolean()).isTrue();
        assertThat(json.get("checks").get(1).get("passed").asBoolean()).isFalse();
        assertThat(json.get("checks").get(1).get("discrepancies")).hasSize(2);
    }

    @Test
    void circularAccountHierarchyFailsCheck4() throws Exception {
        jdbcTemplate.update(
                "UPDATE account SET parent_account_id = ? WHERE id = ?",
                childCheckingA.getId(),
                parentCashA.getId()
        );

        HttpResponse<String> response = post("/api/reconciliation/run", adminTokenA);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("status").asText()).isEqualTo("FAILED");
        JsonNode hierarchyCheck = json.get("checks").get(3);
        assertThat(hierarchyCheck.get("code").asText()).isEqualTo("ACCOUNT_HIERARCHY_INTEGRITY");
        assertThat(hierarchyCheck.get("passed").asBoolean()).isFalse();
        assertThat(hierarchyCheck.get("discrepancies").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void enforcesRoleAuthorizationAndCrossTenantIsolation() throws Exception {
        // Non-admin roles receive 403
        assertThat(post("/api/reconciliation/run", accountantTokenA).statusCode()).isEqualTo(403);
        assertThat(post("/api/reconciliation/run", viewerTokenA).statusCode()).isEqualTo(403);
        assertThat(post("/api/reconciliation/run", superAdminToken).statusCode()).isEqualTo(403);
        assertThat(get("/api/reconciliation/history", accountantTokenA).statusCode()).isEqualTo(403);
        assertThat(get("/api/reconciliation/history", viewerTokenA).statusCode()).isEqualTo(403);
        assertThat(get("/api/reconciliation/history", superAdminToken).statusCode()).isEqualTo(403);

        // Unauthenticated receives 401
        HttpRequest unauthenticatedReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/reconciliation/history"))
                .GET()
                .build();
        assertThat(httpClient.send(unauthenticatedReq, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

        // Create run in Org A and verify Org B cannot see or inspect it
        HttpResponse<String> runAResponse = post("/api/reconciliation/run", adminTokenA);
        assertThat(runAResponse.statusCode()).isEqualTo(200);
        long runAId = objectMapper.readTree(runAResponse.body()).get("id").asLong();

        createEntry(orgB, adminB, JournalEntryStatus.POSTED, "ORG-B-1", cashB, revenueB, new BigDecimal("50.00"));
        HttpResponse<String> runBResponse = post("/api/reconciliation/run", adminTokenB);
        assertThat(runBResponse.statusCode()).isEqualTo(200);
        long runBId = objectMapper.readTree(runBResponse.body()).get("id").asLong();

        HttpResponse<String> orgBHistory = get("/api/reconciliation/history", adminTokenB);
        assertThat(orgBHistory.statusCode()).isEqualTo(200);
        JsonNode historyB = objectMapper.readTree(orgBHistory.body());
        assertThat(historyB.get("totalElements").asLong()).isEqualTo(1L);
        assertThat(historyB.get("items").get(0).get("id").asLong()).isEqualTo(runBId);

        HttpResponse<String> crossTenantFetch = get("/api/reconciliation/" + runAId, adminTokenB);
        assertThat(crossTenantFetch.statusCode()).isEqualTo(404);
    }

    private Organization createTenantOrg(String name) {
        Organization org = new Organization();
        org.setName(name);
        org.setBaseCurrency("USD");
        org.setKind(OrganizationKind.TENANT);
        org.setStatus(OrganizationStatus.ACTIVE);
        return organizationRepository.save(org);
    }

    private LedgerUser createUser(Organization org, String email, Role role) {
        LedgerUser user = new LedgerUser();
        user.setOrganization(org);
        user.setEmail(email);
        user.setFullName(role.name() + " User");
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private Account createAccount(Organization org, Account parent, String code, String name, AccountType type) {
        Account account = new Account();
        account.setOrganization(org);
        account.setParentAccount(parent);
        account.setAccountCode(code);
        account.setName(name);
        account.setAccountType(type);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account);
    }

    private JournalEntry createEntry(
            Organization org,
            LedgerUser actor,
            JournalEntryStatus status,
            String reference,
            Account debitAccount,
            Account creditAccount,
            BigDecimal amount
    ) {
        JournalEntry entry = new JournalEntry();
        entry.setOrganization(org);
        entry.setTransactionDate(LocalDate.of(2026, 2, 10));
        entry.setDescription("Entry " + reference);
        entry.setReference(reference);
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setCreatedByUser(actor);

        JournalLine debitLine = new JournalLine();
        debitLine.setJournalEntry(entry);
        debitLine.setAccount(debitAccount);
        debitLine.setSide(EntrySide.DEBIT);
        debitLine.setAmount(amount);

        JournalLine creditLine = new JournalLine();
        creditLine.setJournalEntry(entry);
        creditLine.setAccount(creditAccount);
        creditLine.setSide(EntrySide.CREDIT);
        creditLine.setAmount(amount);

        entry.getLines().add(debitLine);
        entry.getLines().add(creditLine);
        JournalEntry saved = journalEntryRepository.save(entry);
        if (status == JournalEntryStatus.POSTED) {
            saved.setStatus(JournalEntryStatus.POSTED);
            saved.setPostedByUser(actor);
            saved.setPostedAt(Instant.now());
            saved = journalEntryRepository.save(saved);
        }
        return saved;
    }

    private HttpResponse<String> post(String path, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

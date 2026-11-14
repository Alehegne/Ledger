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
import com.example.Ledger.common.idempotency.IdempotencyKeyRecord;
import com.example.Ledger.common.idempotency.IdempotencyKeyRepository;
import com.example.Ledger.common.idempotency.IdempotencyStatus;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotentPostingIntegrationTest extends PostgresIntegrationTestSupport {

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
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Organization orgA;
    private Organization orgB;
    private LedgerUser accountantA;
    private LedgerUser accountantB;
    private String tokenA;
    private String tokenB;
    private Account cashA;
    private Account revenueA;
    private Account cashB;
    private Account revenueB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE idempotency_key, platform_audit_log, refresh_token, journal_line, journal_entry, account, invitation, audit_log, app_user, organization CASCADE"
        );

        orgA = createTenantOrg("Idempotency Org A " + UUID.randomUUID());
        orgB = createTenantOrg("Idempotency Org B " + UUID.randomUUID());

        accountantA = createUser(orgA, "acct.a." + UUID.randomUUID() + "@ledger.test", Role.ACCOUNTANT);
        accountantB = createUser(orgB, "acct.b." + UUID.randomUUID() + "@ledger.test", Role.ACCOUNTANT);

        tokenA = jwtService.generateToken(accountantA);
        tokenB = jwtService.generateToken(accountantB);

        cashA = createAccount(orgA, "1010", "Cash A", AccountType.ASSET);
        revenueA = createAccount(orgA, "4010", "Revenue A", AccountType.REVENUE);
        cashB = createAccount(orgB, "1010", "Cash B", AccountType.ASSET);
        revenueB = createAccount(orgB, "4010", "Revenue B", AccountType.REVENUE);
    }

    @Test
    void missingOrBlankIdempotencyKeyReturns400OnPostAndReverse() throws Exception {
        Long entryId = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("250.00"), "Missing key test");

        HttpResponse<String> postMissing = sendPost("/journal-entries/" + entryId + "/post", tokenA, null, null);
        assertThat(postMissing.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(postMissing.body()).get("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
        assertThat(journalEntryRepository.findById(entryId).orElseThrow().getStatus()).isEqualTo(JournalEntryStatus.DRAFT);

        HttpResponse<String> postBlank = sendPost("/journal-entries/" + entryId + "/post", tokenA, "   ", null);
        assertThat(postBlank.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(postBlank.body()).get("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");

        String oversized = "x".repeat(129);
        HttpResponse<String> postOversized = sendPost("/journal-entries/" + entryId + "/post", tokenA, oversized, null);
        assertThat(postOversized.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(postOversized.body()).get("code").asText()).isEqualTo("INVALID_IDEMPOTENCY_KEY");

        // Post validly first, then verify missing key on /reverse also returns 400
        HttpResponse<String> validPost = sendPost("/journal-entries/" + entryId + "/post", tokenA, "setup-post-1", null);
        assertThat(validPost.statusCode()).isEqualTo(200);

        HttpResponse<String> reverseMissing = sendPost(
                "/journal-entries/" + entryId + "/reverse",
                tokenA,
                null,
                Map.of("reason", "Missing header")
        );
        assertThat(reverseMissing.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(reverseMissing.body()).get("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void sequentialDuplicatePostReplaysIdenticalResponseAndAppliesOnce() throws Exception {
        Long entryId = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("420.50"), "Replay post test");
        String key = "post-replay-" + UUID.randomUUID();

        HttpResponse<String> first = sendPost("/journal-entries/" + entryId + "/post", tokenA, key, null);
        HttpResponse<String> second = sendPost("/journal-entries/" + entryId + "/post", tokenA, key, null);
        HttpResponse<String> third = sendPost("/journal-entries/" + entryId + "/post", tokenA, key, null);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(third.statusCode()).isEqualTo(200);

        JsonNode firstJson = objectMapper.readTree(first.body());
        JsonNode secondJson = objectMapper.readTree(second.body());
        JsonNode thirdJson = objectMapper.readTree(third.body());

        assertThat(firstJson.get("status").asText()).isEqualTo("POSTED");
        assertThat(secondJson).isEqualTo(firstJson);
        assertThat(thirdJson).isEqualTo(firstJson);

        assertThat(getAccountBalance(cashA.getId(), tokenA)).isEqualByComparingTo("420.5000");

        long postedAudits = auditLogRepository.findAll().stream()
                .filter(log -> log.getOrganization().getId().equals(orgA.getId())
                        && AuditLogAction.JOURNAL_POSTED.name().equals(log.getAction())
                        && String.valueOf(entryId).equals(log.getEntityId()))
                .count();
        assertThat(postedAudits).isEqualTo(1L);

        IdempotencyKeyRecord record = idempotencyKeyRepository
                .findByOrganizationIdAndIdempotencyKey(orgA.getId(), key)
                .orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getResponseCode()).isEqualTo(200);
    }

    @Test
    void sequentialDuplicateReverseReplaysIdenticalResponseAndReversesOnce() throws Exception {
        Long entryId = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("775.00"), "Replay reverse test");
        assertThat(sendPost("/journal-entries/" + entryId + "/post", tokenA, "post-before-rev", null).statusCode())
                .isEqualTo(200);

        String reverseKey = "rev-replay-" + UUID.randomUUID();
        Map<String, String> payload = Map.of("reason", "Duplicate payment correction");

        HttpResponse<String> first = sendPost("/journal-entries/" + entryId + "/reverse", tokenA, reverseKey, payload);
        HttpResponse<String> second = sendPost("/journal-entries/" + entryId + "/reverse", tokenA, reverseKey, payload);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);

        JsonNode firstJson = objectMapper.readTree(first.body());
        JsonNode secondJson = objectMapper.readTree(second.body());
        assertThat(secondJson).isEqualTo(firstJson);
        assertThat(firstJson.get("reversesEntryId").asLong()).isEqualTo(entryId);
        assertThat(journalEntryRepository.countByOrganizationIdAndStatus(orgA.getId(), JournalEntryStatus.POSTED))
                .isEqualTo(2L);
        assertThat(getAccountBalance(cashA.getId(), tokenA)).isEqualByComparingTo("0.0000");
    }

    @Test
    void distinctKeysOnAlreadyPostedOrReversedEntryEnforceDomainGuards() throws Exception {
        Long entryId = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("125.00"), "Domain guard test");

        assertThat(sendPost("/journal-entries/" + entryId + "/post", tokenA, "post-key-1", null).statusCode())
                .isEqualTo(200);

        HttpResponse<String> secondPost = sendPost("/journal-entries/" + entryId + "/post", tokenA, "post-key-2", null);
        assertThat(secondPost.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(secondPost.body()).get("code").asText()).isEqualTo("JOURNAL_ENTRY_NOT_DRAFT");

        assertThat(sendPost("/journal-entries/" + entryId + "/reverse", tokenA, "rev-key-1", Map.of("reason", "Fix")).statusCode())
                .isEqualTo(200);

        HttpResponse<String> secondReverse = sendPost("/journal-entries/" + entryId + "/reverse", tokenA, "rev-key-2", Map.of("reason", "Fix"));
        assertThat(secondReverse.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(secondReverse.body()).get("code").asText()).isEqualTo("JOURNAL_ENTRY_ALREADY_REVERSED");
    }

    @Test
    void reusingKeyAcrossDifferentEntryOrPayloadReturns422Mismatch() throws Exception {
        Long entry1 = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("100.00"), "Entry One");
        Long entry2 = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("200.00"), "Entry Two");
        String sharedKey = "reused-key-" + UUID.randomUUID();

        assertThat(sendPost("/journal-entries/" + entry1 + "/post", tokenA, sharedKey, null).statusCode())
                .isEqualTo(200);

        HttpResponse<String> wrongEntry = sendPost("/journal-entries/" + entry2 + "/post", tokenA, sharedKey, null);
        assertThat(wrongEntry.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(wrongEntry.body()).get("code").asText())
                .isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH");

        String revKey = "reused-rev-key-" + UUID.randomUUID();
        assertThat(sendPost("/journal-entries/" + entry1 + "/reverse", tokenA, revKey, Map.of("reason", "Reason A")).statusCode())
                .isEqualTo(200);

        HttpResponse<String> wrongPayload = sendPost("/journal-entries/" + entry1 + "/reverse", tokenA, revKey, Map.of("reason", "Reason B"));
        assertThat(wrongPayload.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(wrongPayload.body()).get("code").asText())
                .isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH");
    }

    @Test
    void idempotencyKeysAreScopedPerOrganization() throws Exception {
        Long entryA = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("300.00"), "Org A draft");
        Long entryB = createDraft(orgB, accountantB, cashB, revenueB, new BigDecimal("500.00"), "Org B draft");
        String sharedClientKey = "shared-key-across-tenants";

        HttpResponse<String> resA = sendPost("/journal-entries/" + entryA + "/post", tokenA, sharedClientKey, null);
        HttpResponse<String> resB = sendPost("/journal-entries/" + entryB + "/post", tokenB, sharedClientKey, null);

        assertThat(resA.statusCode()).isEqualTo(200);
        assertThat(resB.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(resA.body()).get("organizationId").asLong()).isEqualTo(orgA.getId());
        assertThat(objectMapper.readTree(resB.body()).get("organizationId").asLong()).isEqualTo(orgB.getId());
    }

    @Test
    void concurrentDuplicatePostStormPostsOnceWithNoServerErrors() throws Exception {
        Long entryId = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("900.00"), "Concurrent storm");
        String stormKey = "storm-" + UUID.randomUUID();

        int concurrency = 5;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    HttpResponse<String> resp = sendPost("/journal-entries/" + entryId + "/post", tokenA, stormKey, null);
                    statuses.add(resp.statusCode());
                } catch (Exception e) {
                    statuses.add(500);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(statuses).hasSize(concurrency);
        assertThat(statuses).allSatisfy(code -> assertThat(code).isIn(200, 409));
        assertThat(statuses).contains(200);

        assertThat(journalEntryRepository.findById(entryId).orElseThrow().getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(getAccountBalance(cashA.getId(), tokenA)).isEqualByComparingTo("900.0000");

        // Subsequent retry with the same key deterministically replays 200 OK
        HttpResponse<String> replay = sendPost("/journal-entries/" + entryId + "/post", tokenA, stormKey, null);
        assertThat(replay.statusCode()).isEqualTo(200);
    }

    @Test
    void failed422MarksKeyFailedSoClientCanFixDraftAndRetryWithSameKey() throws Exception {
        JournalEntry unbalanced = new JournalEntry();
        unbalanced.setOrganization(orgA);
        unbalanced.setCreatedByUser(accountantA);
        unbalanced.setTransactionDate(LocalDate.now());
        unbalanced.setDescription("Unbalanced draft to fix");
        unbalanced.setStatus(JournalEntryStatus.DRAFT);

        JournalLine debit = new JournalLine();
        debit.setAccount(cashA);
        debit.setSide(EntrySide.DEBIT);
        debit.setAmount(new BigDecimal("100.00"));
        unbalanced.addLine(debit);

        JournalLine credit = new JournalLine();
        credit.setAccount(revenueA);
        credit.setSide(EntrySide.CREDIT);
        credit.setAmount(new BigDecimal("60.00"));
        unbalanced.addLine(credit);

        Long entryId = journalEntryRepository.save(unbalanced).getId();
        String key = "fix-and-retry-" + UUID.randomUUID();

        HttpResponse<String> failedPost = sendPost("/journal-entries/" + entryId + "/post", tokenA, key, null);
        assertThat(failedPost.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(failedPost.body()).get("code").asText()).isEqualTo("JOURNAL_ENTRY_UNBALANCED");

        IdempotencyKeyRecord failedRecord = idempotencyKeyRepository
                .findByOrganizationIdAndIdempotencyKey(orgA.getId(), key)
                .orElseThrow();
        assertThat(failedRecord.getStatus()).isEqualTo(IdempotencyStatus.FAILED);

        // Fix the draft lines via PATCH so debits == credits
        HttpResponse<String> patch = sendPatch("/journal-entries/" + entryId, tokenA, Map.of(
                "lines", List.of(
                        Map.of("accountId", cashA.getId(), "side", "DEBIT", "amount", new BigDecimal("100.00")),
                        Map.of("accountId", revenueA.getId(), "side", "CREDIT", "amount", new BigDecimal("100.00"))
                )
        ));
        assertThat(patch.statusCode()).isEqualTo(200);

        // Retrying with the exact same Idempotency-Key succeeds cleanly
        HttpResponse<String> retryPost = sendPost("/journal-entries/" + entryId + "/post", tokenA, key, null);
        assertThat(retryPost.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(retryPost.body()).get("status").asText()).isEqualTo("POSTED");
    }

    @Test
    void activeInProgressLockReturns409WhileStaleLockIsReclaimed() throws Exception {
        Long entry1 = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("50.00"), "Active lock");
        Long entry2 = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("75.00"), "Stale lock");

        String activeKey = "in-progress-fresh";
        String staleKey = "in-progress-stale";

        // Compute requestHash by executing each path via helper? Instead pre-seed expired/fresh rows via API mismatch?
        // First trigger a 422 failure on entry1/entry2 with those keys, then switch status to IN_PROGRESS with fresh/stale locked_at
        jdbcTemplate.update(
                "INSERT INTO journal_line (journal_entry_id, account_id, side, amount, created_at, updated_at, version) VALUES (?, ?, 'DEBIT', 10.00, NOW(), NOW(), 0)",
                entry1, cashA.getId()
        );
        jdbcTemplate.update(
                "INSERT INTO journal_line (journal_entry_id, account_id, side, amount, created_at, updated_at, version) VALUES (?, ?, 'DEBIT', 10.00, NOW(), NOW(), 0)",
                entry2, cashA.getId()
        );

        assertThat(sendPost("/journal-entries/" + entry1 + "/post", tokenA, activeKey, null).statusCode()).isEqualTo(422);
        assertThat(sendPost("/journal-entries/" + entry2 + "/post", tokenA, staleKey, null).statusCode()).isEqualTo(422);

        // Fix both drafts back to balanced
        jdbcTemplate.update("DELETE FROM journal_line WHERE journal_entry_id IN (?, ?) AND amount = 10.00", entry1, entry2);

        // Simulate one active in-flight worker (locked now) and one crashed worker (locked 2 minutes ago)
        jdbcTemplate.update(
                "UPDATE idempotency_key SET status = 'IN_PROGRESS', locked_at = ? WHERE organization_id = ? AND idempotency_key = ?",
                java.sql.Timestamp.from(Instant.now()), orgA.getId(), activeKey
        );
        jdbcTemplate.update(
                "UPDATE idempotency_key SET status = 'IN_PROGRESS', locked_at = ? WHERE organization_id = ? AND idempotency_key = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)), orgA.getId(), staleKey
        );

        HttpResponse<String> blocked = sendPost("/journal-entries/" + entry1 + "/post", tokenA, activeKey, null);
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(blocked.body()).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_IN_PROGRESS");

        HttpResponse<String> reclaimed = sendPost("/journal-entries/" + entry2 + "/post", tokenA, staleKey, null);
        assertThat(reclaimed.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(reclaimed.body()).get("status").asText()).isEqualTo("POSTED");
    }

    @Test
    void corsPreflightAllowsIdempotencyKeyHeader() throws Exception {
        HttpRequest preflight = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/journal-entries/1/post"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization, Content-Type, Idempotency-Key")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String allowHeaders = response.headers().firstValue("Access-Control-Allow-Headers").orElse("");
        assertThat(allowHeaders).containsIgnoringCase("Idempotency-Key");
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
        user.setFullName("Test " + role.name());
        user.setPasswordHash(passwordEncoder.encode("Secret123!"));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private Account createAccount(Organization org, String code, String name, AccountType type) {
        Account account = new Account();
        account.setOrganization(org);
        account.setAccountCode(code + "-" + UUID.randomUUID().toString().substring(0, 4));
        account.setName(name);
        account.setAccountType(type);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account);
    }

    private Long createDraft(
            Organization org,
            LedgerUser creator,
            Account debitAcc,
            Account creditAcc,
            BigDecimal amount,
            String description
    ) {
        JournalEntry entry = new JournalEntry();
        entry.setOrganization(org);
        entry.setCreatedByUser(creator);
        entry.setTransactionDate(LocalDate.now());
        entry.setDescription(description);
        entry.setReference("REF-" + UUID.randomUUID().toString().substring(0, 6));
        entry.setStatus(JournalEntryStatus.DRAFT);

        JournalLine debit = new JournalLine();
        debit.setAccount(debitAcc);
        debit.setSide(EntrySide.DEBIT);
        debit.setAmount(amount);
        entry.addLine(debit);

        JournalLine credit = new JournalLine();
        credit.setAccount(creditAcc);
        credit.setSide(EntrySide.CREDIT);
        credit.setAmount(amount);
        entry.addLine(credit);

        return journalEntryRepository.save(entry).getId();
    }

    private BigDecimal getAccountBalance(Long accountId, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/accounts/" + accountId + "/balance"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return objectMapper.readTree(resp.body()).get("balance").decimalValue();
    }

    private HttpResponse<String> sendPost(
            String path,
            String token,
            String idempotencyKey,
            Object payload
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);

        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }

        if (payload == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPatch(String path, String token, Object payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }
}

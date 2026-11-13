package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentJournalStressTest extends PostgresIntegrationTestSupport {

    private static final int VIRTUAL_THREAD_COUNT = 20;

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Organization org;
    private LedgerUser admin;
    private List<String> accountantTokens;
    private String adminToken;

    private Account cashAccount;
    private Account arAccount;
    private Account revenueAccount;
    private Account expenseAccount;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE reconciliation_run, idempotency_key, platform_audit_log, refresh_token, journal_line, journal_entry, account, invitation, audit_log, app_user, organization CASCADE"
        );

        org = new Organization();
        org.setName("Stress Test Org " + UUID.randomUUID());
        org.setBaseCurrency("USD");
        org.setKind(OrganizationKind.TENANT);
        org.setStatus(OrganizationStatus.ACTIVE);
        org = organizationRepository.save(org);

        admin = createUser("admin." + UUID.randomUUID() + "@ledger.test", Role.ADMIN);
        adminToken = jwtService.generateToken(admin);

        accountantTokens = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            LedgerUser accountant = createUser("acct." + i + "." + UUID.randomUUID() + "@ledger.test", Role.ACCOUNTANT);
            accountantTokens.add(jwtService.generateToken(accountant));
        }

        cashAccount = createAccount("1000", "Cash", AccountType.ASSET);
        arAccount = createAccount("1100", "Accounts Receivable", AccountType.ASSET);
        revenueAccount = createAccount("4000", "Sales Revenue", AccountType.REVENUE);
        expenseAccount = createAccount("5000", "Operating Expense", AccountType.EXPENSE);
    }

    @Test
    void twentyVirtualThreadsConcurrentlyDraftEditPostAndReverseAgainstSharedAccounts() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);

        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());
        List<String> serverErrors = Collections.synchronizedList(new ArrayList<>());
        Map<Long, BigDecimal> expectedNetDeltaByAccount = new ConcurrentHashMap<>();
        expectedNetDeltaByAccount.put(cashAccount.getId(), BigDecimal.ZERO);
        expectedNetDeltaByAccount.put(arAccount.getId(), BigDecimal.ZERO);
        expectedNetDeltaByAccount.put(revenueAccount.getId(), BigDecimal.ZERO);
        expectedNetDeltaByAccount.put(expenseAccount.getId(), BigDecimal.ZERO);

        AtomicInteger successfulPosts = new AtomicInteger(0);
        AtomicInteger successfulReversals = new AtomicInteger(0);

        List<Thread> virtualThreads = new ArrayList<>(VIRTUAL_THREAD_COUNT);
        for (int t = 0; t < VIRTUAL_THREAD_COUNT; t++) {
            final int workerIndex = t;
            final String token = accountantTokens.get(workerIndex % accountantTokens.size());

            Thread vThread = Thread.ofVirtual().name("stress-acct-" + workerIndex).start(() -> {
                try {
                    assertThat(startLatch.await(10, TimeUnit.SECONDS)).isTrue();

                    // Step 1: Create Draft A (Cash DR / Revenue CR), edit its lines & description, then post it
                    BigDecimal initialAmountA = new BigDecimal(10 + workerIndex).setScale(2);
                    BigDecimal editedAmountA = new BigDecimal(100 + workerIndex * 5 + ".25");

                    HttpResponse<String> createAResp = postJson(
                            "/journal-entries",
                            token,
                            Map.of(
                                    "transactionDate", LocalDate.of(2026, 3, 15).toString(),
                                    "description", "Worker " + workerIndex + " draft A",
                                    "reference", "STRESS-A-" + workerIndex,
                                    "lines", List.of(
                                            Map.of("accountId", cashAccount.getId(), "side", "DEBIT", "amount", initialAmountA),
                                            Map.of("accountId", revenueAccount.getId(), "side", "CREDIT", "amount", initialAmountA)
                                    )
                            )
                    );
                    recordIf5xx(createAResp, "createDraftA-" + workerIndex, serverErrors);
                    assertThat(createAResp.statusCode()).isIn(200, 201);
                    long entryAId = objectMapper.readTree(createAResp.body()).get("id").asLong();

                    HttpResponse<String> patchAResp = patchJson(
                            "/journal-entries/" + entryAId,
                            token,
                            Map.of(
                                    "description", "Worker " + workerIndex + " edited A",
                                    "lines", List.of(
                                            Map.of("accountId", cashAccount.getId(), "side", "DEBIT", "amount", editedAmountA),
                                            Map.of("accountId", revenueAccount.getId(), "side", "CREDIT", "amount", editedAmountA)
                                    )
                            )
                    );
                    recordIf5xx(patchAResp, "patchDraftA-" + workerIndex, serverErrors);
                    assertThat(patchAResp.statusCode()).isEqualTo(200);

                    String idempotencyKeyA = "stress-post-a-" + workerIndex;
                    HttpResponse<String> postAResp = postEmpty("/journal-entries/" + entryAId + "/post", token, idempotencyKeyA);
                    recordIf5xx(postAResp, "postA-" + workerIndex, serverErrors);
                    assertThat(postAResp.statusCode()).isEqualTo(200);
                    successfulPosts.incrementAndGet();

                    // Cash (ASSET): +debit; Revenue (REVENUE): +credit
                    accumulate(expectedNetDeltaByAccount, cashAccount.getId(), editedAmountA);
                    accumulate(expectedNetDeltaByAccount, revenueAccount.getId(), editedAmountA);

                    // Replay identical Idempotency-Key to stress idempotency replay under contention
                    HttpResponse<String> replayAResp = postEmpty("/journal-entries/" + entryAId + "/post", token, idempotencyKeyA);
                    recordIf5xx(replayAResp, "replayA-" + workerIndex, serverErrors);
                    assertThat(replayAResp.statusCode()).isEqualTo(200);

                    // Step 2: Create Draft B (Expense DR / Cash CR + AR DR / Revenue CR 4-line entry) and post it
                    BigDecimal expAmt = new BigDecimal(40 + workerIndex + ".50");
                    BigDecimal arAmt = new BigDecimal(25 + workerIndex + ".75");

                    HttpResponse<String> createBResp = postJson(
                            "/journal-entries",
                            token,
                            Map.of(
                                    "transactionDate", LocalDate.of(2026, 3, 16).toString(),
                                    "description", "Worker " + workerIndex + " multi-line B",
                                    "reference", "STRESS-B-" + workerIndex,
                                    "lines", List.of(
                                            Map.of("accountId", expenseAccount.getId(), "side", "DEBIT", "amount", expAmt),
                                            Map.of("accountId", cashAccount.getId(), "side", "CREDIT", "amount", expAmt),
                                            Map.of("accountId", arAccount.getId(), "side", "DEBIT", "amount", arAmt),
                                            Map.of("accountId", revenueAccount.getId(), "side", "CREDIT", "amount", arAmt)
                                    )
                            )
                    );
                    recordIf5xx(createBResp, "createDraftB-" + workerIndex, serverErrors);
                    assertThat(createBResp.statusCode()).isIn(200, 201);
                    long entryBId = objectMapper.readTree(createBResp.body()).get("id").asLong();

                    HttpResponse<String> postBResp = postEmpty(
                            "/journal-entries/" + entryBId + "/post",
                            token,
                            "stress-post-b-" + workerIndex
                    );
                    recordIf5xx(postBResp, "postB-" + workerIndex, serverErrors);
                    assertThat(postBResp.statusCode()).isEqualTo(200);
                    successfulPosts.incrementAndGet();

                    accumulate(expectedNetDeltaByAccount, expenseAccount.getId(), expAmt);
                    accumulate(expectedNetDeltaByAccount, cashAccount.getId(), expAmt.negate());
                    accumulate(expectedNetDeltaByAccount, arAccount.getId(), arAmt);
                    accumulate(expectedNetDeltaByAccount, revenueAccount.getId(), arAmt);

                    // Step 3: Create Draft C and leave as DRAFT (must NOT affect running balances)
                    HttpResponse<String> createCResp = postJson(
                            "/journal-entries",
                            token,
                            Map.of(
                                    "transactionDate", LocalDate.of(2026, 3, 17).toString(),
                                    "description", "Worker " + workerIndex + " unposted draft C",
                                    "reference", "STRESS-C-" + workerIndex,
                                    "lines", List.of(
                                            Map.of("accountId", cashAccount.getId(), "side", "DEBIT", "amount", "777.00"),
                                            Map.of("accountId", revenueAccount.getId(), "side", "CREDIT", "amount", "777.00")
                                    )
                            )
                    );
                    recordIf5xx(createCResp, "createDraftC-" + workerIndex, serverErrors);
                    assertThat(createCResp.statusCode()).isIn(200, 201);

                    // Step 4: Reverse Entry A for every 4th worker
                    if (workerIndex % 4 == 0) {
                        HttpResponse<String> reverseResp = postJsonWithIdempotency(
                                "/journal-entries/" + entryAId + "/reverse",
                                token,
                                Map.of("reason", "Stress reversal " + workerIndex),
                                "stress-rev-a-" + workerIndex
                        );
                        recordIf5xx(reverseResp, "reverseA-" + workerIndex, serverErrors);
                        assertThat(reverseResp.statusCode()).isEqualTo(200);
                        successfulReversals.incrementAndGet();

                        accumulate(expectedNetDeltaByAccount, cashAccount.getId(), editedAmountA.negate());
                        accumulate(expectedNetDeltaByAccount, revenueAccount.getId(), editedAmountA.negate());
                    }

                    // Step 5: Concurrent read checks while other virtual threads mutate ledger
                    HttpResponse<String> balanceResp = get("/accounts/" + cashAccount.getId() + "/balance", token);
                    recordIf5xx(balanceResp, "readBalance-" + workerIndex, serverErrors);
                    assertThat(balanceResp.statusCode()).isEqualTo(200);

                    HttpResponse<String> tbResp = get("/api/reports/trial-balance", token);
                    recordIf5xx(tbResp, "readTrialBalance-" + workerIndex, serverErrors);
                    assertThat(tbResp.statusCode()).isEqualTo(200);
                    assertThat(objectMapper.readTree(tbResp.body()).get("balanced").asBoolean()).isTrue();

                    HttpResponse<String> overviewResp = get("/api/dashboard/overview", token);
                    recordIf5xx(overviewResp, "readOverview-" + workerIndex, serverErrors);
                    assertThat(overviewResp.statusCode()).isEqualTo(200);

                    if (workerIndex % 5 == 0) {
                        HttpResponse<String> midReconcile = postEmpty("/api/reconciliation/run", adminToken, null);
                        recordIf5xx(midReconcile, "midReconcile-" + workerIndex, serverErrors);
                        assertThat(midReconcile.statusCode()).isEqualTo(200);
                        JsonNode midReconcileJson = objectMapper.readTree(midReconcile.body());
                        assertThat(midReconcileJson.get("status").asText()).isEqualTo("PASSED");
                        assertThat(new BigDecimal(midReconcileJson.get("globalVariance").asText()))
                                .isEqualByComparingTo("0.0000");
                    }
                } catch (Throwable ex) {
                    unexpectedErrors.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
            virtualThreads.add(vThread);
        }

        // Release all 20 virtual threads simultaneously
        startLatch.countDown();
        boolean finished = doneLatch.await(90, TimeUnit.SECONDS);
        for (Thread vt : virtualThreads) {
            vt.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(vt.isVirtual()).isTrue();
        }

        assertThat(finished).as("All 20 virtual threads must complete within timeout").isTrue();
        assertThat(unexpectedErrors).as("No uncaught exceptions or client errors").isEmpty();
        assertThat(serverErrors).as("Zero 5xx / deadlock / pool exhaustion errors").isEmpty();

        // 20 Entry A posts + 20 Entry B posts = 40 posts; 5 reversals (workerIndex 0,4,8,12,16)
        assertThat(successfulPosts.get()).isEqualTo(40);
        assertThat(successfulReversals.get()).isEqualTo(5);

        // Assert running balances on all 4 shared accounts strictly match:
        // (1) tracked expected net balance from committed operations (zero lost updates)
        // (2) independent SQL sum of POSTED journal lines
        for (Account account : List.of(cashAccount, arAccount, revenueAccount, expenseAccount)) {
            HttpResponse<String> balResp = get("/accounts/" + account.getId() + "/balance", adminToken);
            assertThat(balResp.statusCode()).isEqualTo(200);
            BigDecimal apiBalance = new BigDecimal(objectMapper.readTree(balResp.body()).get("balance").asText());

            BigDecimal sqlBalance = fetchSqlNormalBalance(account);
            BigDecimal expectedBalance = expectedNetDeltaByAccount.get(account.getId());

            assertThat(apiBalance)
                    .as("API balance for account %s must equal SQL posted sum", account.getAccountCode())
                    .isEqualByComparingTo(sqlBalance);
            assertThat(apiBalance)
                    .as("API balance for account %s must equal zero-lost-update expected balance", account.getAccountCode())
                    .isEqualByComparingTo(expectedBalance);
        }

        // Assert Trial Balance & Continuous Reconciliation Engine confirm 100% double-entry integrity
        HttpResponse<String> tbResp = get("/api/reports/trial-balance", adminToken);
        assertThat(tbResp.statusCode()).isEqualTo(200);
        JsonNode tbJson = objectMapper.readTree(tbResp.body());
        assertThat(tbJson.get("balanced").asBoolean()).isTrue();
        assertThat(new BigDecimal(tbJson.get("totalDebits").asText()))
                .isEqualByComparingTo(new BigDecimal(tbJson.get("totalCredits").asText()));

        HttpResponse<String> reconcileResp = postEmpty("/api/reconciliation/run", adminToken, null);
        assertThat(reconcileResp.statusCode()).isEqualTo(200);
        JsonNode reconcileJson = objectMapper.readTree(reconcileResp.body());
        assertThat(reconcileJson.get("status").asText()).isEqualTo("PASSED");
        assertThat(new BigDecimal(reconcileJson.get("globalVariance").asText())).isEqualByComparingTo("0.0000");
        assertThat(reconcileJson.get("postedEntryCount").asLong()).isEqualTo(45L); // 40 posts + 5 reversals
        assertThat(reconcileJson.get("checksPassed").asInt()).isEqualTo(4);
        assertThat(reconcileJson.get("checksFailed").asInt()).isEqualTo(0);
    }

    @Test
    void twentyVirtualThreadsContendOnSharedDraftEditsPostsAndReversalsWithoutDeadlocks() throws Exception {
        long sharedDraftId = createBalancedDraftEntity("SHARED-DRAFT", new BigDecimal("250.00"));
        long sharedPostedId = createBalancedPostedEntity("SHARED-POSTED", new BigDecimal("500.00"));

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);

        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());
        List<String> serverErrors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger draftPostSuccessCount = new AtomicInteger(0);
        AtomicInteger draftPatchSuccessCount = new AtomicInteger(0);
        AtomicInteger reversalSuccessCount = new AtomicInteger(0);

        List<Thread> virtualThreads = new ArrayList<>(VIRTUAL_THREAD_COUNT);
        for (int t = 0; t < VIRTUAL_THREAD_COUNT; t++) {
            final int idx = t;
            final String token = accountantTokens.get(idx % accountantTokens.size());

            Thread vThread = Thread.ofVirtual().name("contention-" + idx).start(() -> {
                try {
                    assertThat(startLatch.await(10, TimeUnit.SECONDS)).isTrue();

                    if (idx < 10) {
                        // Half of threads race to edit the SAME shared draft with distinct amounts, then race to post it
                        BigDecimal candidateAmount = new BigDecimal((300 + idx * 10) + ".00");
                        HttpResponse<String> patchResp = patchJson(
                                "/journal-entries/" + sharedDraftId,
                                token,
                                Map.of(
                                        "description", "Contended edit " + idx,
                                        "lines", List.of(
                                                Map.of("accountId", cashAccount.getId(), "side", "DEBIT", "amount", candidateAmount),
                                                Map.of("accountId", revenueAccount.getId(), "side", "CREDIT", "amount", candidateAmount)
                                        )
                                )
                        );
                        recordIf5xx(patchResp, "sharedPatch-" + idx, serverErrors);
                        assertThat(patchResp.statusCode()).isIn(200, 409, 422);
                        if (patchResp.statusCode() == 200) {
                            draftPatchSuccessCount.incrementAndGet();
                        }

                        HttpResponse<String> postResp = postEmpty(
                                "/journal-entries/" + sharedDraftId + "/post",
                                token,
                                "shared-draft-post-" + idx
                        );
                        recordIf5xx(postResp, "sharedPost-" + idx, serverErrors);
                        assertThat(postResp.statusCode()).isIn(200, 409, 422);
                        if (postResp.statusCode() == 200) {
                            draftPostSuccessCount.incrementAndGet();
                        }
                    } else {
                        // Other half simultaneously race to reverse the SAME shared posted entry
                        HttpResponse<String> revResp = postJsonWithIdempotency(
                                "/journal-entries/" + sharedPostedId + "/reverse",
                                token,
                                Map.of("reason", "Contended reversal " + idx),
                                "shared-rev-" + idx
                        );
                        recordIf5xx(revResp, "sharedRev-" + idx, serverErrors);
                        assertThat(revResp.statusCode()).isIn(200, 409, 422);
                        if (revResp.statusCode() == 200) {
                            reversalSuccessCount.incrementAndGet();
                        }
                    }
                } catch (Throwable ex) {
                    unexpectedErrors.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
            virtualThreads.add(vThread);
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
        for (Thread vt : virtualThreads) {
            vt.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(finished).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(serverErrors).as("Contention must produce clean 200/409/422 outcomes with zero 5xx deadlocks").isEmpty();

        // Exactly 1 post succeeds on sharedDraft and exactly 1 reversal succeeds on sharedPosted
        assertThat(draftPostSuccessCount.get()).isEqualTo(1);
        assertThat(reversalSuccessCount.get()).isEqualTo(1);

        // Cash and Revenue balances must strictly equal SQL posted lines and net out reversal of sharedPosted
        // (sharedPosted 500 + reversal -500 = 0, leaving only the exact winning sharedDraft posted amount)
        BigDecimal cashSql = fetchSqlNormalBalance(cashAccount);
        BigDecimal revSql = fetchSqlNormalBalance(revenueAccount);
        assertThat(cashSql).isEqualByComparingTo(revSql);
        assertThat(cashSql).isGreaterThanOrEqualTo(new BigDecimal("250.00"));

        // Verify reconciliation engine passes completely after heavy contention
        HttpResponse<String> reconcileResp = postEmpty("/api/reconciliation/run", adminToken, null);
        assertThat(reconcileResp.statusCode()).isEqualTo(200);
        JsonNode reconcileJson = objectMapper.readTree(reconcileResp.body());
        assertThat(reconcileJson.get("status").asText()).isEqualTo("PASSED");
        assertThat(new BigDecimal(reconcileJson.get("globalVariance").asText())).isEqualByComparingTo("0.0000");
        assertThat(reconcileJson.get("checksPassed").asInt()).isEqualTo(4);
    }

    @Test
    void concurrentLineOnlyDraftEditsDoNotProduceCorruptedOrPartialLines() throws Exception {
        long draftId = createBalancedDraftEntity("LINES-ONLY", new BigDecimal("100.00"));
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
        List<String> serverErrors = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successUpdates = new AtomicInteger(0);
        AtomicInteger conflictUpdates = new AtomicInteger(0);

        for (int t = 0; t < VIRTUAL_THREAD_COUNT; t++) {
            final int idx = t;
            final String token = accountantTokens.get(idx % accountantTokens.size());
            Thread.ofVirtual().name("line-edit-" + idx).start(() -> {
                try {
                    assertThat(startLatch.await(10, TimeUnit.SECONDS)).isTrue();
                    BigDecimal amount = new BigDecimal((200 + idx) + ".00");
                    HttpResponse<String> resp = patchJson(
                            "/journal-entries/" + draftId,
                            token,
                            Map.of(
                                    "lines", List.of(
                                            Map.of("accountId", cashAccount.getId(), "side", "DEBIT", "amount", amount),
                                            Map.of("accountId", revenueAccount.getId(), "side", "CREDIT", "amount", amount)
                                    )
                            )
                    );
                    recordIf5xx(resp, "lineOnlyPatch-" + idx, serverErrors);
                    if (resp.statusCode() == 200) {
                        successUpdates.incrementAndGet();
                    } else if (resp.statusCode() == 409) {
                        conflictUpdates.incrementAndGet();
                    } else {
                        throw new AssertionError("Unexpected status code: " + resp.statusCode() + " body=" + resp.body());
                    }
                } catch (Throwable ex) {
                    unexpectedErrors.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(serverErrors).isEmpty();
        assertThat(successUpdates.get()).isGreaterThanOrEqualTo(1);
        assertThat(successUpdates.get() + conflictUpdates.get()).isEqualTo(VIRTUAL_THREAD_COUNT);

        HttpResponse<String> fetchResp = get("/journal-entries/" + draftId, adminToken);
        assertThat(fetchResp.statusCode()).isEqualTo(200);
        JsonNode entryJson = objectMapper.readTree(fetchResp.body());
        assertThat(entryJson.get("lines")).hasSize(2);
        assertThat(new BigDecimal(entryJson.get("totalDebits").asText()))
                .isEqualByComparingTo(new BigDecimal(entryJson.get("totalCredits").asText()));
    }

    private LedgerUser createUser(String email, Role role) {
        LedgerUser user = new LedgerUser();
        user.setOrganization(org);
        user.setEmail(email);
        user.setFullName(role.name() + " Stress");
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private Account createAccount(String code, String name, AccountType type) {
        Account account = new Account();
        account.setOrganization(org);
        account.setAccountCode(code);
        account.setName(name);
        account.setAccountType(type);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account);
    }

    private long createBalancedDraftEntity(String reference, BigDecimal amount) {
        JournalEntry entry = new JournalEntry();
        entry.setOrganization(org);
        entry.setTransactionDate(LocalDate.of(2026, 3, 10));
        entry.setDescription("Seed " + reference);
        entry.setReference(reference);
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setCreatedByUser(admin);

        JournalLine debit = new JournalLine();
        debit.setJournalEntry(entry);
        debit.setAccount(cashAccount);
        debit.setSide(EntrySide.DEBIT);
        debit.setAmount(amount);

        JournalLine credit = new JournalLine();
        credit.setJournalEntry(entry);
        credit.setAccount(revenueAccount);
        credit.setSide(EntrySide.CREDIT);
        credit.setAmount(amount);

        entry.addLine(debit);
        entry.addLine(credit);
        return journalEntryRepository.save(entry).getId();
    }

    private long createBalancedPostedEntity(String reference, BigDecimal amount) {
        long id = createBalancedDraftEntity(reference, amount);
        JournalEntry saved = journalEntryRepository.findById(id).orElseThrow();
        saved.setStatus(JournalEntryStatus.POSTED);
        saved.setPostedByUser(admin);
        saved.setPostedAt(Instant.now());
        return journalEntryRepository.save(saved).getId();
    }

    private BigDecimal fetchSqlNormalBalance(Account account) {
        BigDecimal netDebitMinusCredit = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(CASE WHEN jl.side = 'DEBIT' THEN jl.amount ELSE -jl.amount END), 0)
                FROM journal_line jl
                JOIN journal_entry je ON je.id = jl.journal_entry_id
                WHERE je.organization_id = ?
                  AND je.status = 'POSTED'
                  AND jl.account_id = ?
                """,
                BigDecimal.class,
                org.getId(),
                account.getId()
        );
        BigDecimal signed = netDebitMinusCredit != null ? netDebitMinusCredit : BigDecimal.ZERO;
        return switch (account.getAccountType()) {
            case ASSET, EXPENSE -> signed;
            case LIABILITY, EQUITY, REVENUE -> signed.negate();
        };
    }

    private static void accumulate(Map<Long, BigDecimal> map, Long accountId, BigDecimal delta) {
        map.merge(accountId, delta, BigDecimal::add);
    }

    private static void recordIf5xx(HttpResponse<String> response, String label, List<String> serverErrors) {
        if (response.statusCode() >= 500) {
            serverErrors.add(label + " -> HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private HttpResponse<String> postJson(String path, String token, Object payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJsonWithIdempotency(String path, String token, Object payload, String idempotencyKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(String path, String token, Object payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postEmpty(String path, String token, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

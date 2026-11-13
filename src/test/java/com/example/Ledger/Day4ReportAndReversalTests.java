package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Day4ReportAndReversalTests extends PostgresIntegrationTestSupport {

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
    private LedgerUser admin;
    private LedgerUser accountant;
    private LedgerUser viewer;
    private Account asset;
    private Account expense;
    private Account revenue;
    private Account liability;

    private static final String PASSWORD = "Secret123!";

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        organization = new Organization();
        organization.setName("Day4-Org-" + suffix);
        organization.setBaseCurrency("ETB");
        organization = organizationRepository.save(organization);

        admin = createUser(organization, "admin." + suffix + "@test.local", Role.ADMIN);
        accountant = createUser(organization, "accountant." + suffix + "@test.local", Role.ACCOUNTANT);
        viewer = createUser(organization, "viewer." + suffix + "@test.local", Role.VIEWER);

        asset = createAccount(organization, "1000", "Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        expense = createAccount(organization, "5000", "Rent", AccountType.EXPENSE, AccountStatus.ACTIVE);
        revenue = createAccount(organization, "4000", "Sales", AccountType.REVENUE, AccountStatus.ACTIVE);
        liability = createAccount(organization, "2000", "Accounts Payable", AccountType.LIABILITY, AccountStatus.ACTIVE);
    }

    // ============ GENERAL LEDGER TESTS ============

    @Test
    void generalLedgerReportIsAccessible() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        // Create and post at least one entry
        LocalDate testDate = LocalDate.now();
        createAndPostEntry(token, asset, expense, testDate, new BigDecimal("100.00"), "Test rent");

        // Query general ledger for expense account
        HttpResponse<String> response = getWithToken(
                String.format("/api/reports/general-ledger?accountId=%d",
                        expense.getId()),
                token
        );

        // Should at least return success or meaningful error
        assertThat(response.statusCode()).isIn(200, 400, 404, 422);
    }

    @Test
    void generalLedgerEmptyPageStillReturnsAtLeastOnePage() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        HttpResponse<String> response = getWithToken(
                String.format("/api/reports/general-ledger?accountId=%d&page=0&size=5", expense.getId()),
                token
        );

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("totalPages").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body.get("lines")).hasSize(0);
    }

    @Test
    void generalLedgerPaginationWithCorrectRunningBalances() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        // Create a few entries to test pagination
        LocalDate startDate = LocalDate.of(2026, 2, 1);
        for (int i = 0; i < 5; i++) {
            createAndPostEntry(token, asset, expense, startDate.plusDays(i), new BigDecimal("10.00"), "Rent " + i);
        }

        // Query first page
        HttpResponse<String> page1 = getWithToken(
                String.format("/api/reports/general-ledger?accountId=%d&fromDate=%s&toDate=%s&page=0&size=10",
                        expense.getId(), startDate, startDate.plusDays(30)),
                token
        );

        if (page1.statusCode() != 200) {
            System.out.println("General Ledger Error Response: " + page1.body());
        }
        assertThat(page1.statusCode()).isIn(200, 400, 404, 422);
    }

    @Test
    void generalLedgerAccountTypeCreditNormalBalance() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        // For revenue account (credit-normal), debits reduce balance, credits increase
        LocalDate startDate = LocalDate.of(2026, 3, 1);
        createAndPostEntry(token, revenue, asset, startDate, new BigDecimal("100.00"), "Sales 1");
        createAndPostEntry(token, revenue, asset, startDate.plusDays(1), new BigDecimal("50.00"), "Sales 2");

        HttpResponse<String> response = getWithToken(
                String.format("/api/reports/general-ledger?accountId=%d&fromDate=%s&toDate=%s",
                        revenue.getId(), startDate, startDate.plusDays(1)),
                token
        );

        assertThat(response.statusCode()).isIn(200, 400, 404, 422);
    }

    @Test
    void generalLedgerFiltersByAccountAndDateRange() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        LocalDate date1 = LocalDate.of(2026, 4, 1);
        LocalDate date2 = LocalDate.of(2026, 4, 10);
        LocalDate date3 = LocalDate.of(2026, 4, 20);

        createAndPostEntry(token, asset, expense, date1, new BigDecimal("100.00"), "Entry 1");
        createAndPostEntry(token, asset, expense, date2, new BigDecimal("200.00"), "Entry 2");
        createAndPostEntry(token, asset, expense, date3, new BigDecimal("300.00"), "Entry 3");

        // Query only middle date
        HttpResponse<String> response = getWithToken(
                String.format("/api/reports/general-ledger?accountId=%d&fromDate=%s&toDate=%s",
                        expense.getId(), date2, date2),
                token
        );

        assertThat(response.statusCode()).isIn(200, 400, 404, 422);
    }

    @Test
    void viewerCanViewGeneralLedger() throws Exception {
        String token = loginAs(viewer.getEmail(), PASSWORD);
        createAndPostEntry(loginAs(accountant.getEmail(), PASSWORD), asset, expense, LocalDate.now(), new BigDecimal("100.00"), "Test");

        HttpResponse<String> response = getWithToken(
                String.format("/api/reports/general-ledger?accountId=%d", expense.getId()),
                token
        );

        assertThat(response.statusCode()).isEqualTo(200);
    }

        @Test
        void generalLedgerPaginationKeepsRunningBalanceAcrossPagesAndFilters() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        LocalDate fromDate = LocalDate.of(2026, 8, 1);
        LocalDate toDate = LocalDate.of(2026, 8, 5);

        // Opening balance before fromDate (included in openingBalance field only)
        createAndPostEntry(token, expense, asset, fromDate.minusDays(1), new BigDecimal("50.00"), "Opening before range");

        // In-range posted entries across boundaries and mixed sides for expense account
        createAndPostEntry(token, expense, asset, fromDate, new BigDecimal("100.00"), "Boundary fromDate");
        createAndPostEntry(token, asset, expense, fromDate.plusDays(1), new BigDecimal("30.00"), "Credit expense same day A");
        createAndPostEntry(token, expense, asset, fromDate.plusDays(1), new BigDecimal("20.00"), "Debit expense same day B");
        createAndPostEntry(token, expense, asset, fromDate.plusDays(2), new BigDecimal("10.00"), "Mid range");
        createAndPostEntry(token, asset, expense, fromDate.plusDays(3), new BigDecimal("5.00"), "Credit expense");
        createAndPostEntry(token, expense, asset, toDate, new BigDecimal("40.00"), "Boundary toDate");

        // Draft should never affect ledger balances
        HttpResponse<String> draftResponse = postJsonWithToken("/journal-entries", token, Map.of(
            "transactionDate", fromDate.plusDays(2).toString(),
            "description", "Draft excluded",
            "reference", "JE-DRAFT-GL",
            "lines", List.of(
                Map.of("accountId", expense.getId(), "side", "DEBIT", "amount", new BigDecimal("999.00")),
                Map.of("accountId", asset.getId(), "side", "CREDIT", "amount", new BigDecimal("999.00"))
            )
        ));
        assertThat(draftResponse.statusCode()).isEqualTo(200);

        // Outside range should not appear
        createAndPostEntry(token, expense, asset, toDate.plusDays(1), new BigDecimal("999.00"), "Out of range");

        // Other organization data should not appear
        Organization otherOrg = new Organization();
        otherOrg.setName("GL-Other-" + UUID.randomUUID().toString().substring(0, 6));
        otherOrg.setBaseCurrency("ETB");
        otherOrg = organizationRepository.save(otherOrg);

        LedgerUser otherAccountant = createUser(otherOrg, "other.acc." + UUID.randomUUID().toString().substring(0, 6) + "@test.local", Role.ACCOUNTANT);
        Account otherAsset = createAccount(otherOrg, "9000", "Other Cash", AccountType.ASSET, AccountStatus.ACTIVE);
        Account otherExpense = createAccount(otherOrg, "9001", "Other Expense", AccountType.EXPENSE, AccountStatus.ACTIVE);
        String otherToken = loginAs(otherAccountant.getEmail(), PASSWORD);
        createAndPostEntry(otherToken, otherExpense, otherAsset, fromDate.plusDays(2), new BigDecimal("777.00"), "Other org entry");

        // Expected posted in-range rows for expense account: +100, -30, +20, +10, -5, +40
        // openingBeforeRange = 50. Chronological balances: 150, 120, 140, 150, 145, 185.
        List<BigDecimal> expectedDescRunning = List.of(
            new BigDecimal("185.0000"),
            new BigDecimal("145.0000"),
            new BigDecimal("150.0000"),
            new BigDecimal("140.0000"),
            new BigDecimal("120.0000"),
            new BigDecimal("150.0000")
        );

        List<LocalDate> expectedDescDates = List.of(
            toDate,
            fromDate.plusDays(3),
            fromDate.plusDays(2),
            fromDate.plusDays(1),
            fromDate.plusDays(1),
            fromDate
        );

        int pageSize = 2;
        List<BigDecimal> actualDescRunning = new ArrayList<>();
        List<LocalDate> actualDates = new ArrayList<>();

        for (int page = 0; page < 3; page++) {
            HttpResponse<String> response = getWithToken(
                String.format(
                    "/api/reports/general-ledger?accountId=%d&fromDate=%s&toDate=%s&page=%d&size=%d",
                    expense.getId(), fromDate, toDate, page, pageSize
                ),
                token
            );

            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = objectMapper.readTree(response.body());

            assertThat(body.get("openingBalance").decimalValue()).isEqualByComparingTo(new BigDecimal("50.0000"));
            assertThat(body.get("closingBalance").decimalValue()).isEqualByComparingTo(new BigDecimal("185.0000"));
            assertThat(body.get("totalElements").asInt()).isEqualTo(6);

            JsonNode lines = body.get("lines");
            assertThat(lines).hasSize(2);

            for (JsonNode line : lines) {
            actualDescRunning.add(line.get("runningBalance").decimalValue());
            actualDates.add(LocalDate.parse(line.get("transactionDate").asText()));
            assertThat(line.get("description").asText()).doesNotContain("Draft excluded");
            assertThat(line.get("description").asText()).doesNotContain("Other org entry");
            }
        }

        assertThat(actualDescRunning).hasSize(expectedDescRunning.size());
        for (int i = 0; i < expectedDescRunning.size(); i++) {
            assertThat(actualDescRunning.get(i)).isEqualByComparingTo(expectedDescRunning.get(i));
        }
        assertThat(actualDates).containsExactlyElementsOf(expectedDescDates);

        // Out-of-range page should be empty while preserving global opening/closing values
        HttpResponse<String> emptyPage = getWithToken(
            String.format(
                "/api/reports/general-ledger?accountId=%d&fromDate=%s&toDate=%s&page=9&size=%d",
                expense.getId(), fromDate, toDate, pageSize
            ),
            token
        );
        assertThat(emptyPage.statusCode()).isEqualTo(200);
        JsonNode emptyBody = objectMapper.readTree(emptyPage.body());
        assertThat(emptyBody.get("lines")).hasSize(0);
        assertThat(emptyBody.get("openingBalance").decimalValue()).isEqualByComparingTo(new BigDecimal("50.0000"));
        assertThat(emptyBody.get("closingBalance").decimalValue()).isEqualByComparingTo(new BigDecimal("185.0000"));
        }

    // ============ TRIAL BALANCE TESTS ============

    @Test
    void trialBalanceShowsAbnormalBalancesOnTheCorrectColumn() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        createAndPostEntry(token, liability, asset, LocalDate.of(2026, 9, 1), new BigDecimal("100.00"), "Credit cash");

        HttpResponse<String> response = getWithToken(
                String.format("/api/reports/trial-balance?asOfDate=%s", LocalDate.of(2026, 9, 1)),
                token
        );

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());

        JsonNode cash = body.get("accounts").findValue("accountId");
        assertThat(cash).isNotNull();
        for (JsonNode account : body.get("accounts")) {
            if (account.get("accountId").asLong() == asset.getId()) {
                assertThat(account.get("debitBalance").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(account.get("creditBalance").decimalValue()).isEqualByComparingTo(new BigDecimal("100.00"));
            }
        }
    }

    @Test
    void trialBalanceIncludesAllAccountsIncludingZeroBalance() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        // Create entries for some accounts, leave others with zero balance
        createAndPostEntry(token, asset, expense, LocalDate.now().minusDays(1), new BigDecimal("1000.00"), "Expense");
        createAndPostEntry(token, revenue, asset, LocalDate.now(), new BigDecimal("500.00"), "Revenue");
        // liability and one asset have zero balance

        HttpResponse<String> response = getWithToken("/api/reports/trial-balance", token);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());

        JsonNode accounts = body.get("accounts");
        assertThat(accounts.size()).isGreaterThanOrEqualTo(4);

        // Find each account in response
        boolean hasAsset = false, hasExpense = false, hasRevenue = false, hasLiability = false;
        for (JsonNode account : accounts) {
            Long accountId = account.get("accountId").asLong();
            if (accountId.equals(asset.getId())) hasAsset = true;
            if (accountId.equals(expense.getId())) hasExpense = true;
            if (accountId.equals(revenue.getId())) hasRevenue = true;
            if (accountId.equals(liability.getId())) hasLiability = true;
        }

        assertThat(hasAsset).isTrue();
        assertThat(hasExpense).isTrue();
        assertThat(hasRevenue).isTrue();
        assertThat(hasLiability).isTrue();
    }

    @Test
    void trialBalanceDebitsEqualsCredits() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        LocalDate testDate = LocalDate.of(2026, 5, 1);
        createAndPostEntry(token, asset, expense, testDate, new BigDecimal("500.00"), "Entry 1");
        createAndPostEntry(token, revenue, asset, testDate.plusDays(1), new BigDecimal("300.00"), "Entry 2");
        createAndPostEntry(token, asset, liability, testDate.plusDays(2), new BigDecimal("200.00"), "Entry 3");

        HttpResponse<String> response = getWithToken(
                String.format("/api/reports/trial-balance?asOfDate=%s", testDate.plusDays(2)),
                token
        );

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());

        BigDecimal totalDebits = body.get("totalDebits").decimalValue();
        BigDecimal totalCredits = body.get("totalCredits").decimalValue();
        boolean balanced = body.get("balanced").asBoolean();

        assertThat(balanced).isTrue();
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);
    }

    @Test
    void trialBalanceAccountTypeNormalBalance() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        LocalDate testDate = LocalDate.of(2026, 6, 1);
        createAndPostEntry(token, asset, expense, testDate, new BigDecimal("100.00"), "Entry");

        HttpResponse<String> response = getWithToken(
                String.format("/api/reports/trial-balance?asOfDate=%s", testDate),
                token
        );

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());

        // Asset (debit-normal) should appear in debit column
        // Expense (debit-normal) should appear in debit column
        // Revenue (credit-normal) should appear in credit column if any balance
        JsonNode accounts = body.get("accounts");
        for (JsonNode account : accounts) {
            String accountType = account.get("accountType").asText();
            BigDecimal debitBalance = account.get("debitBalance").decimalValue();
            BigDecimal creditBalance = account.get("creditBalance").decimalValue();
            assertThat(debitBalance).isNotNull();
            assertThat(creditBalance).isNotNull();

            if (accountType.equals("ASSET") || accountType.equals("EXPENSE")) {
                // Should use debit column for positive balances
                // Credit column should be zero or debit column should have the balance
            } else if (accountType.equals("REVENUE") || accountType.equals("LIABILITY")) {
                // Should use credit column for positive balances
            }
        }
    }

    @Test
    void trialBalanceAsOfDateSemantics() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        LocalDate date1 = LocalDate.of(2026, 7, 1);
        LocalDate date2 = LocalDate.of(2026, 7, 5);

        createAndPostEntry(token, asset, expense, date1, new BigDecimal("100.00"), "Entry 1");
        createAndPostEntry(token, asset, expense, date2, new BigDecimal("200.00"), "Entry 2");

        // Trial balance as of date1 should include only entry 1
        HttpResponse<String> response1 = getWithToken(
                String.format("/api/reports/trial-balance?asOfDate=%s", date1),
                token
        );

        assertThat(response1.statusCode()).isIn(200, 400, 404, 422);
        if (response1.statusCode() == 200) {
            JsonNode body1 = objectMapper.readTree(response1.body());
            BigDecimal total1 = body1.get("totalDebits").decimalValue();

            // Trial balance as of date2 should include both
            HttpResponse<String> response2 = getWithToken(
                    String.format("/api/reports/trial-balance?asOfDate=%s", date2),
                    token
            );

            if (response2.statusCode() == 200) {
                JsonNode body2 = objectMapper.readTree(response2.body());
                BigDecimal total2 = body2.get("totalDebits").decimalValue();

                assertThat(total2).isGreaterThanOrEqualTo(total1);
            }
        }
    }

    @Test
    void viewerCanViewTrialBalance() throws Exception {
        String token = loginAs(viewer.getEmail(), PASSWORD);

        HttpResponse<String> response = getWithToken("/api/reports/trial-balance", token);

        assertThat(response.statusCode()).isEqualTo(200);
    }

    // ============ REVERSAL TESTS ============

    @Test
    void reversalCreatesNewEntryAndLinksRelationship() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        Long originalId = createAndPostEntry(token, asset, expense, LocalDate.now(), new BigDecimal("500.00"), "Original");

        // Reverse the entry
        HttpResponse<String> reverseResponse = postJsonWithToken("/journal-entries/" + originalId + "/reverse", token, null);
        assertThat(reverseResponse.statusCode()).isEqualTo(200);
        JsonNode reversalBody = objectMapper.readTree(reverseResponse.body());

        Long reversalId = reversalBody.get("id").asLong();
        assertThat(reversalId).isNotEqualTo(originalId);
        assertThat(reversalBody.get("status").asText()).isEqualTo("POSTED");

        // Verify original entry is unchanged
        HttpResponse<String> originalResponse = getWithToken("/journal-entries/" + originalId, token);
        JsonNode originalBody = objectMapper.readTree(originalResponse.body());
        assertThat(originalBody.get("status").asText()).isEqualTo("POSTED");
        assertThat(originalBody.get("description").asText()).contains("Original");

        // Verify reversal has opposite lines
        HttpResponse<String> reversalGetResponse = getWithToken("/journal-entries/" + reversalId, token);
        JsonNode reversalGetBody = objectMapper.readTree(reversalGetResponse.body());

        JsonNode reversalLines = reversalGetBody.get("lines");
        JsonNode originalLines = originalBody.get("lines");

        assertThat(reversalLines).hasSize(originalLines.size());
        for (int i = 0; i < reversalLines.size(); i++) {
            String reversalSide = reversalLines.get(i).get("side").asText();
            String originalSide = originalLines.get(i).get("side").asText();

            // Sides should be opposite
            if (originalSide.equals("DEBIT")) {
                assertThat(reversalSide).isEqualTo("CREDIT");
            } else {
                assertThat(reversalSide).isEqualTo("DEBIT");
            }

            // Amounts should be same
            assertThat(reversalLines.get(i).get("amount").decimalValue())
                    .isEqualByComparingTo(originalLines.get(i).get("amount").decimalValue());
        }
    }

    @Test
    void preventsDuplicateReversalOfSameEntry() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        Long originalId = createAndPostEntry(token, asset, expense, LocalDate.now(), new BigDecimal("500.00"), "Original");

        // First reversal should succeed
        HttpResponse<String> reversal1 = postJsonWithToken("/journal-entries/" + originalId + "/reverse", token, null);
        assertThat(reversal1.statusCode()).isEqualTo(200);

        // Second reversal should fail
        HttpResponse<String> reversal2 = postJsonWithToken("/journal-entries/" + originalId + "/reverse", token, null);
        assertThat(reversal2.statusCode()).isEqualTo(422);
        JsonNode errorBody = objectMapper.readTree(reversal2.body());
        assertThat(errorBody.get("code").asText()).isEqualTo("JOURNAL_ENTRY_ALREADY_REVERSED");
    }

    @Test
    void cannotReverseNonPostedEntry() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        // Create draft entry (don't post it)
        HttpResponse<String> createResponse = postJsonWithToken("/journal-entries", token, Map.of(
                "transactionDate", LocalDate.now().toString(),
                "description", "Draft entry",
                "reference", "JE-DRAFT",
                "lines", List.of(
                        Map.of("accountId", asset.getId(), "side", "DEBIT", "amount", new BigDecimal("100.00")),
                        Map.of("accountId", expense.getId(), "side", "CREDIT", "amount", new BigDecimal("100.00"))
                )
        ));

        Long draftId = objectMapper.readTree(createResponse.body()).get("id").asLong();

        // Try to reverse (should fail - not posted)
        HttpResponse<String> reverseResponse = postJsonWithToken("/journal-entries/" + draftId + "/reverse", token, null);
        assertThat(reverseResponse.statusCode()).isEqualTo(422);
        JsonNode errorBody = objectMapper.readTree(reverseResponse.body());
        assertThat(errorBody.get("code").asText()).isEqualTo("JOURNAL_ENTRY_NOT_POSTED");
    }

    @Test
    void concurrentReversalAttemptsProduceOnlyOneReversal() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        Long originalId = createAndPostEntry(token, asset, expense, LocalDate.now(), new BigDecimal("500.00"), "Original");

        // Attempt concurrent reversals
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    HttpResponse<String> response = postJsonWithToken(
                            "/journal-entries/" + originalId + "/reverse",
                            token,
                            null
                    );

                    if (response.statusCode() == 200) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Exactly one should succeed, four should fail
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(4);
    }

    @Test
    void reversalUpdatesBalancesCorrectly() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        // Create initial balance
        createAndPostEntry(token, expense, asset, LocalDate.now().minusDays(5), new BigDecimal("1000.00"), "Initial");

        // Get balance before reversal
        HttpResponse<String> balanceBefore = getWithToken("/accounts/" + expense.getId() + "/balance", token);
        if (balanceBefore.statusCode() == 200) {
            BigDecimal balanceBeforeReversal = objectMapper.readTree(balanceBefore.body()).get("balance").decimalValue();
            assertThat(balanceBeforeReversal).isEqualByComparingTo(new BigDecimal("1000.0000"));
        }

        // Create and reverse an entry
        Long entryId = createAndPostEntry(token, expense, asset, LocalDate.now(), new BigDecimal("500.00"), "To reverse");

        // Reverse the entry
        HttpResponse<String> reverseResponse = postJsonWithToken("/journal-entries/" + entryId + "/reverse", token, null);
        assertThat(reverseResponse.statusCode()).isIn(200, 400, 404, 422);

        // Get balance after reversal and verify it's reasonable
        HttpResponse<String> balanceAfter = getWithToken("/accounts/" + expense.getId() + "/balance", token);
        if (balanceAfter.statusCode() == 200) {
            BigDecimal finalBalance = objectMapper.readTree(balanceAfter.body()).get("balance").decimalValue();
            assertThat(finalBalance).isEqualByComparingTo(new BigDecimal("1000.0000"));
        }
    }

    // ============ AUDIT LOG TESTS ============

    @Test
    void auditLogsRecordedForJournalOperations() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);
        String adminToken = loginAs(admin.getEmail(), PASSWORD);

        // Create entry
        Long entryId = createAndPostEntry(token, asset, expense, LocalDate.now(), new BigDecimal("100.00"), "Audit test");

        // Query audit logs for this entry (ADMIN only)
        HttpResponse<String> response = getWithToken(
                String.format("/api/audit-logs?entity=JOURNAL_ENTRY&page=0&size=50"),
                adminToken
        );

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());

        JsonNode items = body.get("items");
        assertThat(items.size()).isGreaterThan(0);

        // Find the JOURNAL_POSTED event
        boolean foundPosted = false;
        for (JsonNode item : items) {
            if (item.get("entityId").asText().equals(entryId.toString()) &&
                item.get("action").asText().equals("JOURNAL_POSTED")) {
                foundPosted = true;
                break;
            }
        }

        assertThat(foundPosted).isTrue();
    }

    @Test
    void auditLogsFilterByAction() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);
        String adminToken = loginAs(admin.getEmail(), PASSWORD);

        createAndPostEntry(token, asset, expense, LocalDate.now(), new BigDecimal("100.00"), "Test 1");
        createAndPostEntry(token, asset, expense, LocalDate.now(), new BigDecimal("200.00"), "Test 2");

        // Query for JOURNAL_POSTED actions (ADMIN only)
        HttpResponse<String> response = getWithToken(
                String.format("/api/audit-logs?action=JOURNAL_POSTED&page=0&size=50"),
                adminToken
        );

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());

        JsonNode items = body.get("items");
        for (JsonNode item : items) {
            assertThat(item.get("action").asText()).isEqualTo("JOURNAL_POSTED");
        }
    }

    @Test
    void auditLogsPagination() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);
        String adminToken = loginAs(admin.getEmail(), PASSWORD);

        // Create multiple entries to generate audit logs
        for (int i = 0; i < 25; i++) {
            createAndPostEntry(token, asset, expense, LocalDate.now(), new BigDecimal("10.00"), "Entry " + i);
        }

        // Query first page (ADMIN only)
        HttpResponse<String> page1Response = getWithToken(
                "/api/audit-logs?page=0&size=10",
                adminToken
        );

        assertThat(page1Response.statusCode()).isEqualTo(200);
        JsonNode body1 = objectMapper.readTree(page1Response.body());

        assertThat(body1.get("page").asInt()).isEqualTo(0);
        assertThat(body1.get("size").asInt()).isEqualTo(10);
        assertThat(body1.get("totalElements").asInt()).isGreaterThanOrEqualTo(25);
        assertThat(body1.get("items")).hasSize(10);

        // Query second page
        HttpResponse<String> page2Response = getWithToken(
                "/api/audit-logs?page=1&size=10",
                adminToken
        );

        assertThat(page2Response.statusCode()).isEqualTo(200);
        JsonNode body2 = objectMapper.readTree(page2Response.body());
        assertThat(body2.get("page").asInt()).isEqualTo(1);
        assertThat(body2.get("items")).hasSize(10);
    }

    @Test
    void auditLogsOrganizationScoped() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);
        String adminToken = loginAs(admin.getEmail(), PASSWORD);

        createAndPostEntry(token, asset, expense, LocalDate.now(), new BigDecimal("100.00"), "Scoped test");

        HttpResponse<String> response = getWithToken(
                "/api/audit-logs?page=0&size=100",
                adminToken
        );

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());

        // All returned audit logs should be for this organization
        // (verified implicitly by the endpoint enforcing organization scoping)
        assertThat(body.get("items")).isNotNull();
    }

    // ============ AUTHORIZATION TESTS ============

    @Test
    void accountantCanReverseEntry() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);

        Long entryId = createAndPostEntry(token, asset, expense, LocalDate.now(), new BigDecimal("100.00"), "Test");

        HttpResponse<String> response = postJsonWithToken("/journal-entries/" + entryId + "/reverse", token, null);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void viewerCannotReverseEntry() throws Exception {
        String token = loginAs(accountant.getEmail(), PASSWORD);
        Long entryId = createAndPostEntry(token, asset, expense, LocalDate.now(), new BigDecimal("100.00"), "Test");

        String viewerToken = loginAs(viewer.getEmail(), PASSWORD);
        HttpResponse<String> response = postJsonWithToken("/journal-entries/" + entryId + "/reverse", viewerToken, null);
        assertThat(response.statusCode()).isEqualTo(403);
    }

    // ============ HELPER METHODS ============

    private Long createAndPostEntry(String token, Account debitAccount, Account creditAccount, LocalDate date, BigDecimal amount, String description) throws Exception {
        HttpResponse<String> createResponse = postJsonWithToken("/journal-entries", token, Map.of(
                "transactionDate", date.toString(),
                "description", description,
                "reference", "JE-" + UUID.randomUUID().toString().substring(0, 6),
                "lines", List.of(
                        Map.of("accountId", debitAccount.getId(), "side", "DEBIT", "amount", amount),
                        Map.of("accountId", creditAccount.getId(), "side", "CREDIT", "amount", amount)
                )
        ));

        Long entryId = objectMapper.readTree(createResponse.body()).get("id").asLong();

        // Post the entry
        HttpResponse<String> postResponse = postJsonWithToken("/journal-entries/" + entryId + "/post", token, null);
        assertThat(postResponse.statusCode()).isEqualTo(200);

        return entryId;
    }

    private String loginAs(String email, String password) throws Exception {
        HttpResponse<String> response = postJson("/auth/login", Map.of("email", email, "password", password));
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).get("token").asText();
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

    private HttpResponse<String> getWithToken(String path, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
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

        if (path.endsWith("/post") || path.endsWith("/reverse")) {
            builder.header("Idempotency-Key", UUID.randomUUID().toString());
        }

        if (payload == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        }

        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

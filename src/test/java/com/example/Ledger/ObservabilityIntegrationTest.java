package com.example.Ledger;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
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
import com.example.Ledger.common.observability.LedgerMetricsService;
import com.example.Ledger.common.security.JwtService;
import com.example.Ledger.journal.model.JournalEntry;
import com.example.Ledger.journal.model.JournalLine;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.journal.service.JournalEntryService;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityIntegrationTest extends PostgresIntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

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

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Organization orgA;
    private LedgerUser adminA;
    private LedgerUser accountantA;
    private LedgerUser viewerA;
    private LedgerUser superAdmin;
    private String adminTokenA;
    private String accountantTokenA;
    private String viewerTokenA;
    private String superAdminToken;
    private Account cashAccountA;
    private Account revenueAccountA;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE outbox_event, reconciliation_run, idempotency_key, platform_audit_log, refresh_token, journal_line, journal_entry, account, invitation, audit_log, app_user, organization CASCADE"
        );

        Organization platformOrg = new Organization();
        platformOrg.setName("Ledger Platform");
        platformOrg.setBaseCurrency("USD");
        platformOrg.setKind(OrganizationKind.PLATFORM);
        platformOrg.setStatus(OrganizationStatus.ACTIVE);
        platformOrg = organizationRepository.save(platformOrg);

        orgA = new Organization();
        orgA.setName("Observability Org " + UUID.randomUUID());
        orgA.setBaseCurrency("USD");
        orgA.setKind(OrganizationKind.TENANT);
        orgA.setStatus(OrganizationStatus.ACTIVE);
        orgA = organizationRepository.save(orgA);

        adminA = createUser(orgA, "admin." + UUID.randomUUID() + "@ledger.test", Role.ADMIN);
        accountantA = createUser(orgA, "acct." + UUID.randomUUID() + "@ledger.test", Role.ACCOUNTANT);
        viewerA = createUser(orgA, "viewer." + UUID.randomUUID() + "@ledger.test", Role.VIEWER);
        superAdmin = createUser(platformOrg, "super." + UUID.randomUUID() + "@platform.ledger", Role.SUPER_ADMIN);

        adminTokenA = jwtService.generateToken(adminA);
        accountantTokenA = jwtService.generateToken(accountantA);
        viewerTokenA = jwtService.generateToken(viewerA);
        superAdminToken = jwtService.generateToken(superAdmin);

        cashAccountA = createAccount(orgA, "1000", "Cash", AccountType.ASSET);
        revenueAccountA = createAccount(orgA, "4000", "Revenue", AccountType.REVENUE);
    }

    @Test
    void actuatorHealthEnforcesAuthorizedDetailsForSuperAdminOnly() throws Exception {
        HttpResponse<String> anonymousResp = get("/actuator/health", null);
        assertThat(anonymousResp.statusCode()).isEqualTo(200);
        JsonNode anonymousJson = objectMapper.readTree(anonymousResp.body());
        assertThat(anonymousJson.get("status").asText()).isEqualTo("UP");
        assertThat(anonymousJson.has("components")).isFalse();

        for (String tenantToken : new String[]{adminTokenA, accountantTokenA, viewerTokenA}) {
            HttpResponse<String> tenantResp = get("/actuator/health", tenantToken);
            assertThat(tenantResp.statusCode()).isEqualTo(200);
            JsonNode tenantJson = objectMapper.readTree(tenantResp.body());
            assertThat(tenantJson.get("status").asText()).isEqualTo("UP");
            assertThat(tenantJson.has("components")).isFalse();
        }

        HttpResponse<String> superAdminResp = get("/actuator/health", superAdminToken);
        assertThat(superAdminResp.statusCode()).isEqualTo(200);
        JsonNode superAdminJson = objectMapper.readTree(superAdminResp.body());
        assertThat(superAdminJson.get("status").asText()).isEqualTo("UP");
        assertThat(superAdminJson.has("components")).isTrue();
        JsonNode components = superAdminJson.get("components");
        assertThat(components.has("db")).isTrue();
        assertThat(components.has("diskSpace")).isTrue();
        assertThat(components.has("livenessState")).isTrue();
        assertThat(components.has("readinessState")).isTrue();
    }

    @Test
    void actuatorPrometheusRestrictedToSuperAdminAndExposesLedgerMetrics() throws Exception {
        HttpResponse<String> anonymousResp = get("/actuator/prometheus", null);
        assertThat(anonymousResp.statusCode()).isEqualTo(401);

        assertThat(get("/actuator/prometheus", adminTokenA).statusCode()).isEqualTo(403);
        assertThat(get("/actuator/prometheus", accountantTokenA).statusCode()).isEqualTo(403);
        assertThat(get("/actuator/prometheus", viewerTokenA).statusCode()).isEqualTo(403);

        HttpResponse<String> superAdminResp = get("/actuator/prometheus", superAdminToken);
        assertThat(superAdminResp.statusCode()).isEqualTo(200);
        String body = superAdminResp.body();
        assertThat(body).contains("ledger_journal_posted_count_total");
        assertThat(body).contains("ledger_journal_reversed_count_total");
        assertThat(body).contains("ledger_reconciliation_status");
        assertThat(body).contains("ledger_transaction_duration_seconds_count");
        assertThat(body).contains("ledger_transaction_duration_seconds_bucket");
    }

    @Test
    void ledgerMetricsReflectCommittedTransactionsAndReconciliationStatusOnly() throws Exception {
        Counter postedCounter = meterRegistry.get(LedgerMetricsService.METRIC_JOURNAL_POSTED_COUNT).counter();
        Counter reversedCounter = meterRegistry.get(LedgerMetricsService.METRIC_JOURNAL_REVERSED_COUNT).counter();
        Timer durationTimer = meterRegistry.get(LedgerMetricsService.METRIC_TRANSACTION_DURATION).timer();
        Gauge reconciliationGauge = meterRegistry.get(LedgerMetricsService.METRIC_RECONCILIATION_STATUS).gauge();

        double initialPosted = postedCounter.count();
        double initialReversed = reversedCounter.count();
        long initialDurationCount = durationTimer.count();

        JournalEntry balancedDraft = createDraftEntry(
                "OBS-BALANCED",
                new BigDecimal("150.00"),
                new BigDecimal("150.00")
        );
        HttpResponse<String> postResp = post(
                "/journal-entries/" + balancedDraft.getId() + "/post",
                accountantTokenA,
                "obs-post-" + UUID.randomUUID(),
                null
        );
        assertThat(postResp.statusCode()).isEqualTo(200);
        assertThat(postedCounter.count()).isEqualTo(initialPosted + 1.0);
        assertThat(reversedCounter.count()).isEqualTo(initialReversed);
        assertThat(durationTimer.count()).isEqualTo(initialDurationCount + 1L);

        // Unbalanced entry must fail and leave posted counter untouched (commit-safety)
        JournalEntry unbalancedDraft = createDraftEntry(
                "OBS-UNBALANCED",
                new BigDecimal("200.00"),
                new BigDecimal("190.00")
        );
        HttpResponse<String> failPostResp = post(
                "/journal-entries/" + unbalancedDraft.getId() + "/post",
                accountantTokenA,
                "obs-fail-post-" + UUID.randomUUID(),
                null
        );
        assertThat(failPostResp.statusCode()).isEqualTo(422);
        assertThat(postedCounter.count()).isEqualTo(initialPosted + 1.0);

        // Reversal creates and posts a reversal entry: reversed +1, posted +1
        HttpResponse<String> reverseResp = post(
                "/journal-entries/" + balancedDraft.getId() + "/reverse",
                accountantTokenA,
                "obs-reverse-" + UUID.randomUUID(),
                null
        );
        assertThat(reverseResp.statusCode()).isEqualTo(200);
        assertThat(reversedCounter.count()).isEqualTo(initialReversed + 1.0);
        assertThat(postedCounter.count()).isEqualTo(initialPosted + 2.0);
        assertThat(durationTimer.count()).isGreaterThanOrEqualTo(initialDurationCount + 3L);

        // Clean reconciliation sets gauge to 1 (PASSED)
        HttpResponse<String> cleanReconcile = post("/api/reconciliation/run", adminTokenA, null, null);
        assertThat(cleanReconcile.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(cleanReconcile.body()).get("status").asText()).isEqualTo("PASSED");
        assertThat(reconciliationGauge.value()).isEqualTo(1.0);

        // Corrupt a posted line (bypassing immutability trigger in a controlled test txn) -> FAILED (0.0)
        Long debitLineId = balancedDraft.getLines().stream()
                .filter(l -> l.getSide() == EntrySide.DEBIT)
                .findFirst()
                .orElseThrow()
                .getId();
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL ledger.allow_posted_modification = 'true'");
            jdbcTemplate.update("UPDATE journal_line SET amount = ? WHERE id = ?", new BigDecimal("140.00"), debitLineId);
        });

        HttpResponse<String> failedReconcile = post("/api/reconciliation/run", adminTokenA, null, null);
        assertThat(failedReconcile.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(failedReconcile.body()).get("status").asText()).isEqualTo("FAILED");
        assertThat(reconciliationGauge.value()).isEqualTo(0.0);

        HttpResponse<String> prometheusScrape = get("/actuator/prometheus", superAdminToken);
        assertThat(prometheusScrape.statusCode()).isEqualTo(200);
        assertThat(prometheusScrape.body())
                .contains("ledger_reconciliation_status 0.0")
                .contains("ledger_journal_posted_count_total " + postedCounter.count())
                .contains("ledger_journal_reversed_count_total " + reversedCounter.count());
    }

    @Test
    void correlationFilterAndStructuredJsonLoggingPopulateMdcContext() throws Exception {
        JournalEntry draft = createDraftEntry(
                "OBS-TRACE",
                new BigDecimal("75.00"),
                new BigDecimal("75.00")
        );

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(JournalEntryService.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        serviceLogger.addAppender(listAppender);

        try {
            HttpResponse<String> tracedResp = post(
                    "/journal-entries/" + draft.getId() + "/post",
                    accountantTokenA,
                    "obs-trace-" + UUID.randomUUID(),
                    "wp55-trace-123"
            );
            assertThat(tracedResp.statusCode()).isEqualTo(200);
            assertThat(tracedResp.headers().firstValue("X-Trace-Id")).contains("wp55-trace-123");

            assertThat(listAppender.list).isNotEmpty();
            ILoggingEvent event = listAppender.list.getLast();
            assertThat(event.getMDCPropertyMap())
                    .containsEntry("traceId", "wp55-trace-123")
                    .containsEntry("organizationId", orgA.getId().toString())
                    .containsEntry("userId", accountantA.getId().toString());

            ConsoleAppender<ILoggingEvent> consoleAppender = findConsoleAppender();
            assertThat(consoleAppender).isNotNull();
            byte[] encodedBytes = consoleAppender.getEncoder().encode(event);
            String jsonLine = new String(encodedBytes, StandardCharsets.UTF_8).trim();
            JsonNode logJson = objectMapper.readTree(jsonLine);
            assertThat(logJson.get("traceId").asText()).isEqualTo("wp55-trace-123");
            assertThat(logJson.get("organizationId").asText()).isEqualTo(orgA.getId().toString());
            assertThat(logJson.get("userId").asText()).isEqualTo(accountantA.getId().toString());
            assertThat(logJson.get("message").asText()).contains("Posted journal entry id=" + draft.getId());
        } finally {
            serviceLogger.detachAppender(listAppender);
            listAppender.stop();
        }

        // Invalid correlation header falls back to generated UUID
        HttpResponse<String> unsafeHeaderResp = postWithCustomTrace(
                "/api/reconciliation/run",
                adminTokenA,
                "invalid header with spaces!"
        );
        assertThat(unsafeHeaderResp.statusCode()).isEqualTo(200);
        String generatedFromUnsafe = unsafeHeaderResp.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(UUID.fromString(generatedFromUnsafe)).isNotNull();

        // Missing correlation header generates UUID
        HttpResponse<String> missingHeaderResp = post("/api/reconciliation/run", adminTokenA, null, null);
        assertThat(missingHeaderResp.statusCode()).isEqualTo(200);
        String generatedFromMissing = missingHeaderResp.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(UUID.fromString(generatedFromMissing)).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private ConsoleAppender<ILoggingEvent> findConsoleAppender() {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Iterator<Appender<ILoggingEvent>> iterator = rootLogger.iteratorForAppenders();
        while (iterator.hasNext()) {
            Appender<ILoggingEvent> appender = iterator.next();
            if (appender instanceof ConsoleAppender<?>) {
                return (ConsoleAppender<ILoggingEvent>) appender;
            }
        }
        return null;
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

    private Account createAccount(Organization org, String code, String name, AccountType type) {
        Account account = new Account();
        account.setOrganization(org);
        account.setAccountCode(code);
        account.setName(name);
        account.setAccountType(type);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account);
    }

    private JournalEntry createDraftEntry(String reference, BigDecimal debitAmount, BigDecimal creditAmount) {
        JournalEntry entry = new JournalEntry();
        entry.setOrganization(orgA);
        entry.setTransactionDate(LocalDate.of(2026, 2, 10));
        entry.setDescription("Observability entry " + reference);
        entry.setReference(reference);
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setCreatedByUser(accountantA);

        JournalLine debitLine = new JournalLine();
        debitLine.setJournalEntry(entry);
        debitLine.setAccount(cashAccountA);
        debitLine.setSide(EntrySide.DEBIT);
        debitLine.setAmount(debitAmount);

        JournalLine creditLine = new JournalLine();
        creditLine.setJournalEntry(entry);
        creditLine.setAccount(revenueAccountA);
        creditLine.setSide(EntrySide.CREDIT);
        creditLine.setAmount(creditAmount);

        entry.getLines().add(debitLine);
        entry.getLines().add(creditLine);
        return journalEntryRepository.save(entry);
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(
            String path,
            String bearerToken,
            String idempotencyKey,
            String traceId
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json");
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (traceId != null) {
            builder.header("X-Trace-Id", traceId);
        }
        return httpClient.send(builder.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithCustomTrace(
            String path,
            String bearerToken,
            String traceId
    ) throws Exception {
        return post(path, bearerToken, null, traceId);
    }
}

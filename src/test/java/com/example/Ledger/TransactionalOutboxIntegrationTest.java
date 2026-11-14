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
import com.example.Ledger.outbox.model.OutboxEvent;
import com.example.Ledger.outbox.model.OutboxStatus;
import com.example.Ledger.outbox.repository.OutboxEventRepository;
import com.example.Ledger.outbox.service.OutboxEventService;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionalOutboxIntegrationTest extends PostgresIntegrationTestSupport {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerKafkaAndOutboxProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("ledger.outbox.dispatcher.enabled", () -> "true");
        registry.add("ledger.outbox.poll-delay-ms", () -> "200");
        registry.add("ledger.outbox.max-retries", () -> "2");
    }

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
    private OutboxEventRepository outboxEventRepository;

    @MockitoSpyBean
    private OutboxEventService outboxEventService;

    @MockitoSpyBean
    private KafkaTemplate<?, ?> kafkaTemplate;

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
    private Account cashA;
    private Account revenueA;

    @BeforeEach
    void setUp() {
        reset(outboxEventService, kafkaTemplate);
        jdbcTemplate.execute(
                "TRUNCATE TABLE outbox_event, reconciliation_run, idempotency_key, platform_audit_log, refresh_token, journal_line, journal_entry, account, invitation, audit_log, app_user, organization CASCADE"
        );

        Organization platformOrg = new Organization();
        platformOrg.setName("Ledger Platform " + UUID.randomUUID());
        platformOrg.setBaseCurrency("USD");
        platformOrg.setKind(OrganizationKind.PLATFORM);
        platformOrg.setStatus(OrganizationStatus.ACTIVE);
        platformOrg = organizationRepository.save(platformOrg);

        orgA = createTenantOrg("Outbox Org A " + UUID.randomUUID());
        orgB = createTenantOrg("Outbox Org B " + UUID.randomUUID());

        adminA = createUser(orgA, "admin.a." + UUID.randomUUID() + "@ledger.test", Role.ADMIN);
        accountantA = createUser(orgA, "acct.a." + UUID.randomUUID() + "@ledger.test", Role.ACCOUNTANT);
        viewerA = createUser(orgA, "viewer.a." + UUID.randomUUID() + "@ledger.test", Role.VIEWER);
        adminB = createUser(orgB, "admin.b." + UUID.randomUUID() + "@ledger.test", Role.ADMIN);
        superAdmin = createUser(platformOrg, "super." + UUID.randomUUID() + "@ledger.test", Role.SUPER_ADMIN);

        adminTokenA = jwtService.generateToken(adminA);
        accountantTokenA = jwtService.generateToken(accountantA);
        viewerTokenA = jwtService.generateToken(viewerA);
        adminTokenB = jwtService.generateToken(adminB);
        superAdminToken = jwtService.generateToken(superAdmin);

        cashA = createAccount(orgA, "1010", "Cash A", AccountType.ASSET);
        revenueA = createAccount(orgA, "4010", "Revenue A", AccountType.REVENUE);
    }

    @AfterEach
    void tearDown() {
        reset(outboxEventService, kafkaTemplate);
    }

    @Test
    void postingFailureRollsBackBothJournalEntryAndOutboxEventAtomically() throws Exception {
        Long entryId = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("750.00"), "Atomic rollback test");

        // OutboxEventService.recordEvent uses Propagation.MANDATORY, so register the Mockito spy stub
        // inside a no-op transaction to satisfy Spring's TransactionInterceptor before Mockito intercepts.
        transactionTemplate.executeWithoutResult(status ->
                doThrow(new IllegalStateException("Simulated outbox persistence error"))
                        .when(outboxEventService)
                        .recordEvent(any(), any(), any(), any(), any())
        );

        HttpResponse<String> response = sendPost(
                "/journal-entries/" + entryId + "/post",
                accountantTokenA,
                "outbox-rollback-" + UUID.randomUUID(),
                null
        );

        assertThat(response.statusCode()).isEqualTo(500);

        JournalEntry persisted = journalEntryRepository.findById(entryId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(JournalEntryStatus.DRAFT);
        assertThat(persisted.getPostedAt()).isNull();
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(auditLogRepository.findAll())
                .noneMatch(log -> AuditLogAction.JOURNAL_POSTED.name().equals(log.getAction()));

        // Also verify the symmetric invariant: an outbox row written in a rolled-back transaction is never visible/dispatched
        reset(outboxEventService);
        transactionTemplate.executeWithoutResult(status -> {
            outboxEventService.recordEvent(
                    orgA,
                    "JOURNAL_POSTED",
                    "JOURNAL_ENTRY",
                    entryId.toString(),
                    Map.of("entryId", entryId)
            );
            status.setRollbackOnly();
        });
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void postingAndReversalEmitOutboxEventsDeliveredEndToEndToKafka() throws Exception {
        String topic = "ledger.events";
        String consumerGroup = "outbox-e2e-" + UUID.randomUUID();
        long testStartEpochMillis = Instant.now().minusSeconds(5).toEpochMilli();

        Long entryId = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("1250.50"), "Outbox e2e entry");

        HttpResponse<String> postResp = sendPost(
                "/journal-entries/" + entryId + "/post",
                accountantTokenA,
                "outbox-post-" + UUID.randomUUID(),
                null
        );
        assertThat(postResp.statusCode()).isEqualTo(200);

        HttpResponse<String> reverseResp = sendPost(
                "/journal-entries/" + entryId + "/reverse",
                accountantTokenA,
                "outbox-reverse-" + UUID.randomUUID(),
                Map.of("reason", "Customer refund adjustment")
        );
        assertThat(reverseResp.statusCode()).isEqualTo(200);
        Long reversalEntryId = objectMapper.readTree(reverseResp.body()).get("id").asLong();

        // Wait until all 3 outbox events for orgA are PROCESSED
        List<OutboxEvent> processedEvents = waitForAllProcessed(orgA.getId(), 3, Duration.ofSeconds(15));
        assertThat(processedEvents).hasSize(3);
        assertThat(processedEvents)
                .allSatisfy(event -> {
                    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
                    assertThat(event.getProcessedAt()).isNotNull();
                    assertThat(event.getLastError()).isNull();
                });

        // Verify ADMIN list endpoint reflects PROCESSED outbox records
        HttpResponse<String> listResp = sendGet("/api/outbox/events?status=PROCESSED", adminTokenA);
        assertThat(listResp.statusCode()).isEqualTo(200);
        JsonNode listJson = objectMapper.readTree(listResp.body());
        assertThat(listJson.get("totalElements").asLong()).isEqualTo(3L);

        // Consume real records directly from KafkaContainer
        List<ConsumerRecord<String, String>> kafkaRecords = consumeRecords(topic, consumerGroup, orgA.getId(), 3, testStartEpochMillis);
        assertThat(kafkaRecords).hasSize(3);

        List<String> eventTypes = kafkaRecords.stream()
                .map(rec -> headerValue(rec, "event-type"))
                .toList();
        assertThat(eventTypes).containsExactlyInAnyOrder("JOURNAL_POSTED", "JOURNAL_REVERSED", "JOURNAL_POSTED");

        ConsumerRecord<String, String> originalPostedRecord = kafkaRecords.stream()
                .filter(rec -> "JOURNAL_POSTED".equals(headerValue(rec, "event-type"))
                        && entryId.toString().equals(headerValue(rec, "aggregate-id")))
                .findFirst()
                .orElseThrow();
        assertThat(originalPostedRecord.key()).isEqualTo(orgA.getId() + ":" + entryId);
        assertThat(headerValue(originalPostedRecord, "aggregate-type")).isEqualTo("JOURNAL_ENTRY");
        assertThat(headerValue(originalPostedRecord, "organization-id")).isEqualTo(orgA.getId().toString());
        JsonNode postedEnvelope = objectMapper.readTree(originalPostedRecord.value());
        assertThat(postedEnvelope.get("eventType").asText()).isEqualTo("JOURNAL_POSTED");
        assertThat(postedEnvelope.get("organizationId").asLong()).isEqualTo(orgA.getId());
        assertThat(postedEnvelope.get("data").get("totalDebits").decimalValue()).isEqualByComparingTo("1250.50");
        assertThat(postedEnvelope.get("data").get("isReversal").asBoolean()).isFalse();

        ConsumerRecord<String, String> reversedRecord = kafkaRecords.stream()
                .filter(rec -> "JOURNAL_REVERSED".equals(headerValue(rec, "event-type")))
                .findFirst()
                .orElseThrow();
        JsonNode reversedEnvelope = objectMapper.readTree(reversedRecord.value());
        assertThat(reversedEnvelope.get("aggregateId").asText()).isEqualTo(entryId.toString());
        assertThat(reversedEnvelope.get("data").get("reversalEntryId").asLong()).isEqualTo(reversalEntryId);
        assertThat(reversedEnvelope.get("data").get("reason").asText()).isEqualTo("Customer refund adjustment");

        ConsumerRecord<String, String> reversalPostedRecord = kafkaRecords.stream()
                .filter(rec -> "JOURNAL_POSTED".equals(headerValue(rec, "event-type"))
                        && reversalEntryId.toString().equals(headerValue(rec, "aggregate-id")))
                .findFirst()
                .orElseThrow();
        JsonNode reversalPostedEnvelope = objectMapper.readTree(reversalPostedRecord.value());
        assertThat(reversalPostedEnvelope.get("data").get("isReversal").asBoolean()).isTrue();
        assertThat(reversalPostedEnvelope.get("data").get("reversesEntryId").asLong()).isEqualTo(entryId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedDeliveryTransitionsToFailedAndAdminRetryDeliversToKafka() throws Exception {
        // Prevent background scheduler interference while testing deterministic failure transitions,
        // by temporarily stubbing KafkaTemplate.send to fail asynchronously.
        doReturn(CompletableFuture.failedFuture(new RuntimeException("Simulated Kafka broker outage")))
                .when(kafkaTemplate)
                .send(any(ProducerRecord.class));

        Long entryId = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("333.00"), "Retry test entry");
        HttpResponse<String> postResp = sendPost(
                "/journal-entries/" + entryId + "/post",
                accountantTokenA,
                "outbox-retry-post-" + UUID.randomUUID(),
                null
        );
        assertThat(postResp.statusCode()).isEqualTo(200);

        OutboxEvent event = waitForAnyOutboxEvent(orgA.getId(), Duration.ofSeconds(5));
        Long outboxEventId = event.getId();

        // Wait for attempt #1 failure (retry_count >= 1, backoff scheduled into the future)
        OutboxEvent afterFirstFailure = waitUntilEventMatches(
                outboxEventId,
                e -> e.getRetryCount() >= 1 && e.getLastError() != null,
                Duration.ofSeconds(10)
        );
        if (afterFirstFailure.getStatus() == OutboxStatus.PENDING) {
            assertThat(afterFirstFailure.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
            // Fast-forward next_attempt_at so the dispatcher processes retry #2 immediately
            jdbcTemplate.update(
                    "UPDATE outbox_event SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
                    outboxEventId
            );
        }

        // Wait until max-retries (2) is exhausted and status reaches FAILED
        OutboxEvent failedEvent = waitUntilEventMatches(
                outboxEventId,
                e -> e.getStatus() == OutboxStatus.FAILED,
                Duration.ofSeconds(10)
        );
        assertThat(failedEvent.getRetryCount()).isEqualTo(2);
        assertThat(failedEvent.getLastError()).contains("Simulated Kafka broker outage");

        // Restore real KafkaTemplate behavior
        reset(kafkaTemplate);

        // Tenant ADMIN triggers manual retry
        HttpResponse<String> retryResp = sendPost("/api/outbox/events/" + outboxEventId + "/retry", adminTokenA, null, null);
        assertThat(retryResp.statusCode()).isEqualTo(200);
        JsonNode retryJson = objectMapper.readTree(retryResp.body());
        assertThat(retryJson.get("status").asText()).isEqualTo("PENDING");
        assertThat(retryJson.get("retryCount").asInt()).isZero();

        // Trigger/wait for delivery to KafkaContainer
        OutboxEvent recovered = waitUntilEventMatches(
                outboxEventId,
                e -> e.getStatus() == OutboxStatus.PROCESSED,
                Duration.ofSeconds(10)
        );
        assertThat(recovered.getProcessedAt()).isNotNull();
        assertThat(recovered.getLastError()).isNull();
        assertThat(auditLogRepository.findAll())
                .anyMatch(log -> AuditLogAction.OUTBOX_EVENT_RETRY_REQUESTED.name().equals(log.getAction())
                        && outboxEventId.toString().equals(log.getEntityId()));

        // Retrying an already PROCESSED event returns 422
        HttpResponse<String> duplicateRetry = sendPost("/api/outbox/events/" + outboxEventId + "/retry", adminTokenA, null, null);
        assertThat(duplicateRetry.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(duplicateRetry.body()).get("code").asText())
                .isEqualTo("OUTBOX_EVENT_ALREADY_PROCESSED");
    }

    @Test
    void outboxEndpointsEnforceAdminRoleAndStrictTenantIsolation() throws Exception {
        Long entryId = createDraft(orgA, accountantA, cashA, revenueA, new BigDecimal("199.00"), "Isolation test entry");
        HttpResponse<String> postResp = sendPost(
                "/journal-entries/" + entryId + "/post",
                accountantTokenA,
                "outbox-isolation-" + UUID.randomUUID(),
                null
        );
        assertThat(postResp.statusCode()).isEqualTo(200);

        OutboxEvent orgAEvent = waitForAnyOutboxEvent(orgA.getId(), Duration.ofSeconds(5));

        assertThat(sendGet("/api/outbox/events", null).statusCode()).isEqualTo(401);
        assertThat(sendGet("/api/outbox/events", viewerTokenA).statusCode()).isEqualTo(403);
        assertThat(sendGet("/api/outbox/events", accountantTokenA).statusCode()).isEqualTo(403);
        assertThat(sendGet("/api/outbox/events", superAdminToken).statusCode()).isEqualTo(403);
        assertThat(sendPost("/api/outbox/events/" + orgAEvent.getId() + "/retry", accountantTokenA, null, null).statusCode())
                .isEqualTo(403);

        // Tenant B ADMIN cannot see or retry Tenant A's outbox events
        HttpResponse<String> orgBList = sendGet("/api/outbox/events", adminTokenB);
        assertThat(orgBList.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(orgBList.body()).get("totalElements").asLong()).isZero();

        HttpResponse<String> crossTenantRetry = sendPost(
                "/api/outbox/events/" + orgAEvent.getId() + "/retry",
                adminTokenB,
                null,
                null
        );
        assertThat(crossTenantRetry.statusCode()).isEqualTo(404);
    }

    private List<OutboxEvent> waitForAllProcessed(Long orgId, int expectedCount, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<OutboxEvent> all = outboxEventRepository.findAll().stream()
                    .filter(e -> orgId.equals(e.getOrganization().getId()))
                    .toList();
            if (all.size() >= expectedCount && all.stream().allMatch(e -> e.getStatus() == OutboxStatus.PROCESSED)) {
                return all;
            }
            Thread.sleep(150);
        }
        return outboxEventRepository.findAll().stream()
                .filter(e -> orgId.equals(e.getOrganization().getId()))
                .toList();
    }

    private OutboxEvent waitForAnyOutboxEvent(Long orgId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<OutboxEvent> all = outboxEventRepository.findAll().stream()
                    .filter(e -> orgId.equals(e.getOrganization().getId()))
                    .toList();
            if (!all.isEmpty()) {
                return all.getFirst();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for outbox event for org " + orgId);
    }

    private OutboxEvent waitUntilEventMatches(
            Long eventId,
            java.util.function.Predicate<OutboxEvent> predicate,
            Duration timeout
    ) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
            if (predicate.test(event)) {
                return event;
            }
            Thread.sleep(150);
        }
        OutboxEvent latest = outboxEventRepository.findById(eventId).orElseThrow();
        throw new AssertionError("Timed out waiting for outbox event " + eventId + ", current status=" + latest.getStatus()
                + ", retryCount=" + latest.getRetryCount() + ", lastError=" + latest.getLastError());
    }

    private List<ConsumerRecord<String, String>> consumeRecords(
            String topic,
            String groupId,
            Long expectedOrgId,
            int expectedCount,
            long minTimestamp
    ) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline) && matched.size() < expectedCount) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(300));
                for (ConsumerRecord<String, String> record : polled) {
                    if (record.timestamp() >= minTimestamp
                            && expectedOrgId.toString().equals(headerValue(record, "organization-id"))) {
                        matched.add(record);
                    }
                }
            }
        }
        return matched;
    }

    private String headerValue(ConsumerRecord<String, String> record, String headerName) {
        var header = record.headers().lastHeader(headerName);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> sendGet(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPost(
            String path,
            String bearerToken,
            String idempotencyKey,
            Object body
    ) throws Exception {
        String jsonBody = body == null ? "" : objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
        user.setFullName("Test " + role.name());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("StrongPass123!"));
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

    private Long createDraft(
            Organization org,
            LedgerUser creator,
            Account debitAccount,
            Account creditAccount,
            BigDecimal amount,
            String description
    ) {
        JournalEntry entry = new JournalEntry();
        entry.setOrganization(org);
        entry.setCreatedByUser(creator);
        entry.setTransactionDate(LocalDate.now());
        entry.setDescription(description);
        entry.setReference("OUTBOX-" + UUID.randomUUID().toString().substring(0, 8));
        entry.setStatus(JournalEntryStatus.DRAFT);

        JournalLine debit = new JournalLine();
        debit.setAccount(debitAccount);
        debit.setSide(EntrySide.DEBIT);
        debit.setAmount(amount);
        entry.addLine(debit);

        JournalLine credit = new JournalLine();
        credit.setAccount(creditAccount);
        credit.setSide(EntrySide.CREDIT);
        credit.setAmount(amount);
        entry.addLine(credit);

        return journalEntryRepository.save(entry).getId();
    }
}

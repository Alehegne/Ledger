package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.EntrySide;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.security.JwtService;
import com.example.Ledger.journal.dto.JournalEntryResponse;
import com.example.Ledger.journal.model.JournalEntry;
import com.example.Ledger.journal.model.JournalLine;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.journal.service.JournalEntryService;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JournalConcurrencyIntegrationTest extends PostgresIntegrationTestSupport {

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

    private Organization organization;
    private LedgerUser admin;
    private String adminToken;
    private Account cashAccount;
    private Account revenueAccount;

    @BeforeEach
    void setUp() throws Exception {
        organization = new Organization();
        organization.setName("Concurrency Org " + UUID.randomUUID().toString().substring(0, 8));
        organization.setBaseCurrency("USD");
        organization = organizationRepository.save(organization);

        String adminEmail = "admin." + UUID.randomUUID().toString().substring(0, 8) + "@acme.test";
        admin = new LedgerUser();
        admin.setOrganization(organization);
        admin.setEmail(adminEmail);
        admin.setFullName("Concurrency Admin");
        admin.setPasswordHash(passwordEncoder.encode("Secret123!"));
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);

        adminToken = loginAs(adminEmail, "Secret123!");

        cashAccount = new Account();
        cashAccount.setOrganization(organization);
        cashAccount.setAccountCode("1000-" + UUID.randomUUID().toString().substring(0, 4));
        cashAccount.setName("Cash");
        cashAccount.setAccountType(AccountType.ASSET);
        cashAccount.setStatus(AccountStatus.ACTIVE);
        cashAccount = accountRepository.save(cashAccount);

        revenueAccount = new Account();
        revenueAccount.setOrganization(organization);
        revenueAccount.setAccountCode("4000-" + UUID.randomUUID().toString().substring(0, 4));
        revenueAccount.setName("Sales Revenue");
        revenueAccount.setAccountType(AccountType.REVENUE);
        revenueAccount.setStatus(AccountStatus.ACTIVE);
        revenueAccount = accountRepository.save(revenueAccount);
    }

    @Test
    void optimisticLockingVersionIncrementsOnUpdate() throws Exception {
        JournalEntry entry = new JournalEntry();
        entry.setOrganization(organization);
        entry.setCreatedByUser(admin);
        entry.setReference("JE-VER-1");
        entry.setTransactionDate(LocalDate.now());
        entry.setDescription("Initial draft");
        entry.setStatus(JournalEntryStatus.DRAFT);

        JournalLine debit = new JournalLine();
        debit.setJournalEntry(entry);
        debit.setAccount(cashAccount);
        debit.setSide(EntrySide.DEBIT);
        debit.setAmount(new BigDecimal("50.00"));

        JournalLine credit = new JournalLine();
        credit.setJournalEntry(entry);
        credit.setAccount(revenueAccount);
        credit.setSide(EntrySide.CREDIT);
        credit.setAmount(new BigDecimal("50.00"));

        entry.addLine(debit);
        entry.addLine(credit);
        entry = journalEntryRepository.save(entry);

        assertThat(entry.getVersion()).isEqualTo(0L);

        // Update via API
        HttpResponse<String> response = patchJsonWithToken("/journal-entries/" + entry.getId(), adminToken, Map.of(
                "description", "Updated draft description"
        ));

        assertThat(response.statusCode()).isEqualTo(200);

        JournalEntry reloaded = journalEntryRepository.findById(entry.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getDescription()).isEqualTo("Updated draft description");
    }

    @Test
    void concurrentPostRequestsOnSameDraftEnsureOnlyOneSucceeds() throws Exception {
        // Create a balanced draft
        JournalEntry entry = new JournalEntry();
        entry.setOrganization(organization);
        entry.setCreatedByUser(admin);
        entry.setReference("JE-CONC-" + UUID.randomUUID().toString().substring(0, 6));
        entry.setTransactionDate(LocalDate.now());
        entry.setDescription("Concurrent post target");
        entry.setStatus(JournalEntryStatus.DRAFT);

        JournalLine debit = new JournalLine();
        debit.setJournalEntry(entry);
        debit.setAccount(cashAccount);
        debit.setSide(EntrySide.DEBIT);
        debit.setAmount(new BigDecimal("100.00"));

        JournalLine credit = new JournalLine();
        credit.setJournalEntry(entry);
        credit.setAccount(revenueAccount);
        credit.setSide(EntrySide.CREDIT);
        credit.setAmount(new BigDecimal("100.00"));

        entry.addLine(debit);
        entry.addLine(credit);
        final Long entryId = journalEntryRepository.save(entry).getId();

        int threadCount = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    HttpResponse<String> resp = postEmptyWithToken("/journal-entries/" + entryId + "/post", adminToken);
                    statusCodes.add(resp.statusCode());
                } catch (Exception e) {
                    statusCodes.add(500);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        long successCount = statusCodes.stream().filter(code -> code == 200).count();
        assertThat(successCount).as("Exactly one post request must succeed").isEqualTo(1);

        JournalEntry finalEntry = journalEntryRepository.findById(entryId).orElseThrow();
        assertThat(finalEntry.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
    }

    private String loginAs(String email, String password) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of("email", email, "password", password));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).get("token").asText();
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

    private HttpResponse<String> postEmptyWithToken(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
        if (path.endsWith("/post") || path.endsWith("/reverse")) {
            builder.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}

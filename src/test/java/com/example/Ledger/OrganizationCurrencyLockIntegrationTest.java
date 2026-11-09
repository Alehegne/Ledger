package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.EntrySide;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.security.JwtService;
import com.example.Ledger.journal.model.JournalEntry;
import com.example.Ledger.journal.model.JournalLine;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationCurrencyLockIntegrationTest extends PostgresIntegrationTestSupport {

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
    private String adminEmail;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        organization = new Organization();
        organization.setName("Currency Test Org " + UUID.randomUUID().toString().substring(0, 8));
        organization.setBaseCurrency("USD");
        organization = organizationRepository.save(organization);

        adminEmail = "admin." + UUID.randomUUID().toString().substring(0, 8) + "@acme.test";
        admin = new LedgerUser();
        admin.setOrganization(organization);
        admin.setEmail(adminEmail);
        admin.setFullName("Currency Test Admin");
        admin.setPasswordHash(passwordEncoder.encode("Secret123!"));
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);

        adminToken = loginAs(adminEmail, "Secret123!");
    }

    @Test
    void allowsCurrencyChangeWhenNoJournalEntriesExist() throws Exception {
        HttpResponse<String> response = patchJsonWithToken("/organizations/me", adminToken, Map.of(
                "baseCurrency", "EUR"
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("baseCurrency").asText()).isEqualTo("EUR");
    }

    @Test
    void rejectsCurrencyChangeWhenJournalEntriesExist() throws Exception {
        // Create accounts and a journal entry for the organization
        Account cash = new Account();
        cash.setOrganization(organization);
        cash.setAccountCode("1001-" + UUID.randomUUID().toString().substring(0, 4));
        cash.setName("Cash");
        cash.setAccountType(AccountType.ASSET);
        cash.setStatus(AccountStatus.ACTIVE);
        cash = accountRepository.save(cash);

        Account revenue = new Account();
        revenue.setOrganization(organization);
        revenue.setAccountCode("4001-" + UUID.randomUUID().toString().substring(0, 4));
        revenue.setName("Revenue");
        revenue.setAccountType(AccountType.REVENUE);
        revenue.setStatus(AccountStatus.ACTIVE);
        revenue = accountRepository.save(revenue);

        JournalEntry entry = new JournalEntry();
        entry.setOrganization(organization);
        entry.setCreatedByUser(admin);
        entry.setReference("JE-TEST-" + UUID.randomUUID().toString().substring(0, 6));
        entry.setTransactionDate(LocalDate.now());
        entry.setDescription("Test journal entry");
        entry.setStatus(JournalEntryStatus.DRAFT);

        JournalLine debit = new JournalLine();
        debit.setJournalEntry(entry);
        debit.setAccount(cash);
        debit.setSide(EntrySide.DEBIT);
        debit.setAmount(new BigDecimal("100.00"));

        JournalLine credit = new JournalLine();
        credit.setJournalEntry(entry);
        credit.setAccount(revenue);
        credit.setSide(EntrySide.CREDIT);
        credit.setAmount(new BigDecimal("100.00"));

        entry.addLine(debit);
        entry.addLine(credit);
        entry = journalEntryRepository.saveAndFlush(entry);

        entry.setStatus(JournalEntryStatus.POSTED);
        journalEntryRepository.saveAndFlush(entry);

        // Attempt to change currency
        HttpResponse<String> response = patchJsonWithToken("/organizations/me", adminToken, Map.of(
                "baseCurrency", "EUR"
        ));

        assertThat(response.statusCode()).isEqualTo(409);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo("BASE_CURRENCY_LOCKED");
        assertThat(body.get("message").asText()).contains("Base currency cannot be changed");
    }

    @Test
    void allowsUpdatingNameWhenJournalEntriesExistIfCurrencyUnchanged() throws Exception {
        // Create a journal entry
        JournalEntry entry = new JournalEntry();
        entry.setOrganization(organization);
        entry.setCreatedByUser(admin);
        entry.setReference("JE-NAME-" + UUID.randomUUID().toString().substring(0, 6));
        entry.setTransactionDate(LocalDate.now());
        entry.setDescription("Entry for name update test");
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry = journalEntryRepository.saveAndFlush(entry);

        entry.setStatus(JournalEntryStatus.POSTED);
        journalEntryRepository.saveAndFlush(entry);

        String uniqueName = "Renamed Org " + UUID.randomUUID().toString().substring(0, 6);

        // Update name with same currency
        HttpResponse<String> response = patchJsonWithToken("/organizations/me", adminToken, Map.of(
                "name", uniqueName,
                "baseCurrency", "USD"
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("name").asText()).isEqualTo(uniqueName);
        assertThat(body.get("baseCurrency").asText()).isEqualTo("USD");
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
}

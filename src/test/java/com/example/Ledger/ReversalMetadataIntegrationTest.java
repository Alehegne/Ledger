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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReversalMetadataIntegrationTest extends PostgresIntegrationTestSupport {

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

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Organization organization;
    private LedgerUser adminUser;
    private String adminToken;
    private Account cashAccount;
    private Account revenueAccount;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE journal_line, journal_entry, account, invitation, audit_log, app_user, organization CASCADE");

        organization = new Organization();
        organization.setName("Reversal Test Org " + UUID.randomUUID());
        organization.setBaseCurrency("USD");
        organization = organizationRepository.save(organization);

        adminUser = new LedgerUser();
        adminUser.setEmail("admin-" + UUID.randomUUID() + "@reversal.test");
        adminUser.setPasswordHash(passwordEncoder.encode("Secret123!"));
        adminUser.setFullName("Admin Reversal Tester");
        adminUser.setRole(Role.ADMIN);
        adminUser.setOrganization(organization);
        adminUser = userRepository.save(adminUser);

        adminToken = jwtService.generateToken(adminUser);

        cashAccount = new Account();
        cashAccount.setOrganization(organization);
        cashAccount.setAccountCode("1010");
        cashAccount.setName("Cash Account");
        cashAccount.setAccountType(AccountType.ASSET);
        cashAccount.setStatus(AccountStatus.ACTIVE);
        cashAccount = accountRepository.save(cashAccount);

        revenueAccount = new Account();
        revenueAccount.setOrganization(organization);
        revenueAccount.setAccountCode("4010");
        revenueAccount.setName("Revenue Account");
        revenueAccount.setAccountType(AccountType.REVENUE);
        revenueAccount.setStatus(AccountStatus.ACTIVE);
        revenueAccount = accountRepository.save(revenueAccount);
    }

    private JournalEntry createPostedEntry() {
        JournalEntry entry = new JournalEntry();
        entry.setOrganization(organization);
        entry.setCreatedByUser(adminUser);
        entry.setTransactionDate(LocalDate.now());
        entry.setDescription("Original posted entry");
        entry.setReference("JE-ORIG-01");
        entry.setStatus(JournalEntryStatus.DRAFT);

        JournalLine debit = new JournalLine();
        debit.setAccount(cashAccount);
        debit.setSide(EntrySide.DEBIT);
        debit.setAmount(new BigDecimal("500.00"));
        entry.addLine(debit);

        JournalLine credit = new JournalLine();
        credit.setAccount(revenueAccount);
        credit.setSide(EntrySide.CREDIT);
        credit.setAmount(new BigDecimal("500.00"));
        entry.addLine(credit);

        JournalEntry savedDraft = journalEntryRepository.save(entry);
        savedDraft.setStatus(JournalEntryStatus.POSTED);
        savedDraft.setPostedByUser(adminUser);
        savedDraft.setPostedAt(Instant.now());
        return journalEntryRepository.save(savedDraft);
    }

    @Test
    void reversalIncludesMetadataAndLinksBothWays() throws Exception {
        JournalEntry original = createPostedEntry();

        String reversalReason = "Customer billed incorrect amount by billing department";
        String requestJson = objectMapper.writeValueAsString(Map.of("reason", reversalReason));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/journal-entries/" + original.getId() + "/reverse"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode reversalNode = objectMapper.readTree(response.body());
        Long reversalId = reversalNode.get("id").asLong();

        // 1. Verify reversal entry response fields
        assertThat(reversalNode.get("status").asText()).isEqualTo("POSTED");
        assertThat(reversalNode.get("reversesEntryId").asLong()).isEqualTo(original.getId());
        assertThat(reversalNode.get("reversalReason").asText()).isEqualTo(reversalReason);
        assertThat(reversalNode.get("reversedByEntryId").isNull()).isTrue();

        // 2. Fetch original entry and verify it now reports reversal linkage
        HttpRequest getOriginal = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/journal-entries/" + original.getId()))
                .header("Authorization", "Bearer " + adminToken)
                .GET()
                .build();

        HttpResponse<String> originalResponse = httpClient.send(getOriginal, HttpResponse.BodyHandlers.ofString());
        assertThat(originalResponse.statusCode()).isEqualTo(200);

        JsonNode originalNode = objectMapper.readTree(originalResponse.body());
        assertThat(originalNode.get("reversedByEntryId").asLong()).isEqualTo(reversalId);
        assertThat(originalNode.get("reversalReason").asText()).isEqualTo(reversalReason);
        assertThat(originalNode.get("reversesEntryId").isNull()).isTrue();

        // 3. Attempting to reverse the reversal entry must be rejected
        HttpRequest reverseReversal = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/journal-entries/" + reversalId + "/reverse"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        HttpResponse<String> failResponse = httpClient.send(reverseReversal, HttpResponse.BodyHandlers.ofString());
        assertThat(failResponse.statusCode()).isEqualTo(422);
        assertThat(failResponse.body()).contains("CANNOT_REVERSE_REVERSAL");
    }
}

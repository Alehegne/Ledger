package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.EntrySide;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.security.JwtService;
import com.example.Ledger.invitation.dto.AcceptInvitationRequest;
import com.example.Ledger.invitation.dto.InvitationRequest;
import com.example.Ledger.journal.dto.JournalEntryRequest;
import com.example.Ledger.journal.dto.JournalLineRequest;
import com.example.Ledger.journal.dto.JournalReversalRequest;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndAccountingWorkflowTest extends PostgresIntegrationTestSupport {

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

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Organization organization;
    private LedgerUser adminUser;
    private String adminToken;
    private Account cashAccount;
    private Account serviceRevenueAccount;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE journal_line, journal_entry, account, invitation, audit_log, app_user, organization CASCADE");

        organization = new Organization();
        organization.setName("Apex Financial Services");
        organization.setBaseCurrency("USD");
        organization = organizationRepository.save(organization);

        adminUser = new LedgerUser();
        adminUser.setEmail("admin-" + UUID.randomUUID() + "@apex.test");
        adminUser.setPasswordHash(passwordEncoder.encode("AdminPass123!"));
        adminUser.setFullName("Admin Apex");
        adminUser.setRole(Role.ADMIN);
        adminUser.setOrganization(organization);
        adminUser = userRepository.save(adminUser);

        adminToken = jwtService.generateToken(adminUser);

        cashAccount = new Account();
        cashAccount.setOrganization(organization);
        cashAccount.setAccountCode("1010");
        cashAccount.setName("Operating Cash");
        cashAccount.setAccountType(AccountType.ASSET);
        cashAccount.setStatus(AccountStatus.ACTIVE);
        cashAccount = accountRepository.save(cashAccount);

        serviceRevenueAccount = new Account();
        serviceRevenueAccount.setOrganization(organization);
        serviceRevenueAccount.setAccountCode("4010");
        serviceRevenueAccount.setName("Consulting Revenue");
        serviceRevenueAccount.setAccountType(AccountType.REVENUE);
        serviceRevenueAccount.setStatus(AccountStatus.ACTIVE);
        serviceRevenueAccount = accountRepository.save(serviceRevenueAccount);
    }

    @Test
    void completeAccountingLifecycle_InviteToPostToReverse() throws Exception {
        String accountantEmail = "accountant-" + UUID.randomUUID() + "@apex.test";
        String accountantPassword = "AccountantPass123!";

        // 1. Admin invites Accountant
        InvitationRequest inviteRequest = new InvitationRequest(accountantEmail, Role.ACCOUNTANT);
        HttpRequest inviteHttp = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/users/invite"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(inviteRequest)))
                .build();

        HttpResponse<String> inviteResponse = httpClient.send(inviteHttp, HttpResponse.BodyHandlers.ofString());
        assertThat(inviteResponse.statusCode()).isEqualTo(200);

        JsonNode inviteNode = objectMapper.readTree(inviteResponse.body());
        String invitationToken = inviteNode.get("invitationToken").asText();
        assertThat(invitationToken).isNotBlank();

        // 2. Admin lists pending invitations
        HttpRequest listInvitationsHttp = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/invitations"))
                .header("Authorization", "Bearer " + adminToken)
                .GET()
                .build();

        HttpResponse<String> listInvResponse = httpClient.send(listInvitationsHttp, HttpResponse.BodyHandlers.ofString());
        assertThat(listInvResponse.statusCode()).isEqualTo(200);
        JsonNode pendingInvitations = objectMapper.readTree(listInvResponse.body());
        assertThat(pendingInvitations.isArray()).isTrue();
        assertThat(pendingInvitations.size()).isGreaterThanOrEqualTo(1);

        // 3. Accountant accepts invitation and sets password
        AcceptInvitationRequest acceptRequest = new AcceptInvitationRequest(
                invitationToken,
                accountantEmail,
                "Alex Accountant",
                accountantPassword
        );
        HttpRequest acceptHttp = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/invitations/accept"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(acceptRequest)))
                .build();

        HttpResponse<String> acceptResponse = httpClient.send(acceptHttp, HttpResponse.BodyHandlers.ofString());
        assertThat(acceptResponse.statusCode()).isEqualTo(200);

        // 4. Accountant logs in with new credentials
        HttpRequest loginHttp = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("email", accountantEmail, "password", accountantPassword)
                )))
                .build();

        HttpResponse<String> loginResponse = httpClient.send(loginHttp, HttpResponse.BodyHandlers.ofString());
        assertThat(loginResponse.statusCode()).isEqualTo(200);

        JsonNode loginNode = objectMapper.readTree(loginResponse.body());
        String accountantToken = loginNode.get("token").asText();
        JsonNode userSummary = loginNode.get("user");
        assertThat(userSummary.get("role").asText()).isEqualTo("ACCOUNTANT");
        assertThat(userSummary.get("organizationName").asText()).isEqualTo("Apex Financial Services");
        assertThat(userSummary.get("baseCurrency").asText()).isEqualTo("USD");

        // 5. Accountant creates a balanced draft
        JournalEntryRequest draftRequest = new JournalEntryRequest(
                LocalDate.now(),
                "Client consulting fee invoice 101",
                "INV-101",
                List.of(
                        new JournalLineRequest(cashAccount.getId(), EntrySide.DEBIT, new BigDecimal("1500.00")),
                        new JournalLineRequest(serviceRevenueAccount.getId(), EntrySide.CREDIT, new BigDecimal("1500.00"))
                )
        );

        HttpRequest draftHttp = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/journal-entries"))
                .header("Authorization", "Bearer " + accountantToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(draftRequest)))
                .build();

        HttpResponse<String> draftResponse = httpClient.send(draftHttp, HttpResponse.BodyHandlers.ofString());
        assertThat(draftResponse.statusCode()).isEqualTo(200);
        JsonNode draftNode = objectMapper.readTree(draftResponse.body());
        Long entryId = draftNode.get("id").asLong();
        assertThat(draftNode.get("status").asText()).isEqualTo("DRAFT");

        // 6. Accountant posts the draft
        HttpRequest postHttp = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/journal-entries/" + entryId + "/post"))
                .header("Authorization", "Bearer " + accountantToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> postResponse = httpClient.send(postHttp, HttpResponse.BodyHandlers.ofString());
        assertThat(postResponse.statusCode()).isEqualTo(200);
        JsonNode postedNode = objectMapper.readTree(postResponse.body());
        assertThat(postedNode.get("status").asText()).isEqualTo("POSTED");

        // 7. Admin reverses the posted entry with an audit reason
        String reversalReason = "Client invoice cancelled due to scope renegotiation";
        HttpRequest reverseHttp = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/journal-entries/" + entryId + "/reverse"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(new JournalReversalRequest(reversalReason))))
                .build();

        HttpResponse<String> reverseResponse = httpClient.send(reverseHttp, HttpResponse.BodyHandlers.ofString());
        assertThat(reverseResponse.statusCode()).isEqualTo(200);

        JsonNode reversalNode = objectMapper.readTree(reverseResponse.body());
        Long reversalId = reversalNode.get("id").asLong();
        assertThat(reversalNode.get("status").asText()).isEqualTo("POSTED");
        assertThat(reversalNode.get("reversesEntryId").asLong()).isEqualTo(entryId);
        assertThat(reversalNode.get("reversalReason").asText()).isEqualTo(reversalReason);

        // 8. Verify original entry reports bidirectional linkage
        HttpRequest getOriginalHttp = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/journal-entries/" + entryId))
                .header("Authorization", "Bearer " + accountantToken)
                .GET()
                .build();

        HttpResponse<String> getOrigResponse = httpClient.send(getOriginalHttp, HttpResponse.BodyHandlers.ofString());
        JsonNode originalVerified = objectMapper.readTree(getOrigResponse.body());
        assertThat(originalVerified.get("reversedByEntryId").asLong()).isEqualTo(reversalId);
        assertThat(originalVerified.get("reversalReason").asText()).isEqualTo(reversalReason);

        // 9. Verify General Ledger report for cash account reflects both original and reversal
        HttpRequest glHttp = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/reports/general-ledger?accountId=" + cashAccount.getId()))
                .header("Authorization", "Bearer " + accountantToken)
                .GET()
                .build();

        HttpResponse<String> glResponse = httpClient.send(glHttp, HttpResponse.BodyHandlers.ofString());
        assertThat(glResponse.statusCode()).isEqualTo(200);
        JsonNode glNode = objectMapper.readTree(glResponse.body());
        JsonNode lines = glNode.get("lines");
        assertThat(lines).hasSize(2); // One debit of 1500, one credit of 1500
        assertThat(new BigDecimal(glNode.get("closingBalance").asText())).isEqualByComparingTo(BigDecimal.ZERO);
    }
}

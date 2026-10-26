package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.audit.model.AuditLog;
import com.example.Ledger.audit.repository.AuditLogRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.EntrySide;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.invitation.model.Invitation;
import com.example.Ledger.invitation.repository.InvitationRepository;
import com.example.Ledger.journal.model.JournalEntry;
import com.example.Ledger.journal.model.JournalLine;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.journal.repository.JournalLineRepository;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RepositoryPersistenceTests extends PostgresIntegrationTestSupport {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private LedgerUserRepository ledgerUserRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private JournalLineRepository journalLineRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    @Test
    void savesAndLoadsCoreDayOneEntities() {
        Organization organization = new Organization();
        organization.setName("Acme Ledger");
        organization.setBaseCurrency("ETB");
        organization = organizationRepository.save(organization);

        LedgerUser user = new LedgerUser();
        user.setOrganization(organization);
        user.setEmail("admin@acme.test");
        user.setPasswordHash("placeholder-hash");
        user.setFullName("Acme Admin");
        user.setRole(Role.ADMIN);
        user = ledgerUserRepository.save(user);

        Account cash = new Account();
        cash.setOrganization(organization);
        cash.setAccountCode("1000");
        cash.setName("Cash");
        cash.setAccountType(AccountType.ASSET);
        cash.setStatus(AccountStatus.ACTIVE);
        cash = accountRepository.save(cash);

        JournalEntry entry = new JournalEntry();
        entry.setOrganization(organization);
        entry.setTransactionDate(LocalDate.now());
        entry.setDescription("Initial capital");
        entry.setReference("JE-001");
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setCreatedByUser(user);
        entry = journalEntryRepository.save(entry);

        JournalLine line = new JournalLine();
        line.setJournalEntry(entry);
        line.setAccount(cash);
        line.setSide(EntrySide.DEBIT);
        line.setAmount(new BigDecimal("1000.00"));
        line = journalLineRepository.save(line);

        Invitation invitation = new Invitation();
        invitation.setOrganization(organization);
        invitation.setInvitedByUser(user);
        invitation.setInvitedEmail("viewer@acme.test");
        invitation.setRole(Role.VIEWER);
        invitation.setInvitationToken(UUID.randomUUID().toString());
        invitation.setExpiresAt(Instant.now().plusSeconds(86400));
        invitation = invitationRepository.save(invitation);

        AuditLog auditLog = new AuditLog();
        auditLog.setOrganization(organization);
        auditLog.setActorUser(user);
        auditLog.setAction("JOURNAL_CREATED");
        auditLog.setEntityType("JOURNAL_ENTRY");
        auditLog.setEntityId(entry.getId().toString());
        auditLog.setMetadataJson("{\"source\":\"test\"}");
        auditLog = auditLogRepository.save(auditLog);

        assertThat(organization.getId()).isNotNull();
        assertThat(user.getId()).isNotNull();
        assertThat(cash.getId()).isNotNull();
        assertThat(entry.getId()).isNotNull();
        assertThat(line.getId()).isNotNull();
        assertThat(invitation.getId()).isNotNull();
        assertThat(auditLog.getId()).isNotNull();

        assertThat(organizationRepository.findById(organization.getId())).isPresent();
        assertThat(ledgerUserRepository.findById(user.getId())).isPresent();
        assertThat(accountRepository.findById(cash.getId())).isPresent();
        assertThat(journalEntryRepository.findById(entry.getId())).isPresent();
        assertThat(journalLineRepository.findById(line.getId())).isPresent();
        assertThat(invitationRepository.findById(invitation.getId())).isPresent();
        assertThat(auditLogRepository.findById(auditLog.getId())).isPresent();
    }
}
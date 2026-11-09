package com.example.Ledger;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
import com.example.Ledger.common.enums.EntrySide;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.journal.model.JournalEntry;
import com.example.Ledger.journal.model.JournalLine;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DatabaseImmutabilityIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private Organization organization;
    private LedgerUser user;
    private Account cashAccount;
    private Account revenueAccount;

    @BeforeEach
    void setUp() {
        organization = new Organization();
        organization.setName("Immutability Test Org " + UUID.randomUUID().toString().substring(0, 8));
        organization.setBaseCurrency("USD");
        organization = organizationRepository.save(organization);

        user = new LedgerUser();
        user.setOrganization(organization);
        user.setEmail("user." + UUID.randomUUID().toString().substring(0, 8) + "@acme.test");
        user.setFullName("Immutability Tester");
        user.setPasswordHash(passwordEncoder.encode("Secret123!"));
        user.setRole(Role.ADMIN);
        user = userRepository.save(user);

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
        revenueAccount.setName("Revenue");
        revenueAccount.setAccountType(AccountType.REVENUE);
        revenueAccount.setStatus(AccountStatus.ACTIVE);
        revenueAccount = accountRepository.save(revenueAccount);
    }

    private JournalEntry createPostedEntry() {
        JournalEntry entry = new JournalEntry();
        entry.setOrganization(organization);
        entry.setCreatedByUser(user);
        entry.setReference("JE-IMM-" + UUID.randomUUID().toString().substring(0, 6));
        entry.setTransactionDate(LocalDate.now());
        entry.setDescription("Posted entry for immutability test");
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
        entry = journalEntryRepository.saveAndFlush(entry);

        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setPostedByUser(user);
        entry.setPostedAt(Instant.now());
        return journalEntryRepository.saveAndFlush(entry);
    }

    @Test
    void databaseTriggerBlocksDeletingPostedJournalEntry() {
        JournalEntry entry = createPostedEntry();
        Long entryId = entry.getId();

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM journal_entry WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void databaseTriggerBlocksModifyingFinancialFieldsOnPostedJournalEntry() {
        JournalEntry entry = createPostedEntry();
        Long entryId = entry.getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_entry SET description = 'Altered Description' WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("financially immutable");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_entry SET status = 'DRAFT' WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("financially immutable");
    }

    @Test
    void databaseTriggerBlocksDeletingLinesFromPostedJournalEntry() {
        JournalEntry entry = createPostedEntry();
        Long lineId = entry.getLines().get(0).getId();

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM journal_line WHERE id = ?", lineId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot be deleted because parent entry");
    }

    @Test
    void databaseTriggerBlocksModifyingLinesOnPostedJournalEntry() {
        JournalEntry entry = createPostedEntry();
        Long lineId = entry.getLines().get(0).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_line SET amount = 999.00 WHERE id = ?", lineId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot be modified for POSTED entry");
    }

    @Test
    void databaseTriggerBlocksInsertingNewLinesIntoPostedJournalEntry() {
        JournalEntry entry = createPostedEntry();
        Long entryId = entry.getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO journal_line (journal_entry_id, account_id, side, amount, created_at, updated_at, version) " +
                        "VALUES (?, ?, 'DEBIT', 50.00, NOW(), NOW(), 0)", entryId, cashAccount.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot be inserted into already POSTED");
    }

    @Test
    void databaseTriggerAllowsReversalLinkageUpdateOnPostedJournalEntry() {
        JournalEntry original = createPostedEntry();
        JournalEntry reversal = createPostedEntry();

        // Updating reversed_by_journal_entry_id must succeed
        int updated = jdbcTemplate.update(
                "UPDATE journal_entry SET reversed_by_journal_entry_id = ? WHERE id = ?",
                reversal.getId(), original.getId()
        );
        assertThat(updated).isEqualTo(1);

        // Attempting to overwrite with a different reversal entry must fail
        JournalEntry secondReversal = createPostedEntry();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_entry SET reversed_by_journal_entry_id = ? WHERE id = ?",
                secondReversal.getId(), original.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("has already been reversed");
    }
}

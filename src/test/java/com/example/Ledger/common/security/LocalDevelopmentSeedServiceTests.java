package com.example.Ledger.common.security;

import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.invitation.repository.InvitationRepository;
import com.example.Ledger.journal.repository.JournalEntryRepository;
import com.example.Ledger.journal.repository.JournalLineRepository;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LocalDevelopmentSeedServiceTests extends PostgresIntegrationTestSupport {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private LedgerUserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LocalDevelopmentSeedService seedService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JournalLineRepository journalLineRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private com.example.Ledger.audit.repository.AuditLogRepository auditLogRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSeedState() {
        jdbcTemplate.execute("TRUNCATE TABLE journal_line, journal_entry, invitation, audit_log, app_user, account, organization CASCADE");
    }

    @Test
    void seedDevelopmentDataIsIdempotentAndCreatesRequiredAccounts() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String organizationName = "Local Seed " + suffix;
        String adminEmail = "admin." + suffix + "@ledger.test";
        String accountantEmail = "accountant." + suffix + "@ledger.test";
        String viewerEmail = "viewer." + suffix + "@ledger.test";

        seedService.seedDevelopmentData(
                organizationName,
                "ETB",
                adminEmail,
                "Admin123!",
                accountantEmail,
                "Accountant123!",
                viewerEmail,
                "Viewer123!"
        );

        Organization organization = organizationRepository.findByNameIgnoreCase(organizationName).orElseThrow();
        assertThat(organization.getBaseCurrency()).isEqualTo("ETB");

        LedgerUser admin = userRepository.findByOrganizationIdAndEmailIgnoreCase(organization.getId(), adminEmail)
                .orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(passwordEncoder.matches("Admin123!", admin.getPasswordHash())).isTrue();

        LedgerUser accountant = userRepository.findByOrganizationIdAndEmailIgnoreCase(organization.getId(), accountantEmail)
                .orElseThrow();
        assertThat(accountant.getRole()).isEqualTo(Role.ACCOUNTANT);

        LedgerUser viewer = userRepository.findByOrganizationIdAndEmailIgnoreCase(organization.getId(), viewerEmail)
                .orElseThrow();
        assertThat(viewer.getRole()).isEqualTo(Role.VIEWER);

        assertThat(accountRepository.findByOrganizationIdOrderByAccountCodeAsc(organization.getId())).isNotEmpty();

        seedService.seedDevelopmentData(
                organizationName,
                "ETB",
                adminEmail,
                "Admin123!",
                accountantEmail,
                "Accountant123!",
                viewerEmail,
                "Viewer123!"
        );

        Organization sameOrganization = organizationRepository.findByNameIgnoreCase(organizationName).orElseThrow();
        assertThat(sameOrganization.getId()).isEqualTo(organization.getId());
        assertThat(userRepository.findAllByEmailIgnoreCase(adminEmail)).hasSize(1);
        assertThat(userRepository.findAllByEmailIgnoreCase(accountantEmail)).hasSize(1);
        assertThat(userRepository.findAllByEmailIgnoreCase(viewerEmail)).hasSize(1);
    }

    @Test
    void seedTreatsSameOrganizationCaseVariantAsExistingUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String organizationName = "Case-safe Org " + suffix;
        String email = "admin." + suffix + "@ledger.test";

        Organization organization = new Organization();
        organization.setName(organizationName);
        organization.setBaseCurrency("ETB");
        organization = organizationRepository.save(organization);

        LedgerUser existing = new LedgerUser();
        existing.setOrganization(organization);
        existing.setEmail(email.toLowerCase(Locale.ROOT));
        existing.setPasswordHash(passwordEncoder.encode("OriginalPass!"));
        existing.setFullName("Same Org Admin");
        existing.setRole(Role.ADMIN);
        existing = userRepository.saveAndFlush(existing);

        seedService.seedDevelopmentData(
                organizationName,
                "ETB",
                email.toUpperCase(Locale.ROOT),
                "SeedPass!",
                "accountant." + suffix + "@ledger.test",
                "Accountant123!",
                "viewer." + suffix + "@ledger.test",
                "Viewer123!"
        );

        LedgerUser reloaded = userRepository.findById(existing.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo(email.toLowerCase(Locale.ROOT));
        assertThat(reloaded.getRole()).isEqualTo(Role.ADMIN);
        assertThat(passwordEncoder.matches("OriginalPass!", reloaded.getPasswordHash())).isTrue();
    }

    @Test
    void seedReusesExistingBootstrapOrganizationInsteadOfCreatingAnotherDefaultOrg() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "admin." + suffix + "@ledger.test";

        Organization bootstrapOrganization = new Organization();
        bootstrapOrganization.setName("Ledger Bootstrap");
        bootstrapOrganization.setBaseCurrency("ETB");
        bootstrapOrganization = organizationRepository.save(bootstrapOrganization);

        LedgerUser existingUser = new LedgerUser();
        existingUser.setOrganization(bootstrapOrganization);
        existingUser.setEmail(email.toLowerCase(Locale.ROOT));
        existingUser.setPasswordHash(passwordEncoder.encode("OriginalPass!"));
        existingUser.setFullName("Bootstrap Admin");
        existingUser.setRole(Role.ADMIN);
        userRepository.saveAndFlush(existingUser);

        seedService.seedDevelopmentData(
                "Ledger Development",
                "ETB",
                email,
                "SeedPass!",
                "accountant." + suffix + "@ledger.test",
                "Accountant123!",
                "viewer." + suffix + "@ledger.test",
                "Viewer123!"
        );

        Organization reusedOrganization = organizationRepository.findByNameIgnoreCase("Ledger Bootstrap").orElseThrow();
        assertThat(reusedOrganization.getId()).isEqualTo(bootstrapOrganization.getId());
        assertThat(organizationRepository.findByNameIgnoreCase("Ledger Development")).isEmpty();

        LedgerUser reloaded = userRepository.findByOrganizationIdAndEmailIgnoreCase(reusedOrganization.getId(), email)
                .orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(Role.ADMIN);
        assertThat(passwordEncoder.matches("OriginalPass!", reloaded.getPasswordHash())).isTrue();
    }

    @Test
    void databaseEnforcesCaseInsensitiveOrganizationNameUniqueness() {
        Organization first = new Organization();
        first.setName("Ledger Bootstrap");
        first.setBaseCurrency("ETB");
        organizationRepository.saveAndFlush(first);

        Organization duplicate = new Organization();
        duplicate.setName("ledger bootstrap");
        duplicate.setBaseCurrency("USD");

        assertThatThrownBy(() -> organizationRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seedDetectsExistingUserWithSameEmailInDifferentCaseAcrossOrganizations() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "admin." + suffix + "@ledger.test";

        Organization firstOrganization = new Organization();
        firstOrganization.setName("Org One " + suffix);
        firstOrganization.setBaseCurrency("USD");
        firstOrganization = organizationRepository.save(firstOrganization);

        LedgerUser firstUser = new LedgerUser();
        firstUser.setOrganization(firstOrganization);
        firstUser.setEmail(email.toLowerCase());
        firstUser.setPasswordHash(passwordEncoder.encode("OriginalPass!"));
        firstUser.setFullName("Original Admin");
        firstUser.setRole(Role.ADMIN);
        userRepository.saveAndFlush(firstUser);

        final Organization secondOrganization = new Organization();
        secondOrganization.setName("Org Two " + suffix);
        secondOrganization.setBaseCurrency("EUR");
        final Organization savedSecondOrganization = organizationRepository.save(secondOrganization);

        assertThatThrownBy(() -> seedService.seedDevelopmentData(
                savedSecondOrganization.getName(),
                "EUR",
                email.toUpperCase(),
                "SeedPass!",
                "accountant." + suffix + "@ledger.test",
                "Accountant123!",
                "viewer." + suffix + "@ledger.test",
                "Viewer123!"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(email.toLowerCase())
                .hasMessageContaining("already belongs to organization")
                .hasMessageContaining("Use a different local seed email");
    }

    @Test
    void seedDoesNotSilentlyOverwriteExistingUserCredentialsOrRole() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "conflict." + suffix + "@ledger.test";

        Organization organization = new Organization();
        organization.setName("Protected Org " + suffix);
        organization.setBaseCurrency("ETB");
        organization = organizationRepository.save(organization);

        LedgerUser existingUser = new LedgerUser();
        existingUser.setOrganization(organization);
        existingUser.setEmail(email);
        existingUser.setPasswordHash(passwordEncoder.encode("OriginalPassword!"));
        existingUser.setFullName("Original Name");
        existingUser.setRole(Role.VIEWER);
        existingUser = userRepository.saveAndFlush(existingUser);

        seedService.seedDevelopmentData(
                organization.getName(),
                "ETB",
                email,
                "ReplacementPassword!",
                "accountant." + suffix + "@ledger.test",
                "Accountant123!",
                "viewer." + suffix + "@ledger.test",
                "Viewer123!"
        );

        LedgerUser reloaded = userRepository.findById(existingUser.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(Role.VIEWER);
        assertThat(passwordEncoder.matches("OriginalPassword!", reloaded.getPasswordHash())).isTrue();
        assertThat(reloaded.getFullName()).isEqualTo("Original Name");
    }

    @Test
    void databaseUniquenessTriggerRejectsSameEmailAcrossOrganizations() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "unique." + suffix + "@ledger.test";

        Organization org1 = new Organization();
        org1.setName("Org A " + suffix);
        org1.setBaseCurrency("USD");
        org1 = organizationRepository.save(org1);

        final Organization org2 = new Organization();
        org2.setName("Org B " + suffix);
        org2.setBaseCurrency("EUR");
        final Organization savedOrg2 = organizationRepository.save(org2);

        LedgerUser first = new LedgerUser();
        first.setOrganization(org1);
        first.setEmail(email.toLowerCase());
        first.setPasswordHash(passwordEncoder.encode("Pass123!"));
        first.setFullName("First User");
        first.setRole(Role.ADMIN);
        userRepository.saveAndFlush(first);

        LedgerUser second = new LedgerUser();
        second.setOrganization(savedOrg2);
        second.setEmail(email.toUpperCase());
        second.setPasswordHash(passwordEncoder.encode("Pass456!"));
        second.setFullName("Second User");
        second.setRole(Role.ACCOUNTANT);

        assertThatThrownBy(() -> userRepository.saveAndFlush(second))
                .isInstanceOf(JpaSystemException.class);
    }
}

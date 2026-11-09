package com.example.Ledger.common.security;

import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.enums.UserStatus;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrganizationBootstrapTests extends PostgresIntegrationTestSupport {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private OrganizationBootstrap organizationBootstrap;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private LedgerUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${ledger.super-admin.bootstrap.enabled}")
    private boolean bootstrapEnabledByDefault;

    @Value("${ledger.super-admin.email}")
    private String defaultEmail;

    @Value("${ledger.super-admin.password}")
    private String defaultPassword;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE platform_audit_log, refresh_token, journal_line, journal_entry, invitation, audit_log, app_user, account, organization CASCADE");
    }

    @Test
    void bootstrapIsDisabledWithEmptyCredentialsByDefault() {
        assertThat(bootstrapEnabledByDefault).isFalse();
        assertThat(defaultEmail).isEmpty();
        assertThat(defaultPassword).isEmpty();
        assertThat(applicationContext.containsBean("bootstrapSuperAdmin")).isFalse();
    }

    @Test
    void bootstrapFailsFastWhenEnabledWithMissingOrWeakCredentials() {
        assertThatThrownBy(() -> organizationBootstrap.ensureSuperAdminExists(
                organizationRepository,
                userRepository,
                passwordEncoder,
                "",
                "StrongPassword123!",
                "Super Admin",
                "Ledger Platform",
                "USD"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials are missing");

        assertThatThrownBy(() -> organizationBootstrap.ensureSuperAdminExists(
                organizationRepository,
                userRepository,
                passwordEncoder,
                "super@ledger.test",
                "short",
                "Super Admin",
                "Ledger Platform",
                "USD"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 12 characters");
    }

    @Test
    void bootstrapCreatesPlatformAnchorAndSuperAdminIdempotently() {
        String email = "super." + UUID.randomUUID().toString().substring(0, 8) + "@ledger.test";
        organizationBootstrap.ensureSuperAdminExists(
                organizationRepository,
                userRepository,
                passwordEncoder,
                email,
                "StrongPlatformPass!99",
                "Root Platform Admin",
                "Ledger Platform",
                "USD"
        );

        Organization anchor = organizationRepository.findByNameIgnoreCase("Ledger Platform").orElseThrow();
        assertThat(anchor.getKind()).isEqualTo(OrganizationKind.PLATFORM);
        assertThat(anchor.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);

        LedgerUser superAdmin = userRepository.findAllWithOrganizationByEmailIgnoreCase(email).getFirst();
        assertThat(superAdmin.getRole()).isEqualTo(Role.SUPER_ADMIN);
        assertThat(superAdmin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(passwordEncoder.matches("StrongPlatformPass!99", superAdmin.getPasswordHash())).isTrue();

        // Re-running with different password does not duplicate user or overwrite existing credentials
        organizationBootstrap.ensureSuperAdminExists(
                organizationRepository,
                userRepository,
                passwordEncoder,
                email.toUpperCase(),
                "AnotherStrongPass!99",
                "Root Platform Admin",
                "Ledger Platform",
                "USD"
        );
        assertThat(userRepository.findAllWithOrganizationByEmailIgnoreCase(email)).hasSize(1);
    }

    @Test
    void bootstrapRejectsTenantOrgAnchorOrNonSuperAdminEmailConflict() {
        Organization wronglyMarkedOrg = new Organization();
        wronglyMarkedOrg.setName("Ledger Platform");
        wronglyMarkedOrg.setBaseCurrency("USD");
        wronglyMarkedOrg.setKind(OrganizationKind.TENANT);
        wronglyMarkedOrg.setStatus(OrganizationStatus.ACTIVE);
        organizationRepository.saveAndFlush(wronglyMarkedOrg);

        assertThatThrownBy(() -> organizationBootstrap.ensureSuperAdminExists(
                organizationRepository,
                userRepository,
                passwordEncoder,
                "root@ledger.test",
                "StrongPlatformPass!99",
                "Root",
                "Ledger Platform",
                "USD"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not marked as PLATFORM");

        Organization platformOrg = new Organization();
        platformOrg.setName("Custom Platform");
        platformOrg.setBaseCurrency("USD");
        platformOrg.setKind(OrganizationKind.PLATFORM);
        platformOrg.setStatus(OrganizationStatus.ACTIVE);
        Organization savedPlatformOrg = organizationRepository.saveAndFlush(platformOrg);

        Organization tenantOrg = new Organization();
        tenantOrg.setName("Tenant Conflict Org");
        tenantOrg.setBaseCurrency("USD");
        tenantOrg = organizationRepository.saveAndFlush(tenantOrg);

        LedgerUser tenantUser = new LedgerUser();
        tenantUser.setOrganization(tenantOrg);
        tenantUser.setEmail("conflict.admin@ledger.test");
        tenantUser.setFullName("Tenant Admin");
        tenantUser.setPasswordHash(passwordEncoder.encode("StrongPassword!1"));
        tenantUser.setRole(Role.ADMIN);
        userRepository.saveAndFlush(tenantUser);

        assertThatThrownBy(() -> organizationBootstrap.ensureSuperAdminExists(
                organizationRepository,
                userRepository,
                passwordEncoder,
                "conflict.admin@ledger.test",
                "StrongPlatformPass!99",
                "Root",
                savedPlatformOrg.getName(),
                "USD"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-SUPER_ADMIN role");
    }
}

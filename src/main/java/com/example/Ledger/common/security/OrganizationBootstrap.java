package com.example.Ledger.common.security;

import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.enums.UserStatus;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Configuration
public class OrganizationBootstrap {

    @Bean
    @ConditionalOnProperty(name = "ledger.super-admin.bootstrap.enabled", havingValue = "true")
    public ApplicationRunner bootstrapSuperAdmin(
            OrganizationRepository organizationRepository,
            LedgerUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${ledger.super-admin.email:}") String superAdminEmail,
            @Value("${ledger.super-admin.password:}") String superAdminPassword,
            @Value("${ledger.super-admin.full-name:Platform Super Admin}") String superAdminFullName,
            @Value("${ledger.super-admin.organization:Ledger Platform}") String platformOrganizationName,
            @Value("${ledger.super-admin.currency:USD}") String platformCurrency
    ) {
        return args -> ensureSuperAdminExists(
                organizationRepository,
                userRepository,
                passwordEncoder,
                superAdminEmail,
                superAdminPassword,
                superAdminFullName,
                platformOrganizationName,
                platformCurrency
        );
    }

    @Transactional
    public void ensureSuperAdminExists(
            OrganizationRepository organizationRepository,
            LedgerUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            String superAdminEmail,
            String superAdminPassword,
            String superAdminFullName,
            String platformOrganizationName,
            String platformCurrency
    ) {
        String normalizedEmail = superAdminEmail == null ? "" : superAdminEmail.trim().toLowerCase(Locale.ROOT);
        String rawPassword = superAdminPassword == null ? "" : superAdminPassword.trim();
        if (normalizedEmail.isEmpty() || rawPassword.isEmpty()) {
            throw new IllegalStateException("Super admin bootstrap is enabled but credentials are missing");
        }

        if (rawPassword.length() < 12) {
            throw new IllegalStateException("Super admin password must be at least 12 characters");
        }

        String resolvedOrgName = platformOrganizationName == null || platformOrganizationName.isBlank()
                ? "Ledger Platform"
                : platformOrganizationName.trim();
        String resolvedCurrency = platformCurrency == null || platformCurrency.isBlank()
                ? "USD"
                : platformCurrency.trim().toUpperCase(Locale.ROOT);

        Organization organization = organizationRepository.findByNameIgnoreCase(resolvedOrgName)
                .map(existing -> {
                    if (existing.getKind() != OrganizationKind.PLATFORM) {
                        throw new IllegalStateException(
                                "Configured platform organization '" + existing.getName() + "' is not marked as PLATFORM"
                        );
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    Organization created = new Organization();
                    created.setName(resolvedOrgName);
                    created.setBaseCurrency(resolvedCurrency);
                    created.setKind(OrganizationKind.PLATFORM);
                    created.setStatus(OrganizationStatus.ACTIVE);
                    return organizationRepository.save(created);
                });

        List<LedgerUser> existingMatches = userRepository.findAllWithOrganizationByEmailIgnoreCase(normalizedEmail);
        if (!existingMatches.isEmpty()) {
            LedgerUser existing = existingMatches.getFirst();
            if (existing.getRole() != Role.SUPER_ADMIN) {
                throw new IllegalStateException(
                        "Configured super admin email already exists with a non-SUPER_ADMIN role"
                );
            }
            if (existing.getOrganization() == null
                    || !existing.getOrganization().getId().equals(organization.getId())) {
                throw new IllegalStateException(
                        "Configured super admin belongs to a different organization than the platform anchor"
                );
            }
            return;
        }

        LedgerUser user = new LedgerUser();
        user.setOrganization(organization);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName(superAdminFullName == null || superAdminFullName.isBlank()
                ? "Platform Super Admin"
                : superAdminFullName.trim());
        user.setRole(Role.SUPER_ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
    }
}

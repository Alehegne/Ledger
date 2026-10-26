package com.example.Ledger.common.security;

import com.example.Ledger.common.enums.Role;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Configuration
public class OrganizationBootstrap {

    @Bean
    public ApplicationRunner bootstrapDefaultOrganization(
            OrganizationRepository organizationRepository,
            LedgerUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${ledger.bootstrap.email}") String adminEmail,
            @Value("${ledger.bootstrap.password}") String adminPassword,
            @Value("${ledger.bootstrap.organization}") String organizationName,
            @Value("${ledger.bootstrap.currency}") String currency
    ) {
        return args -> ensureBootstrapExists(
                organizationRepository,
                userRepository,
                passwordEncoder,
                adminEmail,
                adminPassword,
                organizationName,
                currency
        );
    }

    @Transactional
    public void ensureBootstrapExists(
            OrganizationRepository organizationRepository,
            LedgerUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            String adminEmail,
            String adminPassword,
            String organizationName,
            String currency
    ) {
        if (organizationRepository.count() > 0) {
            return;
        }

        Organization organization = new Organization();
        organization.setName(organizationName);
        organization.setBaseCurrency(currency.trim().toUpperCase());
        organization = organizationRepository.save(organization);

        if (!userRepository.existsByEmailIgnoreCase(adminEmail)) {
            LedgerUser user = new LedgerUser();
            user.setOrganization(organization);
            user.setEmail(adminEmail.trim().toLowerCase());
            user.setPasswordHash(passwordEncoder.encode(adminPassword));
            user.setFullName("System Administrator");
            user.setRole(Role.ADMIN);
            userRepository.save(user);
        }
    }
}

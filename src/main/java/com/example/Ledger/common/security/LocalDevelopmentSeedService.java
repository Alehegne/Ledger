package com.example.Ledger.common.security;

import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.AccountType;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Configuration
public class LocalDevelopmentSeedService {

    private final OrganizationRepository organizationRepository;
    private final LedgerUserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    public LocalDevelopmentSeedService(
            OrganizationRepository organizationRepository,
            LedgerUserRepository userRepository,
            AccountRepository accountRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    @Transactional
    @ConditionalOnProperty(name = "ledger.seed.enabled", havingValue = "true")
    public ApplicationRunner seedLocalDevelopmentData(
            @Value("${ledger.seed.organization:Ledger Bootstrap}") String organizationName,
            @Value("${ledger.seed.currency:ETB}") String currency,
            @Value("${ledger.seed.admin-email:admin@ledger.test}") String adminEmail,
            @Value("${ledger.seed.admin-password:Admin123!}") String adminPassword,
            @Value("${ledger.seed.accountant-email:accountant@ledger.test}") String accountantEmail,
            @Value("${ledger.seed.accountant-password:Accountant123!}") String accountantPassword,
            @Value("${ledger.seed.viewer-email:viewer@ledger.test}") String viewerEmail,
            @Value("${ledger.seed.viewer-password:Viewer123!}") String viewerPassword
    ) {
        return args -> seedDevelopmentData(
                organizationName,
                currency,
                adminEmail,
                adminPassword,
                accountantEmail,
                accountantPassword,
                viewerEmail,
                viewerPassword
        );
    }

    @Transactional
    public void seedDevelopmentData(
            String organizationName,
            String currency,
            String adminEmail,
            String adminPassword,
            String accountantEmail,
            String accountantPassword,
            String viewerEmail,
            String viewerPassword
    ) {
        String normalizedName = organizationName == null ? "Ledger Bootstrap" : organizationName.trim();
        if (normalizedName.isEmpty()) {
            normalizedName = "Ledger Bootstrap";
        }

        String normalizedCurrency = currency == null ? "ETB" : currency.trim().toUpperCase(Locale.ROOT);
        if (normalizedCurrency.length() != 3) {
            normalizedCurrency = "ETB";
        }

        final String seedOrganizationName = normalizedName;
        final String seedCurrencyCode = normalizedCurrency;

        Organization organization = resolveSeedOrganization(seedOrganizationName, seedCurrencyCode);
        if (organization.getKind() == OrganizationKind.PLATFORM) {
            throw new IllegalStateException("Cannot run tenant development seed against PLATFORM organization");
        }

        ensureUser(organization, adminEmail, Role.ADMIN, adminPassword, "System Administrator");
        ensureUser(organization, accountantEmail, Role.ACCOUNTANT, accountantPassword, "Finance Accountant");
        ensureUser(organization, viewerEmail, Role.VIEWER, viewerPassword, "Operations Viewer");
        ensureChartOfAccounts(organization);
    }

    private Organization resolveSeedOrganization(String requestedName, String currencyCode) {
        String canonicalBootstrapOrganizationName = "Ledger Bootstrap";
        String legacyLocalSeedOrganizationName = "Ledger Development";
        final String resolvedName = requestedName == null || requestedName.isBlank()
                ? canonicalBootstrapOrganizationName
                : requestedName.trim();

        if (resolvedName.equalsIgnoreCase(legacyLocalSeedOrganizationName)) {
            Organization canonical = findUniqueOrganizationByName(canonicalBootstrapOrganizationName);
            if (canonical != null) {
                return canonical;
            }

            Organization legacy = findUniqueOrganizationByName(legacyLocalSeedOrganizationName);
            if (legacy != null) {
                return legacy;
            }

            return createOrganization(canonicalBootstrapOrganizationName, currencyCode);
        }

        Organization existingOrganization = findUniqueOrganizationByName(resolvedName);
        if (existingOrganization != null) {
            return existingOrganization;
        }

        return createOrganization(resolvedName, currencyCode);
    }

    private Organization findUniqueOrganizationByName(String name) {
        List<Organization> matches = organizationRepository.findAllByNameIgnoreCase(name);
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Seed organization '" + name + "' is ambiguous because the database contains multiple organizations with that name. " +
                            "Use a dedicated local development database or set LEDGER_SEED_ORGANIZATION to a unique organization name; existing organization records were not changed."
            );
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private Organization createOrganization(String name, String currencyCode) {
        Organization created = new Organization();
        created.setName(name.trim());
        created.setBaseCurrency(currencyCode);
        created.setKind(OrganizationKind.TENANT);
        created.setStatus(OrganizationStatus.ACTIVE);
        return organizationRepository.save(created);
    }

    private void ensureUser(
            Organization organization,
            String email,
            Role role,
            String password,
            String fullName
    ) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty()) {
            return;
        }

        List<LedgerUser> matches = userRepository.findAllWithOrganizationByEmailIgnoreCase(normalizedEmail);
        LedgerUser sameOrganizationUser = matches.stream()
                .filter(user -> user.getOrganization() != null
                        && user.getOrganization().getId() != null
                        && user.getOrganization().getId().equals(organization.getId()))
                .findFirst()
                .orElse(null);

        if (sameOrganizationUser != null) {
            return;
        }

        LedgerUser conflictingUser = matches.stream()
                .filter(user -> user.getOrganization() != null
                        && user.getOrganization().getId() != null
                        && !user.getOrganization().getId().equals(organization.getId()))
                .findFirst()
                .orElse(null);

        if (conflictingUser != null) {
            String conflictingOrganizationName = conflictingUser.getOrganization() != null
                    && conflictingUser.getOrganization().getName() != null
                    ? conflictingUser.getOrganization().getName()
                    : "another organization";
            throw new IllegalStateException(
                    "Seed conflict: email " + normalizedEmail + " already belongs to organization '"
                            + conflictingOrganizationName + "'. Use a different local seed email or a dedicated local database; existing user credentials and role were not changed."
            );
        }

        LedgerUser created = new LedgerUser();
        created.setOrganization(organization);
        created.setEmail(normalizedEmail);
        created.setPasswordHash(passwordEncoder.encode(password));
        created.setFullName(fullName);
        created.setRole(role);
        created.setStatus(UserStatus.ACTIVE);

        try {
            userRepository.save(created);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException(
                    "Seed conflict: user with email " + normalizedEmail + " already exists in another organization",
                    ex
            );
        }
    }

    private void ensureChartOfAccounts(Organization organization) {
        List<String[]> accountSeeds = List.of(
                new String[] {"1000", "Cash", "ASSET", "ACTIVE"},
                new String[] {"2000", "Accounts Payable", "LIABILITY", "ACTIVE"},
                new String[] {"3000", "Owner Equity", "EQUITY", "ACTIVE"},
                new String[] {"4000", "Revenue", "REVENUE", "ACTIVE"},
                new String[] {"5000", "Operating Expenses", "EXPENSE", "ACTIVE"}
        );

        for (String[] seed : accountSeeds) {
            String code = seed[0];
            String name = seed[1];
            String type = seed[2];
            String status = seed[3];

            if (accountRepository.existsByOrganizationIdAndAccountCodeIgnoreCase(organization.getId(), code)) {
                continue;
            }

            Account account = new Account();
            account.setOrganization(organization);
            account.setAccountCode(code);
            account.setName(name);
            account.setAccountType(AccountType.valueOf(type));
            account.setStatus(AccountStatus.valueOf(status));
            accountRepository.save(account);
        }
    }
}

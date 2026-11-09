package com.example.Ledger.auth.service;

import com.example.Ledger.auth.dto.LoginRequest;
import com.example.Ledger.auth.dto.LoginResponse;
import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.enums.UserStatus;
import com.example.Ledger.common.exception.ForbiddenException;
import com.example.Ledger.common.exception.UnauthorizedException;
import com.example.Ledger.common.security.JwtService;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final LedgerUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            LedgerUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        List<LedgerUser> candidates = userRepository.findAllWithOrganizationByEmailIgnoreCase(normalizedEmail);
        LedgerUser user = candidates.size() == 1 ? candidates.getFirst() : null;

        if (candidates.size() > 1) {
            log.warn("Ambiguous login identity for email {}: {} matching users", normalizedEmail, candidates.size());
        }

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        if (user.getStatus() == UserStatus.DISABLED) {
            throw new ForbiddenException("USER_DISABLED", "User account has been disabled");
        }

        Organization organization = user.getOrganization();
        if (organization != null && organization.getKind() == OrganizationKind.TENANT) {
            if (organization.getStatus() == OrganizationStatus.SUSPENDED) {
                throw new ForbiddenException("ORGANIZATION_SUSPENDED", "Organization has been suspended");
            }
            if (organization.getStatus() == OrganizationStatus.PENDING) {
                throw new ForbiddenException("ORGANIZATION_PENDING", "Organization onboarding has not been completed");
            }
        }

        String token = jwtService.generateToken(user);
        Instant expiresAt = jwtService.parseClaims(token).getExpiration().toInstant();
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issueRefreshToken(user);

        return new LoginResponse(
                token,
                expiresAt,
                refresh.rawToken(),
                refresh.expiresAt(),
                new LoginResponse.UserSummary(
                        user.getId(),
                        user.getEmail(),
                        user.getFullName(),
                        user.getRole().name(),
                        organization == null ? null : organization.getId(),
                        organization == null ? null : organization.getName(),
                        organization == null ? null : organization.getBaseCurrency()
                )
        );
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

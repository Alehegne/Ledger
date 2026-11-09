package com.example.Ledger.auth.service;

import com.example.Ledger.auth.dto.LoginResponse;
import com.example.Ledger.auth.model.RefreshToken;
import com.example.Ledger.auth.repository.RefreshTokenRepository;
import com.example.Ledger.common.exception.UnauthorizedException;
import com.example.Ledger.common.security.JwtService;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.user.model.LedgerUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTE_LENGTH = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final long refreshExpirationMs;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            @Value("${ledger.jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public record IssuedRefreshToken(
            String rawToken,
            Instant expiresAt
    ) {
    }

    @Transactional
    public IssuedRefreshToken issueRefreshToken(LedgerUser user) {
        return createAndSaveToken(user, UUID.randomUUID().toString(), Instant.now());
    }

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResponse rotateRefreshToken(String rawRefreshToken) {
        if (!StringUtils.hasText(rawRefreshToken)) {
            throw new UnauthorizedException("Invalid refresh token");
        }

        String presentedHash = hashToken(rawRefreshToken.trim());
        RefreshToken existing = refreshTokenRepository.findLockedByTokenHash(presentedHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        Instant now = Instant.now();
        LedgerUser user = existing.getUser();

        if (existing.isRevoked()) {
            log.warn(
                    "Detected reuse of revoked refresh token for userId={} familyId={}; revoking active token family",
                    user.getId(),
                    existing.getFamilyId()
            );
            refreshTokenRepository.revokeActiveByFamilyId(existing.getFamilyId(), now);
            throw new UnauthorizedException("Refresh token has been revoked");
        }

        if (existing.isExpired(now)) {
            existing.setRevokedAt(now);
            refreshTokenRepository.save(existing);
            throw new UnauthorizedException("Refresh token has expired");
        }

        Organization organization = user.getOrganization();

        IssuedRefreshToken replacement = createAndSaveToken(user, existing.getFamilyId(), now);
        existing.setRevokedAt(now);
        existing.setReplacedByTokenHash(hashToken(replacement.rawToken()));
        refreshTokenRepository.save(existing);

        String accessToken = jwtService.generateToken(user);
        Instant accessExpiresAt = jwtService.parseClaims(accessToken).getExpiration().toInstant();

        return new LoginResponse(
                accessToken,
                accessExpiresAt,
                replacement.rawToken(),
                replacement.expiresAt(),
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

    @Transactional
    public void revokeRefreshToken(String rawRefreshToken, Long authenticatedUserId) {
        Instant now = Instant.now();

        if (StringUtils.hasText(rawRefreshToken)) {
            String presentedHash = hashToken(rawRefreshToken.trim());
            refreshTokenRepository.findByTokenHash(presentedHash).ifPresent(token -> {
                if (authenticatedUserId == null || token.getUser().getId().equals(authenticatedUserId)) {
                    refreshTokenRepository.revokeActiveByFamilyId(token.getFamilyId(), now);
                }
            });
            return;
        }

        if (authenticatedUserId != null) {
            refreshTokenRepository.revokeActiveByUserId(authenticatedUserId, now);
        }
    }

    public static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    private IssuedRefreshToken createAndSaveToken(LedgerUser user, String familyId, Instant now) {
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant expiresAt = now.plusMillis(refreshExpirationMs);

        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(hashToken(rawToken));
        entity.setFamilyId(familyId);
        entity.setExpiresAt(expiresAt);
        refreshTokenRepository.save(entity);

        return new IssuedRefreshToken(rawToken, expiresAt);
    }
}

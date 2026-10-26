package com.example.Ledger.common.security;

import com.example.Ledger.common.enums.Role;
import com.example.Ledger.user.model.LedgerUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final String secret;
    private final String issuer;
    private final long expirationMs;

    public JwtService(
            @Value("${ledger.jwt.secret}") String secret,
            @Value("${ledger.jwt.issuer}") String issuer,
            @Value("${ledger.jwt.expiration-ms}") long expirationMs
    ) {
        this.secret = secret;
        this.issuer = issuer;
        this.expirationMs = expirationMs;
    }

    public String generateToken(LedgerUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .claim("email", user.getEmail())
                .claim("organizationId", user.getOrganization().getId())
                .claim("role", user.getRole().name())
                .signWith(secretKey())
                .compact();
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .requireIssuer(issuer)
                .verifyWith(secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public UserPrincipal toPrincipal(Claims claims) {
        Long userId = Long.valueOf(claims.getSubject());
        String email = claims.get("email", String.class);
        Long organizationId = claims.get("organizationId", Number.class).longValue();
        Role role = Role.valueOf(claims.get("role", String.class));
        return new UserPrincipal(userId, email, organizationId, role);
    }
}

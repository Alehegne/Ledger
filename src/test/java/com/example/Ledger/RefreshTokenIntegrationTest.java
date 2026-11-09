package com.example.Ledger;

import com.example.Ledger.auth.model.RefreshToken;
import com.example.Ledger.auth.repository.RefreshTokenRepository;
import com.example.Ledger.auth.service.RefreshTokenService;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import com.example.Ledger.support.PostgresIntegrationTestSupport;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefreshTokenIntegrationTest extends PostgresIntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private LedgerUserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${ledger.jwt.secret}")
    private String jwtSecret;

    @Value("${ledger.jwt.issuer}")
    private String jwtIssuer;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Organization organization;
    private LedgerUser admin;
    private String adminEmail;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Refresh Auth Org " + suffix);
        organization.setBaseCurrency("USD");
        organization = organizationRepository.save(organization);

        adminEmail = "refresh.admin." + suffix + "@ledger.test";
        admin = new LedgerUser();
        admin.setOrganization(organization);
        admin.setEmail(adminEmail);
        admin.setFullName("Refresh Admin");
        admin.setPasswordHash(passwordEncoder.encode("Secret123!"));
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void loginIssuesAccessAndHashedRefreshToken() throws Exception {
        HttpResponse<String> response = postJson("/auth/login", Map.of(
                "email", adminEmail,
                "password", "Secret123!"
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        String accessToken = body.get("token").asText();
        String refreshToken = body.get("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(body.get("refreshExpiresAt").asText()).isNotBlank();

        // Raw refresh token must NEVER be persisted; only its SHA-256 hash should be stored
        assertThat(refreshTokenRepository.findByTokenHash(refreshToken)).isEmpty();
        RefreshToken persisted = refreshTokenRepository
                .findByTokenHash(RefreshTokenService.hashToken(refreshToken))
                .orElseThrow();
        assertThat(persisted.getUser().getId()).isEqualTo(admin.getId());
        assertThat(persisted.getRevokedAt()).isNull();
        assertThat(persisted.getFamilyId()).isNotBlank();
    }

    @Test
    void refreshRotatesTokenAndExpiredJwtCannotAccessProtectedRoutesUntilRefreshed() throws Exception {
        HttpResponse<String> loginResponse = postJson("/auth/login", Map.of(
                "email", adminEmail,
                "password", "Secret123!"
        ));
        assertThat(loginResponse.statusCode()).isEqualTo(200);
        JsonNode loginBody = objectMapper.readTree(loginResponse.body());
        String originalRefresh = loginBody.get("refreshToken").asText();

        // Confirm an expired access token gets 401 on protected endpoint
        String expiredAccessToken = buildExpiredJwt(admin);
        HttpResponse<String> expiredCall = getWithToken("/accounts", expiredAccessToken);
        assertThat(expiredCall.statusCode()).isEqualTo(401);

        // Rotate refresh token
        HttpResponse<String> refreshResponse = postJson("/auth/refresh", Map.of(
                "refreshToken", originalRefresh
        ));
        assertThat(refreshResponse.statusCode()).isEqualTo(200);
        JsonNode rotatedBody = objectMapper.readTree(refreshResponse.body());
        String newAccess = rotatedBody.get("token").asText();
        String newRefresh = rotatedBody.get("refreshToken").asText();

        assertThat(newAccess).isNotBlank();
        assertThat(newRefresh).isNotBlank().isNotEqualTo(originalRefresh);

        // New access token works on protected endpoint
        HttpResponse<String> validCall = getWithToken("/accounts", newAccess);
        assertThat(validCall.statusCode()).isEqualTo(200);

        // Original refresh token is marked revoked & linked to replacement hash
        RefreshToken revokedOriginal = refreshTokenRepository
                .findByTokenHash(RefreshTokenService.hashToken(originalRefresh))
                .orElseThrow();
        assertThat(revokedOriginal.getRevokedAt()).isNotNull();
        assertThat(revokedOriginal.getReplacedByTokenHash())
                .isEqualTo(RefreshTokenService.hashToken(newRefresh));
    }

    @Test
    void replayingRevokedRefreshTokenRevokesEntireTokenFamily() throws Exception {
        HttpResponse<String> loginResponse = postJson("/auth/login", Map.of(
                "email", adminEmail,
                "password", "Secret123!"
        ));
        String refresh1 = objectMapper.readTree(loginResponse.body()).get("refreshToken").asText();

        HttpResponse<String> rotationResponse = postJson("/auth/refresh", Map.of(
                "refreshToken", refresh1
        ));
        assertThat(rotationResponse.statusCode()).isEqualTo(200);
        String refresh2 = objectMapper.readTree(rotationResponse.body()).get("refreshToken").asText();

        // Attacker replays already-rotated refresh1 -> must fail with 401 AND revoke active family (refresh2)
        HttpResponse<String> replayResponse = postJson("/auth/refresh", Map.of(
                "refreshToken", refresh1
        ));
        assertThat(replayResponse.statusCode()).isEqualTo(401);

        // Legitimate refresh2 must now also be revoked and rejected with 401
        HttpResponse<String> followUpResponse = postJson("/auth/refresh", Map.of(
                "refreshToken", refresh2
        ));
        assertThat(followUpResponse.statusCode()).isEqualTo(401);

        List<RefreshToken> familyTokens = refreshTokenRepository.findAllByUserId(admin.getId());
        assertThat(familyTokens).isNotEmpty();
        assertThat(familyTokens).allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
    }

    @Test
    void expiredOrUnknownRefreshTokenReturnsUnauthorized() throws Exception {
        HttpResponse<String> unknownRes = postJson("/auth/refresh", Map.of(
                "refreshToken", "non-existent-opaque-token"
        ));
        assertThat(unknownRes.statusCode()).isEqualTo(401);

        // Persist an expired refresh token directly
        String expiredRaw = "expired-raw-token-" + UUID.randomUUID();
        RefreshToken expiredEntity = new RefreshToken();
        expiredEntity.setUser(admin);
        expiredEntity.setTokenHash(RefreshTokenService.hashToken(expiredRaw));
        expiredEntity.setFamilyId(UUID.randomUUID().toString());
        expiredEntity.setExpiresAt(Instant.now().minusSeconds(60));
        refreshTokenRepository.save(expiredEntity);

        HttpResponse<String> expiredRes = postJson("/auth/refresh", Map.of(
                "refreshToken", expiredRaw
        ));
        assertThat(expiredRes.statusCode()).isEqualTo(401);

        RefreshToken reloaded = refreshTokenRepository
                .findByTokenHash(RefreshTokenService.hashToken(expiredRaw))
                .orElseThrow();
        assertThat(reloaded.getRevokedAt()).isNotNull();
    }

    @Test
    void logoutRevokesRefreshTokenEvenWhenAccessTokenIsMissingOrExpired() throws Exception {
        HttpResponse<String> loginResponse = postJson("/auth/login", Map.of(
                "email", adminEmail,
                "password", "Secret123!"
        ));
        String refreshToken = objectMapper.readTree(loginResponse.body()).get("refreshToken").asText();

        String expiredAccessToken = buildExpiredJwt(admin);
        HttpRequest logoutRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/auth/logout"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + expiredAccessToken)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "refreshToken", refreshToken
                ))))
                .build();

        HttpResponse<String> logoutRes = httpClient.send(logoutRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(logoutRes.statusCode()).isEqualTo(204);

        HttpResponse<String> afterLogoutRefresh = postJson("/auth/refresh", Map.of(
                "refreshToken", refreshToken
        ));
        assertThat(afterLogoutRefresh.statusCode()).isEqualTo(401);
    }

    @Test
    void logoutWithValidJwtAndEmptyBodyRevokesAllActiveTokensForUser() throws Exception {
        HttpResponse<String> loginResponse = postJson("/auth/login", Map.of(
                "email", adminEmail,
                "password", "Secret123!"
        ));
        JsonNode loginBody = objectMapper.readTree(loginResponse.body());
        String accessToken = loginBody.get("token").asText();
        String refreshToken = loginBody.get("refreshToken").asText();

        HttpRequest logoutRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/auth/logout"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> logoutRes = httpClient.send(logoutRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(logoutRes.statusCode()).isEqualTo(204);

        HttpResponse<String> afterLogoutRefresh = postJson("/auth/refresh", Map.of(
                "refreshToken", refreshToken
        ));
        assertThat(afterLogoutRefresh.statusCode()).isEqualTo(401);
    }

    @Test
    void refreshRejectsBlankTokenWithBadRequest() throws Exception {
        HttpResponse<String> badRes = postJson("/auth/refresh", Map.of(
                "refreshToken", "   "
        ));
        assertThat(badRes.statusCode()).isEqualTo(400);
    }

    private String buildExpiredJwt(LedgerUser user) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Instant expiredAt = Instant.now().minusSeconds(120);
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuer(jwtIssuer)
                .issuedAt(Date.from(expiredAt.minusSeconds(60)))
                .expiration(Date.from(expiredAt))
                .claim("email", user.getEmail())
                .claim("organizationId", user.getOrganization().getId())
                .claim("role", user.getRole().name())
                .signWith(key)
                .compact();
    }

    private HttpResponse<String> postJson(String path, Map<String, String> payload)
            throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithToken(String path, String token)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

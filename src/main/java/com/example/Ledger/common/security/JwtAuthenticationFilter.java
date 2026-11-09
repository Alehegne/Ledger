package com.example.Ledger.common.security;

import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.enums.UserStatus;
import com.example.Ledger.common.response.ApiErrorResponse;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.persistence.PersistenceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/auth/login",
            "/auth/refresh",
            "/invitations/accept",
            "/actuator/health"
    );

    private final JwtService jwtService;
    private final LedgerUserRepository userRepository;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            LedgerUserRepository userRepository,
            ObjectMapper objectMapper
    ) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!StringUtils.hasText(path)) {
            path = request.getRequestURI();
        }
        return PUBLIC_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorizationHeader) && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring(7).trim();
            UserPrincipal tokenPrincipal;
            try {
                Claims claims = jwtService.parseClaims(token);
                tokenPrincipal = jwtService.toPrincipal(claims);
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            Optional<LedgerUser> userOpt;
            try {
                userOpt = userRepository.findWithOrganizationById(tokenPrincipal.getUserId());
            } catch (DataAccessException | TransactionException | PersistenceException ex) {
                SecurityContextHolder.clearContext();
                log.error(
                        "Database failure while verifying authentication state for userId={}",
                        tokenPrincipal.getUserId(),
                        ex
                );
                writeErrorResponse(
                        response,
                        request,
                        HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "SERVICE_UNAVAILABLE",
                        "Authentication verification is temporarily unavailable"
                );
                return;
            }

            if (userOpt.isEmpty()) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            LedgerUser user = userOpt.get();
            if (user.getStatus() == UserStatus.DISABLED) {
                SecurityContextHolder.clearContext();
                writeErrorResponse(
                        response,
                        request,
                        HttpServletResponse.SC_FORBIDDEN,
                        "USER_DISABLED",
                        "User account has been disabled"
                );
                return;
            }

            Organization org = user.getOrganization();
            if (org == null) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            if (org.getKind() == OrganizationKind.TENANT && org.getStatus() == OrganizationStatus.SUSPENDED) {
                SecurityContextHolder.clearContext();
                writeErrorResponse(
                        response,
                        request,
                        HttpServletResponse.SC_FORBIDDEN,
                        "ORGANIZATION_SUSPENDED",
                        "Organization has been suspended"
                );
                return;
            }

            if (org.getKind() == OrganizationKind.TENANT && org.getStatus() == OrganizationStatus.PENDING) {
                SecurityContextHolder.clearContext();
                writeErrorResponse(
                        response,
                        request,
                        HttpServletResponse.SC_FORBIDDEN,
                        "ORGANIZATION_PENDING",
                        "Organization onboarding has not been completed"
                );
                return;
            }

            UserPrincipal livePrincipal = new UserPrincipal(
                    user.getId(),
                    user.getEmail(),
                    org.getId(),
                    user.getRole()
            );
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    livePrincipal,
                    null,
                    livePrincipal.getAuthorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private void writeErrorResponse(
            HttpServletResponse response,
            HttpServletRequest request,
            int status,
            String code,
            String message
    ) throws IOException {
        ApiErrorResponse payload = new ApiErrorResponse(
                Instant.now(),
                status,
                code,
                message,
                request.getRequestURI()
        );
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), payload);
    }
}

package com.example.Ledger.common.security;

import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.enums.UserStatus;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jsonwebtoken.Claims;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterFailureTests {

    private JwtService jwtService;
    private LedgerUserRepository userRepository;
    private ObjectMapper objectMapper;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userRepository = mock(LedgerUserRepository.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new JwtAuthenticationFilter(jwtService, userRepository, objectMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest authorizedRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setServletPath(uri);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid.jwt.token");
        Claims claims = mock(Claims.class);
        when(jwtService.parseClaims("valid.jwt.token")).thenReturn(claims);
        when(jwtService.toPrincipal(claims)).thenReturn(new UserPrincipal(42L, "user@tenant.test", 7L, Role.ADMIN));
        return request;
    }

    @Test
    void returns503ServiceUnavailableOnDataAccessFailureWithoutBlockingAs401Or403() throws Exception {
        MockHttpServletRequest request = authorizedRequest("/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = mock(MockFilterChain.class);

        when(userRepository.findWithOrganizationById(42L))
                .thenThrow(new QueryTimeoutException("PostgreSQL read timed out"));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(json.get("status").asInt()).isEqualTo(503);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void returns503ServiceUnavailableOnPersistenceExceptionWithoutAllowingAccess() throws Exception {
        MockHttpServletRequest request = authorizedRequest("/journal-entries");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = mock(MockFilterChain.class);

        when(userRepository.findWithOrganizationById(42L))
                .thenThrow(new PersistenceException("EntityManager connection closed"));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void returnsStructured403UserDisabledForDisabledUser() throws Exception {
        MockHttpServletRequest request = authorizedRequest("/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = mock(MockFilterChain.class);

        Organization org = new Organization();
        ReflectionTestUtils.setField(org, "id", 7L);
        org.setKind(OrganizationKind.TENANT);
        org.setStatus(OrganizationStatus.ACTIVE);

        LedgerUser disabledUser = new LedgerUser();
        ReflectionTestUtils.setField(disabledUser, "id", 42L);
        disabledUser.setOrganization(org);
        disabledUser.setEmail("user@tenant.test");
        disabledUser.setRole(Role.ADMIN);
        disabledUser.setStatus(UserStatus.DISABLED);

        when(userRepository.findWithOrganizationById(42L)).thenReturn(Optional.of(disabledUser));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("code").asText()).isEqualTo("USER_DISABLED");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void returnsStructured403OrganizationSuspendedAndPending() throws Exception {
        MockHttpServletRequest suspendedReq = authorizedRequest("/accounts");
        MockHttpServletResponse suspendedRes = new MockHttpServletResponse();
        MockFilterChain chain = mock(MockFilterChain.class);

        Organization suspendedOrg = new Organization();
        ReflectionTestUtils.setField(suspendedOrg, "id", 7L);
        suspendedOrg.setKind(OrganizationKind.TENANT);
        suspendedOrg.setStatus(OrganizationStatus.SUSPENDED);

        LedgerUser activeUser = new LedgerUser();
        ReflectionTestUtils.setField(activeUser, "id", 42L);
        activeUser.setOrganization(suspendedOrg);
        activeUser.setEmail("user@tenant.test");
        activeUser.setRole(Role.ADMIN);
        activeUser.setStatus(UserStatus.ACTIVE);

        when(userRepository.findWithOrganizationById(42L)).thenReturn(Optional.of(activeUser));

        filter.doFilter(suspendedReq, suspendedRes, chain);

        assertThat(suspendedRes.getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(suspendedRes.getContentAsString()).get("code").asText())
                .isEqualTo("ORGANIZATION_SUSPENDED");
        verifyNoInteractions(chain);

        suspendedOrg.setStatus(OrganizationStatus.PENDING);
        MockHttpServletRequest pendingReq = authorizedRequest("/accounts");
        MockHttpServletResponse pendingRes = new MockHttpServletResponse();

        filter.doFilter(pendingReq, pendingRes, chain);

        assertThat(pendingRes.getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(pendingRes.getContentAsString()).get("code").asText())
                .isEqualTo("ORGANIZATION_PENDING");
        verifyNoInteractions(chain);
    }

    @Test
    void proceedsWhenUserAndTenantOrganizationAreActive() throws Exception {
        MockHttpServletRequest request = authorizedRequest("/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = mock(MockFilterChain.class);

        Organization activeOrg = new Organization();
        ReflectionTestUtils.setField(activeOrg, "id", 7L);
        activeOrg.setKind(OrganizationKind.TENANT);
        activeOrg.setStatus(OrganizationStatus.ACTIVE);

        LedgerUser activeUser = new LedgerUser();
        ReflectionTestUtils.setField(activeUser, "id", 42L);
        activeUser.setOrganization(activeOrg);
        activeUser.setEmail("user@tenant.test");
        activeUser.setRole(Role.ADMIN);
        activeUser.setStatus(UserStatus.ACTIVE);

        when(userRepository.findWithOrganizationById(42L)).thenReturn(Optional.of(activeUser));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }
}

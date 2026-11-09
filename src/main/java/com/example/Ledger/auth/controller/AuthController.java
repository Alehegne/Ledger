package com.example.Ledger.auth.controller;

import com.example.Ledger.auth.dto.LoginRequest;
import com.example.Ledger.auth.dto.LoginResponse;
import com.example.Ledger.auth.dto.LogoutRequest;
import com.example.Ledger.auth.dto.RefreshTokenRequest;
import com.example.Ledger.auth.service.AuthService;
import com.example.Ledger.auth.service.RefreshTokenService;
import com.example.Ledger.common.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(AuthService authService, RefreshTokenService refreshTokenService) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/auth/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return refreshTokenService.rotateRefreshToken(request.refreshToken());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) LogoutRequest request,
            Authentication authentication
    ) {
        Long authenticatedUserId = authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                ? principal.getUserId()
                : null;
        refreshTokenService.revokeRefreshToken(
                request == null ? null : request.refreshToken(),
                authenticatedUserId
        );
        return ResponseEntity.noContent().build();
    }
}

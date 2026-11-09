package com.example.Ledger.admin.controller;

import com.example.Ledger.admin.dto.AdminCreateOrganizationRequest;
import com.example.Ledger.admin.dto.AdminCreateOrganizationResponse;
import com.example.Ledger.admin.dto.PlatformAuditLogPageResponse;
import com.example.Ledger.admin.dto.PlatformOrganizationDetailResponse;
import com.example.Ledger.admin.dto.PlatformOrganizationPageResponse;
import com.example.Ledger.admin.dto.PlatformOrganizationUserResponse;
import com.example.Ledger.admin.dto.PlatformOverviewResponse;
import com.example.Ledger.admin.dto.RecoveryInvitationRequest;
import com.example.Ledger.admin.dto.RecoveryInvitationResponse;
import com.example.Ledger.admin.dto.UpdateAdminOrganizationRequest;
import com.example.Ledger.admin.dto.UpdateOrganizationStatusRequest;
import com.example.Ledger.admin.dto.UpdateUserStatusRequest;
import com.example.Ledger.admin.service.PlatformAdminService;
import com.example.Ledger.admin.service.PlatformAuditQueryService;
import com.example.Ledger.common.enums.OrganizationStatus;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.organization.service.OrganizationProvisioningService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminPlatformController {

    private final PlatformAdminService platformAdminService;
    private final OrganizationProvisioningService organizationProvisioningService;
    private final PlatformAuditQueryService platformAuditQueryService;
    private final CurrentUserProvider currentUserProvider;

    public AdminPlatformController(
            PlatformAdminService platformAdminService,
            OrganizationProvisioningService organizationProvisioningService,
            PlatformAuditQueryService platformAuditQueryService,
            CurrentUserProvider currentUserProvider
    ) {
        this.platformAdminService = platformAdminService;
        this.organizationProvisioningService = organizationProvisioningService;
        this.platformAuditQueryService = platformAuditQueryService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/overview")
    public PlatformOverviewResponse getOverview() {
        return platformAdminService.getOverview();
    }

    @GetMapping("/organizations")
    public PlatformOrganizationPageResponse listOrganizations(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) OrganizationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return platformAdminService.listOrganizations(search, status, page, size);
    }

    @PostMapping("/organizations")
    public ResponseEntity<AdminCreateOrganizationResponse> createOrganization(
            @Valid @RequestBody AdminCreateOrganizationRequest request
    ) {
        Long actorUserId = currentUserProvider.getCurrentUserId();
        AdminCreateOrganizationResponse response = organizationProvisioningService.provisionTenantOrganization(request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/organizations/{organizationId}")
    public PlatformOrganizationDetailResponse getOrganizationDetail(@PathVariable Long organizationId) {
        return platformAdminService.getOrganizationDetail(organizationId);
    }

    @PatchMapping("/organizations/{organizationId}")
    public PlatformOrganizationDetailResponse updateOrganization(
            @PathVariable Long organizationId,
            @Valid @RequestBody UpdateAdminOrganizationRequest request
    ) {
        Long actorUserId = currentUserProvider.getCurrentUserId();
        return platformAdminService.updateOrganization(organizationId, request, actorUserId);
    }

    @PatchMapping("/organizations/{organizationId}/status")
    public PlatformOrganizationDetailResponse updateOrganizationStatus(
            @PathVariable Long organizationId,
            @Valid @RequestBody UpdateOrganizationStatusRequest request
    ) {
        Long actorUserId = currentUserProvider.getCurrentUserId();
        return platformAdminService.updateOrganizationStatus(organizationId, request, actorUserId);
    }

    @GetMapping("/organizations/{organizationId}/users")
    public List<PlatformOrganizationUserResponse> listOrganizationUsers(@PathVariable Long organizationId) {
        return platformAdminService.listOrganizationUsers(organizationId);
    }

    @PatchMapping("/organizations/{organizationId}/users/{userId}/status")
    public PlatformOrganizationUserResponse updateOrganizationUserStatus(
            @PathVariable Long organizationId,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        Long actorUserId = currentUserProvider.getCurrentUserId();
        return platformAdminService.updateOrganizationUserStatus(organizationId, userId, request, actorUserId);
    }

    @PostMapping("/organizations/{organizationId}/recovery-invitation")
    public ResponseEntity<RecoveryInvitationResponse> createRecoveryInvitation(
            @PathVariable Long organizationId,
            @Valid @RequestBody RecoveryInvitationRequest request
    ) {
        Long actorUserId = currentUserProvider.getCurrentUserId();
        RecoveryInvitationResponse response = platformAdminService.createRecoveryInvitation(organizationId, request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/audit-logs")
    public PlatformAuditLogPageResponse getPlatformAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long targetOrganizationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return platformAuditQueryService.queryAuditLogs(action, targetOrganizationId, from, to, page, size);
    }
}

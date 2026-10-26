package com.example.Ledger.organization.controller;

import com.example.Ledger.organization.dto.OrganizationRequest;
import com.example.Ledger.organization.dto.OrganizationResponse;
import com.example.Ledger.organization.dto.OrganizationSettingsUpdateRequest;
import com.example.Ledger.organization.service.OrganizationService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @PostMapping("/organizations")
    @PreAuthorize("hasRole('ADMIN')")
    public OrganizationResponse create(@Valid @RequestBody OrganizationRequest request) {
        return organizationService.createOrganization(request);
    }

    @GetMapping("/organizations/me")
    @PreAuthorize("hasRole('ADMIN')")
    public OrganizationResponse getCurrentSettings() {
        return organizationService.getCurrentOrganizationSettings();
    }

    @PatchMapping("/organizations/me")
    @PreAuthorize("hasRole('ADMIN')")
    public OrganizationResponse updateCurrentSettings(@Valid @RequestBody OrganizationSettingsUpdateRequest request) {
        return organizationService.updateCurrentOrganizationSettings(request);
    }
}

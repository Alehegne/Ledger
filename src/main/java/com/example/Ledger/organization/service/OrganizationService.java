package com.example.Ledger.organization.service;

import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.organization.dto.OrganizationRequest;
import com.example.Ledger.organization.dto.OrganizationResponse;
import com.example.Ledger.organization.dto.OrganizationSettingsUpdateRequest;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final CurrentUserProvider currentUserProvider;

    public OrganizationService(OrganizationRepository organizationRepository, CurrentUserProvider currentUserProvider) {
        this.organizationRepository = organizationRepository;
        this.currentUserProvider = currentUserProvider;
    }

    public OrganizationResponse createOrganization(OrganizationRequest request) {
        currentUserProvider.requireRole(Role.ADMIN);

        String cleanName = request.name().trim();
        String cleanCurrency = request.baseCurrency().trim().toUpperCase();

        if (organizationRepository.findByNameIgnoreCase(cleanName).isPresent()) {
            throw new BusinessException("ORGANIZATION_NAME_EXISTS", "Organization name already exists");
        }

        Organization organization = new Organization();
        organization.setName(cleanName);
        organization.setBaseCurrency(cleanCurrency);
        organization = organizationRepository.save(organization);

        return toResponse(organization);
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getCurrentOrganization() {
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        return toResponse(organization);
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getCurrentOrganizationSettings() {
        currentUserProvider.requireRole(Role.ADMIN);
        return getCurrentOrganization();
    }

    public OrganizationResponse updateCurrentOrganizationSettings(OrganizationSettingsUpdateRequest request) {
        currentUserProvider.requireRole(Role.ADMIN);

        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        if (request == null) {
            return toResponse(organization);
        }

        String nextName = request.name() == null ? null : request.name().trim();
        if (nextName != null && nextName.isBlank()) {
            throw new BusinessException("ORGANIZATION_NAME_REQUIRED", "Organization name is required");
        }

        final Organization currentOrganization = organization;
        if (nextName != null && !nextName.equalsIgnoreCase(currentOrganization.getName())
                && organizationRepository.findByNameIgnoreCase(nextName)
                        .filter(existing -> !existing.getId().equals(currentOrganization.getId()))
                        .isPresent()) {
            throw new BusinessException("ORGANIZATION_NAME_EXISTS", "Organization name already exists");
        }

        String nextCurrency = request.baseCurrency() == null ? null : request.baseCurrency().trim().toUpperCase();
        if (nextCurrency != null && !nextCurrency.matches("^[A-Z]{3}$")) {
            throw new BusinessException("INVALID_CURRENCY", "Currency must be a 3-letter ISO code");
        }

        if (nextName != null) {
            organization.setName(nextName);
        }
        if (nextCurrency != null) {
            organization.setBaseCurrency(nextCurrency);
        }

        organization = organizationRepository.save(organization);
        return toResponse(organization);
    }

    private OrganizationResponse toResponse(Organization organization) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.getBaseCurrency(),
                organization.getCreatedAt()
        );
    }
}

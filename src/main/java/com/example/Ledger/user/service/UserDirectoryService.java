package com.example.Ledger.user.service;

import com.example.Ledger.audit.service.AuditLogService;
import com.example.Ledger.common.enums.AuditLogAction;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.user.dto.OrganizationUserResponse;
import com.example.Ledger.user.model.LedgerUser;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class UserDirectoryService {

    private static final String ACTIVE_INVITATION_STATUS = "ACTIVE";

    private final LedgerUserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLogService auditLogService;

    public UserDirectoryService(
            LedgerUserRepository userRepository,
            CurrentUserProvider currentUserProvider,
            AuditLogService auditLogService
    ) {
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.auditLogService = auditLogService;
    }

    public List<OrganizationUserResponse> listOrganizationUsers() {
        currentUserProvider.requireRole(Role.ADMIN);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        return userRepository.findByOrganizationIdOrderByFullNameAscEmailAsc(organizationId)
                .stream()
                .map(user -> new OrganizationUserResponse(
                        user.getId(),
                        user.getEmail(),
                        user.getFullName(),
                        user.getRole(),
                        ACTIVE_INVITATION_STATUS
                ))
                .toList();
    }

    @Transactional
    public OrganizationUserResponse updateUserRole(Long userId, Role newRole) {
        currentUserProvider.requireRole(Role.ADMIN);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        LedgerUser user = userRepository.findByIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.getRole() == Role.ADMIN && newRole != Role.ADMIN) {
            long adminCount = userRepository.countByOrganizationIdAndRole(organizationId, Role.ADMIN);
            if (adminCount <= 1) {
                throw new BusinessException("LAST_ADMIN_PROTECTION", "Cannot demote the last administrator in the organization");
            }
        }

        Role oldRole = user.getRole();
        user.setRole(newRole);
        user = userRepository.save(user);

        auditLogService.recordAudit(
                AuditLogAction.USER_ROLE_UPDATED,
                "USER",
                user.getId().toString(),
                Map.of(
                        "email", user.getEmail(),
                        "previousRole", oldRole.name(),
                        "newRole", newRole.name()
                )
        );

        return new OrganizationUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                ACTIVE_INVITATION_STATUS
        );
    }
}

package com.example.Ledger.user.service;

import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.user.dto.OrganizationUserResponse;
import com.example.Ledger.user.repository.LedgerUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class UserDirectoryService {

    private static final String ACTIVE_INVITATION_STATUS = "ACTIVE";

    private final LedgerUserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;

    public UserDirectoryService(LedgerUserRepository userRepository, CurrentUserProvider currentUserProvider) {
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
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
}

package com.example.Ledger.common.security;

import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.exception.ForbiddenException;
import com.example.Ledger.common.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public UserPrincipal getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new UnauthorizedException("Authentication required");
        }
        return principal;
    }

    public Long getCurrentUserId() {
        return getCurrentUser().getUserId();
    }

    public Long getCurrentOrganizationId() {
        return getCurrentUser().getOrganizationId();
    }

    public void requireRole(Role... roles) {
        UserPrincipal current = getCurrentUser();
        Role currentRole = current.getRole();
        if (currentRole == null) {
            throw new ForbiddenException("You do not have permission to perform this action");
        }
        for (Role role : roles) {
            if (currentRole == role) {
                return;
            }
        }
        throw new ForbiddenException("You do not have permission to perform this action");
    }
}

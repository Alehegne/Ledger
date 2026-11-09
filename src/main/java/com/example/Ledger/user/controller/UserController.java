package com.example.Ledger.user.controller;

import com.example.Ledger.user.dto.OrganizationUserResponse;
import com.example.Ledger.user.dto.UpdateUserRoleRequest;
import com.example.Ledger.user.service.UserDirectoryService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class UserController {

    private final UserDirectoryService userDirectoryService;

    public UserController(UserDirectoryService userDirectoryService) {
        this.userDirectoryService = userDirectoryService;
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<OrganizationUserResponse> listOrganizationUsers() {
        return userDirectoryService.listOrganizationUsers();
    }

    @PatchMapping("/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public OrganizationUserResponse updateUserRole(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserRoleRequest request
    ) {
        return userDirectoryService.updateUserRole(userId, request.role());
    }
}

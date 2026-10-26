package com.example.Ledger.user.controller;

import com.example.Ledger.user.dto.OrganizationUserResponse;
import com.example.Ledger.user.service.UserDirectoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
}

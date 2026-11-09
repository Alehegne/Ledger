package com.example.Ledger.invitation.controller;

import com.example.Ledger.invitation.dto.AcceptInvitationRequest;
import com.example.Ledger.invitation.dto.InvitationRequest;
import com.example.Ledger.invitation.dto.InvitationResponse;
import com.example.Ledger.invitation.service.InvitationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @PostMapping("/users/invite")
    @PreAuthorize("hasRole('ADMIN')")
    public InvitationResponse invite(@Valid @RequestBody InvitationRequest request) {
        return invitationService.createInvitation(request);
    }

    @GetMapping("/invitations")
    @PreAuthorize("hasRole('ADMIN')")
    public List<InvitationResponse> listPending() {
        return invitationService.listPendingInvitations();
    }

    @DeleteMapping("/invitations/{invitationId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> revoke(@PathVariable Long invitationId) {
        invitationService.revokeInvitation(invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<Map<String, String>> accept(@Valid @RequestBody AcceptInvitationRequest request) {
        return ResponseEntity.ok(Map.of("message", invitationService.acceptInvitation(request)));
    }
}

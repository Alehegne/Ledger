package com.example.Ledger.invitation.repository;

import com.example.Ledger.invitation.model.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    List<Invitation> findByInvitedEmailIgnoreCase(String invitedEmail);

    Optional<Invitation> findByIdAndOrganizationId(Long id, Long organizationId);
}
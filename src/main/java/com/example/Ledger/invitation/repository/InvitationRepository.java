package com.example.Ledger.invitation.repository;

import com.example.Ledger.invitation.model.Invitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    @EntityGraph(attributePaths = {"organization", "targetUser"})
    List<Invitation> findByInvitedEmailIgnoreCase(String invitedEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i join fetch i.organization left join fetch i.targetUser where i.id = :id")
    Optional<Invitation> findLockedById(@Param("id") Long id);

    Optional<Invitation> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Invitation> findByOrganizationIdAndAcceptedAtIsNullAndSupersededAtIsNullOrderByCreatedAtDesc(Long organizationId);

    long countByOrganizationIdAndAcceptedAtIsNullAndSupersededAtIsNull(Long organizationId);

    @Query("""
            select i.organization.id, count(i)
            from Invitation i
            where i.organization.id in :orgIds
              and i.acceptedAt is null
              and i.supersededAt is null
            group by i.organization.id
            """)
    List<Object[]> summarizeOpenInvitationsByOrganizationIds(@Param("orgIds") Collection<Long> orgIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Invitation i
            set i.supersededAt = :now, i.updatedAt = :now
            where i.organization.id = :organizationId
              and lower(i.invitedEmail) = lower(:email)
              and i.acceptedAt is null
              and i.supersededAt is null
            """)
    int supersedeOpenByOrganizationIdAndEmail(
            @Param("organizationId") Long organizationId,
            @Param("email") String email,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Invitation i
            set i.supersededAt = :now, i.updatedAt = :now
            where i.organization.id = :organizationId
              and i.targetUser.id = :targetUserId
              and i.acceptedAt is null
              and i.supersededAt is null
            """)
    int supersedeOpenByOrganizationIdAndTargetUserId(
            @Param("organizationId") Long organizationId,
            @Param("targetUserId") Long targetUserId,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Invitation i
            set i.supersededAt = :now, i.updatedAt = :now
            where i.organization.id = :organizationId
              and i.role = com.example.Ledger.common.enums.Role.ADMIN
              and i.acceptedAt is null
              and i.supersededAt is null
            """)
    int supersedeOpenAdminInvitesByOrganizationId(
            @Param("organizationId") Long organizationId,
            @Param("now") Instant now
    );
}

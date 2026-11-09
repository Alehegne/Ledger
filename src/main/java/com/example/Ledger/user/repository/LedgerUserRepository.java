package com.example.Ledger.user.repository;

import com.example.Ledger.common.enums.OrganizationKind;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.enums.UserStatus;
import com.example.Ledger.user.model.LedgerUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LedgerUserRepository extends JpaRepository<LedgerUser, Long> {

    Optional<LedgerUser> findTopByEmailIgnoreCaseOrderByIdDesc(String email);

    @Query("select u from LedgerUser u join fetch u.organization o where u.id = :id")
    Optional<LedgerUser> findWithOrganizationById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from LedgerUser u join fetch u.organization o where u.id = :id")
    Optional<LedgerUser> findLockedWithOrganizationById(@Param("id") Long id);

    @Query("select u from LedgerUser u join fetch u.organization o where lower(u.email) = lower(:email)")
    List<LedgerUser> findAllWithOrganizationByEmailIgnoreCase(@Param("email") String email);

    List<LedgerUser> findAllByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<LedgerUser> findByOrganizationIdAndEmailIgnoreCase(Long organizationId, String email);

    boolean existsByOrganizationIdAndEmailIgnoreCase(Long organizationId, String email);

    Optional<LedgerUser> findByIdAndOrganizationId(Long id, Long organizationId);

    long countByOrganizationId(Long organizationId);

    long countByOrganizationIdAndRole(Long organizationId, Role role);

    long countByOrganizationIdAndStatus(Long organizationId, UserStatus status);

    long countByOrganizationIdAndRoleAndStatus(Long organizationId, Role role, UserStatus status);

    @Query("select count(u) from LedgerUser u where u.organization.kind = :kind")
    long countByOrganizationKind(@Param("kind") OrganizationKind kind);

    @Query("select count(u) from LedgerUser u where u.organization.kind = :kind and u.status = :status")
    long countByOrganizationKindAndStatus(@Param("kind") OrganizationKind kind, @Param("status") UserStatus status);

    @Query("""
            select u.organization.id,
                   count(u),
                   coalesce(sum(case when u.role = com.example.Ledger.common.enums.Role.ADMIN and u.status = com.example.Ledger.common.enums.UserStatus.ACTIVE then 1L else 0L end), 0L),
                   coalesce(sum(case when u.status = com.example.Ledger.common.enums.UserStatus.DISABLED then 1L else 0L end), 0L),
                   max(u.updatedAt)
            from LedgerUser u
            where u.organization.id in :orgIds
            group by u.organization.id
            """)
    List<Object[]> summarizeUsersByOrganizationIds(@Param("orgIds") Collection<Long> orgIds);

    List<LedgerUser> findByOrganizationIdOrderByFullNameAscEmailAsc(Long organizationId);
}

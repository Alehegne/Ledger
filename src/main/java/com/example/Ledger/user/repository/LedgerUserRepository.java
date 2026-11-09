package com.example.Ledger.user.repository;

import com.example.Ledger.common.enums.Role;
import com.example.Ledger.user.model.LedgerUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LedgerUserRepository extends JpaRepository<LedgerUser, Long> {

    Optional<LedgerUser> findTopByEmailIgnoreCaseOrderByIdDesc(String email);

    @Query("select u from LedgerUser u join fetch u.organization o where lower(u.email) = lower(:email)")
    List<LedgerUser> findAllWithOrganizationByEmailIgnoreCase(@Param("email") String email);

    List<LedgerUser> findAllByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<LedgerUser> findByOrganizationIdAndEmailIgnoreCase(Long organizationId, String email);

    boolean existsByOrganizationIdAndEmailIgnoreCase(Long organizationId, String email);

    Optional<LedgerUser> findByIdAndOrganizationId(Long id, Long organizationId);

    long countByOrganizationIdAndRole(Long organizationId, Role role);

    List<LedgerUser> findByOrganizationIdOrderByFullNameAscEmailAsc(Long organizationId);
}
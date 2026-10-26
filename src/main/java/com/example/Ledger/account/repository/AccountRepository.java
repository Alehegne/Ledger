package com.example.Ledger.account.repository;

import com.example.Ledger.account.model.Account;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByOrganizationIdOrderByAccountCodeAsc(Long organizationId);

    Optional<Account> findByIdAndOrganizationId(Long id, Long organizationId);

        @Query("""
            select a
            from Account a
            where a.id in :ids
              and a.organization.id = :organizationId
            """)
        List<Account> findAllByIdInAndOrganizationId(
            @Param("ids") Collection<Long> ids,
            @Param("organizationId") Long organizationId
        );

    boolean existsByOrganizationIdAndAccountCodeIgnoreCase(Long organizationId, String accountCode);

    boolean existsByOrganizationIdAndAccountCodeIgnoreCaseAndIdNot(Long organizationId, String accountCode, Long id);
}
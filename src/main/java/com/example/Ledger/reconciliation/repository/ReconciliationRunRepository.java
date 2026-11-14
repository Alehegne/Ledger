package com.example.Ledger.reconciliation.repository;

import com.example.Ledger.reconciliation.model.ReconciliationRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    @EntityGraph(attributePaths = {"triggeredByUser", "organization"})
    Page<ReconciliationRun> findByOrganizationIdOrderByCreatedAtDescIdDesc(Long organizationId, Pageable pageable);

    @EntityGraph(attributePaths = {"triggeredByUser", "organization"})
    Optional<ReconciliationRun> findByIdAndOrganizationId(Long id, Long organizationId);

    Optional<ReconciliationRun> findTopByOrderByCreatedAtDescIdDesc();
}

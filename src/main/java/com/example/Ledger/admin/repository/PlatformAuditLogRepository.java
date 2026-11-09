package com.example.Ledger.admin.repository;

import com.example.Ledger.admin.model.PlatformAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface PlatformAuditLogRepository extends JpaRepository<PlatformAuditLog, Long>, JpaSpecificationExecutor<PlatformAuditLog> {

    @Override
    @EntityGraph(attributePaths = {"actorUser", "targetOrganization", "targetUser"})
    Page<PlatformAuditLog> findAll(Specification<PlatformAuditLog> spec, Pageable pageable);

    @Query("select max(p.createdAt) from PlatformAuditLog p")
    Optional<Instant> findLatestCreatedAt();
}

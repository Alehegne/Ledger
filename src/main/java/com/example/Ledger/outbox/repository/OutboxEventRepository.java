package com.example.Ledger.outbox.repository;

import com.example.Ledger.outbox.model.OutboxEvent;
import com.example.Ledger.outbox.model.OutboxStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT *
            FROM outbox_event
            WHERE status = 'PENDING'
              AND next_attempt_at <= :now
            ORDER BY next_attempt_at ASC, id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findDuePendingEventsForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    @Query("""
            SELECT e
            FROM OutboxEvent e
            WHERE e.organization.id = :organizationId
              AND (:status IS NULL OR e.status = :status)
            ORDER BY e.createdAt DESC, e.id DESC
            """)
    Page<OutboxEvent> findByOrganizationIdAndOptionalStatus(
            @Param("organizationId") Long organizationId,
            @Param("status") OutboxStatus status,
            Pageable pageable
    );

    Optional<OutboxEvent> findByIdAndOrganizationId(Long id, Long organizationId);
}

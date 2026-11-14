package com.example.Ledger.common.idempotency;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO idempotency_key (
                organization_id,
                idempotency_key,
                endpoint,
                request_hash,
                status,
                locked_at,
                expires_at,
                version,
                created_at,
                updated_at
            ) VALUES (
                :organizationId,
                :idempotencyKey,
                :endpoint,
                :requestHash,
                'IN_PROGRESS',
                :now,
                :expiresAt,
                0,
                :now,
                :now
            )
            ON CONFLICT (organization_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int tryInsertClaim(
            @Param("organizationId") Long organizationId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("endpoint") String endpoint,
            @Param("requestHash") String requestHash,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
            from IdempotencyKeyRecord r
            where r.organizationId = :organizationId
              and r.idempotencyKey = :idempotencyKey
            """)
    Optional<IdempotencyKeyRecord> findByOrganizationIdAndKeyForUpdate(
            @Param("organizationId") Long organizationId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from IdempotencyKeyRecord r where r.id = :id")
    Optional<IdempotencyKeyRecord> findByIdForUpdate(@Param("id") Long id);

    Optional<IdempotencyKeyRecord> findByOrganizationIdAndIdempotencyKey(Long organizationId, String idempotencyKey);

    @Modifying
    @Query("delete from IdempotencyKeyRecord r where r.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}

package com.example.Ledger.common.idempotency;

import com.example.Ledger.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class IdempotencyService {

    public sealed interface ClaimOutcome permits ClaimOutcome.Acquired, ClaimOutcome.Replay {
        record Acquired(Long recordId, Instant claimLockedAt) implements ClaimOutcome {
        }

        record Replay(int statusCode, String responseBody) implements ClaimOutcome {
        }
    }

    private final IdempotencyKeyRepository repository;
    private final Duration ttl;
    private final Duration lockTimeout;

    public IdempotencyService(
            IdempotencyKeyRepository repository,
            @Value("${ledger.idempotency.ttl-hours:24}") long ttlHours,
            @Value("${ledger.idempotency.in-progress-timeout-seconds:30}") long lockTimeoutSeconds
    ) {
        this.repository = repository;
        this.ttl = Duration.ofHours(ttlHours);
        this.lockTimeout = Duration.ofSeconds(lockTimeoutSeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimOutcome claimOrLoad(Long organizationId, String key, String endpoint, String requestHash) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plus(ttl);

        for (int attempt = 0; attempt < 2; attempt++) {
            int inserted = repository.tryInsertClaim(organizationId, key, endpoint, requestHash, now, expiresAt);
            if (inserted == 1) {
                IdempotencyKeyRecord created = repository.findByOrganizationIdAndKeyForUpdate(organizationId, key)
                        .orElseThrow();
                return new ClaimOutcome.Acquired(created.getId(), created.getLockedAt());
            }

            Optional<IdempotencyKeyRecord> maybeExisting =
                    repository.findByOrganizationIdAndKeyForUpdate(organizationId, key);
            if (maybeExisting.isEmpty()) {
                // Record was concurrently purged between INSERT and SELECT FOR UPDATE; retry once.
                continue;
            }

            IdempotencyKeyRecord existing = maybeExisting.get();

            if (!existing.getExpiresAt().isAfter(now)) {
                existing.setEndpoint(endpoint);
                existing.setRequestHash(requestHash);
                existing.setStatus(IdempotencyStatus.IN_PROGRESS);
                existing.setResponseCode(null);
                existing.setResponseBody(null);
                existing.setLockedAt(now);
                existing.setExpiresAt(expiresAt);
                IdempotencyKeyRecord saved = repository.save(existing);
                return new ClaimOutcome.Acquired(saved.getId(), saved.getLockedAt());
            }

            if (!existing.getEndpoint().equals(endpoint) || !existing.getRequestHash().equals(requestHash)) {
                throw new BusinessException(
                        "IDEMPOTENCY_PAYLOAD_MISMATCH",
                        "Idempotency-Key was already used with a different endpoint or request payload"
                );
            }

            if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                return new ClaimOutcome.Replay(
                        existing.getResponseCode() != null ? existing.getResponseCode() : 200,
                        existing.getResponseBody()
                );
            }

            if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS
                    && existing.getLockedAt().plus(lockTimeout).isAfter(now)) {
                throw new BusinessException(
                        "IDEMPOTENCY_KEY_IN_PROGRESS",
                        "A request with this Idempotency-Key is currently in progress"
                );
            }

            // Reclaim FAILED record or stale IN_PROGRESS lock
            existing.setStatus(IdempotencyStatus.IN_PROGRESS);
            existing.setResponseCode(null);
            existing.setResponseBody(null);
            existing.setLockedAt(now);
            existing.setExpiresAt(expiresAt);
            IdempotencyKeyRecord saved = repository.save(existing);
            return new ClaimOutcome.Acquired(saved.getId(), saved.getLockedAt());
        }

        throw new BusinessException(
                "IDEMPOTENCY_KEY_IN_PROGRESS",
                "Unable to acquire idempotency lock; please retry"
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void markCompleted(Long recordId, Instant expectedLockedAt, int statusCode, String responseBody) {
        IdempotencyKeyRecord record = repository.findByIdForUpdate(recordId).orElseThrow();
        if (record.getStatus() != IdempotencyStatus.IN_PROGRESS || !record.getLockedAt().equals(expectedLockedAt)) {
            throw new BusinessException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "Idempotency lock ownership changed before completion"
            );
        }
        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setResponseCode(statusCode);
        record.setResponseBody(responseBody);
        repository.save(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long recordId, Instant expectedLockedAt) {
        repository.findByIdForUpdate(recordId).ifPresent(record -> {
            if (record.getStatus() == IdempotencyStatus.IN_PROGRESS
                    && record.getLockedAt().equals(expectedLockedAt)) {
                record.setStatus(IdempotencyStatus.FAILED);
                repository.save(record);
            }
        });
    }

    @Transactional
    public int purgeExpiredKeys() {
        return repository.deleteExpiredBefore(Instant.now());
    }
}

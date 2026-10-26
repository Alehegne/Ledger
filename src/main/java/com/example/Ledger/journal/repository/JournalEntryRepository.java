package com.example.Ledger.journal.repository;

import com.example.Ledger.journal.model.JournalEntry;
import jakarta.persistence.LockModeType;
import com.example.Ledger.common.enums.JournalEntryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {

		@EntityGraph(attributePaths = {"lines", "lines.account", "createdByUser", "postedByUser"})
		Optional<JournalEntry> findByIdAndOrganizationId(Long id, Long organizationId);

		@EntityGraph(attributePaths = {"lines", "lines.account", "createdByUser", "postedByUser"})
		Page<JournalEntry> findByOrganizationIdOrderByTransactionDateDescIdDesc(Long organizationId, Pageable pageable);

		long countByOrganizationIdAndStatus(Long organizationId, JournalEntryStatus status);

		@Lock(LockModeType.PESSIMISTIC_WRITE)
		@Query("""
						select je
						from JournalEntry je
						where je.id = :id
							and je.organization.id = :organizationId
						""")
		Optional<JournalEntry> findByIdAndOrganizationIdForUpdate(@Param("id") Long id, @Param("organizationId") Long organizationId);

		@Override
		@EntityGraph(attributePaths = {"lines", "lines.account", "createdByUser", "postedByUser"})
		Page<JournalEntry> findAll(Specification<JournalEntry> spec, Pageable pageable);
}
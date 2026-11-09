package com.example.Ledger.journal.repository;

import com.example.Ledger.journal.model.JournalEntry;
import jakarta.persistence.LockModeType;
import com.example.Ledger.common.enums.JournalEntryStatus;
import com.example.Ledger.common.enums.OrganizationKind;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {

		@EntityGraph(attributePaths = {"lines", "lines.account", "createdByUser", "postedByUser", "reversedByEntry", "reversesEntry"})
		Optional<JournalEntry> findByIdAndOrganizationId(Long id, Long organizationId);

		@EntityGraph(attributePaths = {"lines", "lines.account", "createdByUser", "postedByUser", "reversedByEntry", "reversesEntry"})
		Page<JournalEntry> findByOrganizationIdOrderByTransactionDateDescIdDesc(Long organizationId, Pageable pageable);

		long countByOrganizationIdAndStatus(Long organizationId, JournalEntryStatus status);

		@Query("select count(je) from JournalEntry je where je.organization.kind = :kind and je.status = :status")
		long countByOrganizationKindAndStatus(@Param("kind") OrganizationKind kind, @Param("status") JournalEntryStatus status);

		@Query("""
						select je.organization.id,
						       coalesce(sum(case when je.status = com.example.Ledger.common.enums.JournalEntryStatus.POSTED then 1L else 0L end), 0L),
						       coalesce(sum(case when je.status = com.example.Ledger.common.enums.JournalEntryStatus.DRAFT then 1L else 0L end), 0L),
						       max(je.updatedAt)
						from JournalEntry je
						where je.organization.id in :orgIds
						group by je.organization.id
						""")
		List<Object[]> summarizeEntriesByOrganizationIds(@Param("orgIds") Collection<Long> orgIds);

		boolean existsByOrganizationId(Long organizationId);

		@Lock(LockModeType.PESSIMISTIC_WRITE)
		@Query("""
						select je
						from JournalEntry je
						where je.id = :id
							and je.organization.id = :organizationId
						""")
		Optional<JournalEntry> findByIdAndOrganizationIdForUpdate(@Param("id") Long id, @Param("organizationId") Long organizationId);

		@Override
		@EntityGraph(attributePaths = {"lines", "lines.account", "createdByUser", "postedByUser", "reversedByEntry", "reversesEntry"})
		Page<JournalEntry> findAll(Specification<JournalEntry> spec, Pageable pageable);
}
package com.example.Ledger.journal.repository;

import com.example.Ledger.journal.model.JournalLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {

	record PostedAccountTotals(BigDecimal totalDebits, BigDecimal totalCredits) {
	}

	/**
	 * Returns debit and credit totals for an account across all posted entries.
	 */
	@Query("""
					select new com.example.Ledger.journal.repository.JournalLineRepository$PostedAccountTotals(
							coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.DEBIT then jl.amount else 0 end), 0),
							coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.CREDIT then jl.amount else 0 end), 0)
					)
					from JournalLine jl
					join jl.journalEntry je
					where je.organization.id = :organizationId
						and je.status = com.example.Ledger.common.enums.JournalEntryStatus.POSTED
						and jl.account.id = :accountId
					""")
	PostedAccountTotals sumPostedDebitsAndCreditsForAccount(
					@Param("organizationId") Long organizationId,
					@Param("accountId") Long accountId
	);

	/**
	 * Returns debit and credit totals for an account through a specific date (inclusive).
	 */
	@Query("""
					select new com.example.Ledger.journal.repository.JournalLineRepository$PostedAccountTotals(
							coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.DEBIT then jl.amount else 0 end), 0),
							coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.CREDIT then jl.amount else 0 end), 0)
					)
					from JournalLine jl
					join jl.journalEntry je
					where je.organization.id = :organizationId
						and je.status = com.example.Ledger.common.enums.JournalEntryStatus.POSTED
						and jl.account.id = :accountId
						and je.transactionDate <= :throughDate
					""")
	PostedAccountTotals sumPostedDebitsAndCreditsForAccountThroughDate(
					@Param("organizationId") Long organizationId,
					@Param("accountId") Long accountId,
					@Param("throughDate") LocalDate throughDate
	);

	/**
	 * Returns paginated journal lines for a specific account for general ledger reporting.
	 * Results are ordered by transaction date (descending) and journal entry ID (descending) for stable pagination.
	 */
	@Query("""
					select jl
					from JournalLine jl
					join jl.journalEntry je
					where je.organization.id = :organizationId
						and je.status = com.example.Ledger.common.enums.JournalEntryStatus.POSTED
						and jl.account.id = :accountId
						and je.transactionDate >= :fromDate
						and je.transactionDate <= :toDate
					order by je.transactionDate desc, je.id desc, jl.id desc
					""")
	Page<JournalLine> findPostedLinesForAccountInDateRange(
					@Param("organizationId") Long organizationId,
					@Param("accountId") Long accountId,
					@Param("fromDate") LocalDate fromDate,
					@Param("toDate") LocalDate toDate,
					Pageable pageable
	);

	/**
	 * Returns debit and credit totals for an account inside an inclusive date range.
	 */
	@Query("""
					select new com.example.Ledger.journal.repository.JournalLineRepository$PostedAccountTotals(
							coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.DEBIT then jl.amount else 0 end), 0),
							coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.CREDIT then jl.amount else 0 end), 0)
					)
					from JournalLine jl
					join jl.journalEntry je
					where je.organization.id = :organizationId
						and je.status = com.example.Ledger.common.enums.JournalEntryStatus.POSTED
						and jl.account.id = :accountId
						and je.transactionDate >= :fromDate
						and je.transactionDate <= :toDate
					""")
	PostedAccountTotals sumPostedDebitsAndCreditsForAccountInDateRange(
					@Param("organizationId") Long organizationId,
					@Param("accountId") Long accountId,
					@Param("fromDate") LocalDate fromDate,
					@Param("toDate") LocalDate toDate
	);

	/**
	 * Returns debit and credit totals for rows newer than the supplied anchor row,
	 * using the same deterministic ordering as the paginated General Ledger query.
	 */
	@Query("""
					select new com.example.Ledger.journal.repository.JournalLineRepository$PostedAccountTotals(
							coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.DEBIT then jl.amount else 0 end), 0),
							coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.CREDIT then jl.amount else 0 end), 0)
					)
					from JournalLine jl
					join jl.journalEntry je
					where je.organization.id = :organizationId
						and je.status = com.example.Ledger.common.enums.JournalEntryStatus.POSTED
						and jl.account.id = :accountId
						and je.transactionDate >= :fromDate
						and je.transactionDate <= :toDate
						and (
							je.transactionDate > :anchorDate
							or (je.transactionDate = :anchorDate and je.id > :anchorEntryId)
							or (je.transactionDate = :anchorDate and je.id = :anchorEntryId and jl.id > :anchorLineId)
						)
					""")
	PostedAccountTotals sumPostedDebitsAndCreditsForAccountNewerThanLine(
					@Param("organizationId") Long organizationId,
					@Param("accountId") Long accountId,
					@Param("fromDate") LocalDate fromDate,
					@Param("toDate") LocalDate toDate,
					@Param("anchorDate") LocalDate anchorDate,
					@Param("anchorEntryId") Long anchorEntryId,
					@Param("anchorLineId") Long anchorLineId
	);

	/**
	 * Returns all posted journal lines for a specific account up to a given date,
	 * ordered for running balance calculation. Used to calculate running balance before first page.
	 */
	@Query("""
					select jl
					from JournalLine jl
					join jl.journalEntry je
					where je.organization.id = :organizationId
						and je.status = com.example.Ledger.common.enums.JournalEntryStatus.POSTED
						and jl.account.id = :accountId
						and je.transactionDate <= :throughDate
					order by je.transactionDate asc, je.id asc
					""")
	List<JournalLine> findPostedLinesForAccountUpToDate(
					@Param("organizationId") Long organizationId,
					@Param("accountId") Long accountId,
					@Param("throughDate") LocalDate throughDate
	);

	@Query("""
					select new com.example.Ledger.journal.repository.JournalLineRepository$PostedAccountTotals(
							coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.DEBIT then jl.amount else 0 end), 0),
							coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.CREDIT then jl.amount else 0 end), 0)
					)
					from JournalLine jl
					join jl.journalEntry je
					where je.organization.id = :organizationId
						and je.status = com.example.Ledger.common.enums.JournalEntryStatus.POSTED
					""")
	PostedAccountTotals sumPostedDebitsAndCreditsForOrganization(@Param("organizationId") Long organizationId);

	@Query("""
					select je.organization.id
					from JournalLine jl
					join jl.journalEntry je
					where je.organization.kind = com.example.Ledger.common.enums.OrganizationKind.TENANT
						and je.status = com.example.Ledger.common.enums.JournalEntryStatus.POSTED
					group by je.organization.id
					having coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.DEBIT then jl.amount else 0 end), 0)
					    <> coalesce(sum(case when jl.side = com.example.Ledger.common.enums.EntrySide.CREDIT then jl.amount else 0 end), 0)
					""")
	List<Long> findUnbalancedTenantOrganizationIds();
}
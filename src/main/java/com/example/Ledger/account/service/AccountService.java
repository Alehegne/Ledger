package com.example.Ledger.account.service;

import com.example.Ledger.account.dto.AccountRequest;
import com.example.Ledger.account.dto.AccountResponse;
import com.example.Ledger.account.dto.AccountBalanceResponse;
import com.example.Ledger.account.dto.AccountUpdateRequest;
import com.example.Ledger.account.model.Account;
import com.example.Ledger.account.repository.AccountRepository;
import com.example.Ledger.common.enums.AccountStatus;
import com.example.Ledger.common.enums.Role;
import com.example.Ledger.common.exception.BusinessException;
import com.example.Ledger.common.exception.ResourceNotFoundException;
import com.example.Ledger.common.security.CurrentUserProvider;
import com.example.Ledger.journal.repository.JournalLineRepository;
import com.example.Ledger.journal.repository.JournalLineRepository.PostedAccountTotals;
import com.example.Ledger.organization.model.Organization;
import com.example.Ledger.organization.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final OrganizationRepository organizationRepository;
    private final JournalLineRepository journalLineRepository;
    private final CurrentUserProvider currentUserProvider;

    public AccountService(
            AccountRepository accountRepository,
            OrganizationRepository organizationRepository,
            JournalLineRepository journalLineRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.accountRepository = accountRepository;
        this.organizationRepository = organizationRepository;
        this.journalLineRepository = journalLineRepository;
        this.currentUserProvider = currentUserProvider;
    }

    public AccountResponse createAccount(AccountRequest request) {
        currentUserProvider.requireRole(Role.ADMIN, Role.ACCOUNTANT);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        String accountCode = normalizeCode(request.accountCode());
        if (accountRepository.existsByOrganizationIdAndAccountCodeIgnoreCase(organizationId, accountCode)) {
            throw new BusinessException("DUPLICATE_ACCOUNT_CODE", "Account code already exists in this organization");
        }

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        Account account = new Account();
        account.setOrganization(organization);
        account.setAccountCode(accountCode);
        account.setName(request.name().trim());
        account.setAccountType(request.accountType());
        account.setStatus(request.status());

        if (request.parentAccountId() != null) {
            account.setParentAccount(loadParentForOrganization(request.parentAccountId(), organizationId));
        }

        account = accountRepository.save(account);
        return map(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts() {
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        return accountRepository.findByOrganizationIdOrderByAccountCodeAsc(organizationId)
                .stream()
                .map(this::map)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(Long accountId) {
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        Account account = accountRepository.findByIdAndOrganizationId(accountId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        return map(account);
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse getAccountBalance(Long accountId) {
        Long organizationId = currentUserProvider.getCurrentOrganizationId();
        Account account = accountRepository.findByIdAndOrganizationId(accountId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        PostedAccountTotals totals = journalLineRepository.sumPostedDebitsAndCreditsForAccount(organizationId, accountId);
        BigDecimal totalDebits = totals == null ? BigDecimal.ZERO : castDecimal(totals.totalDebits());
        BigDecimal totalCredits = totals == null ? BigDecimal.ZERO : castDecimal(totals.totalCredits());

        BigDecimal balance = switch (account.getAccountType()) {
            case ASSET, EXPENSE -> totalDebits.subtract(totalCredits);
            case LIABILITY, EQUITY, REVENUE -> totalCredits.subtract(totalDebits);
        };

        return new AccountBalanceResponse(
                account.getId(),
                account.getAccountCode(),
                account.getAccountType(),
                balance
        );
    }

    public AccountResponse updateAccount(Long accountId, AccountUpdateRequest request) {
        currentUserProvider.requireRole(Role.ADMIN, Role.ACCOUNTANT);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        Account account = accountRepository.findByIdAndOrganizationId(accountId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        if (request.accountCode() != null && !request.accountCode().isBlank()) {
            String normalized = normalizeCode(request.accountCode());
            if (accountRepository.existsByOrganizationIdAndAccountCodeIgnoreCaseAndIdNot(organizationId, normalized, accountId)) {
                throw new BusinessException("DUPLICATE_ACCOUNT_CODE", "Account code already exists in this organization");
            }
            account.setAccountCode(normalized);
        }

        if (request.name() != null && !request.name().isBlank()) {
            account.setName(request.name().trim());
        }

        if (request.accountType() != null) {
            account.setAccountType(request.accountType());
        }

        if (request.status() != null) {
            account.setStatus(request.status());
        }

        if (request.parentAccountId() != null) {
            account.setParentAccount(loadParentForOrganization(request.parentAccountId(), organizationId));
            if (request.parentAccountId().equals(account.getId())) {
                throw new BusinessException("INVALID_PARENT_ACCOUNT", "Parent account cannot be the same as the account");
            }
            if (isCircularReference(account, request.parentAccountId())) {
                throw new BusinessException("INVALID_PARENT_ACCOUNT", "Circular account hierarchy is not allowed");
            }
        }

        account = accountRepository.save(account);
        return map(account);
    }

    public AccountResponse deactivateAccount(Long accountId) {
        currentUserProvider.requireRole(Role.ADMIN, Role.ACCOUNTANT);
        Long organizationId = currentUserProvider.getCurrentOrganizationId();

        Account account = accountRepository.findByIdAndOrganizationId(accountId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        account.setStatus(AccountStatus.INACTIVE);
        account = accountRepository.save(account);
        return map(account);
    }

    private Account loadParentForOrganization(Long parentAccountId, Long organizationId) {
        Account parent = accountRepository.findById(parentAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("Parent account not found"));

        if (!organizationId.equals(parent.getOrganization().getId())) {
            throw new BusinessException("CROSS_ORGANIZATION_PARENT", "Parent account must belong to the same organization");
        }

        return parent;
    }

    private boolean isCircularReference(Account account, Long potentialParentId) {
        Long currentParentId = potentialParentId;
        while (currentParentId != null) {
            Account parent = accountRepository.findById(currentParentId)
                    .orElse(null);
            if (parent == null) {
                return false;
            }
            if (parent.getId().equals(account.getId())) {
                return true;
            }
            currentParentId = parent.getParentAccount() == null ? null : parent.getParentAccount().getId();
        }
        return false;
    }

    private String normalizeCode(String value) {
        return value == null ? "" : value.trim();
    }

    private AccountResponse map(Account account) {
        Long parentId = account.getParentAccount() == null ? null : account.getParentAccount().getId();
        return new AccountResponse(
                account.getId(),
                account.getOrganization().getId(),
                account.getAccountCode(),
                account.getName(),
                account.getAccountType(),
                account.getStatus(),
                parentId,
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }

    private BigDecimal castDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return BigDecimal.ZERO;
    }
}

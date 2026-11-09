package com.example.Ledger.account.controller;

import com.example.Ledger.account.dto.AccountRequest;
import com.example.Ledger.account.dto.AccountResponse;
import com.example.Ledger.account.dto.AccountBalanceResponse;
import com.example.Ledger.account.dto.AccountUpdateRequest;
import com.example.Ledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasRole('ADMIN')")
    public AccountResponse create(@Valid @RequestBody AccountRequest request) {
        return accountService.createAccount(request);
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','VIEWER')")
    public List<AccountResponse> list() {
        return accountService.listAccounts();
    }

    @GetMapping("/accounts/{accountId}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','VIEWER')")
    public AccountResponse get(@PathVariable Long accountId) {
        return accountService.getAccount(accountId);
    }

    @GetMapping("/accounts/{accountId}/balance")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','VIEWER')")
    public AccountBalanceResponse getBalance(@PathVariable Long accountId) {
        return accountService.getAccountBalance(accountId);
    }

    @PatchMapping("/accounts/{accountId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AccountResponse update(@PathVariable Long accountId, @RequestBody AccountUpdateRequest request) {
        return accountService.updateAccount(accountId, request);
    }

    @PostMapping("/accounts/{accountId}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public AccountResponse deactivate(@PathVariable Long accountId) {
        return accountService.deactivateAccount(accountId);
    }

    // delete 
    @DeleteMapping("/accounts/{accountId}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public void delete(@PathVariable Long accountId) {
        // accountService.deleteAccount(accountId);
        return ;

    }
}

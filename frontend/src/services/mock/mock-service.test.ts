import { beforeEach, describe, expect, it } from "vitest";
import { createMockService, resetMockServiceState } from "./mock-service";
import type { AuthSession } from "../../types/domain";

const makeSession = (role: AuthSession["user"]["role"]): AuthSession => ({
  token: "test-token",
  expiresAt: "2099-01-01T00:00:00Z",
  user: {
    id: role === "ADMIN" ? 10 : role === "ACCOUNTANT" ? 11 : 12,
    email: `${role.toLowerCase()}@northstar.co`,
    fullName: `${role} User`,
    role,
    organizationId: 42,
  },
});

describe("mock accounting model", () => {
  beforeEach(() => {
    resetMockServiceState();
  });
  it("keeps journal entries balanced before posting", async () => {
    const service = createMockService();

    await expect(
      service.createJournalDraft({
        transactionDate: "2025-02-01",
        description: "Unbalanced draft",
        lines: [
          { accountId: 1, side: "DEBIT", amount: "100.00" },
          { accountId: 5, side: "CREDIT", amount: "50.00" },
        ],
      }),
    ).rejects.toThrow(/balanced/i);
  });

  it("rejects drafts that reference inactive accounts", async () => {
    const service = createMockService();
    await service.deactivateAccount(1);

    await expect(
      service.createJournalDraft({
        transactionDate: "2025-02-01",
        description: "Draft with inactive account",
        lines: [
          { accountId: 1, side: "DEBIT", amount: "100.00" },
          { accountId: 5, side: "CREDIT", amount: "100.00" },
        ],
      }),
    ).rejects.toThrow(/inactive/i);
  });

  it("keeps posted reports distinct from draft activity", async () => {
    const service = createMockService();
    const before = await service.getTrialBalance("2025-01-31");

    await service.createJournalDraft({
      transactionDate: "2025-02-01",
      description: "Draft only",
      lines: [
        { accountId: 1, side: "DEBIT", amount: "500.00" },
        { accountId: 5, side: "CREDIT", amount: "500.00" },
      ],
    });

    const after = await service.getTrialBalance("2025-01-31");
    expect(after.totalDebits).toBe(before.totalDebits);
    expect(after.totalCredits).toBe(before.totalCredits);
  });

  it("rejects duplicate account codes within the organization", async () => {
    const service = createMockService();

    await expect(
      service.createAccount({
        accountCode: "1000",
        name: "Duplicate cash",
        accountType: "ASSET",
        status: "ACTIVE",
      }),
    ).rejects.toThrow(/already exists|unique/i);
  });

  it("creates, updates, deactivates, and deletes accounts when allowed", async () => {
    const service = createMockService(makeSession("ADMIN"));

    const created = await service.createAccount({
      accountCode: "6100",
      name: "Travel Expense",
      accountType: "EXPENSE",
      status: "ACTIVE",
    });

    expect(created.accountCode).toBe("6100");

    const updated = await service.updateAccount(created.id, {
      name: "Travel and meals",
      status: "INACTIVE",
    });
    expect(updated.name).toBe("Travel and meals");
    expect(updated.status).toBe("INACTIVE");

    const reactivated = await service.updateAccount(created.id, {
      status: "ACTIVE",
    });
    expect(reactivated.status).toBe("ACTIVE");

    await service.deleteAccount(created.id);

    const accounts = await service.listAccounts();
    expect(accounts.some((account) => account.id === created.id)).toBe(false);
  });

  it("prevents deleting accounts that already have transactions", async () => {
    const service = createMockService(makeSession("ADMIN"));

    await expect(service.deleteAccount(1)).rejects.toThrow(/transactions/i);
  });

  it("enforces service-level permissions for account and journal mutations", async () => {
    const viewerService = createMockService(makeSession("VIEWER"));

    await expect(
      viewerService.createAccount({
        accountCode: "6200",
        name: "Viewer account",
        accountType: "EXPENSE",
        status: "ACTIVE",
      }),
    ).rejects.toThrow(/not authorized/i);

    await expect(
      viewerService.createJournalDraft({
        transactionDate: "2025-02-01",
        description: "Viewer draft",
        lines: [
          { accountId: 1, side: "DEBIT", amount: "10.00" },
          { accountId: 5, side: "CREDIT", amount: "10.00" },
        ],
      }),
    ).rejects.toThrow(/not authorized/i);
  });

  it("prevents unauthorized access to organization admin data", async () => {
    const viewerService = createMockService(makeSession("VIEWER"));

    await expect(viewerService.listOrganizationUsers()).rejects.toThrow(
      /not authorized/i,
    );
    await expect(viewerService.getOrganizationSettings()).rejects.toThrow(
      /not authorized/i,
    );
    await expect(
      viewerService.listAuditLogs({ page: 1, size: 5 }),
    ).rejects.toThrow(/not authorized/i);
  });

  it("loads and updates organization settings in mock mode without API calls", async () => {
    const adminService = createMockService(makeSession("ADMIN"));

    await expect(adminService.getOrganizationSettings()).resolves.toMatchObject(
      {
        name: "Northstar Co.",
        baseCurrency: "USD",
      },
    );

    const updated = await adminService.updateOrganizationSettings({
      name: "Northstar Labs",
      baseCurrency: "EUR",
    });
    expect(updated).toMatchObject({
      name: "Northstar Labs",
      baseCurrency: "EUR",
    });

    await expect(adminService.getOrganizationSettings()).resolves.toMatchObject(
      {
        name: "Northstar Labs",
        baseCurrency: "EUR",
      },
    );
  });

  it("does not mutate state when unauthorized mutations fail", async () => {
    const adminService = createMockService(makeSession("ADMIN"));
    const beforeAccounts = await adminService.listAccounts();
    const beforeEntries = await adminService.listJournalEntries({
      page: 1,
      size: 50,
    });

    const viewerService = createMockService(makeSession("VIEWER"));

    await expect(
      viewerService.createAccount({
        accountCode: "6300",
        name: "Unauthorized account",
        accountType: "EXPENSE",
        status: "ACTIVE",
      }),
    ).rejects.toThrow(/not authorized/i);

    await expect(
      viewerService.createJournalDraft({
        transactionDate: "2025-02-01",
        description: "Unauthorized draft",
        lines: [
          { accountId: 1, side: "DEBIT", amount: "12.00" },
          { accountId: 5, side: "CREDIT", amount: "12.00" },
        ],
      }),
    ).rejects.toThrow(/not authorized/i);

    const afterAccounts = await adminService.listAccounts();
    const afterEntries = await adminService.listJournalEntries({
      page: 1,
      size: 50,
    });

    expect(afterAccounts.length).toBe(beforeAccounts.length);
    expect(afterEntries.totalElements).toBe(beforeEntries.totalElements);
  });

  it("allows accountants to manage accounts and journal drafts", async () => {
    const service = createMockService(makeSession("ACCOUNTANT"));

    const created = await service.createAccount({
      accountCode: "6200",
      name: "Travel Expense",
      accountType: "EXPENSE",
      status: "ACTIVE",
    });

    expect(created.accountCode).toBe("6200");

    const updated = await service.updateAccount(created.id, {
      name: "Travel and meals",
      status: "INACTIVE",
    });
    expect(updated.name).toBe("Travel and meals");
    expect(updated.status).toBe("INACTIVE");

    const draft = await service.createJournalDraft({
      transactionDate: "2025-02-02",
      description: "Draft to post",
      lines: [
        { accountId: 1, side: "DEBIT", amount: "120.00" },
        { accountId: 5, side: "CREDIT", amount: "120.00" },
      ],
    });

    const posted = await service.postJournalEntry(draft.id);
    expect(posted.status).toBe("POSTED");
  });

  it("supports draft update, posting, and immutable posted entries", async () => {
    const service = createMockService(makeSession("ACCOUNTANT"));

    const draft = await service.createJournalDraft({
      transactionDate: "2025-02-02",
      description: "Draft to post",
      lines: [
        { accountId: 1, side: "DEBIT", amount: "120.00" },
        { accountId: 5, side: "CREDIT", amount: "120.00" },
      ],
    });

    const updated = await service.updateJournalDraft(draft.id, {
      transactionDate: "2025-02-02",
      description: "Updated draft",
      lines: [
        { accountId: 1, side: "DEBIT", amount: "180.00" },
        { accountId: 5, side: "CREDIT", amount: "180.00" },
      ],
    });
    expect(updated.description).toBe("Updated draft");

    const posted = await service.postJournalEntry(draft.id);
    expect(posted.status).toBe("POSTED");

    await expect(
      service.updateJournalDraft(draft.id, {
        transactionDate: "2025-02-02",
        description: "Mutate posted",
        lines: [
          { accountId: 1, side: "DEBIT", amount: "180.00" },
          { accountId: 5, side: "CREDIT", amount: "180.00" },
        ],
      }),
    ).rejects.toThrow(/cannot be edited/i);

    await expect(service.deleteDraft(draft.id)).rejects.toThrow(
      /cannot be edited/i,
    );
  });

  it("updates trial balance and general ledger after posting a draft", async () => {
    const service = createMockService(makeSession("ACCOUNTANT"));
    const before = await service.getTrialBalance("2025-02-28");

    const draft = await service.createJournalDraft({
      transactionDate: "2025-02-15",
      description: "Posted update test",
      lines: [
        { accountId: 1, side: "DEBIT", amount: "200.00" },
        { accountId: 5, side: "CREDIT", amount: "200.00" },
      ],
    });

    await service.postJournalEntry(draft.id);

    const after = await service.getTrialBalance("2025-02-28");
    expect(after.totalDebits).not.toBe(before.totalDebits);
    expect(after.totalCredits).not.toBe(before.totalCredits);

    const ledger = await service.getGeneralLedger({
      accountId: 1,
      fromDate: "2025-02-01",
      toDate: "2025-02-28",
      page: 1,
      size: 10,
    });

    expect(
      ledger.lines.some((line) => line.description === "Posted update test"),
    ).toBe(true);
  });

  it("keeps general ledger running balances stable across pagination", async () => {
    const service = createMockService();
    const pageOne = await service.getGeneralLedger({
      accountId: 1,
      fromDate: "2025-01-01",
      toDate: "2025-01-31",
      page: 1,
      size: 1,
    });
    const pageTwo = await service.getGeneralLedger({
      accountId: 1,
      fromDate: "2025-01-01",
      toDate: "2025-01-31",
      page: 2,
      size: 1,
    });

    expect(pageOne.openingBalance).toBe("0.00");
    expect(pageOne.lines.length).toBeGreaterThan(0);
    expect(pageOne.closingBalance).toBe("-850.00");
    expect(pageTwo.closingBalance).toBe("-850.00");
    expect(pageTwo.lines.at(-1)?.runningBalance ?? pageTwo.closingBalance).toBe(
      "-850.00",
    );
  });

  it("returns deterministic trial balance totals and date-filtered ledger results", async () => {
    const service = createMockService(makeSession("ADMIN"));

    const trial = await service.getTrialBalance("2025-01-31");
    expect(trial.totalDebits).toBe("5650.00");
    expect(trial.totalCredits).toBe("5650.00");
    expect(trial.balanced).toBe(true);

    const januaryLedger = await service.getGeneralLedger({
      accountId: 1,
      fromDate: "2025-01-01",
      toDate: "2025-01-31",
      page: 1,
      size: 20,
    });
    expect(januaryLedger.totalElements).toBe(2);

    const februaryLedger = await service.getGeneralLedger({
      accountId: 1,
      fromDate: "2025-02-01",
      toDate: "2025-02-28",
      page: 1,
      size: 20,
    });
    expect(februaryLedger.totalElements).toBe(0);
  });

  it("does not mutate original posted entries when reversing", async () => {
    const service = createMockService();
    const original = await service.getJournalEntry(101);

    const reversed = await service.reverseJournalEntry(101);

    expect(original.status).toBe("POSTED");
    expect(reversed.id).not.toBe(original.id);
    expect(reversed.description).toContain("reversal");
  });
});

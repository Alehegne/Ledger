import { describe, expect, it, vi } from "vitest";
import type { AuthSession } from "../../types/domain";
import { type ApiHttp, createApiService } from "./api-service";

const adminSession: AuthSession = {
  token: "admin-token",
  expiresAt: "2099-12-31T00:00:00Z",
  user: {
    id: 10,
    email: "admin@northstar.co",
    fullName: "Admin User",
    role: "ADMIN",
    organizationId: 42,
  },
};

const viewerSession: AuthSession = {
  ...adminSession,
  user: {
    ...adminSession.user,
    id: 12,
    role: "VIEWER",
    email: "viewer@northstar.co",
  },
};

describe("api service foundational mapping", () => {
  it("maps the backend login response into the frontend session model", async () => {
    const http = vi.fn<ApiHttp>(async (path, init) => {
      expect(path).toBe("/auth/login");
      expect(init).toMatchObject({ method: "POST" });
      return {
        token: "jwt-token",
        expiresAt: "2026-10-14T12:00:00Z",
        user: {
          id: 7,
          email: "admin@ledger.test",
          fullName: "Admin User",
          role: "ADMIN",
          organizationId: 42,
        },
      };
    });

    const service = createApiService(http, adminSession);
    await expect(
      service.login("admin@ledger.test", "Admin123!"),
    ).resolves.toEqual({
      token: "jwt-token",
      expiresAt: "2026-10-14T12:00:00Z",
      user: {
        id: 7,
        email: "admin@ledger.test",
        fullName: "Admin User",
        role: "ADMIN",
        organizationId: 42,
      },
    });
  });

  it("rejects malformed login payloads instead of trusting the cast", async () => {
    const http = vi.fn<ApiHttp>(async () => ({
      token: "jwt-token",
      expiresAt: "2026-10-14T12:00:00Z",
      user: { id: 7, email: "admin@ledger.test", role: "ADMIN" },
    }));

    const service = createApiService(http, adminSession);
    await expect(
      service.login("admin@ledger.test", "Admin123!"),
    ).rejects.toThrow(/Invalid login response/i);
  });

  it("maps organization settings and sends partial patch updates to the verified backend contract", async () => {
    const http = vi.fn<ApiHttp>(async (path, init) => {
      if (path === "/organizations/me" && (!init || init.method === "GET")) {
        return {
          id: 42,
          name: "Northstar Co.",
          baseCurrency: "USD",
          createdAt: "2024-08-01T00:00:00Z",
        };
      }

      if (path === "/organizations/me" && init?.method === "PATCH") {
        expect(JSON.parse(String(init.body))).toEqual({
          name: "Updated Co.",
          baseCurrency: "EUR",
        });
        return {
          id: 42,
          name: "Updated Co.",
          baseCurrency: "EUR",
          createdAt: "2024-08-01T00:00:00Z",
        };
      }

      throw new Error(`Unexpected path: ${path}`);
    });

    const service = createApiService(http, adminSession);

    await expect(service.getOrganizationSettings()).resolves.toEqual({
      id: 42,
      name: "Northstar Co.",
      baseCurrency: "USD",
      createdAt: "2024-08-01T00:00:00Z",
    });

    await expect(
      service.updateOrganizationSettings({
        name: "Updated Co.",
        baseCurrency: "EUR",
      }),
    ).resolves.toEqual({
      id: 42,
      name: "Updated Co.",
      baseCurrency: "EUR",
      createdAt: "2024-08-01T00:00:00Z",
    });
  });

  it("rejects malformed organization settings payloads instead of trusting the cast", async () => {
    const http = vi.fn<ApiHttp>(async () => ({
      id: 42,
      name: "Northstar Co.",
      createdAt: "2024-08-01T00:00:00Z",
    }));

    const service = createApiService(http, adminSession);
    await expect(service.getOrganizationSettings()).rejects.toThrow(
      /Invalid organization settings response/i,
    );
  });

  it("filters unsupported keys before patching organization settings", async () => {
    const http = vi.fn<ApiHttp>(async (path, init) => {
      if (path === "/organizations/me" && init?.method === "PATCH") {
        expect(JSON.parse(String(init.body))).toEqual({
          name: "Updated Co.",
          baseCurrency: "EUR",
        });
        return {
          id: 42,
          name: "Updated Co.",
          baseCurrency: "EUR",
          createdAt: "2024-08-01T00:00:00Z",
        };
      }

      if (path === "/organizations/me" && (!init || init.method === "GET")) {
        return {
          id: 42,
          name: "Northstar Co.",
          baseCurrency: "USD",
          createdAt: "2024-08-01T00:00:00Z",
        };
      }

      throw new Error(`Unexpected path: ${path}`);
    });

    const service = createApiService(http, adminSession);

    await expect(
      service.updateOrganizationSettings({
        name: "Updated Co.",
        baseCurrency: "EUR",
        // @ts-expect-error intentional runtime test for unsupported fields
        id: 99,
      }),
    ).resolves.toMatchObject({
      name: "Updated Co.",
      baseCurrency: "EUR",
    });
  });

  it("maps account list and detail responses to the frontend account model", async () => {
    const http = vi.fn<ApiHttp>(async (path) => {
      if (path === "/accounts") {
        return [
          {
            id: 1,
            organizationId: 42,
            accountCode: "1000",
            name: "Cash",
            accountType: "ASSET",
            status: "ACTIVE",
            parentAccountId: null,
            createdAt: "2026-10-01T00:00:00Z",
            updatedAt: "2026-10-02T00:00:00Z",
          },
        ];
      }

      if (path === "/accounts/1") {
        return {
          id: 1,
          organizationId: 42,
          accountCode: "1000",
          name: "Cash",
          accountType: "ASSET",
          status: "ACTIVE",
          parentAccountId: null,
          createdAt: "2026-10-01T00:00:00Z",
          updatedAt: "2026-10-02T00:00:00Z",
        };
      }

      if (path === "/accounts/1/balance") {
        return {
          accountId: 1,
          accountCode: "1000",
          accountType: "ASSET",
          balance: "1234.56",
        };
      }

      throw new Error(`Unexpected path: ${path}`);
    });

    const service = createApiService(http, adminSession);

    await expect(service.listAccounts()).resolves.toEqual([
      {
        id: 1,
        organizationId: 42,
        accountCode: "1000",
        name: "Cash",
        accountType: "ASSET",
        status: "ACTIVE",
        parentAccountId: null,
        createdAt: "2026-10-01T00:00:00Z",
        updatedAt: "2026-10-02T00:00:00Z",
      },
    ]);

    await expect(service.getAccount(1)).resolves.toMatchObject({
      id: 1,
      accountCode: "1000",
      name: "Cash",
      accountType: "ASSET",
    });

    await expect(service.getAccountBalance(1)).resolves.toMatchObject({
      accountId: 1,
      accountCode: "1000",
      accountType: "ASSET",
      balance: "1234.56",
    });
  });

  it("rejects malformed account payloads instead of trusting the cast", async () => {
    const http = vi.fn<ApiHttp>(async () => ({
      id: 1,
      organizationId: 42,
      accountCode: "1000",
      accountType: "ASSET",
      status: "ACTIVE",
      parentAccountId: null,
      createdAt: "2026-10-01T00:00:00Z",
      updatedAt: "2026-10-02T00:00:00Z",
    }));

    const service = createApiService(http, adminSession);
    await expect(service.getAccount(1)).rejects.toThrow(
      /Invalid account response/i,
    );
  });

  it("sends the exact journal list query params and rejects malformed journal entries", async () => {
    const http = vi.fn<ApiHttp>(async (path) => {
      expect(path).toBe("/journal-entries?status=POSTED&page=0&size=10");
      return {
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
        items: [
          {
            id: 101,
            organizationId: 42,
            transactionDate: "2026-10-01",
            description: "Sample",
            reference: "REF-1",
            status: "POSTED",
            createdByUserId: 10,
            postedByUserId: 10,
            postedAt: "2026-10-01T00:00:00Z",
            totalDebits: 2000,
            totalCredits: 2000,
            lines: [
              {
                id: 1,
                accountId: 5,
                accountCode: "4000",
                accountName: "Revenue",
                accountType: "REVENUE",
                side: "CREDIT",
                amount: 2000,
              },
            ],
            createdAt: "2026-10-01T00:00:00Z",
            updatedAt: "2026-10-01T00:00:00Z",
          },
        ],
      };
    });

    const service = createApiService(http, adminSession);
    const result = await service.listJournalEntries({
      status: "POSTED",
      page: 1,
      size: 10,
    });

    expect(result.page).toBe(1);
    expect(result.totalElements).toBe(1);
    expect(result.items[0].totalDebits).toBe("2000");
    expect(result.items[0].lines[0].amount).toBe("2000");
  });

  it("preserves refresh tokens on login/refresh and sends logout revocation", async () => {
    const http = vi.fn<ApiHttp>(async (path, init) => {
      if (path === "/auth/refresh") {
        expect(init).toMatchObject({ method: "POST" });
        expect(JSON.parse(String(init?.body))).toEqual({
          refreshToken: "refresh-1",
        });
        return {
          token: "jwt-2",
          expiresAt: "2026-11-10T12:15:00Z",
          refreshToken: "refresh-2",
          refreshExpiresAt: "2026-11-17T12:00:00Z",
          user: {
            id: 7,
            email: "admin@ledger.test",
            fullName: "Admin User",
            role: "ADMIN",
            organizationId: 42,
          },
        };
      }

      if (path === "/auth/logout") {
        expect(init).toMatchObject({ method: "POST" });
        expect(JSON.parse(String(init?.body))).toEqual({
          refreshToken: "refresh-2",
        });
        return undefined;
      }

      throw new Error(`Unexpected path: ${path}`);
    });

    const service = createApiService(http, adminSession);
    const refreshed = await service.refreshSession("refresh-1");
    expect(refreshed.token).toBe("jwt-2");
    expect(refreshed.refreshToken).toBe("refresh-2");
    expect(refreshed.refreshExpiresAt).toBe("2026-11-17T12:00:00Z");

    await expect(service.logout("refresh-2")).resolves.toBeUndefined();
  });

  it("rejects malformed journal entries", async () => {
    const http = vi.fn<ApiHttp>(async () => ({
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      items: [
        {
          id: 101,
          organizationId: 42,
          transactionDate: "2026-10-01",
          description: "Sample",
          reference: "REF-1",
          status: "POSTED",
          createdByUserId: 10,
          postedByUserId: 10,
          postedAt: "2026-10-01T00:00:00Z",
          totalDebits: 2000,
          totalCredits: 2000,
          lines: [{}],
          createdAt: "2026-10-01T00:00:00Z",
          updatedAt: "2026-10-01T00:00:00Z",
        },
      ],
    }));

    const service = createApiService(http, adminSession);
    await expect(
      service.listJournalEntries({ status: "POSTED", page: 1, size: 10 }),
    ).rejects.toThrow(/Invalid journal entry response/i);
  });

  it("forces viewer journal listing to posted status", async () => {
    const http = vi.fn<ApiHttp>(async (path) => {
      expect(path).toContain("status=POSTED");
      return {
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
        items: [],
      };
    });

    const service = createApiService(http, viewerSession);
    await service.listJournalEntries({ status: "DRAFT", page: 1, size: 20 });
  });

  it("maps the dashboard overview contract into the frontend snapshot", async () => {
    const http = vi.fn<ApiHttp>(async (path) => {
      expect(path).toBe("/api/dashboard/overview");
      return {
        asOfDate: "2026-10-15",
        totalAssets: 1250.5,
        totalRevenue: 250,
        totalExpenses: 1000.5,
        draftsCount: 2,
        postedCount: 3,
        recentEntries: [
          {
            id: 101,
            organizationId: 42,
            transactionDate: "2026-10-15",
            description: "Draft entry",
            reference: "REF-DRAFT",
            status: "DRAFT",
            createdByUserId: 10,
            postedByUserId: null,
            postedAt: null,
            totalDebits: 300,
            totalCredits: 300,
            lines: [
              {
                id: 1,
                accountId: 4,
                accountCode: "1000",
                accountName: "Cash",
                accountType: "ASSET",
                side: "DEBIT",
                amount: 300,
              },
              {
                id: 2,
                accountId: 5,
                accountCode: "5000",
                accountName: "Rent",
                accountType: "EXPENSE",
                side: "CREDIT",
                amount: 300,
              },
            ],
            createdAt: "2026-10-15T00:00:00Z",
            updatedAt: "2026-10-15T00:00:00Z",
          },
        ],
      };
    });

    const service = createApiService(http, adminSession);
    await expect(service.getOverview()).resolves.toEqual({
      asOfDate: "2026-10-15",
      totalAssets: "1250.5",
      totalRevenue: "250",
      totalExpenses: "1000.5",
      draftsCount: 2,
      postedCount: 3,
      recentEntries: [
        {
          id: 101,
          organizationId: 42,
          transactionDate: "2026-10-15",
          description: "Draft entry",
          reference: "REF-DRAFT",
          status: "DRAFT",
          createdByUserId: 10,
          postedByUserId: null,
          postedAt: null,
          totalDebits: "300",
          totalCredits: "300",
          lines: [
            {
              id: 1,
              accountId: 4,
              accountCode: "1000",
              accountName: "Cash",
              accountType: "ASSET",
              side: "DEBIT",
              amount: "300",
            },
            {
              id: 2,
              accountId: 5,
              accountCode: "5000",
              accountName: "Rent",
              accountType: "EXPENSE",
              side: "CREDIT",
              amount: "300",
            },
          ],
          createdAt: "2026-10-15T00:00:00Z",
          updatedAt: "2026-10-15T00:00:00Z",
          reversedByEntryId: null,
          reversesEntryId: null,
          reversalReason: null,
        },
      ],
    });
  });

  it("maps empty totals and recent entries as zero-values instead of crashing", async () => {
    const http = vi.fn<ApiHttp>(async () => ({
      asOfDate: "2026-10-15",
      totalAssets: 0,
      totalRevenue: 0,
      totalExpenses: 0,
      draftsCount: 0,
      postedCount: 0,
      recentEntries: [],
    }));

    const service = createApiService(http, adminSession);
    await expect(service.getOverview()).resolves.toEqual({
      asOfDate: "2026-10-15",
      totalAssets: "0",
      totalRevenue: "0",
      totalExpenses: "0",
      draftsCount: 0,
      postedCount: 0,
      recentEntries: [],
    });
  });

  it("rejects malformed overview payloads instead of accepting invalid data", async () => {
    const http = vi.fn<ApiHttp>(async () => ({
      asOfDate: "2026-10-15",
      totalAssets: 0,
      totalRevenue: 0,
      totalExpenses: 0,
      draftsCount: 0,
      postedCount: 0,
      recentEntries: [{ id: "bad" }],
    }));

    const service = createApiService(http, adminSession);
    await expect(service.getOverview()).rejects.toThrow(
      /Invalid journal entry response/i,
    );
  });

  it("maps general ledger monetary fields as strings and page to 1-based", async () => {
    const http = vi.fn<ApiHttp>(async (path) => {
      expect(path).toBe(
        "/api/reports/general-ledger?accountId=1&fromDate=2026-10-01&toDate=2026-10-31&page=0&size=5",
      );
      return {
        accountId: 1,
        accountCode: "1000",
        accountName: "Cash",
        accountType: "ASSET",
        lines: [
          {
            journalEntryId: 10,
            transactionDate: "2026-10-10",
            description: "Entry",
            reference: "R-1",
            side: "DEBIT",
            amount: 100.25,
            runningBalance: 150.75,
          },
        ],
        page: 0,
        size: 5,
        totalElements: 1,
        totalPages: 1,
        openingBalance: 50.5,
        closingBalance: 150.75,
      };
    });

    const service = createApiService(http, adminSession);
    const result = await service.getGeneralLedger({
      accountId: 1,
      fromDate: "2026-10-01",
      toDate: "2026-10-31",
      page: 1,
      size: 5,
    });

    expect(result.page).toBe(1);
    expect(result.openingBalance).toBe("50.5");
    expect(result.closingBalance).toBe("150.75");
    expect(result.lines[0].amount).toBe("100.25");
    expect(result.lines[0].runningBalance).toBe("150.75");
  });

  it("maps trial balance payloads and rejects malformed report responses", async () => {
    const http = vi.fn<ApiHttp>(async (path) => {
      expect(path).toBe("/api/reports/trial-balance?asOfDate=2026-10-14");
      return {
        asOfDate: "2026-10-14",
        accounts: [
          {
            accountId: 1,
            accountCode: "1000",
            accountName: "Cash",
            accountType: "ASSET",
            debitBalance: 500,
            creditBalance: 0,
          },
        ],
        totalDebits: 500,
        totalCredits: 0,
        balanced: false,
      };
    });

    const service = createApiService(http, adminSession);
    const result = await service.getTrialBalance("2026-10-14");

    expect(result.asOfDate).toBe("2026-10-14");
    expect(result.totalDebits).toBe("500");
    expect(result.accounts[0].debitBalance).toBe("500");
    expect(result.balanced).toBe(false);

    http.mockImplementationOnce(async () => ({
      asOfDate: "2026-10-14",
      accounts: [{ accountId: 1, accountCode: "1000", accountName: "Cash" }],
      totalDebits: 500,
      totalCredits: 0,
      balanced: false,
    }));

    await expect(service.getTrialBalance("2026-10-14")).rejects.toThrow(
      /Invalid trial balance response/i,
    );
  });

  it("creates journal drafts through the verified backend endpoint and maps the response", async () => {
    const http = vi.fn<ApiHttp>(async (path, init) => {
      expect(path).toBe("/journal-entries");
      expect(init).toMatchObject({ method: "POST" });
      expect(JSON.parse(String(init?.body))).toEqual({
        transactionDate: "2026-10-15",
        description: "Rent accrual",
        reference: "REF-100",
        lines: [
          { accountId: 4, side: "DEBIT", amount: "300" },
          { accountId: 9, side: "CREDIT", amount: "300" },
        ],
      });

      return {
        id: 301,
        organizationId: 42,
        transactionDate: "2026-10-15",
        description: "Rent accrual",
        reference: "REF-100",
        status: "DRAFT",
        createdByUserId: 10,
        postedByUserId: null,
        postedAt: null,
        totalDebits: 300,
        totalCredits: 300,
        lines: [
          {
            id: 1001,
            accountId: 4,
            accountCode: "1000",
            accountName: "Cash",
            accountType: "ASSET",
            side: "DEBIT",
            amount: 300,
          },
          {
            id: 1002,
            accountId: 9,
            accountCode: "5000",
            accountName: "Rent",
            accountType: "EXPENSE",
            side: "CREDIT",
            amount: 300,
          },
        ],
        createdAt: "2026-10-15T10:00:00Z",
        updatedAt: "2026-10-15T10:00:00Z",
      };
    });

    const service = createApiService(http, adminSession);
    await expect(
      service.createJournalDraft({
        transactionDate: "2026-10-15",
        description: "Rent accrual",
        reference: "REF-100",
        lines: [
          { accountId: 4, side: "DEBIT", amount: "300" },
          { accountId: 9, side: "CREDIT", amount: "300" },
        ],
      }),
    ).resolves.toMatchObject({
      id: 301,
      status: "DRAFT",
      description: "Rent accrual",
      totalDebits: "300",
      totalCredits: "300",
    });
  });

  it("creates invitations through the admin-only user invitation contract and maps the response", async () => {
    const http = vi.fn<ApiHttp>(async (path, init) => {
      expect(path).toBe("/users/invite");
      expect(init).toMatchObject({ method: "POST" });
      expect(JSON.parse(String(init?.body))).toEqual({
        email: "new.user@northstar.co",
        role: "ACCOUNTANT",
      });

      return {
        id: 77,
        organizationId: 42,
        invitedEmail: "new.user@northstar.co",
        role: "ACCOUNTANT",
        invitationToken: "abc123",
        expiresAt: "2026-10-22T00:00:00Z",
        createdAt: "2026-10-15T00:00:00Z",
      };
    });

    const service = createApiService(http, adminSession);
    await expect(
      service.createInvitation({
        email: "new.user@northstar.co",
        role: "ACCOUNTANT",
      }),
    ).resolves.toEqual({
      id: 77,
      organizationId: 42,
      invitedEmail: "new.user@northstar.co",
      role: "ACCOUNTANT",
      invitationToken: "abc123",
      expiresAt: "2026-10-22T00:00:00Z",
      createdAt: "2026-10-15T00:00:00Z",
    });
  });

  it("lists organization users from the verified user directory endpoint", async () => {
    const http = vi.fn<ApiHttp>(async (path) => {
      expect(path).toBe("/users");
      return [
        {
          id: 14,
          email: "admin@northstar.co",
          fullName: "Admin User",
          role: "ADMIN",
          invitationStatus: "ACTIVE",
        },
        {
          id: 15,
          email: "accountant@northstar.co",
          fullName: "Jane Accountant",
          role: "ACCOUNTANT",
          invitationStatus: "ACTIVE",
        },
      ];
    });

    const service = createApiService(http, adminSession);
    await expect(service.listOrganizationUsers()).resolves.toEqual([
      {
        id: 14,
        email: "admin@northstar.co",
        fullName: "Admin User",
        role: "ADMIN",
        invitationStatus: "ACTIVE",
      },
      {
        id: 15,
        email: "accountant@northstar.co",
        fullName: "Jane Accountant",
        role: "ACCOUNTANT",
        invitationStatus: "ACTIVE",
      },
    ]);
  });

  it("maps SUPER_ADMIN login and platform governance endpoints accurately", async () => {
    const calls: Array<{ path: string; method?: string; body?: string }> = [];
    const http = vi.fn<ApiHttp>(async (path, init) => {
      calls.push({
        path,
        method: init?.method,
        body: typeof init?.body === "string" ? init.body : undefined,
      });

      if (path === "/auth/login") {
        return {
          token: "super-jwt",
          expiresAt: "2026-11-10T12:00:00Z",
          user: {
            id: 1,
            email: "super@platform.ledger",
            fullName: "Platform Super Admin",
            role: "SUPER_ADMIN",
            organizationId: 3,
            organizationName: "Ledger Platform",
          },
        };
      }

      if (path.startsWith("/admin/organizations?")) {
        return {
          page: 0,
          size: 15,
          totalElements: 1,
          totalPages: 1,
          items: [
            {
              id: 42,
              name: "Northstar Co.",
              baseCurrency: "USD",
              status: "ACTIVE",
              userCount: 4,
              activeAdminCount: 1,
              disabledUserCount: 0,
              accountCount: 6,
              postedEntryCount: 12,
              draftEntryCount: 1,
              pendingInvitationCount: 0,
              lastActivityAt: "2026-11-09T10:00:00Z",
              createdAt: "2026-10-01T00:00:00Z",
              suspendedAt: null,
              suspendedReason: null,
            },
          ],
        };
      }

      if (path === "/admin/organizations" && init?.method === "POST") {
        return {
          organizationId: 99,
          organizationName: "Beacon Fintech",
          baseCurrency: "EUR",
          status: "PENDING",
          invitationId: 500,
          invitedAdminEmail: "founder@beacon.test",
          invitationToken: "onetime-token-abc",
          invitationExpiresAt: "2026-11-16T00:00:00Z",
          createdAt: "2026-11-09T12:00:00Z",
        };
      }

      if (path === "/admin/organizations/42/status" && init?.method === "PATCH") {
        return {
          id: 42,
          name: "Northstar Co.",
          baseCurrency: "USD",
          kind: "TENANT",
          status: "SUSPENDED",
          suspendedAt: "2026-11-09T12:05:00Z",
          suspendedReason: "Billing overdue",
          userCount: 4,
          activeUserCount: 4,
          disabledUserCount: 0,
          activeAdminCount: 1,
          accountCount: 6,
          postedEntryCount: 12,
          draftEntryCount: 1,
          hasTransactions: true,
          trialBalanceBalanced: true,
          pendingInvitations: [],
          createdAt: "2026-10-01T00:00:00Z",
          updatedAt: "2026-11-09T12:05:00Z",
        };
      }

      if (
        path === "/admin/organizations/42/recovery-invitation" &&
        init?.method === "POST"
      ) {
        return {
          invitationId: 501,
          organizationId: 42,
          invitedEmail: "recover@northstar.co",
          role: "ADMIN",
          purpose: "ADMIN_RECOVERY",
          targetUserId: 10,
          invitationToken: "recovery-token-xyz",
          expiresAt: "2026-11-16T00:00:00Z",
          createdAt: "2026-11-09T12:10:00Z",
        };
      }

      if (path.startsWith("/admin/audit-logs?")) {
        return {
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
          items: [
            {
              id: 700,
              actor: "Platform Super Admin (super@platform.ledger)",
              action: "ORGANIZATION_SUSPENDED",
              targetOrganizationId: 42,
              targetOrganizationName: "Northstar Co.",
              targetUserId: null,
              targetUserEmail: null,
              createdAt: "2026-11-09T12:05:00Z",
              metadata: '{"reason":"Billing overdue"}',
            },
          ],
        };
      }

      throw new Error(`Unexpected path: ${path}`);
    });

    const service = createApiService(http, adminSession);

    const loginSession = await service.login("super@platform.ledger", "Pass!");
    expect(loginSession.user.role).toBe("SUPER_ADMIN");
    expect(loginSession.user.organizationName).toBe("Ledger Platform");

    const orgPage = await service.listPlatformOrganizations({
      page: 1,
      size: 15,
      status: "ACTIVE",
      search: "North",
    });
    expect(orgPage.page).toBe(1);
    expect(orgPage.items[0].name).toBe("Northstar Co.");
    expect(calls.at(-1)?.path).toBe(
      "/admin/organizations?search=North&status=ACTIVE&page=0&size=15",
    );

    const createdOrg = await service.createPlatformOrganization({
      organizationName: "Beacon Fintech",
      baseCurrency: "EUR",
      adminEmail: "founder@beacon.test",
    });
    expect(createdOrg.status).toBe("PENDING");
    expect(createdOrg.invitationToken).toBe("onetime-token-abc");

    const updatedDetail = await service.updatePlatformOrganizationStatus(42, {
      status: "SUSPENDED",
      reason: "Billing overdue",
    });
    expect(updatedDetail.status).toBe("SUSPENDED");
    expect(updatedDetail.suspendedReason).toBe("Billing overdue");

    const recovery = await service.createAdminRecoveryInvitation(42, {
      adminEmail: "recover@northstar.co",
    });
    expect(recovery.targetUserId).toBe(10);
    expect(recovery.purpose).toBe("ADMIN_RECOVERY");

    const auditPage = await service.listPlatformAuditLogs({
      page: 1,
      size: 20,
      action: "ORGANIZATION_SUSPENDED",
      targetOrganizationId: 42,
      fromDate: "2026-11-01",
      toDate: "2026-11-09",
    });
    expect(auditPage.page).toBe(1);
    expect(auditPage.items[0].action).toBe("ORGANIZATION_SUSPENDED");
    expect(calls.at(-1)?.path).toBe(
      "/admin/audit-logs?action=ORGANIZATION_SUSPENDED&targetOrganizationId=42&from=2026-11-01&to=2026-11-09&page=0&size=20",
    );
  });
});

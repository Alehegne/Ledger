// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect } from "react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "../../app/auth-context";
import { env } from "../../lib/env";
import { ServiceContext } from "../../services/interfaces/service-context";
import type { LedgerService } from "../../services/interfaces/ledger-service";
import {
  createMockService,
  resetMockServiceState,
} from "../../services/mock/mock-service";
import type { AuthSession } from "../../types/domain";
import { AccountsPage } from "./accounts-page";

const baseSession: AuthSession = {
  token: "admin-token",
  expiresAt: "2099-12-31T23:59:59Z",
  user: {
    id: 10,
    email: "admin@northstar.co",
    fullName: "Admin User",
    role: "ADMIN",
    organizationId: 42,
  },
};

const renderAccountsPage = (
  customService?: LedgerService,
  role: "ADMIN" | "ACCOUNTANT" | "VIEWER" = "ADMIN",
) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const session: AuthSession = {
    ...baseSession,
    user: { ...baseSession.user, role },
  };

  const SessionSetter = () => {
    const { setSession } = useAuth();
    useEffect(() => {
      setSession(session);
    }, [setSession]);
    return <AccountsPage />;
  };

  const service = customService ?? createMockService(session);

  return render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <ServiceContext.Provider value={service}>
          <MemoryRouter>
            <SessionSetter />
          </MemoryRouter>
        </ServiceContext.Provider>
      </QueryClientProvider>
    </AuthProvider>,
  );
};

describe("accounts page workflows", () => {
  beforeEach(() => {
    resetMockServiceState();
    localStorage.clear();
    env.mode = "mock";
    vi.restoreAllMocks();
  });

  it("hides account mutation controls for non-admin users (ACCOUNTANT and VIEWER)", async () => {
    const mockService = createMockService({
      ...baseSession,
      user: { ...baseSession.user, role: "ACCOUNTANT" },
    });

    renderAccountsPage(mockService, "ACCOUNTANT");

    expect(await screen.findByText("Cash & Equivalents")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "New account" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByLabelText(/edit cash & equivalents/i),
    ).not.toBeInTheDocument();
  });

  it("allows admin to create, edit, and deactivate accounts in API mode", async () => {
    env.mode = "api";

    const createAccountMock = vi.fn(async () => ({
      id: 2,
      organizationId: 42,
      accountCode: "1050",
      name: "Savings",
      accountType: "ASSET" as const,
      status: "ACTIVE" as const,
      parentAccountId: null,
      createdAt: "2026-10-01T00:00:00Z",
      updatedAt: "2026-10-02T00:00:00Z",
    }));

    const updateAccountMock = vi.fn(async () => ({
      id: 1,
      organizationId: 42,
      accountCode: "1000",
      name: "Operating Cash",
      accountType: "ASSET" as const,
      status: "ACTIVE" as const,
      parentAccountId: null,
      createdAt: "2026-10-01T00:00:00Z",
      updatedAt: "2026-10-02T00:00:00Z",
    }));

    const deactivateAccountMock = vi.fn(async () => ({
      id: 1,
      organizationId: 42,
      accountCode: "1000",
      name: "Cash",
      accountType: "ASSET" as const,
      status: "INACTIVE" as const,
      parentAccountId: null,
      createdAt: "2026-10-01T00:00:00Z",
      updatedAt: "2026-10-02T00:00:00Z",
    }));

    const apiService = {
      login: vi.fn(),
      getOverview: vi.fn(),
      listAccounts: vi.fn(async () => [
        {
          id: 1,
          organizationId: 42,
          accountCode: "1000",
          name: "Cash",
          accountType: "ASSET" as const,
          status: "ACTIVE" as const,
          parentAccountId: null,
          createdAt: "2026-10-01T00:00:00Z",
          updatedAt: "2026-10-02T00:00:00Z",
        },
      ]),
      getAccount: vi.fn(),
      getAccountBalance: vi.fn(),
      createAccount: createAccountMock,
      updateAccount: updateAccountMock,
      deactivateAccount: deactivateAccountMock,
      deleteAccount: vi.fn(),
      listJournalEntries: vi.fn(),
      getJournalEntry: vi.fn(),
      createJournalDraft: vi.fn(),
      updateJournalDraft: vi.fn(),
      postJournalEntry: vi.fn(),
      reverseJournalEntry: vi.fn(),
      deleteDraft: vi.fn(),
      getGeneralLedger: vi.fn(),
      getTrialBalance: vi.fn(async () => ({
        asOfDate: "2026-10-12",
        accounts: [
          {
            accountId: 1,
            accountCode: "1000",
            accountName: "Cash",
            accountType: "ASSET" as const,
            debitBalance: "100.00",
            creditBalance: "0.00",
          },
        ],
        totalDebits: "100.00",
        totalCredits: "0.00",
        balanced: true,
      })),
      createInvitation: vi.fn(),
      listOrganizationUsers: vi.fn(),
      getOrganizationSettings: vi.fn(),
      listAuditLogs: vi.fn(),
    } as unknown as LedgerService;

    renderAccountsPage(apiService, "ADMIN");
    const user = userEvent.setup();

    expect(await screen.findByText("Cash")).toBeInTheDocument();

    // Verify New Account is visible for Admin in API mode
    const newAccountBtn = screen.getByRole("button", { name: "New account" });
    expect(newAccountBtn).toBeInTheDocument();
    await user.click(newAccountBtn);

    await user.type(screen.getByLabelText("Account code"), "1050");
    await user.type(screen.getByLabelText("Account name"), "Savings");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(createAccountMock).toHaveBeenCalledWith(
      expect.objectContaining({
        accountCode: "1050",
        name: "Savings",
      }),
    );

    // Verify Edit Account
    await user.click(screen.getByLabelText("Edit Cash"));
    const nameInput = screen.getByLabelText("Account name");
    await user.clear(nameInput);
    await user.type(nameInput, "Operating Cash");
    await user.click(screen.getByRole("button", { name: "Save account" }));

    expect(updateAccountMock).toHaveBeenCalledWith(
      1,
      expect.objectContaining({
        name: "Operating Cash",
      }),
    );

    // Verify Deactivate Account
    await user.click(screen.getByLabelText("Toggle Cash status"));
    expect(deactivateAccountMock).toHaveBeenCalledWith(1);

    // Verify Delete button does not exist (accounts cannot be deleted in double-entry systems)
    expect(screen.queryByLabelText(/delete/i)).not.toBeInTheDocument();
  });

  it("creates, edits, and deactivates an account in mock mode without delete action", async () => {
    renderAccountsPage();
    const user = userEvent.setup();

    await user.click(
      await screen.findByRole("button", { name: "New account" }),
    );

    await user.type(screen.getByLabelText("Account code"), "6100");
    await user.type(screen.getByLabelText("Account name"), "Travel Expense");
    await user.selectOptions(screen.getByLabelText("Account type"), "EXPENSE");

    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText("Travel Expense")).toBeInTheDocument();

    await user.click(screen.getByLabelText("Edit Travel Expense"));

    const accountNameInput = screen.getByLabelText("Account name");
    await user.clear(accountNameInput);
    await user.type(accountNameInput, "Travel Expense Updated");
    await user.click(screen.getByRole("button", { name: "Save account" }));

    expect(
      await screen.findByText("Travel Expense Updated"),
    ).toBeInTheDocument();

    await user.click(screen.getByLabelText("Toggle Cash & Equivalents status"));

    const cashRow = screen.getByText("Cash & Equivalents").closest("tr");
    expect(cashRow).not.toBeNull();
    if (cashRow) {
      expect(within(cashRow).getByText("INACTIVE")).toBeInTheDocument();
    }

    // Verify Delete button does not exist
    expect(screen.queryByLabelText(/delete/i)).not.toBeInTheDocument();
  });
});

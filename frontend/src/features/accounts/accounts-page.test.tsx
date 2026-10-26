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
import {
  createMockService,
  resetMockServiceState,
} from "../../services/mock/mock-service";
import type { AuthSession } from "../../types/domain";
import { AccountsPage } from "./accounts-page";

const session: AuthSession = {
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

const renderAccountsPage = () => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const SessionSetter = () => {
    const { setSession } = useAuth();
    useEffect(() => {
      setSession(session);
    }, [setSession]);
    return <AccountsPage />;
  };

  return render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <ServiceContext.Provider value={createMockService(session)}>
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

  it("hides account mutation controls in API mode and shows a read-only message", async () => {
    env.mode = "api";
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    const apiService = {
      login: vi.fn(),
      getOverview: vi.fn(),
      listAccounts: vi.fn(async () => [
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
      ]),
      getAccount: vi.fn(),
      getAccountBalance: vi.fn(),
      createAccount: vi.fn(),
      updateAccount: vi.fn(),
      deactivateAccount: vi.fn(),
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
            accountType: "ASSET",
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
    } as any;

    render(
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <ServiceContext.Provider value={apiService}>
            <MemoryRouter>
              <AccountsPage />
            </MemoryRouter>
          </ServiceContext.Provider>
        </QueryClientProvider>
      </AuthProvider>,
    );

    expect(await screen.findByText("Cash")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "New account" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/edit cash/i)).not.toBeInTheDocument();
    expect(
      screen.getByText(
        /Read-only API mode: account mutation endpoints are not enabled yet\./i,
      ),
    ).toBeInTheDocument();
  });

  it("creates and edits an account, deactivates, and blocks deleting transacted accounts", async () => {
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

    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    await user.click(screen.getByLabelText("Delete Cash & Equivalents"));

    expect(confirmSpy).toHaveBeenCalled();
    expect(
      await screen.findByText(
        /Cannot delete an account that has transactions/i,
      ),
    ).toBeInTheDocument();
  });
});

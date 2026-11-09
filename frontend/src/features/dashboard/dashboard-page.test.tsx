// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { env } from "../../lib/env";
import { ServiceContext } from "../../services/interfaces/service-context";
import type { LedgerService } from "../../services/interfaces/ledger-service";
import { DashboardPage } from "./dashboard-page";

describe("dashboard page overview handling", () => {
  beforeEach(() => {
    env.mode = "mock";
    vi.restoreAllMocks();
  });

  it("renders the overview snapshot in mock mode without showing fallback messaging", async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    const mockService = {
      login: vi.fn(),
      getOverview: vi.fn().mockResolvedValue({
        asOfDate: "2026-10-15",
        totalAssets: "1250.50",
        totalRevenue: "250.00",
        totalExpenses: "1000.50",
        draftsCount: 1,
        postedCount: 2,
        recentEntries: [
          {
            id: 1,
            organizationId: 42,
            transactionDate: "2026-10-15",
            description: "Posted revenue",
            reference: "REF-1",
            status: "POSTED",
            createdByUserId: 10,
            postedByUserId: 10,
            postedAt: "2026-10-15T00:00:00Z",
            totalDebits: "250",
            totalCredits: "250",
            lines: [],
            createdAt: "2026-10-15T00:00:00Z",
            updatedAt: "2026-10-15T00:00:00Z",
          },
        ],
      }),
      listAccounts: vi.fn(),
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
      getTrialBalance: vi.fn(),
      createInvitation: vi.fn(),
      listOrganizationUsers: vi.fn(),
      getOrganizationSettings: vi.fn(),
      listAuditLogs: vi.fn(),
    } as unknown as LedgerService;

    render(
      <QueryClientProvider client={queryClient}>
        <ServiceContext.Provider value={mockService}>
          <MemoryRouter>
            <DashboardPage />
          </MemoryRouter>
        </ServiceContext.Provider>
      </QueryClientProvider>,
    );

    expect(await screen.findByText("Financial snapshot")).toBeInTheDocument();
    expect(screen.getByText("$1,250.50")).toBeInTheDocument();
    expect(screen.getByText("Posted revenue")).toBeInTheDocument();
    expect(
      screen.queryByText(/Dashboard unavailable/i),
    ).not.toBeInTheDocument();
  });

  it("shows a retry action when the API overview request fails instead of falling back to mock data", async () => {
    env.mode = "api";
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    const apiService = {
      login: vi.fn(),
      getOverview: vi
        .fn()
        .mockRejectedValueOnce(
          new Error(
            "Overview API is not available in the current backend contract.",
          ),
        )
        .mockResolvedValueOnce({
          asOfDate: "2026-10-15",
          totalAssets: "1250.50",
          totalRevenue: "250.00",
          totalExpenses: "1000.50",
          draftsCount: 0,
          postedCount: 1,
          recentEntries: [],
        }),
      listAccounts: vi.fn(),
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
      getTrialBalance: vi.fn(),
      createInvitation: vi.fn(),
      listOrganizationUsers: vi.fn(),
      getOrganizationSettings: vi.fn(),
      listAuditLogs: vi.fn(),
    } as unknown as LedgerService;

    render(
      <QueryClientProvider client={queryClient}>
        <ServiceContext.Provider value={apiService}>
          <MemoryRouter>
            <DashboardPage />
          </MemoryRouter>
        </ServiceContext.Provider>
      </QueryClientProvider>,
    );

    expect(
      await screen.findByText(/Dashboard unavailable/i),
    ).toBeInTheDocument();
    const retryButton = screen.getByRole("button", { name: /retry/i });
    expect(retryButton).toBeInTheDocument();

    await retryButton.click();

    expect(apiService.getOverview).toHaveBeenCalledTimes(2);
    expect(await screen.findByText("Financial snapshot")).toBeInTheDocument();
  });
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../../app/auth-context";
import type { LedgerService } from "../../services/interfaces/ledger-service";
import { ServiceContext } from "../../services/interfaces/service-context";
import type { PageResult, ReconciliationRun } from "../../types/domain";
import { ReconciliationPage } from "./reconciliation-page";

const passedRun: ReconciliationRun = {
  id: 1,
  organizationId: 42,
  status: "PASSED",
  totalPostedDebits: "5650.00",
  totalPostedCredits: "5650.00",
  globalVariance: "0.00",
  postedEntryCount: 2,
  accountCount: 5,
  checksPassed: 4,
  checksFailed: 0,
  triggeredByUserId: 10,
  triggeredByName: "Alex Morgan",
  createdAt: "2026-11-12T10:00:00Z",
  checks: [
    {
      code: "GLOBAL_DEBIT_CREDIT_BALANCE",
      name: "Global Posted Debit/Credit Balance",
      passed: true,
      blocking: true,
      variance: "0.00",
      summary: "All 2 posted journal entries balance globally.",
      discrepancies: [],
    },
    {
      code: "PER_ENTRY_BALANCE",
      name: "Per-Entry Double-Entry Balance",
      passed: true,
      blocking: true,
      variance: "0.00",
      summary: "All posted entries individually balance.",
      discrepancies: [],
    },
    {
      code: "TRIAL_BALANCE_AND_GL_PARITY",
      name: "Trial Balance & GL Integrity Parity",
      passed: true,
      blocking: true,
      variance: "0.00",
      summary: "Trial Balance ties out to General Ledger totals.",
      discrepancies: [],
    },
    {
      code: "ACCOUNT_HIERARCHY_INTEGRITY",
      name: "Account Hierarchy & Rollup Integrity",
      passed: true,
      blocking: false,
      variance: "0.00",
      summary: "No cycles or invalid parent links detected.",
      discrepancies: [],
    },
  ],
};

const failedRun: ReconciliationRun = {
  ...passedRun,
  id: 2,
  status: "FAILED",
  totalPostedDebits: "5700.00",
  totalPostedCredits: "5650.00",
  globalVariance: "50.00",
  checksPassed: 3,
  checksFailed: 1,
  createdAt: "2026-11-12T10:05:00Z",
  checks: [
    {
      code: "GLOBAL_DEBIT_CREDIT_BALANCE",
      name: "Global Posted Debit/Credit Balance",
      passed: false,
      blocking: true,
      variance: "50.00",
      summary: "Global ledger out of balance by 50.00.",
      discrepancies: [
        {
          entityType: "ORGANIZATION",
          entityId: "42",
          reference: "Northstar Co.",
          expectedAmount: "5700.00",
          actualAmount: "5650.00",
          variance: "50.00",
          detail: "Global posted debits do not equal global posted credits.",
        },
      ],
    },
    ...passedRun.checks.slice(1),
  ],
};

const renderPage = (service: LedgerService) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <ServiceContext.Provider value={service}>
          <ReconciliationPage />
        </ServiceContext.Provider>
      </QueryClientProvider>
    </AuthProvider>,
  );
};

describe("ReconciliationPage", () => {
  it("renders reconciliation run breakdown and history, and runs a new reconciliation check", async () => {
    const user = userEvent.setup();
    const listReconciliationHistory = vi
      .fn()
      .mockResolvedValue({
        page: 1,
        size: 10,
        totalElements: 1,
        totalPages: 1,
        items: [passedRun],
      } satisfies PageResult<ReconciliationRun>);
    const runReconciliation = vi.fn().mockResolvedValue(failedRun);

    const mockService = {
      listReconciliationHistory,
      runReconciliation,
    } as unknown as LedgerService;

    renderPage(mockService);

    await waitFor(() => {
      expect(screen.getByText("Reconciliation Run #1")).toBeInTheDocument();
      expect(
        screen.getByText("Global Posted Debit/Credit Balance"),
      ).toBeInTheDocument();
      expect(
        screen.getByText("Per-Entry Double-Entry Balance"),
      ).toBeInTheDocument();
    });

    await user.click(
      screen.getByRole("button", { name: /Run Reconciliation/i }),
    );

    await waitFor(() => {
      expect(runReconciliation).toHaveBeenCalledTimes(1);
      expect(screen.getByText("Reconciliation Run #2")).toBeInTheDocument();
      expect(
        screen.getByText(
          "Global posted debits do not equal global posted credits.",
        ),
      ).toBeInTheDocument();
    });
  });

  it("shows empty state when no reconciliation runs exist yet", async () => {
    const mockService = {
      listReconciliationHistory: vi.fn().mockResolvedValue({
        page: 1,
        size: 10,
        totalElements: 0,
        totalPages: 1,
        items: [],
      }),
      runReconciliation: vi.fn(),
    } as unknown as LedgerService;

    renderPage(mockService);

    await waitFor(() => {
      expect(
        screen.getByText("No reconciliation runs recorded yet."),
      ).toBeInTheDocument();
    });
  });
});

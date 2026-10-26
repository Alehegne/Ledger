// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "../../app/auth-context";
import { ServiceContext } from "../../services/interfaces/service-context";
import {
  createMockService,
  resetMockServiceState,
} from "../../services/mock/mock-service";
import type { AuthSession } from "../../types/domain";
import { ReportsPage } from "./reports-page";

const session: AuthSession = {
  token: "viewer-token",
  expiresAt: "2099-12-31T23:59:59Z",
  user: {
    id: 12,
    email: "viewer@northstar.co",
    fullName: "Viewer User",
    role: "VIEWER",
    organizationId: 42,
  },
};

const renderReportsPage = () => {
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
    return <ReportsPage />;
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

describe("reports page workflows", () => {
  beforeEach(() => {
    resetMockServiceState();
    localStorage.clear();
  });

  it("shows deterministic trial-balance totals and ledger empty state for filtered dates", async () => {
    renderReportsPage();

    const totals = await screen.findAllByText("$5,650.00");
    expect(totals.length).toBeGreaterThanOrEqual(2);

    const fromDateInput = screen.getByLabelText("From date");
    const toDateInput = screen.getByLabelText("To date");

    fireEvent.change(fromDateInput, { target: { value: "2025-02-01" } });
    fireEvent.change(toDateInput, { target: { value: "2025-02-28" } });

    expect(
      await screen.findByText(/No posted transactions for this account/i),
    ).toBeInTheDocument();
  });

  it("surfaces report errors instead of silently falling back", async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
      },
    });

    const badService = {
      ...createMockService(session),
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
          updatedAt: "2026-10-01T00:00:00Z",
        },
      ]),
      getTrialBalance: vi.fn(async () => {
        throw new Error("Trial balance API is down");
      }),
      getGeneralLedger: vi.fn(async () => {
        throw new Error("General ledger API is down");
      }),
    } as any;

    const SessionSetter = () => {
      const { setSession } = useAuth();
      useEffect(() => {
        setSession(session);
      }, [setSession]);
      return <ReportsPage />;
    };

    render(
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <ServiceContext.Provider value={badService}>
            <MemoryRouter>
              <SessionSetter />
            </MemoryRouter>
          </ServiceContext.Provider>
        </QueryClientProvider>
      </AuthProvider>,
    );

    expect(await screen.findByText(/Reports unavailable/i)).toBeInTheDocument();
    expect(screen.getByText(/Trial balance API is down/i)).toBeInTheDocument();
  });
});

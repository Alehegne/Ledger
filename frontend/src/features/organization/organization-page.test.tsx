// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect } from "react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "../../app/auth-context";
import { AppShell } from "../../components/layout/app-shell";
import { env } from "../../lib/env";
import { ServiceContext } from "../../services/interfaces/service-context";
import type { LedgerService } from "../../services/interfaces/ledger-service";
import type { AuthSession } from "../../types/domain";
import { OrganizationPage } from "./organization-page";

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

describe("organization settings page", () => {
  beforeEach(() => {
    env.mode = "api";
    vi.restoreAllMocks();
  });

  it("loads and saves organization settings using the verified API contract", async () => {
    const user = userEvent.setup();
    const updateOrganizationSettings = vi.fn(
      async (payload: { name?: string; baseCurrency?: string }) => ({
        id: 42,
        name: payload.name ?? "Northstar Co.",
        baseCurrency: payload.baseCurrency ?? "USD",
        createdAt: "2024-08-01T00:00:00Z",
      }),
    );

    const apiService = {
      login: vi.fn(),
      getOverview: vi.fn(),
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
      getOrganizationSettings: vi.fn(async () => ({
        id: 42,
        name: "Northstar Co.",
        baseCurrency: "USD",
        createdAt: "2024-08-01T00:00:00Z",
      })),
      updateOrganizationSettings,
      listAuditLogs: vi.fn(),
    } as unknown as LedgerService;

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
      return <OrganizationPage />;
    };

    render(
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <ServiceContext.Provider value={apiService}>
            <MemoryRouter initialEntries={["/settings"]}>
              <SessionSetter />
            </MemoryRouter>
          </ServiceContext.Provider>
        </QueryClientProvider>
      </AuthProvider>,
    );

    expect(
      await screen.findByDisplayValue("Northstar Co."),
    ).toBeInTheDocument();
    expect(screen.getByDisplayValue("USD")).toBeInTheDocument();

    await user.clear(screen.getByLabelText("Organization name"));
    await user.type(screen.getByLabelText("Organization name"), "Updated Co.");
    await user.clear(screen.getByLabelText("Base currency"));
    await user.type(screen.getByLabelText("Base currency"), "EUR");
    await user.click(screen.getByRole("button", { name: /save changes/i }));

    expect(updateOrganizationSettings).toHaveBeenCalledWith({
      name: "Updated Co.",
      baseCurrency: "EUR",
    });
    expect(
      await screen.findByText(/Organization settings saved/i),
    ).toBeInTheDocument();
  });

  it("updates the shared session and app shell when organization settings change", async () => {
    const user = userEvent.setup();
    const updateOrganizationSettings = vi.fn(
      async (payload: { name?: string; baseCurrency?: string }) => ({
        id: 42,
        name: payload.name ?? "Northstar Co.",
        baseCurrency: payload.baseCurrency ?? "USD",
        createdAt: "2024-08-01T00:00:00Z",
      }),
    );

    const apiService = {
      login: vi.fn(),
      getOverview: vi.fn(),
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
      getOrganizationSettings: vi.fn(async () => ({
        id: 42,
        name: "Northstar Co.",
        baseCurrency: "USD",
        createdAt: "2024-08-01T00:00:00Z",
      })),
      updateOrganizationSettings,
      listAuditLogs: vi.fn(),
    } as unknown as LedgerService;

    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    const SessionSetter = () => {
      const { setSession } = useAuth();
      useEffect(() => {
        setSession({
          ...session,
          user: { ...session.user, organizationName: "Northstar Co." },
        });
      }, [setSession]);
      return (
        <AppShell>
          <OrganizationPage />
        </AppShell>
      );
    };

    render(
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <ServiceContext.Provider value={apiService}>
            <MemoryRouter initialEntries={["/settings"]}>
              <SessionSetter />
            </MemoryRouter>
          </ServiceContext.Provider>
        </QueryClientProvider>
      </AuthProvider>,
    );

    await screen.findByDisplayValue("Northstar Co.");
    await user.clear(screen.getByLabelText("Organization name"));
    await user.type(screen.getByLabelText("Organization name"), "Updated Co.");
    await user.click(screen.getByRole("button", { name: /save changes/i }));

    expect(updateOrganizationSettings).toHaveBeenCalledWith({
      name: "Updated Co.",
      baseCurrency: "USD",
    });
    expect(await screen.findByText("Updated Co.")).toBeInTheDocument();
  });

  it("refreshes the shared session when the fetched settings name differs from the stored session value", async () => {
    const apiService = {
      login: vi.fn(),
      getOverview: vi.fn(),
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
      getOrganizationSettings: vi.fn(async () => ({
        id: 42,
        name: "Freshly renamed Co.",
        baseCurrency: "USD",
        createdAt: "2024-08-01T00:00:00Z",
      })),
      updateOrganizationSettings: vi.fn(),
      listAuditLogs: vi.fn(),
    } as unknown as LedgerService;

    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    const SessionSetter = () => {
      const { setSession } = useAuth();
      useEffect(() => {
        setSession({
          ...session,
          user: { ...session.user, organizationName: "Old Co." },
        });
      }, [setSession]);
      return (
        <AppShell>
          <OrganizationPage />
        </AppShell>
      );
    };

    render(
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <ServiceContext.Provider value={apiService}>
            <MemoryRouter initialEntries={["/settings"]}>
              <SessionSetter />
            </MemoryRouter>
          </ServiceContext.Provider>
        </QueryClientProvider>
      </AuthProvider>,
    );

    expect(
      await screen.findByDisplayValue("Freshly renamed Co."),
    ).toBeInTheDocument();
    expect(await screen.findByText("Freshly renamed Co.")).toBeInTheDocument();
  });

  it("shows validation errors instead of reporting a false success", async () => {
    const user = userEvent.setup();
    const apiService = {
      login: vi.fn(),
      getOverview: vi.fn(),
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
      getOrganizationSettings: vi.fn(async () => ({
        id: 42,
        name: "Northstar Co.",
        baseCurrency: "USD",
        createdAt: "2024-08-01T00:00:00Z",
      })),
      updateOrganizationSettings: vi.fn(async () => {
        throw new Error("Currency must be a 3-letter ISO code.");
      }),
      listAuditLogs: vi.fn(),
    } as unknown as LedgerService;

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
      return <OrganizationPage />;
    };

    render(
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <ServiceContext.Provider value={apiService}>
            <MemoryRouter initialEntries={["/settings"]}>
              <SessionSetter />
            </MemoryRouter>
          </ServiceContext.Provider>
        </QueryClientProvider>
      </AuthProvider>,
    );

    await screen.findByDisplayValue("Northstar Co.");
    await user.clear(screen.getByLabelText("Base currency"));
    await user.type(screen.getByLabelText("Base currency"), "eu");
    await user.click(screen.getByRole("button", { name: /save changes/i }));

    expect(
      await screen.findByText(/Currency must be a 3-letter ISO code/i),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/Organization settings saved/i),
    ).not.toBeInTheDocument();
  });

  it("keeps the save action hidden for non-admin users", async () => {
    const viewerSession = {
      ...session,
      user: { ...session.user, role: "VIEWER" as const },
    };

    const apiService = {
      login: vi.fn(),
      getOverview: vi.fn(),
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
      getOrganizationSettings: vi.fn(async () => ({
        id: 42,
        name: "Northstar Co.",
        baseCurrency: "USD",
        createdAt: "2024-08-01T00:00:00Z",
      })),
      updateOrganizationSettings: vi.fn(),
      listAuditLogs: vi.fn(),
    } as unknown as LedgerService;

    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    const SessionSetter = () => {
      const { setSession } = useAuth();
      useEffect(() => {
        setSession(viewerSession);
      }, [setSession]);
      return <OrganizationPage />;
    };

    render(
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <ServiceContext.Provider value={apiService}>
            <MemoryRouter initialEntries={["/settings"]}>
              <SessionSetter />
            </MemoryRouter>
          </ServiceContext.Provider>
        </QueryClientProvider>
      </AuthProvider>,
    );

    expect(
      screen.queryByRole("button", { name: /save changes/i }),
    ).not.toBeInTheDocument();
  });

  it("shows the saving state while a request is in flight", async () => {
    const user = userEvent.setup();
    let resolveSave:
      | ((value: {
          id: number;
          name: string;
          baseCurrency: string;
          createdAt: string;
        }) => void)
      | undefined;

    const updateOrganizationSettings = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveSave = resolve as typeof resolveSave;
        }),
    );

    const apiService = {
      login: vi.fn(),
      getOverview: vi.fn(),
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
      getOrganizationSettings: vi.fn(async () => ({
        id: 42,
        name: "Northstar Co.",
        baseCurrency: "USD",
        createdAt: "2024-08-01T00:00:00Z",
      })),
      updateOrganizationSettings,
      listAuditLogs: vi.fn(),
    } as unknown as LedgerService;

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
      return <OrganizationPage />;
    };

    render(
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <ServiceContext.Provider value={apiService}>
            <MemoryRouter initialEntries={["/settings"]}>
              <SessionSetter />
            </MemoryRouter>
          </ServiceContext.Provider>
        </QueryClientProvider>
      </AuthProvider>,
    );

    await screen.findByDisplayValue("Northstar Co.");
    const saveButton = screen.getByRole("button", { name: /save changes/i });
    await user.clear(screen.getByLabelText("Organization name"));
    await user.type(screen.getByLabelText("Organization name"), "Updated Co.");
    await user.click(saveButton);

    expect(screen.getByRole("button", { name: /saving/i })).toBeInTheDocument();

    resolveSave?.({
      id: 42,
      name: "Updated Co.",
      baseCurrency: "USD",
      createdAt: "2024-08-01T00:00:00Z",
    });

    expect(
      await screen.findByText(/Organization settings saved/i),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /saving/i }),
    ).not.toBeInTheDocument();
  });
});

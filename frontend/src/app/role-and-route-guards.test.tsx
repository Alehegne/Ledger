// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import App from "../App";
import { AuthProvider } from "./auth-context";
import { RequireAuth, RequireRole } from "./route-guards";
import { AccountsPage } from "../features/accounts/accounts-page";
import { JournalPage } from "../features/journal/journal-page";
import { createApiService } from "../services/api/api-service";
import {
  createMockService,
  resetMockServiceState,
} from "../services/mock/mock-service";
import { ServiceContext } from "../services/interfaces/service-context";
import type { AuthSession } from "../types/domain";

const makeSession = (role: AuthSession["user"]["role"]): AuthSession => ({
  token: `${role.toLowerCase()}-token`,
  expiresAt: "2099-12-31T00:00:00Z",
  user: {
    id: role === "ADMIN" ? 10 : role === "ACCOUNTANT" ? 11 : 12,
    email: `${role.toLowerCase()}@northstar.co`,
    fullName: `${role} User`,
    role,
    organizationId: 42,
  },
});

const renderWithProviders = (ui: React.ReactNode, session?: AuthSession) => {
  const storageKey = "ledger.frontend.auth.session";
  if (session) {
    localStorage.setItem(storageKey, JSON.stringify(session));
  } else {
    localStorage.removeItem(storageKey);
  }

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <ServiceContext.Provider value={createMockService(session ?? null)}>
          {ui}
        </ServiceContext.Provider>
      </QueryClientProvider>
    </AuthProvider>,
  );
};

describe("frontend architectural contracts", () => {
  beforeEach(() => {
    resetMockServiceState();
    localStorage.clear();
  });

  it("redirects unauthenticated users away from protected routes", () => {
    renderWithProviders(
      <MemoryRouter initialEntries={["/accounts"]}>
        <Routes>
          <Route path="/login" element={<div>Login page</div>} />
          <Route
            path="/accounts"
            element={
              <RequireAuth>
                <div>Accounts page</div>
              </RequireAuth>
            }
          />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText("Login page")).toBeInTheDocument();
  });

  it("denies viewer direct access to admin-only routes", () => {
    renderWithProviders(
      <>
        <MemoryRouter initialEntries={["/organization/users"]}>
          <Routes>
            <Route path="/forbidden" element={<div>Forbidden</div>} />
            <Route
              path="/organization/users"
              element={
                <RequireRole allowedRoles={["ADMIN"]}>
                  <div>Users page</div>
                </RequireRole>
              }
            />
          </Routes>
        </MemoryRouter>
      </>,
      makeSession("VIEWER"),
    );

    expect(screen.getByText("Forbidden")).toBeInTheDocument();
    expect(screen.queryByText("Users page")).not.toBeInTheDocument();
  });

  it("allows accountant access to journal route", () => {
    renderWithProviders(
      <MemoryRouter initialEntries={["/journal"]}>
        <Routes>
          <Route
            path="/journal"
            element={
              <RequireRole allowedRoles={["ADMIN", "ACCOUNTANT"]}>
                <div>Journal page</div>
              </RequireRole>
            }
          />
        </Routes>
      </MemoryRouter>,
      makeSession("ACCOUNTANT"),
    );

    expect(screen.getByText("Journal page")).toBeInTheDocument();
  });

  it("shows account and journal management actions for accountants", async () => {
    renderWithProviders(
      <MemoryRouter>
        <>
          <AccountsPage />
          <JournalPage />
        </>
      </MemoryRouter>,
      makeSession("ACCOUNTANT"),
    );

    expect(
      await screen.findByRole("button", { name: /new account/i }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: /new draft/i }),
    ).toBeInTheDocument();
  });

  it("allows viewer direct access to journal route as read-only", async () => {
    const viewerSession = makeSession("VIEWER");
    localStorage.setItem(
      "ledger.frontend.auth.session",
      JSON.stringify(viewerSession),
    );
    window.history.replaceState({}, "", "/journal");

    render(<App />);

    expect(
      await screen.findByRole("heading", { name: "Journal" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "New draft" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("Post")).not.toBeInTheDocument();
  });

  it("hides management actions for viewer users", () => {
    renderWithProviders(
      <MemoryRouter>
        <>
          <AccountsPage />
          <JournalPage />
        </>
      </MemoryRouter>,
      makeSession("VIEWER"),
    );

    expect(screen.queryByText("New account")).not.toBeInTheDocument();
    expect(screen.queryByText("New draft")).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/edit .*cash/i)).not.toBeInTheDocument();
    expect(screen.queryByText("Post")).not.toBeInTheDocument();
  });

  it("keeps the mock and API service contracts aligned", () => {
    const mockService = createMockService();
    const apiService = createApiService(() => null, null);

    expect(Object.keys(mockService).sort()).toEqual(
      Object.keys(apiService).sort(),
    );
  });
});

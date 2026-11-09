// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../../app/auth-context";
import { env } from "../../lib/env";
import type { LedgerService } from "../../services/interfaces/ledger-service";
import { ServiceContext } from "../../services/interfaces/service-context";
import { createMockService } from "../../services/mock/mock-service";
import type { AuthSession } from "../../types/domain";
import { AppShell } from "./app-shell";

describe("app shell mode indicators", () => {
  beforeEach(() => {
    env.mode = "mock";
    localStorage.clear();
    vi.restoreAllMocks();
  });

  const renderShell = (sessionOverride?: AuthSession | null) => {
    if (sessionOverride) {
      localStorage.setItem(
        "ledger.frontend.auth.session",
        JSON.stringify(sessionOverride),
      );
    } else {
      localStorage.removeItem("ledger.frontend.auth.session");
    }

    render(
      <AuthProvider>
        <MemoryRouter>
          <AppShell>
            <div>Body content</div>
          </AppShell>
        </MemoryRouter>
      </AuthProvider>,
    );
  };

  it("does not display an API-mode label in the shell when using the backend", () => {
    env.mode = "api";
    renderShell({
      token: "demo-token",
      expiresAt: "2099-12-31T23:59:59Z",
      user: {
        id: 1,
        email: "viewer@northstar.co",
        fullName: "Viewer User",
        role: "VIEWER",
        organizationId: 42,
      },
    });

    expect(screen.queryByText(/api mode/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/backend mode/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/mock mode/i)).not.toBeInTheDocument();
  });

  it("keeps the mock/demo indicator visible in mock mode", () => {
    env.mode = "mock";
    renderShell({
      token: "mock-token",
      expiresAt: "2099-12-31T23:59:59Z",
      user: {
        id: 10,
        email: "admin@northstar.co",
        fullName: "Admin User",
        role: "ADMIN",
        organizationId: 42,
      },
    });

    expect(screen.getByText(/mock mode/i)).toBeInTheDocument();
  });

  it("still shows the demo indicator when no session is active in mock mode", () => {
    env.mode = "mock";
    renderShell(null);

    expect(screen.getByText(/demo mode/i)).toBeInTheDocument();
  });

  it("revokes the backend refresh token on sign out before clearing local session", async () => {
    env.mode = "api";
    const session: AuthSession = {
      token: "jwt-1",
      expiresAt: "2099-12-31T23:59:59Z",
      refreshToken: "refresh-token-xyz",
      user: {
        id: 10,
        email: "admin@northstar.co",
        fullName: "Admin User",
        role: "ADMIN",
        organizationId: 42,
      },
    };
    localStorage.setItem("ledger.frontend.auth.session", JSON.stringify(session));

    const logoutSpy = vi.fn<LedgerService["logout"]>(async () => undefined);
    const service: LedgerService = {
      ...createMockService(session),
      logout: logoutSpy,
    };

    render(
      <AuthProvider>
        <ServiceContext.Provider value={service}>
          <MemoryRouter initialEntries={["/"]}>
            <Routes>
              <Route
                path="/"
                element={
                  <AppShell>
                    <div>Workspace body</div>
                  </AppShell>
                }
              />
              <Route path="/login" element={<div>Login screen</div>} />
            </Routes>
          </MemoryRouter>
        </ServiceContext.Provider>
      </AuthProvider>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => {
      expect(logoutSpy).toHaveBeenCalledWith("refresh-token-xyz");
      expect(screen.getByText("Login screen")).toBeInTheDocument();
    });
    expect(localStorage.getItem("ledger.frontend.auth.session")).toBeNull();
  });

});

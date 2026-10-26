// @vitest-environment jsdom
import { render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "../../app/auth-context";
import { env } from "../../lib/env";
import { AppShell } from "./app-shell";

describe("app shell mode indicators", () => {
  beforeEach(() => {
    env.mode = "mock";
    localStorage.clear();
    vi.restoreAllMocks();
  });

  const renderShell = (sessionOverride?: { token: string; expiresAt: string; user: any } | null) => {
    const TestHarness = () => {
      const { setSession } = useAuth();

      useEffect(() => {
        if (sessionOverride) {
          setSession(sessionOverride);
        } else {
          setSession(null);
        }
      }, [sessionOverride, setSession]);

      return (
        <AppShell>
          <div>Body content</div>
        </AppShell>
      );
    };

    render(
      <AuthProvider>
        <MemoryRouter>
          <TestHarness />
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
});

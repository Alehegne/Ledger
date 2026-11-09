// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import { AuthProvider } from "../../app/auth-context";
import { ToastProvider } from "../../app/toast-provider";
import { env } from "../../lib/env";
import {
  createMockService,
  resetMockServiceState,
} from "../../services/mock/mock-service";
import { ServiceContext } from "../../services/interfaces/service-context";
import type { AuthSession } from "../../types/domain";
import { LoginPage } from "../auth/login-page";
import { AdminAuditPage } from "./admin-audit-page";
import { AdminOrganizationsPage } from "./admin-organizations-page";
import { AdminOverviewPage } from "./admin-overview-page";

const superAdminSession: AuthSession = {
  token: "mock-super-token",
  expiresAt: "2099-12-31T00:00:00Z",
  user: {
    id: 1,
    email: "superadmin@ledger.local",
    fullName: "Platform Super Admin",
    role: "SUPER_ADMIN",
    organizationId: 3,
    organizationName: "Ledger Platform",
  },
};

const renderAdminUi = (
  ui: React.ReactNode,
  session: AuthSession | null = superAdminSession,
  initialEntries: string[] = ["/"],
) => {
  localStorage.clear();
  if (session) {
    localStorage.setItem(
      "ledger.frontend.auth.session",
      JSON.stringify(session),
    );
  }

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const service = createMockService(session);

  return render(
    <ToastProvider>
      <AuthProvider>
        <QueryClientProvider client={queryClient}>
          <ServiceContext.Provider value={service}>
            <MemoryRouter initialEntries={initialEntries}>{ui}</MemoryRouter>
          </ServiceContext.Provider>
        </QueryClientProvider>
      </AuthProvider>
    </ToastProvider>,
  );
};

describe("SUPER_ADMIN platform UI workflows", () => {
  beforeEach(() => {
    env.mode = "mock";
    resetMockServiceState();
    localStorage.clear();
  });

  it("LoginPage hides obsolete public registration and routes SUPER_ADMIN to /admin/overview", async () => {
    renderAdminUi(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/admin/overview" element={<div>Platform Dashboard</div>} />
        <Route path="/" element={<div>Tenant Dashboard</div>} />
      </Routes>,
      null,
      ["/login"],
    );

    expect(
      screen.queryByRole("link", { name: /register/i }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /accept invitation/i }),
    ).toHaveAttribute("href", "/accept-invitation");

    const emailInput = screen.getByPlaceholderText("alex@northstar.co");
    const passwordInput = screen.getByPlaceholderText("Enter password");

    fireEvent.change(emailInput, {
      target: { value: "superadmin@ledger.local" },
    });
    fireEvent.change(passwordInput, { target: { value: "SuperPass123!" } });
    fireEvent.click(
      screen.getByRole("button", { name: /continue to ledger/i }),
    );

    expect(
      await screen.findByText("Platform Dashboard"),
    ).toBeInTheDocument();
  });

  it("AdminOverviewPage displays aggregate customer metrics without platform org inflation", async () => {
    renderAdminUi(<AdminOverviewPage />);

    expect(
      await screen.findByRole("heading", { name: "Customer Platform Health" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/1 active • 1 pending • 0 suspended/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/0 disabled accounts/i)).toBeInTheDocument();
    expect(
      screen.getByText(/Posted entries across 12 accounts/i),
    ).toBeInTheDocument();
    expect(screen.getByText("All Balanced")).toBeInTheDocument();
    expect(screen.getByText("Harbor Logistics LLC")).toBeInTheDocument();
  });

  it("AdminOrganizationsPage supports invite-first provisioning without passwords, suspension guardrails, user disable, and recovery invites", async () => {
    renderAdminUi(<AdminOrganizationsPage />);

    expect(await screen.findByText("Northstar Co.")).toBeInTheDocument();
    expect(screen.getByText("Harbor Logistics LLC")).toBeInTheDocument();

    // 1. Invite-first provisioning (never sets a customer password)
    fireEvent.click(
      screen.getByRole("button", { name: /provision organization/i }),
    );
    expect(screen.queryByLabelText(/password/i)).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Organization name"), {
      target: { value: "Zephyr Treasury" },
    });
    fireEvent.change(screen.getByLabelText(/base currency/i), {
      target: { value: "GBP" },
    });
    fireEvent.change(screen.getByLabelText("Initial administrator email"), {
      target: { value: "founder@zephyr.io" },
    });

    fireEvent.click(
      screen.getByRole("button", {
        name: /create & issue onboarding invite/i,
      }),
    );

    expect(
      await screen.findByText(/Zephyr Treasury created \(PENDING\)/i),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("onboarding-invitation-token").textContent?.trim(),
    ).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(await screen.findByText("Zephyr Treasury")).toBeInTheDocument();

    // 2. Manage "Northstar Co." -> suspend, disable admin, hit NO_ACTIVE_ADMIN on reactivate, recover/re-enable, reactivate
    const northstarRow = screen.getByText("Northstar Co.").closest("tr")!;
    fireEvent.click(within(northstarRow).getByRole("button", { name: "Manage" }));

    expect(
      await screen.findByText(/Organization Governance #42/i),
    ).toBeInTheDocument();
    expect(await screen.findByText("alex@northstar.co")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Suspension reason"), {
      target: { value: "Compliance audit hold" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: /suspend organization/i }),
    );

    expect(
      await screen.findByText(/Organization suspended/i),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Reason: Compliance audit hold/i),
    ).toBeInTheDocument();

    // Disable sole ADMIN alex@northstar.co
    const alexRow = screen.getByText("alex@northstar.co").closest("tr")!;
    fireEvent.click(within(alexRow).getByRole("button", { name: /disable/i }));
    await waitFor(() => {
      expect(within(alexRow).getByText("DISABLED")).toBeInTheDocument();
    });

    // Attempting reactivation without an ACTIVE ADMIN surfaces NO_ACTIVE_ADMIN guidance
    fireEvent.click(
      screen.getByRole("button", { name: /reactivate organization/i }),
    );
    expect(
      await screen.findByText(/at least one active administrator/i),
    ).toBeInTheDocument();

    // Issue recovery invitation for disabled admin email
    fireEvent.click(within(alexRow).getByRole("button", { name: /recover/i }));
    expect(screen.getByLabelText("Recovery administrator email")).toHaveValue(
      "alex@northstar.co",
    );
    fireEvent.click(
      screen.getByRole("button", { name: /issue recovery invitation/i }),
    );
    expect(
      await screen.findByTestId("recovery-invitation-token"),
    ).toBeInTheDocument();

    // Re-enable admin directly and reactivate organization
    fireEvent.click(within(alexRow).getByRole("button", { name: /enable/i }));
    await waitFor(() => {
      expect(within(alexRow).getByText("ACTIVE")).toBeInTheDocument();
    });

    fireEvent.click(
      screen.getByRole("button", { name: /reactivate organization/i }),
    );
    await waitFor(() => {
      expect(
        screen.queryByText(/Organization suspended/i),
      ).not.toBeInTheDocument();
    });
  });

  it("AdminAuditPage displays and filters platform audit entries and metadata drawer", async () => {
    renderAdminUi(<AdminAuditPage />);

    expect(
      await screen.findByRole("heading", { name: "Platform Audit Trail" }),
    ).toBeInTheDocument();
    expect(await screen.findByText("ORGANIZATION_CREATED")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /metadata/i }));
    expect(
      await screen.findByRole("heading", { name: /Platform Audit Log #1/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/"invitedAdminEmail": "founder@harbor\.eu"/i),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    fireEvent.change(screen.getByLabelText("Filter by platform action"), {
      target: { value: "ORGANIZATION_SUSPENDED" },
    });
    expect(
      await screen.findByText(/No platform audit events matching criteria/i),
    ).toBeInTheDocument();
  });
});

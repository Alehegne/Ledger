// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect } from "react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "../../app/auth-context";
import { env } from "../../lib/env";
import { JournalPage } from "./journal-page";
import { ServiceContext } from "../../services/interfaces/service-context";
import {
  createMockService,
  resetMockServiceState,
} from "../../services/mock/mock-service";
import type { AuthSession } from "../../types/domain";

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

const renderJournalPage = (service = createMockService(session)) => {
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
    return <JournalPage />;
  };

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

describe("journal page workflows", () => {
  beforeEach(() => {
    resetMockServiceState();
    localStorage.clear();
  });

  it("hides journal mutations in api mode while preserving read-only reads", async () => {
    const originalMode = env.mode;
    env.mode = "api";

    try {
      const service = {
        ...createMockService(session),
        listJournalEntries: vi.fn(async () => ({
          page: 1,
          size: 10,
          totalElements: 1,
          totalPages: 1,
          items: [
            {
              id: 101,
              organizationId: 42,
              transactionDate: "2026-10-01",
              description: "Posted entry",
              reference: "REF-1",
              status: "POSTED",
              createdByUserId: 10,
              postedByUserId: 10,
              postedAt: "2026-10-01T00:00:00Z",
              totalDebits: "500.00",
              totalCredits: "500.00",
              lines: [
                {
                  id: 1,
                  accountId: 1,
                  accountCode: "1000",
                  accountName: "Cash",
                  accountType: "ASSET",
                  side: "DEBIT",
                  amount: "500.00",
                },
                {
                  id: 2,
                  accountId: 2,
                  accountCode: "2000",
                  accountName: "Payables",
                  accountType: "LIABILITY",
                  side: "CREDIT",
                  amount: "500.00",
                },
              ],
              createdAt: "2026-10-01T00:00:00Z",
              updatedAt: "2026-10-01T00:00:00Z",
            },
          ],
        })),
        getJournalEntry: vi.fn(async () => ({
          id: 101,
          organizationId: 42,
          transactionDate: "2026-10-01",
          description: "Posted entry",
          reference: "REF-1",
          status: "POSTED",
          createdByUserId: 10,
          postedByUserId: 10,
          postedAt: "2026-10-01T00:00:00Z",
          totalDebits: "500.00",
          totalCredits: "500.00",
          lines: [
            {
              id: 1,
              accountId: 1,
              accountCode: "1000",
              accountName: "Cash",
              accountType: "ASSET",
              side: "DEBIT",
              amount: "500.00",
            },
            {
              id: 2,
              accountId: 2,
              accountCode: "2000",
              accountName: "Payables",
              accountType: "LIABILITY",
              side: "CREDIT",
              amount: "500.00",
            },
          ],
          createdAt: "2026-10-01T00:00:00Z",
          updatedAt: "2026-10-01T00:00:00Z",
        })),
        createJournalDraft: vi.fn(),
        updateJournalDraft: vi.fn(),
        postJournalEntry: vi.fn(),
        deleteDraft: vi.fn(),
      } as any;

      renderJournalPage(service);

      expect(
        screen.queryByRole("button", { name: "New draft" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "Post" }),
      ).not.toBeInTheDocument();
      expect(screen.queryByLabelText(/Edit /i)).not.toBeInTheDocument();
      expect(screen.queryByLabelText(/Delete /i)).not.toBeInTheDocument();
    } finally {
      env.mode = originalMode;
    }
  });

  it("shows inline validation feedback for unbalanced draft totals", async () => {
    renderJournalPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "New draft" }));

    await user.type(
      screen.getByLabelText("Description"),
      "Unbalanced form validation",
    );

    await user.selectOptions(screen.getByLabelText("Account 1"), "1");
    await user.selectOptions(screen.getByLabelText("Account 2"), "5");

    const amountInputs = screen.getAllByRole("spinbutton", { name: "Amount" });
    await user.clear(amountInputs[0]);
    await user.type(amountInputs[0], "100");
    await user.clear(amountInputs[1]);
    await user.type(amountInputs[1], "75");

    await user.click(screen.getByRole("button", { name: "Create draft" }));

    expect(
      await screen.findByText(/Journal entry is not balanced/i),
    ).toBeInTheDocument();
  });

  it("excludes inactive accounts from new journal line selection", async () => {
    const service = createMockService(session);
    await service.updateAccount(1, { status: "INACTIVE" });

    renderJournalPage(service);
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "New draft" }));

    const accountSelect = screen.getByLabelText("Account 1");
    expect(accountSelect).not.toHaveTextContent("1000 - Cash & Equivalents");
  });

  it("creates and posts a balanced draft through the page", async () => {
    renderJournalPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "New draft" }));
    await user.type(screen.getByLabelText("Description"), "UI posted entry");
    await user.selectOptions(screen.getByLabelText("Account 1"), "1");
    await user.selectOptions(screen.getByLabelText("Account 2"), "5");

    const amountInputs = screen.getAllByRole("spinbutton", { name: "Amount" });
    await user.clear(amountInputs[0]);
    await user.type(amountInputs[0], "150");
    await user.clear(amountInputs[1]);
    await user.type(amountInputs[1], "150");

    await user.click(screen.getByRole("button", { name: "Create draft" }));
    expect(
      await screen.findByText(/Draft created successfully/i),
    ).toBeInTheDocument();

    const row = await screen.findByText("UI posted entry");
    const rowElement = row.closest("tr");
    expect(rowElement).not.toBeNull();
    if (!rowElement) return;

    const postButton = within(rowElement).getByRole("button", { name: "Post" });
    await user.click(postButton);

    expect(
      await screen.findByText(/Draft posted successfully/i),
    ).toBeInTheDocument();
    const postedRow = (await screen.findByText("UI posted entry")).closest(
      "tr",
    );
    expect(postedRow).not.toBeNull();
    if (!postedRow) return;
    expect(
      within(postedRow).queryByRole("button", { name: "Post" }),
    ).not.toBeInTheDocument();
    expect(
      within(postedRow).queryByLabelText(/Delete UI posted entry/i),
    ).not.toBeInTheDocument();
  });
});

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AuditPage } from "./audit-page";
import { ServiceContext } from "../../services/interfaces/service-context";
import type { LedgerService } from "../../services/interfaces/ledger-service";
import type { AuditLogItem, PageResult } from "../../types/domain";

const mockAuditLogs: AuditLogItem[] = [
  {
    id: 1,
    actor: "Admin (admin@ledger.test)",
    action: "ACCOUNT_CREATED",
    entityType: "ACCOUNT",
    entityId: "101",
    createdAt: "2026-11-08T12:00:00Z",
    metadata: JSON.stringify({ accountCode: "1010", name: "Operating Cash" }),
  },
  {
    id: 2,
    actor: "Accountant (accountant@ledger.test)",
    action: "JOURNAL_POSTED",
    entityType: "JOURNAL_ENTRY",
    entityId: "42",
    createdAt: "2026-11-08T12:30:00Z",
    metadata: JSON.stringify({ totalAmount: "1500.00" }),
  },
];

const mockPageResult: PageResult<AuditLogItem> = {
  page: 1,
  size: 15,
  totalElements: 2,
  totalPages: 1,
  items: mockAuditLogs,
};

describe("AuditPage", () => {
  it("renders audit logs table with actor and action badges", async () => {
    const listAuditLogs = vi.fn().mockResolvedValue(mockPageResult);
    const mockService = {
      listAuditLogs,
    } as unknown as LedgerService;

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <ServiceContext.Provider value={mockService}>
          <AuditPage />
        </ServiceContext.Provider>
      </QueryClientProvider>,
    );

    expect(screen.getByText("System Audit Trail")).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText("Admin (admin@ledger.test)")).toBeInTheDocument();
      expect(screen.getByText("ACCOUNT_CREATED")).toBeInTheDocument();
      expect(screen.getByText("JOURNAL_POSTED")).toBeInTheDocument();
    });
  });

  it("filters audit logs by action selection", async () => {
    const user = userEvent.setup();
    const listAuditLogs = vi.fn().mockResolvedValue(mockPageResult);
    const mockService = {
      listAuditLogs,
    } as unknown as LedgerService;

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <ServiceContext.Provider value={mockService}>
          <AuditPage />
        </ServiceContext.Provider>
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("ACCOUNT_CREATED")).toBeInTheDocument();
    });

    const actionSelect = screen.getByLabelText("Filter by action");
    await user.selectOptions(actionSelect, "JOURNAL_POSTED");

    await waitFor(() => {
      expect(listAuditLogs).toHaveBeenCalledWith(
        expect.objectContaining({ action: "JOURNAL_POSTED" }),
      );
    });
  });

  it("opens metadata modal when clicking Metadata button", async () => {
    const user = userEvent.setup();
    const listAuditLogs = vi.fn().mockResolvedValue(mockPageResult);
    const mockService = {
      listAuditLogs,
    } as unknown as LedgerService;

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <ServiceContext.Provider value={mockService}>
          <AuditPage />
        </ServiceContext.Provider>
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getAllByRole("button", { name: /Metadata/i })).toHaveLength(
        2,
      );
    });

    const metadataButtons = screen.getAllByRole("button", { name: /Metadata/i });
    await user.click(metadataButtons[0]);

    await waitFor(() => {
      expect(screen.getByText("Audit Log #1")).toBeInTheDocument();
      expect(screen.getByText(/Operating Cash/)).toBeInTheDocument();
    });

    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => {
      expect(screen.queryByText("Audit Log #1")).not.toBeInTheDocument();
    });
  });
});

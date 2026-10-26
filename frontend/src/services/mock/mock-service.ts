import Decimal from "decimal.js";
import type {
  Account,
  AccountBalance,
  AuditLogItem,
  AuthSession,
  GeneralLedgerReport,
  Invitation,
  JournalEntry,
  OrganizationSettings,
  OrganizationUser,
  OverviewSnapshot,
  PageResult,
  TrialBalanceReport,
} from "../../types/domain";
import type {
  LedgerService,
  ListAuditInput,
  ListJournalEntriesInput,
} from "../interfaces/ledger-service";

const accountSeed: Account[] = [
  {
    id: 1,
    organizationId: 42,
    accountCode: "1000",
    name: "Cash & Equivalents",
    accountType: "ASSET",
    status: "ACTIVE",
    parentAccountId: null,
    createdAt: "2025-01-01T00:00:00Z",
    updatedAt: "2025-01-16T00:00:00Z",
  },
  {
    id: 2,
    organizationId: 42,
    accountCode: "1100",
    name: "Accounts Receivable",
    accountType: "ASSET",
    status: "ACTIVE",
    parentAccountId: null,
    createdAt: "2025-01-01T00:00:00Z",
    updatedAt: "2025-01-16T00:00:00Z",
  },
  {
    id: 3,
    organizationId: 42,
    accountCode: "2000",
    name: "Accounts Payable",
    accountType: "LIABILITY",
    status: "ACTIVE",
    parentAccountId: null,
    createdAt: "2025-01-01T00:00:00Z",
    updatedAt: "2025-01-16T00:00:00Z",
  },
  {
    id: 4,
    organizationId: 42,
    accountCode: "3000",
    name: "Owner Equity",
    accountType: "EQUITY",
    status: "ACTIVE",
    parentAccountId: null,
    createdAt: "2025-01-01T00:00:00Z",
    updatedAt: "2025-01-16T00:00:00Z",
  },
  {
    id: 5,
    organizationId: 42,
    accountCode: "4000",
    name: "Revenue",
    accountType: "REVENUE",
    status: "ACTIVE",
    parentAccountId: null,
    createdAt: "2025-01-01T00:00:00Z",
    updatedAt: "2025-01-16T00:00:00Z",
  },
  {
    id: 6,
    organizationId: 42,
    accountCode: "5000",
    name: "Operating Expenses",
    accountType: "EXPENSE",
    status: "ACTIVE",
    parentAccountId: null,
    createdAt: "2025-01-01T00:00:00Z",
    updatedAt: "2025-01-16T00:00:00Z",
  },
];

const journalSeed: JournalEntry[] = [
  {
    id: 101,
    organizationId: 42,
    transactionDate: "2025-01-12",
    description: "Monthly subscription revenue",
    reference: "INV-1042",
    status: "POSTED",
    createdByUserId: 11,
    postedByUserId: 11,
    postedAt: "2025-01-12T18:00:00Z",
    totalDebits: "2400.00",
    totalCredits: "2400.00",
    lines: [
      {
        id: 1,
        accountId: 5,
        accountCode: "4000",
        accountName: "Revenue",
        accountType: "REVENUE",
        side: "CREDIT",
        amount: "2400.00",
      },
      {
        id: 2,
        accountId: 1,
        accountCode: "1000",
        accountName: "Cash & Equivalents",
        accountType: "ASSET",
        side: "DEBIT",
        amount: "2400.00",
      },
    ],
    createdAt: "2025-01-12T17:45:00Z",
    updatedAt: "2025-01-12T18:00:00Z",
  },
  {
    id: 102,
    organizationId: 42,
    transactionDate: "2025-01-18",
    description: "Payroll run",
    reference: "PAY-2081",
    status: "POSTED",
    createdByUserId: 12,
    postedByUserId: 12,
    postedAt: "2025-01-18T09:55:00Z",
    totalDebits: "3250.00",
    totalCredits: "3250.00",
    lines: [
      {
        id: 3,
        accountId: 6,
        accountCode: "5000",
        accountName: "Operating Expenses",
        accountType: "EXPENSE",
        side: "DEBIT",
        amount: "3250.00",
      },
      {
        id: 4,
        accountId: 1,
        accountCode: "1000",
        accountName: "Cash & Equivalents",
        accountType: "ASSET",
        side: "CREDIT",
        amount: "3250.00",
      },
    ],
    createdAt: "2025-01-18T09:30:00Z",
    updatedAt: "2025-01-18T09:55:00Z",
  },
  {
    id: 103,
    organizationId: 42,
    transactionDate: "2025-01-25",
    description: "Client prepayment",
    reference: "AR-2025-09",
    status: "DRAFT",
    createdByUserId: 13,
    postedByUserId: null,
    postedAt: null,
    totalDebits: "1500.00",
    totalCredits: "1500.00",
    lines: [
      {
        id: 5,
        accountId: 2,
        accountCode: "1100",
        accountName: "Accounts Receivable",
        accountType: "ASSET",
        side: "DEBIT",
        amount: "1500.00",
      },
      {
        id: 6,
        accountId: 5,
        accountCode: "4000",
        accountName: "Revenue",
        accountType: "REVENUE",
        side: "CREDIT",
        amount: "1500.00",
      },
    ],
    createdAt: "2025-01-25T12:00:00Z",
    updatedAt: "2025-01-25T12:00:00Z",
  },
];

const userSeed = [
  {
    id: 10,
    email: "alex@northstar.co",
    fullName: "Alex Morgan",
    role: "ADMIN" as const,
    invitationStatus: "ACTIVE" as const,
  },
  {
    id: 11,
    email: "mila@northstar.co",
    fullName: "Mila Alvarez",
    role: "ACCOUNTANT" as const,
    invitationStatus: "ACTIVE" as const,
  },
  {
    id: 12,
    email: "nate@northstar.co",
    fullName: "Nate Brooks",
    role: "VIEWER" as const,
    invitationStatus: "PENDING" as const,
  },
];

const auditSeed: AuditLogItem[] = [
  {
    id: 1,
    actor: "Alex Morgan",
    action: "POSTED_ENTRY",
    entityType: "journal_entry",
    entityId: "101",
    createdAt: "2025-01-12T18:00:00Z",
    metadata: "INV-1042",
  },
  {
    id: 2,
    actor: "Mila Alvarez",
    action: "CREATED_ACCOUNT",
    entityType: "account",
    entityId: "6",
    createdAt: "2025-01-18T09:30:00Z",
    metadata: "Operating Expenses",
  },
  {
    id: 3,
    actor: "Alex Morgan",
    action: "INVITED_USER",
    entityType: "user",
    entityId: "12",
    createdAt: "2025-01-20T14:22:00Z",
    metadata: "Nate Brooks",
  },
];

const settingsSeed: OrganizationSettings = {
  id: 42,
  name: "Northstar Co.",
  baseCurrency: "USD",
  createdAt: "2024-08-01T00:00:00Z",
  editable: true,
};

const invitationsSeed: Invitation[] = [
  {
    id: 1,
    organizationId: 42,
    invitedEmail: "nate@northstar.co",
    role: "VIEWER",
    invitationToken: "tok_northstar_001",
    expiresAt: "2025-02-15T00:00:00Z",
    createdAt: "2025-01-20T14:20:00Z",
  },
];

const STORAGE_KEY = "ledger-demo-state-v1";
const STORAGE_VERSION_KEY = "ledger-demo-state-version";
const DEMO_STATE_VERSION = 1;

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

const toMoney = (value: Decimal.Value) =>
  new Decimal(value ?? 0).toDecimalPlaces(2, Decimal.ROUND_HALF_UP).toFixed(2);

const toDecimal = (value: Decimal.Value) => new Decimal(value ?? 0);

const normalizeAccountCode = (value: string) => value.trim();

const getPostedEntries = (entries: JournalEntry[]) =>
  entries.filter((entry) => entry.status === "POSTED");

const sortEntries = (entries: JournalEntry[]) =>
  [...entries].sort((left, right) => {
    const dateCompare = left.transactionDate.localeCompare(
      right.transactionDate,
    );
    if (dateCompare !== 0) return dateCompare;
    return left.id - right.id;
  });

const ensureValidAmount = (value: string | number) => {
  const decimalAmount = toDecimal(value);
  if (!decimalAmount.isFinite() || decimalAmount.lte(0)) {
    throw new Error("All journal line amounts must be positive numbers.");
  }

  return decimalAmount;
};

const validateDraftLines = (
  state: { accounts: Account[] },
  organizationId: number,
  payload: {
    lines: Array<{
      accountId: number;
      side: "DEBIT" | "CREDIT";
      amount: string;
    }>;
  },
) => {
  if (!payload.lines || payload.lines.length < 2) {
    throw new Error("Journal entries must contain at least two lines.");
  }

  const seenAccounts = new Set<number>();
  let debitTotal = new Decimal(0);
  let creditTotal = new Decimal(0);

  for (const line of payload.lines) {
    const account = state.accounts.find((item) => item.id === line.accountId);
    if (!account) {
      throw new Error(
        `Account ${line.accountId} does not belong to this organization.`,
      );
    }

    if (account.organizationId !== organizationId) {
      throw new Error(
        `Account ${line.accountId} does not belong to this organization.`,
      );
    }

    if (account.status === "INACTIVE") {
      throw new Error(
        `Account ${account.accountCode} is inactive and cannot be used for a new posting.`,
      );
    }

    const amount = ensureValidAmount(line.amount);
    if (line.side === "DEBIT") {
      debitTotal = debitTotal.plus(amount);
    } else {
      creditTotal = creditTotal.plus(amount);
    }

    seenAccounts.add(account.id);
  }

  if (seenAccounts.size < 2) {
    throw new Error(
      "Journal entries must reference at least two unique accounts.",
    );
  }

  if (!debitTotal.equals(creditTotal)) {
    throw new Error(
      "Journal entry is not balanced. Debits and credits must match.",
    );
  }

  return { debitTotal, creditTotal };
};

const normalizeJournalPayload = (payload: {
  transactionDate: string;
  description: string;
  reference?: string | null;
  lines: Array<{
    accountId: number;
    side: "DEBIT" | "CREDIT";
    amount: string;
  }>;
}) => {
  if (!payload.description?.trim()) {
    throw new Error("Journal entry description is required.");
  }

  if (!payload.transactionDate) {
    throw new Error("Journal entry date is required.");
  }

  const safeLines = payload.lines.map((line) => ({
    ...line,
    accountId: Number(line.accountId),
    amount: String(line.amount ?? "0"),
  }));

  return { ...payload, lines: safeLines };
};

const getInitialState = () => ({
  accounts: clone(accountSeed),
  journalEntries: clone(journalSeed),
  users: clone(userSeed),
  invitations: clone(invitationsSeed),
  settings: clone(settingsSeed),
  auditLogs: clone(auditSeed),
});

export const resetMockServiceState = () => {
  const fresh = getInitialState();
  if (typeof window !== "undefined") {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(fresh));
    window.localStorage.setItem(
      STORAGE_VERSION_KEY,
      String(DEMO_STATE_VERSION),
    );
  }
  return fresh;
};

const loadState = () => {
  if (typeof window === "undefined") {
    return getInitialState();
  }

  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const savedVersion = Number(
      window.localStorage.getItem(STORAGE_VERSION_KEY) ?? "1",
    );
    if (!raw || savedVersion !== DEMO_STATE_VERSION) {
      return resetMockServiceState();
    }

    const parsed = JSON.parse(raw) as Partial<
      ReturnType<typeof getInitialState>
    >;
    if (
      !parsed ||
      !Array.isArray(parsed.accounts) ||
      !Array.isArray(parsed.journalEntries) ||
      !Array.isArray(parsed.users) ||
      !Array.isArray(parsed.invitations)
    ) {
      throw new Error("Stored ledger state is invalid.");
    }

    return {
      accounts: parsed.accounts as Account[],
      journalEntries: parsed.journalEntries as JournalEntry[],
      users: parsed.users as typeof userSeed,
      invitations: parsed.invitations as typeof invitationsSeed,
      settings: (parsed.settings ?? settingsSeed) as OrganizationSettings,
      auditLogs: (parsed.auditLogs ?? auditSeed) as AuditLogItem[],
    };
  } catch {
    if (typeof window !== "undefined") {
      window.localStorage.removeItem(STORAGE_KEY);
      window.localStorage.setItem(
        STORAGE_VERSION_KEY,
        String(DEMO_STATE_VERSION),
      );
    }
    return getInitialState();
  }
};

export const createMockService = (
  currentSession: Pick<AuthSession, "user"> | null = null,
): LedgerService => {
  const state = loadState();

  const persist = () => {
    if (typeof window !== "undefined") {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    }
  };

  const makeSession = (): AuthSession => ({
    token: "mock-token-123",
    expiresAt: "2099-12-31T23:59:59Z",
    user: {
      id: 10,
      email: "alex@northstar.co",
      fullName: "Alex Morgan",
      role: "ADMIN",
      organizationId: 42,
      organizationName: "Northstar Co.",
    },
  });

  const activeSession = currentSession ?? makeSession();
  const organizationId = activeSession.user.organizationId;
  const currentRole = activeSession.user.role;
  const currentUserId = activeSession.user.id;

  const requireRole = (
    allowedRoles: Array<AuthSession["user"]["role"]>,
    action: string,
  ) => {
    if (!allowedRoles.includes(currentRole)) {
      throw new Error(`You are not authorized to ${action}.`);
    }
  };

  const validateJournalDraft = (payload: {
    transactionDate: string;
    description: string;
    reference?: string | null;
    lines: Array<{
      accountId: number;
      side: "DEBIT" | "CREDIT";
      amount: string;
    }>;
  }) => {
    const normalized = normalizeJournalPayload(payload);
    return validateDraftLines(state, organizationId, normalized);
  };

  const computeBalanceForAccount = (accountId: number, asOfDate?: string) => {
    const targetDate = asOfDate ?? "9999-12-31";
    return getPostedEntries(state.journalEntries)
      .filter(
        (entry) =>
          entry.organizationId === organizationId &&
          entry.transactionDate <= targetDate,
      )
      .flatMap((entry) =>
        entry.lines
          .filter((line) => line.accountId === accountId)
          .map((line) =>
            line.side === "DEBIT"
              ? new Decimal(line.amount)
              : new Decimal(line.amount).negated(),
          ),
      )
      .reduce((sum, delta) => sum.plus(delta), new Decimal(0));
  };

  return {
    async login(): Promise<AuthSession> {
      return makeSession();
    },

    async getOverview(): Promise<OverviewSnapshot> {
      const organizationAccounts = state.accounts.filter(
        (account) => account.organizationId === organizationId,
      );
      const organizationEntries = state.journalEntries.filter(
        (entry) => entry.organizationId === organizationId,
      );
      const postedEntries = getPostedEntries(organizationEntries);
      const totalAssets = organizationAccounts
        .filter((account) => account.accountType === "ASSET")
        .reduce(
          (sum, account) =>
            sum.plus(computeBalanceForAccount(account.id, "2025-01-31")),
          new Decimal(0),
        );
      const totalRevenue = organizationAccounts
        .filter((account) => account.accountType === "REVENUE")
        .reduce(
          (sum, account) =>
            sum.plus(computeBalanceForAccount(account.id, "2025-01-31")),
          new Decimal(0),
        );
      const totalExpenses = organizationAccounts
        .filter((account) => account.accountType === "EXPENSE")
        .reduce(
          (sum, account) =>
            sum.plus(computeBalanceForAccount(account.id, "2025-01-31").abs()),
          new Decimal(0),
        );

      return {
        asOfDate: "2025-01-31",
        totalAssets: toMoney(totalAssets),
        totalRevenue: toMoney(totalRevenue),
        totalExpenses: toMoney(totalExpenses),
        draftsCount: organizationEntries.filter(
          (entry) => entry.status === "DRAFT",
        ).length,
        postedCount: postedEntries.length,
        recentEntries: sortEntries(organizationEntries).slice(0, 3),
      };
    },

    async listAccounts(): Promise<Account[]> {
      return clone(
        state.accounts.filter(
          (account) => account.organizationId === organizationId,
        ),
      );
    },

    async getAccount(accountId: number): Promise<Account> {
      const account = state.accounts.find(
        (item) =>
          item.id === accountId && item.organizationId === organizationId,
      );
      if (!account) throw new Error(`Account ${accountId} not found`);
      return clone(account);
    },

    async getAccountBalance(accountId: number): Promise<AccountBalance> {
      const account = await this.getAccount(accountId);
      const balance = computeBalanceForAccount(accountId, "2025-01-31");

      return {
        accountId: account.id,
        accountCode: account.accountCode,
        accountType: account.accountType,
        balance: toMoney(balance),
      };
    },

    async createAccount(payload): Promise<Account> {
      requireRole(["ADMIN", "ACCOUNTANT"], "manage accounts");
      const trimmedCode = normalizeAccountCode(payload.accountCode);
      if (!trimmedCode) {
        throw new Error("Account code is required.");
      }
      if (!payload.name?.trim()) {
        throw new Error("Account name is required.");
      }
      if (
        state.accounts.some(
          (item) =>
            item.organizationId === organizationId &&
            normalizeAccountCode(item.accountCode).toUpperCase() ===
              trimmedCode.toUpperCase(),
        )
      ) {
        throw new Error(`Account code ${trimmedCode} already exists.`);
      }
      if (payload.parentAccountId) {
        const parent = state.accounts.find(
          (item) =>
            item.id === payload.parentAccountId &&
            item.organizationId === organizationId,
        );
        if (!parent) {
          throw new Error("Parent account not found.");
        }
        if (parent.accountType !== payload.accountType) {
          throw new Error("Parent account type must match account type.");
        }
      }
      const nextId = Math.max(...state.accounts.map((item) => item.id), 0) + 1;
      const account: Account = {
        id: nextId,
        organizationId,
        accountCode: trimmedCode,
        name: payload.name.trim(),
        accountType: payload.accountType,
        status: payload.status,
        parentAccountId: payload.parentAccountId ?? null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      state.accounts.push(account);
      persist();
      return clone(account);
    },

    async updateAccount(accountId: number, payload): Promise<Account> {
      requireRole(["ADMIN", "ACCOUNTANT"], "manage accounts");
      const index = state.accounts.findIndex(
        (item) =>
          item.id === accountId && item.organizationId === organizationId,
      );
      if (index < 0) throw new Error(`Account ${accountId} not found`);

      const nextCode = payload.accountCode
        ? normalizeAccountCode(payload.accountCode)
        : state.accounts[index].accountCode;
      if (
        nextCode &&
        state.accounts.some(
          (item) =>
            item.id !== accountId &&
            item.organizationId === organizationId &&
            normalizeAccountCode(item.accountCode).toUpperCase() ===
              nextCode.toUpperCase(),
        )
      ) {
        throw new Error(`Account code ${nextCode} already exists.`);
      }

      if (
        payload.parentAccountId !== undefined &&
        payload.parentAccountId !== null
      ) {
        const parent = state.accounts.find(
          (item) =>
            item.id === payload.parentAccountId &&
            item.organizationId === organizationId,
        );
        if (!parent) {
          throw new Error("Parent account not found.");
        }
        if (parent.id === accountId) {
          throw new Error("An account cannot be its own parent.");
        }
        const candidateType =
          payload.accountType ?? state.accounts[index].accountType;
        if (parent.accountType !== candidateType) {
          throw new Error("Parent account type must match account type.");
        }
      }

      state.accounts[index] = {
        ...state.accounts[index],
        ...payload,
        accountCode: nextCode,
        name: payload.name?.trim() ?? state.accounts[index].name,
        updatedAt: new Date().toISOString(),
      };
      persist();
      return clone(state.accounts[index]);
    },

    async deactivateAccount(accountId: number): Promise<Account> {
      requireRole(["ADMIN", "ACCOUNTANT"], "manage accounts");
      const account = await this.getAccount(accountId);
      if (account.status === "INACTIVE") {
        return clone(account);
      }
      return this.updateAccount(accountId, { status: "INACTIVE" });
    },

    async deleteAccount(accountId: number): Promise<void> {
      requireRole(["ADMIN", "ACCOUNTANT"], "delete accounts");
      const target = state.accounts.find(
        (account) =>
          account.id === accountId && account.organizationId === organizationId,
      );
      if (!target) {
        throw new Error(`Account ${accountId} not found`);
      }

      const hasChildren = state.accounts.some(
        (account) => account.parentAccountId === accountId,
      );
      if (hasChildren) {
        throw new Error("Cannot delete an account that has child accounts.");
      }

      const hasTransactions = state.journalEntries.some((entry) =>
        entry.lines.some((line) => line.accountId === accountId),
      );
      if (hasTransactions) {
        throw new Error("Cannot delete an account that has transactions.");
      }

      state.accounts = state.accounts.filter(
        (account) => account.id !== accountId,
      );
      persist();
    },

    async listJournalEntries(
      input: ListJournalEntriesInput,
    ): Promise<PageResult<JournalEntry>> {
      const filtered = sortEntries(
        state.journalEntries.filter((entry) => {
          if (entry.organizationId !== organizationId) return false;
          if (currentRole === "VIEWER" && entry.status !== "POSTED")
            return false;
          if (input.status && entry.status !== input.status) return false;
          if (
            input.accountId &&
            !entry.lines.some((line) => line.accountId === input.accountId)
          )
            return false;
          if (input.fromDate && entry.transactionDate < input.fromDate)
            return false;
          if (input.toDate && entry.transactionDate > input.toDate)
            return false;
          return true;
        }),
      ).reverse();

      const page = input.page ?? 1;
      const size = input.size ?? 10;
      const start = (page - 1) * size;
      const items = filtered
        .slice(start, start + size)
        .map((entry) => clone(entry));
      return {
        page,
        size,
        totalElements: filtered.length,
        totalPages: Math.max(1, Math.ceil(filtered.length / size)),
        items,
      };
    },

    async getJournalEntry(entryId: number): Promise<JournalEntry> {
      const entry = state.journalEntries.find(
        (item) => item.id === entryId && item.organizationId === organizationId,
      );
      if (!entry) throw new Error(`Journal entry ${entryId} not found`);
      if (currentRole === "VIEWER" && entry.status !== "POSTED") {
        throw new Error("You are not authorized to view draft entries.");
      }
      return clone(entry);
    },

    async createJournalDraft(payload): Promise<JournalEntry> {
      requireRole(["ADMIN", "ACCOUNTANT"], "create journal drafts");
      const normalized = normalizeJournalPayload(payload);
      const { debitTotal, creditTotal } = validateDraftLines(
        state,
        organizationId,
        normalized,
      );
      const nextId =
        Math.max(...state.journalEntries.map((entry) => entry.id), 0) + 1;
      const draft: JournalEntry = {
        id: nextId,
        organizationId,
        transactionDate: normalized.transactionDate,
        description: normalized.description,
        reference: normalized.reference ?? null,
        status: "DRAFT",
        createdByUserId: currentUserId,
        postedByUserId: null,
        postedAt: null,
        totalDebits: toMoney(debitTotal),
        totalCredits: toMoney(creditTotal),
        lines: normalized.lines.map((line, index) => {
          const account = state.accounts.find(
            (item) => item.id === line.accountId,
          )!;
          return {
            id: index + 1,
            accountId: line.accountId,
            accountCode: account.accountCode,
            accountName: account.name,
            accountType: account.accountType,
            side: line.side,
            amount: toMoney(line.amount),
          };
        }),
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      state.journalEntries.unshift(draft);
      persist();
      return clone(draft);
    },

    async updateJournalDraft(entryId: number, payload): Promise<JournalEntry> {
      requireRole(["ADMIN", "ACCOUNTANT"], "update journal drafts");
      const index = state.journalEntries.findIndex(
        (entry) =>
          entry.id === entryId && entry.organizationId === organizationId,
      );
      if (index < 0) throw new Error(`Journal entry ${entryId} not found`);
      if (state.journalEntries[index].status !== "DRAFT") {
        throw new Error("Posted entries cannot be edited or deleted.");
      }

      const normalized = normalizeJournalPayload(payload);
      const { debitTotal, creditTotal } = validateDraftLines(
        state,
        organizationId,
        normalized,
      );
      const updated: JournalEntry = {
        ...state.journalEntries[index],
        transactionDate: normalized.transactionDate,
        description: normalized.description,
        reference: normalized.reference ?? null,
        lines: normalized.lines.map((line, itemIndex) => {
          const account = state.accounts.find(
            (item) => item.id === line.accountId,
          )!;
          return {
            id: itemIndex + 1,
            accountId: line.accountId,
            accountCode: account.accountCode,
            accountName: account.name,
            accountType: account.accountType,
            side: line.side,
            amount: toMoney(line.amount),
          };
        }),
        totalDebits: toMoney(debitTotal),
        totalCredits: toMoney(creditTotal),
        updatedAt: new Date().toISOString(),
      };

      state.journalEntries[index] = updated;
      persist();
      return clone(updated);
    },

    async postJournalEntry(entryId: number): Promise<JournalEntry> {
      requireRole(["ADMIN", "ACCOUNTANT"], "post journal entries");
      const index = state.journalEntries.findIndex(
        (entry) =>
          entry.id === entryId && entry.organizationId === organizationId,
      );
      if (index < 0) throw new Error(`Journal entry ${entryId} not found`);
      if (state.journalEntries[index].status !== "DRAFT") {
        throw new Error("Only draft journal entries can be posted.");
      }

      const draft = state.journalEntries[index];
      validateJournalDraft({
        transactionDate: draft.transactionDate,
        description: draft.description,
        reference: draft.reference,
        lines: draft.lines.map((line) => ({
          accountId: line.accountId,
          side: line.side,
          amount: line.amount,
        })),
      });

      const posted = {
        ...draft,
        status: "POSTED" as const,
        postedByUserId: currentUserId,
        postedAt: new Date().toISOString(),
      };
      state.journalEntries[index] = posted;
      persist();
      return clone(posted);
    },

    async reverseJournalEntry(entryId: number): Promise<JournalEntry> {
      requireRole(["ADMIN", "ACCOUNTANT"], "reverse journal entries");
      const entry = await this.getJournalEntry(entryId);
      const nextId =
        Math.max(...state.journalEntries.map((item) => item.id), 0) + 1;

      const reversedLines: JournalEntry["lines"] = entry.lines.map((line) => ({
        ...line,
        id: line.id + 1000,
        side: line.side === "DEBIT" ? "CREDIT" : "DEBIT",
        amount: toMoney(new Decimal(line.amount).toString()),
      }));

      const debitTotal = reversedLines
        .filter((line) => line.side === "DEBIT")
        .reduce(
          (sum, line) => sum.plus(new Decimal(line.amount)),
          new Decimal(0),
        );
      const creditTotal = reversedLines
        .filter((line) => line.side === "CREDIT")
        .reduce(
          (sum, line) => sum.plus(new Decimal(line.amount)),
          new Decimal(0),
        );

      const reverse: JournalEntry = {
        ...entry,
        id: nextId,
        description: `${entry.description} reversal`,
        status: "POSTED",
        reference: entry.reference ? `${entry.reference}-REV` : "REVERSAL",
        postedByUserId: 10,
        postedAt: new Date().toISOString(),
        totalDebits: toMoney(debitTotal),
        totalCredits: toMoney(creditTotal),
        lines: reversedLines,
      };
      state.journalEntries.unshift(reverse);
      persist();
      return clone(reverse);
    },

    async deleteDraft(entryId: number): Promise<void> {
      requireRole(["ADMIN", "ACCOUNTANT"], "delete journal drafts");
      const target = state.journalEntries.find(
        (entry) =>
          entry.id === entryId && entry.organizationId === organizationId,
      );
      if (!target) return;
      if (target.status !== "DRAFT") {
        throw new Error("Posted entries cannot be edited or deleted.");
      }

      state.journalEntries = state.journalEntries.filter(
        (entry) => entry.id !== entryId,
      );
      persist();
    },

    async getGeneralLedger(input): Promise<GeneralLedgerReport> {
      const account =
        state.accounts.find(
          (item) =>
            item.id === input.accountId &&
            item.organizationId === organizationId,
        ) ??
        state.accounts.find((item) => item.organizationId === organizationId);
      if (!account) {
        throw new Error("No accounts are available for this organization.");
      }
      const fromDate = input.fromDate ?? "1970-01-01";
      const toDate = input.toDate ?? "9999-12-31";

      const filteredEntries = getPostedEntries(state.journalEntries)
        .filter((entry) => entry.organizationId === organizationId)
        .filter(
          (entry) =>
            entry.transactionDate >= fromDate &&
            entry.transactionDate <= toDate,
        )
        .filter((entry) =>
          entry.lines.some((line) => line.accountId === account.id),
        )
        .sort((left, right) => {
          const dateCompare = left.transactionDate.localeCompare(
            right.transactionDate,
          );
          if (dateCompare !== 0) return dateCompare;
          return left.id - right.id;
        });

      const openingBalance = getPostedEntries(state.journalEntries)
        .filter((entry) => entry.organizationId === organizationId)
        .filter((entry) => entry.transactionDate < fromDate)
        .flatMap((entry) =>
          entry.lines
            .filter((line) => line.accountId === account.id)
            .map((line) =>
              line.side === "DEBIT"
                ? new Decimal(line.amount)
                : new Decimal(line.amount).negated(),
            ),
        )
        .reduce((sum, delta) => sum.plus(delta), new Decimal(0));

      const ledgerLines = filteredEntries.flatMap((entry) =>
        entry.lines
          .filter((line) => line.accountId === account.id)
          .map((line) => ({
            journalEntryId: entry.id,
            transactionDate: entry.transactionDate,
            description: entry.description,
            reference: entry.reference,
            side: line.side,
            amount: line.amount,
          })),
      );

      let runningBalance = new Decimal(openingBalance);
      const runningLedgerLines = ledgerLines.map((line) => {
        const delta =
          line.side === "DEBIT"
            ? new Decimal(line.amount)
            : new Decimal(line.amount).negated();
        runningBalance = runningBalance.plus(delta);
        return {
          ...line,
          runningBalance: toMoney(runningBalance),
        };
      });

      const page = input.page ?? 1;
      const size = input.size ?? 10;
      const start = (page - 1) * size;
      const totalElements = runningLedgerLines.length;
      const totalPages = Math.max(1, Math.ceil(totalElements / size));
      const pagedLines = runningLedgerLines
        .slice(start, start + size)
        .map((line) => ({
          journalEntryId: line.journalEntryId,
          transactionDate: line.transactionDate,
          description: line.description,
          reference: line.reference,
          side: line.side,
          amount: toMoney(line.amount),
          runningBalance: line.runningBalance,
        }));

      return {
        accountId: account.id,
        accountCode: account.accountCode,
        accountName: account.name,
        accountType: account.accountType,
        lines: pagedLines,
        page,
        size,
        totalElements,
        totalPages,
        openingBalance: toMoney(openingBalance),
        closingBalance: toMoney(runningBalance),
      };
    },

    async getTrialBalance(asOfDate?: string): Promise<TrialBalanceReport> {
      const targetDate = asOfDate ?? "9999-12-31";
      const totals = new Map<number, { debit: Decimal; credit: Decimal }>();
      const organizationAccounts = state.accounts.filter(
        (account) => account.organizationId === organizationId,
      );
      for (const account of organizationAccounts) {
        totals.set(account.id, {
          debit: new Decimal(0),
          credit: new Decimal(0),
        });
      }

      for (const entry of getPostedEntries(state.journalEntries)) {
        if (entry.organizationId !== organizationId) continue;
        if (entry.transactionDate > targetDate) continue;
        for (const line of entry.lines) {
          const bucket = totals.get(line.accountId);
          if (!bucket) continue;
          if (line.side === "DEBIT") {
            bucket.debit = bucket.debit.plus(new Decimal(line.amount));
          } else {
            bucket.credit = bucket.credit.plus(new Decimal(line.amount));
          }
        }
      }

      const accounts: TrialBalanceReport["accounts"] = organizationAccounts.map(
        (account) => {
          const balances = totals.get(account.id)!;
          return {
            accountId: account.id,
            accountCode: account.accountCode,
            accountName: account.name,
            accountType: account.accountType,
            debitBalance: toMoney(balances.debit),
            creditBalance: toMoney(balances.credit),
          };
        },
      );

      const totalDebits = accounts.reduce(
        (sum, item) => sum.plus(new Decimal(item.debitBalance)),
        new Decimal(0),
      );
      const totalCredits = accounts.reduce(
        (sum, item) => sum.plus(new Decimal(item.creditBalance)),
        new Decimal(0),
      );

      return {
        asOfDate: targetDate,
        accounts,
        totalDebits: toMoney(totalDebits),
        totalCredits: toMoney(totalCredits),
        balanced: totalDebits.equals(totalCredits),
      };
    },

    async createInvitation(payload): Promise<Invitation> {
      requireRole(["ADMIN"], "invite users");
      const nextId =
        Math.max(...state.invitations.map((invitation) => invitation.id), 0) +
        1;
      const invitation: Invitation = {
        id: nextId,
        organizationId,
        invitedEmail: payload.email,
        role: payload.role,
        invitationToken: `token_${nextId}`,
        expiresAt: "2025-02-20T00:00:00Z",
        createdAt: new Date().toISOString(),
      };
      state.invitations.push(invitation);
      persist();
      return clone(invitation);
    },

    async listOrganizationUsers(): Promise<OrganizationUser[]> {
      requireRole(["ADMIN"], "view organization users");
      return clone(state.users).map((user) => ({
        id: user.id,
        email: user.email,
        fullName: user.fullName,
        role: user.role,
        invitationStatus: user.invitationStatus,
      }));
    },

    async getOrganizationSettings(): Promise<OrganizationSettings> {
      requireRole(["ADMIN"], "view organization settings");
      return { ...state.settings };
    },

    async updateOrganizationSettings(
      payload: Partial<{ name: string; baseCurrency: string }>,
    ): Promise<OrganizationSettings> {
      requireRole(["ADMIN"], "update organization settings");

      const nextName =
        payload.name === undefined ? state.settings.name : payload.name.trim();
      if (!nextName) {
        throw new Error("Organization name is required.");
      }
      if (
        payload.name !== undefined &&
        nextName !== state.settings.name &&
        state.users.some(
          (user) =>
            user.fullName.toLowerCase() === nextName.toLowerCase() || false,
        )
      ) {
        // name uniqueness is enforced at the organization-service level; mock state keeps the same contract.
      }

      const nextCurrency =
        payload.baseCurrency === undefined
          ? state.settings.baseCurrency
          : payload.baseCurrency.trim().toUpperCase();
      if (!/^[A-Z]{3}$/.test(nextCurrency)) {
        throw new Error("Currency must be a 3-letter ISO code.");
      }

      state.settings = {
        ...state.settings,
        name: nextName,
        baseCurrency: nextCurrency,
      };

      return { ...state.settings };
    },

    async listAuditLogs(
      input: ListAuditInput,
    ): Promise<PageResult<AuditLogItem>> {
      requireRole(["ADMIN"], "view audit logs");
      const page = input.page ?? 1;
      const size = input.size ?? 5;
      const start = (page - 1) * size;
      const items = state.auditLogs.slice(start, start + size);
      return {
        page,
        size,
        totalElements: state.auditLogs.length,
        totalPages: Math.max(1, Math.ceil(state.auditLogs.length / size)),
        items: clone(items),
      };
    },
  };
};

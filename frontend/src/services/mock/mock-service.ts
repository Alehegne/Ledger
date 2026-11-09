import Decimal from "decimal.js";
import { AppError } from "../../lib/errors";
import type {
  Account,
  AccountBalance,
  AdminCreateOrganizationResult,
  AuditLogItem,
  AuthSession,
  GeneralLedgerReport,
  Invitation,
  InvitationPurpose,
  JournalEntry,
  OrganizationSettings,
  OrganizationStatus,
  OrganizationUser,
  OverviewSnapshot,
  PageResult,
  PlatformAuditLogItem,
  PlatformOrganizationDetail,
  PlatformOrganizationSummary,
  PlatformOrganizationUser,
  PlatformOverview,
  RecoveryInvitationResult,
  Role,
  TrialBalanceReport,
  UserStatus,
} from "../../types/domain";
import type {
  LedgerService,
  ListAuditInput,
  ListJournalEntriesInput,
  ListPlatformAuditInput,
  ListPlatformOrganizationsInput,
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

const userSeed: OrganizationUser[] = [
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

interface MockPlatformOrganizationRecord {
  id: number;
  name: string;
  baseCurrency: string;
  status: OrganizationStatus;
  suspendedAt: string | null;
  suspendedReason: string | null;
  accountCount: number;
  postedEntryCount: number;
  draftEntryCount: number;
  trialBalanceBalanced: boolean;
  createdAt: string;
  updatedAt: string;
}

interface MockPlatformUserRecord {
  id: number;
  organizationId: number;
  email: string;
  fullName: string;
  role: Role;
  status: UserStatus;
  createdAt: string;
}

interface MockPlatformInvitationRecord {
  id: number;
  organizationId: number;
  invitedEmail: string;
  role: Role;
  purpose: InvitationPurpose;
  targetUserId: number | null;
  invitationToken: string;
  expiresAt: string;
  createdAt: string;
  acceptedAt: string | null;
  supersededAt: string | null;
}

const platformOrganizationsSeed: MockPlatformOrganizationRecord[] = [
  {
    id: 42,
    name: "Northstar Co.",
    baseCurrency: "USD",
    status: "ACTIVE",
    suspendedAt: null,
    suspendedReason: null,
    accountCount: 6,
    postedEntryCount: 2,
    draftEntryCount: 1,
    trialBalanceBalanced: true,
    createdAt: "2024-08-01T00:00:00Z",
    updatedAt: "2025-01-25T12:00:00Z",
  },
  {
    id: 43,
    name: "Harbor Logistics LLC",
    baseCurrency: "EUR",
    status: "PENDING",
    suspendedAt: null,
    suspendedReason: null,
    accountCount: 6,
    postedEntryCount: 0,
    draftEntryCount: 0,
    trialBalanceBalanced: true,
    createdAt: "2025-02-01T10:00:00Z",
    updatedAt: "2025-02-01T10:00:00Z",
  },
];

const platformUsersSeed: MockPlatformUserRecord[] = [
  {
    id: 10,
    organizationId: 42,
    email: "alex@northstar.co",
    fullName: "Alex Morgan",
    role: "ADMIN",
    status: "ACTIVE",
    createdAt: "2024-08-01T00:00:00Z",
  },
  {
    id: 11,
    organizationId: 42,
    email: "mila@northstar.co",
    fullName: "Mila Alvarez",
    role: "ACCOUNTANT",
    status: "ACTIVE",
    createdAt: "2024-09-10T00:00:00Z",
  },
  {
    id: 12,
    organizationId: 42,
    email: "nate@northstar.co",
    fullName: "Nate Brooks",
    role: "VIEWER",
    status: "ACTIVE",
    createdAt: "2025-01-20T14:20:00Z",
  },
];

const platformInvitationsSeed: MockPlatformInvitationRecord[] = [
  {
    id: 101,
    organizationId: 43,
    invitedEmail: "founder@harbor.eu",
    role: "ADMIN",
    purpose: "ONBOARDING",
    targetUserId: null,
    invitationToken: "mock_onboard_harbor_43",
    expiresAt: "2099-12-31T00:00:00Z",
    createdAt: "2025-02-01T10:00:00Z",
    acceptedAt: null,
    supersededAt: null,
  },
];

const platformAuditSeed: PlatformAuditLogItem[] = [
  {
    id: 1,
    actor: "superadmin@ledger.local",
    action: "ORGANIZATION_CREATED",
    targetOrganizationId: 43,
    targetOrganizationName: "Harbor Logistics LLC",
    targetUserId: null,
    targetUserEmail: null,
    createdAt: "2025-02-01T10:00:00Z",
    metadata: JSON.stringify({
      organizationName: "Harbor Logistics LLC",
      baseCurrency: "EUR",
      invitedAdminEmail: "founder@harbor.eu",
    }),
  },
];

const STORAGE_KEY = "ledger-demo-state-v1";
const STORAGE_VERSION_KEY = "ledger-demo-state-version";
const DEMO_STATE_VERSION = 2;

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
  platformOrganizations: clone(platformOrganizationsSeed),
  platformUsers: clone(platformUsersSeed),
  platformInvitations: clone(platformInvitationsSeed),
  platformAuditLogs: clone(platformAuditSeed),
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
      platformOrganizations: Array.isArray(parsed.platformOrganizations)
        ? (parsed.platformOrganizations as MockPlatformOrganizationRecord[])
        : clone(platformOrganizationsSeed),
      platformUsers: Array.isArray(parsed.platformUsers)
        ? (parsed.platformUsers as MockPlatformUserRecord[])
        : clone(platformUsersSeed),
      platformInvitations: Array.isArray(parsed.platformInvitations)
        ? (parsed.platformInvitations as MockPlatformInvitationRecord[])
        : clone(platformInvitationsSeed),
      platformAuditLogs: Array.isArray(parsed.platformAuditLogs)
        ? (parsed.platformAuditLogs as PlatformAuditLogItem[])
        : clone(platformAuditSeed),
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

  const makeSession = (user?: OrganizationUser): AuthSession => ({
    token: "mock-token-123",
    expiresAt: "2099-12-31T23:59:59Z",
    refreshToken: "mock-refresh-token-123",
    refreshExpiresAt: "2099-12-31T23:59:59Z",
    user: user
      ? {
          id: user.id,
          email: user.email,
          fullName: user.fullName,
          role: user.role,
          organizationId: 42,
          organizationName: state.settings.name,
          baseCurrency: state.settings.baseCurrency,
        }
      : {
          id: 10,
          email: "alex@northstar.co",
          fullName: "Alex Morgan",
          role: "ADMIN",
          organizationId: 42,
          organizationName: state.settings.name,
          baseCurrency: state.settings.baseCurrency,
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

  const recordPlatformAudit = (
    action: string,
    targetOrg: MockPlatformOrganizationRecord | null,
    targetUser: MockPlatformUserRecord | null,
    metadataObj: Record<string, unknown>,
  ) => {
    const nextId =
      Math.max(...state.platformAuditLogs.map((log) => log.id), 0) + 1;
    state.platformAuditLogs.unshift({
      id: nextId,
      actor: activeSession.user.email,
      action,
      targetOrganizationId: targetOrg?.id ?? null,
      targetOrganizationName: targetOrg?.name ?? null,
      targetUserId: targetUser?.id ?? null,
      targetUserEmail: targetUser?.email ?? null,
      createdAt: new Date().toISOString(),
      metadata: JSON.stringify(metadataObj),
    });
  };

  const buildPlatformDetail = (
    org: MockPlatformOrganizationRecord,
  ): PlatformOrganizationDetail => {
    const orgUsers = state.platformUsers.filter(
      (user) => user.organizationId === org.id,
    );
    const openInvites = state.platformInvitations
      .filter(
        (inv) =>
          inv.organizationId === org.id &&
          inv.acceptedAt === null &&
          inv.supersededAt === null,
      )
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));

    return {
      id: org.id,
      name: org.name,
      baseCurrency: org.baseCurrency,
      kind: "TENANT",
      status: org.status,
      suspendedAt: org.suspendedAt,
      suspendedReason: org.suspendedReason,
      userCount: orgUsers.length,
      activeUserCount: orgUsers.filter((u) => u.status === "ACTIVE").length,
      disabledUserCount: orgUsers.filter((u) => u.status === "DISABLED").length,
      activeAdminCount: orgUsers.filter(
        (u) => u.role === "ADMIN" && u.status === "ACTIVE",
      ).length,
      accountCount: org.accountCount,
      postedEntryCount: org.postedEntryCount,
      draftEntryCount: org.draftEntryCount,
      hasTransactions: org.postedEntryCount + org.draftEntryCount > 0,
      trialBalanceBalanced: org.trialBalanceBalanced,
      pendingInvitations: openInvites.map((inv) => ({
        id: inv.id,
        invitedEmail: inv.invitedEmail,
        role: inv.role,
        purpose: inv.purpose,
        expiresAt: inv.expiresAt,
        createdAt: inv.createdAt,
      })),
      createdAt: org.createdAt,
      updatedAt: org.updatedAt,
    };
  };

  return {
    async login(email?: string): Promise<AuthSession> {
      if (email && email.trim().toLowerCase().includes("super")) {
        return {
          token: "mock-super-admin-token",
          expiresAt: "2099-12-31T23:59:59Z",
          refreshToken: "mock-super-admin-refresh-token",
          refreshExpiresAt: "2099-12-31T23:59:59Z",
          user: {
            id: 1,
            email: email.trim().toLowerCase(),
            fullName: "Platform Super Admin",
            role: "SUPER_ADMIN",
            organizationId: 1,
            organizationName: "Ledger Platform",
            baseCurrency: "USD",
          },
        };
      }
      return makeSession();
    },

    async refreshSession(refreshToken: string): Promise<AuthSession> {
      void refreshToken;
      return {
        ...activeSession,
        token: "mock-token-refreshed",
        expiresAt: "2099-12-31T23:59:59Z",
        refreshToken: "mock-refresh-token-rotated",
        refreshExpiresAt: "2099-12-31T23:59:59Z",
      };
    },

    async logout(refreshToken?: string): Promise<void> {
      void refreshToken;
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

    async reverseJournalEntry(
      entryId: number,
      payload?: { reason?: string },
    ): Promise<JournalEntry> {
      requireRole(["ADMIN", "ACCOUNTANT"], "reverse journal entries");
      const entry = await this.getJournalEntry(entryId);
      if (entry.status !== "POSTED") {
        throw new Error("Only posted journal entries can be reversed.");
      }
      if (entry.reversedByEntryId) {
        throw new Error("This journal entry has already been reversed.");
      }
      if (entry.reversesEntryId) {
        throw new Error("A reversal journal entry cannot be reversed.");
      }

      const nextId =
        Math.max(...state.journalEntries.map((item) => item.id), 0) + 1;
      const cleanReason = payload?.reason?.trim() || `Reversal of entry #${entry.id}`;

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
        description: `Reversal of: ${entry.description} (reversal)`,
        status: "POSTED",
        reference: entry.reference ? `REV-${entry.reference}` : "REVERSAL",
        postedByUserId: currentUserId,
        postedAt: new Date().toISOString(),
        totalDebits: toMoney(debitTotal),
        totalCredits: toMoney(creditTotal),
        lines: reversedLines,
        reversesEntryId: entry.id,
        reversalReason: cleanReason,
        reversedByEntryId: null,
      };

      // Update original entry
      const origIndex = state.journalEntries.findIndex((item) => item.id === entryId);
      if (origIndex >= 0) {
        state.journalEntries[origIndex] = {
          ...state.journalEntries[origIndex],
          reversedByEntryId: nextId,
          reversalReason: cleanReason,
        };
      }

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

    async listPendingInvitations(): Promise<Invitation[]> {
      requireRole(["ADMIN"], "view pending invitations");
      return clone(state.invitations);
    },

    async revokeInvitation(invitationId: number): Promise<void> {
      requireRole(["ADMIN"], "revoke invitations");
      const index = state.invitations.findIndex(
        (inv) => inv.id === invitationId,
      );
      if (index === -1) {
        throw new Error("Invitation not found.");
      }
      state.invitations.splice(index, 1);
      persist();
    },

    async acceptInvitation(payload): Promise<{ message: string }> {
      const normalizedEmail = payload.email.trim().toLowerCase();
      const invitation = state.invitations.find(
        (inv) =>
          inv.invitedEmail.toLowerCase() === normalizedEmail &&
          inv.invitationToken === payload.token,
      );
      if (invitation) {
        const existingUser = state.users.find(
          (u) => u.email.toLowerCase() === normalizedEmail,
        );
        if (existingUser) {
          throw new Error("A user with this email already exists.");
        }
        const nextUserId = Math.max(...state.users.map((u) => u.id), 0) + 1;
        state.users.push({
          id: nextUserId,
          email: normalizedEmail,
          fullName: payload.fullName.trim(),
          role: invitation.role,
          invitationStatus: "ACTIVE",
        });
        state.invitations = state.invitations.filter(
          (inv) => inv.id !== invitation.id,
        );
        persist();
        return { message: "Invitation accepted successfully." };
      }

      const platformInvite = state.platformInvitations.find(
        (inv) =>
          inv.invitedEmail.toLowerCase() === normalizedEmail &&
          inv.invitationToken === payload.token,
      );
      if (!platformInvite) {
        throw new Error("Invitation is invalid or expired.");
      }
      if (platformInvite.supersededAt) {
        throw new AppError({
          status: 422,
          code: "INVITATION_SUPERSEDED",
          message: "Invitation has been superseded.",
        });
      }
      if (platformInvite.acceptedAt) {
        throw new AppError({
          status: 422,
          code: "INVITATION_ALREADY_USED",
          message: "Invitation has already been accepted.",
        });
      }

      const targetOrg = state.platformOrganizations.find(
        (org) => org.id === platformInvite.organizationId,
      );
      if (!targetOrg) {
        throw new Error("Organization not found.");
      }

      const now = new Date().toISOString();
      if (platformInvite.targetUserId !== null) {
        const targetUser = state.platformUsers.find(
          (u) => u.id === platformInvite.targetUserId,
        );
        if (!targetUser) {
          throw new Error("Recovery target user no longer exists.");
        }
        targetUser.fullName = payload.fullName.trim();
        targetUser.status = "ACTIVE";
      } else {
        const nextUserId =
          Math.max(...state.platformUsers.map((u) => u.id), 0) + 1;
        state.platformUsers.push({
          id: nextUserId,
          organizationId: targetOrg.id,
          email: normalizedEmail,
          fullName: payload.fullName.trim(),
          role: platformInvite.role,
          status: "ACTIVE",
          createdAt: now,
        });
      }

      if (
        platformInvite.purpose === "ONBOARDING" &&
        targetOrg.status === "PENDING"
      ) {
        targetOrg.status = "ACTIVE";
        targetOrg.updatedAt = now;
      }

      platformInvite.acceptedAt = now;
      persist();
      return { message: "Invitation accepted successfully." };
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

    async updateUserRole(
      userId: number,
      role: Role,
    ): Promise<OrganizationUser> {
      requireRole(["ADMIN"], "update user role");
      const targetUser = state.users.find((u) => u.id === userId);
      if (!targetUser) {
        throw new Error("User not found");
      }

      if (targetUser.role === "ADMIN" && role !== "ADMIN") {
        const adminCount = state.users.filter((u) => u.role === "ADMIN").length;
        if (adminCount <= 1) {
          throw new Error("Cannot demote the last administrator in the organization");
        }
      }

      targetUser.role = role;
      state.auditLogs.unshift({
        id: state.auditLogs.length + 1,
        actor: activeSession.user.email ?? "Admin",
        action: "USER_ROLE_UPDATED",
        entityType: "USER",
        entityId: String(userId),
        createdAt: new Date().toISOString(),
        metadata: JSON.stringify({ role }),
      });
      persist();

      return {
        id: targetUser.id,
        email: targetUser.email,
        fullName: targetUser.fullName,
        role: targetUser.role,
        invitationStatus: targetUser.invitationStatus,
      };
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

    async getPlatformOverview(): Promise<PlatformOverview> {
      requireRole(["SUPER_ADMIN"], "view platform overview");
      const orgs = state.platformOrganizations;
      return {
        totalTenantOrganizations: orgs.length,
        activeTenantOrganizations: orgs.filter((o) => o.status === "ACTIVE")
          .length,
        pendingTenantOrganizations: orgs.filter((o) => o.status === "PENDING")
          .length,
        suspendedTenantOrganizations: orgs.filter(
          (o) => o.status === "SUSPENDED",
        ).length,
        totalTenantUsers: state.platformUsers.length,
        disabledTenantUsers: state.platformUsers.filter(
          (u) => u.status === "DISABLED",
        ).length,
        totalTenantAccounts: orgs.reduce(
          (sum, org) => sum + org.accountCount,
          0,
        ),
        totalPostedJournalEntries: orgs.reduce(
          (sum, org) => sum + org.postedEntryCount,
          0,
        ),
        unbalancedTenantOrganizations: orgs.filter(
          (o) => !o.trialBalanceBalanced,
        ).length,
        lastPlatformActionAt: state.platformAuditLogs[0]?.createdAt ?? null,
      };
    },

    async listPlatformOrganizations(
      input: ListPlatformOrganizationsInput,
    ): Promise<PageResult<PlatformOrganizationSummary>> {
      requireRole(["SUPER_ADMIN"], "list tenant organizations");
      const search = input.search?.trim().toLowerCase() ?? "";
      const filtered = state.platformOrganizations
        .filter((org) => {
          if (input.status && org.status !== input.status) return false;
          if (search && !org.name.toLowerCase().includes(search)) return false;
          return true;
        })
        .sort((a, b) => {
          const cmp = b.createdAt.localeCompare(a.createdAt);
          return cmp !== 0 ? cmp : b.id - a.id;
        });

      const page = input.page ?? 1;
      const size = input.size ?? 20;
      const start = (page - 1) * size;
      const items: PlatformOrganizationSummary[] = filtered
        .slice(start, start + size)
        .map((org) => {
          const orgUsers = state.platformUsers.filter(
            (u) => u.organizationId === org.id,
          );
          const pendingInvitationCount = state.platformInvitations.filter(
            (inv) =>
              inv.organizationId === org.id &&
              inv.acceptedAt === null &&
              inv.supersededAt === null,
          ).length;

          return {
            id: org.id,
            name: org.name,
            baseCurrency: org.baseCurrency,
            status: org.status,
            userCount: orgUsers.length,
            activeAdminCount: orgUsers.filter(
              (u) => u.role === "ADMIN" && u.status === "ACTIVE",
            ).length,
            disabledUserCount: orgUsers.filter((u) => u.status === "DISABLED")
              .length,
            accountCount: org.accountCount,
            postedEntryCount: org.postedEntryCount,
            draftEntryCount: org.draftEntryCount,
            pendingInvitationCount,
            lastActivityAt: org.updatedAt,
            createdAt: org.createdAt,
            suspendedAt: org.suspendedAt,
            suspendedReason: org.suspendedReason,
          };
        });

      return {
        page,
        size,
        totalElements: filtered.length,
        totalPages: Math.max(1, Math.ceil(filtered.length / size)),
        items,
      };
    },

    async createPlatformOrganization(payload): Promise<AdminCreateOrganizationResult> {
      requireRole(["SUPER_ADMIN"], "provision tenant organizations");
      const name = payload.organizationName.trim();
      const baseCurrency = payload.baseCurrency.trim().toUpperCase();
      const adminEmail = payload.adminEmail.trim().toLowerCase();

      if (!name) {
        throw new Error("Organization name is required.");
      }
      if (name.toLowerCase() === "ledger platform") {
        throw new AppError({
          status: 422,
          code: "RESERVED_ORGANIZATION_NAME",
          message: "Organization name 'Ledger Platform' is reserved.",
        });
      }
      if (!/^[A-Z]{3}$/.test(baseCurrency)) {
        throw new AppError({
          status: 422,
          code: "INVALID_CURRENCY",
          message: "Base currency must be a 3-letter ISO code.",
        });
      }
      if (!adminEmail.includes("@")) {
        throw new Error("Valid administrator email is required.");
      }
      if (
        state.platformOrganizations.some(
          (org) => org.name.toLowerCase() === name.toLowerCase(),
        )
      ) {
        throw new AppError({
          status: 422,
          code: "ORGANIZATION_NAME_EXISTS",
          message: "Organization name already exists.",
        });
      }
      if (
        state.platformUsers.some((u) => u.email.toLowerCase() === adminEmail)
      ) {
        throw new AppError({
          status: 422,
          code: "DUPLICATE_EMAIL",
          message: "A user with this email already exists.",
        });
      }

      const now = new Date().toISOString();
      const nextOrgId =
        Math.max(...state.platformOrganizations.map((o) => o.id), 43) + 1;
      const newOrg: MockPlatformOrganizationRecord = {
        id: nextOrgId,
        name,
        baseCurrency,
        status: "PENDING",
        suspendedAt: null,
        suspendedReason: null,
        accountCount: 6,
        postedEntryCount: 0,
        draftEntryCount: 0,
        trialBalanceBalanced: true,
        createdAt: now,
        updatedAt: now,
      };
      state.platformOrganizations.unshift(newOrg);

      const nextInviteId =
        Math.max(...state.platformInvitations.map((i) => i.id), 100) + 1;
      const expiresAt = new Date(
        Date.now() + 7 * 24 * 60 * 60 * 1000,
      ).toISOString();
      const rawToken = `mock_onboard_${nextOrgId}_${nextInviteId}`;
      state.platformInvitations.unshift({
        id: nextInviteId,
        organizationId: nextOrgId,
        invitedEmail: adminEmail,
        role: "ADMIN",
        purpose: "ONBOARDING",
        targetUserId: null,
        invitationToken: rawToken,
        expiresAt,
        createdAt: now,
        acceptedAt: null,
        supersededAt: null,
      });

      recordPlatformAudit("ORGANIZATION_CREATED", newOrg, null, {
        organizationName: name,
        baseCurrency,
        invitedAdminEmail: adminEmail,
      });
      persist();

      return {
        organizationId: nextOrgId,
        organizationName: name,
        baseCurrency,
        status: "PENDING",
        invitationId: nextInviteId,
        invitedAdminEmail: adminEmail,
        invitationToken: rawToken,
        invitationExpiresAt: expiresAt,
        createdAt: now,
      };
    },

    async getPlatformOrganizationDetail(
      organizationId: number,
    ): Promise<PlatformOrganizationDetail> {
      requireRole(["SUPER_ADMIN"], "view tenant organization details");
      const org = state.platformOrganizations.find(
        (item) => item.id === organizationId,
      );
      if (!org) {
        throw new AppError({
          status: 404,
          code: "RESOURCE_NOT_FOUND",
          message: "Organization not found",
        });
      }
      return clone(buildPlatformDetail(org));
    },

    async updatePlatformOrganization(
      organizationId: number,
      payload: Partial<{ name: string; baseCurrency: string }>,
    ): Promise<PlatformOrganizationDetail> {
      requireRole(["SUPER_ADMIN"], "update tenant organizations");
      const org = state.platformOrganizations.find(
        (item) => item.id === organizationId,
      );
      if (!org) {
        throw new AppError({
          status: 404,
          code: "RESOURCE_NOT_FOUND",
          message: "Organization not found",
        });
      }

      const previousName = org.name;
      const previousCurrency = org.baseCurrency;

      if (payload.name !== undefined) {
        const nextName = payload.name.trim();
        if (!nextName) {
          throw new Error("Organization name is required.");
        }
        if (nextName.toLowerCase() === "ledger platform") {
          throw new AppError({
            status: 422,
            code: "RESERVED_ORGANIZATION_NAME",
            message: "Organization name 'Ledger Platform' is reserved.",
          });
        }
        if (
          state.platformOrganizations.some(
            (item) =>
              item.id !== organizationId &&
              item.name.toLowerCase() === nextName.toLowerCase(),
          )
        ) {
          throw new AppError({
            status: 422,
            code: "ORGANIZATION_NAME_EXISTS",
            message: "Organization name already exists.",
          });
        }
        org.name = nextName;
      }

      if (payload.baseCurrency !== undefined) {
        const nextCurrency = payload.baseCurrency.trim().toUpperCase();
        if (!/^[A-Z]{3}$/.test(nextCurrency)) {
          throw new AppError({
            status: 422,
            code: "INVALID_CURRENCY",
            message: "Currency must be a 3-letter ISO code.",
          });
        }
        if (
          nextCurrency !== org.baseCurrency &&
          org.postedEntryCount + org.draftEntryCount > 0
        ) {
          throw new AppError({
            status: 422,
            code: "BASE_CURRENCY_LOCKED",
            message:
              "Base currency cannot be changed after journal transactions have been created.",
          });
        }
        org.baseCurrency = nextCurrency;
      }

      org.updatedAt = new Date().toISOString();
      recordPlatformAudit("ORGANIZATION_UPDATED", org, null, {
        previousName,
        newName: org.name,
        previousBaseCurrency: previousCurrency,
        newBaseCurrency: org.baseCurrency,
      });
      persist();
      return clone(buildPlatformDetail(org));
    },

    async updatePlatformOrganizationStatus(
      organizationId: number,
      payload: { status: "ACTIVE" | "SUSPENDED"; reason?: string },
    ): Promise<PlatformOrganizationDetail> {
      requireRole(["SUPER_ADMIN"], "change tenant organization status");
      const org = state.platformOrganizations.find(
        (item) => item.id === organizationId,
      );
      if (!org) {
        throw new AppError({
          status: 404,
          code: "RESOURCE_NOT_FOUND",
          message: "Organization not found",
        });
      }

      const now = new Date().toISOString();
      const trimmedReason = payload.reason?.trim() ?? "";

      if (payload.status === "SUSPENDED") {
        if (!trimmedReason) {
          throw new AppError({
            status: 422,
            code: "SUSPENSION_REASON_REQUIRED",
            message: "A reason is required when suspending an organization.",
          });
        }
        if (org.status === "SUSPENDED") {
          throw new Error("Organization is already suspended.");
        }
        const previousStatus = org.status;
        org.status = "SUSPENDED";
        org.suspendedAt = now;
        org.suspendedReason = trimmedReason;
        org.updatedAt = now;
        recordPlatformAudit("ORGANIZATION_SUSPENDED", org, null, {
          previousStatus,
          reason: trimmedReason,
        });
        persist();
        return clone(buildPlatformDetail(org));
      }

      if (org.status === "PENDING") {
        throw new AppError({
          status: 422,
          code: "ORGANIZATION_PENDING",
          message:
            "Pending organization cannot be manually activated before onboarding completion.",
        });
      }
      if (org.status === "ACTIVE") {
        throw new Error("Organization is already active.");
      }

      const orgUsers = state.platformUsers.filter(
        (u) => u.organizationId === org.id,
      );
      const activeAdmins = orgUsers.filter(
        (u) => u.role === "ADMIN" && u.status === "ACTIVE",
      );

      let restoredStatus: OrganizationStatus;
      if (orgUsers.length === 0) {
        restoredStatus = "PENDING";
      } else if (activeAdmins.length >= 1) {
        restoredStatus = "ACTIVE";
      } else {
        throw new AppError({
          status: 409,
          code: "NO_ACTIVE_ADMIN",
          message:
            "Cannot reactivate organization without at least one active administrator. Enable a disabled administrator or complete a recovery invitation first.",
        });
      }

      org.status = restoredStatus;
      org.suspendedAt = null;
      org.suspendedReason = null;
      org.updatedAt = now;
      recordPlatformAudit("ORGANIZATION_REACTIVATED", org, null, {
        previousStatus: "SUSPENDED",
        restoredStatus,
      });
      persist();
      return clone(buildPlatformDetail(org));
    },

    async listPlatformOrganizationUsers(
      organizationId: number,
    ): Promise<PlatformOrganizationUser[]> {
      requireRole(["SUPER_ADMIN"], "list organization users");
      return clone(
        state.platformUsers
          .filter((u) => u.organizationId === organizationId)
          .sort((a, b) => a.fullName.localeCompare(b.fullName))
          .map((u) => ({
            id: u.id,
            email: u.email,
            fullName: u.fullName,
            role: u.role,
            status: u.status,
            createdAt: u.createdAt,
          })),
      );
    },

    async updatePlatformOrganizationUserStatus(
      organizationId: number,
      userId: number,
      status: UserStatus,
    ): Promise<PlatformOrganizationUser> {
      requireRole(["SUPER_ADMIN"], "update organization user status");
      const org = state.platformOrganizations.find(
        (item) => item.id === organizationId,
      );
      const targetUser = state.platformUsers.find(
        (u) => u.id === userId && u.organizationId === organizationId,
      );
      if (!org || !targetUser) {
        throw new AppError({
          status: 404,
          code: "RESOURCE_NOT_FOUND",
          message: "User not found in organization.",
        });
      }

      const previousStatus = targetUser.status;
      targetUser.status = status;
      const now = new Date().toISOString();

      if (status === "ACTIVE") {
        for (const inv of state.platformInvitations) {
          if (
            inv.organizationId === organizationId &&
            inv.targetUserId === targetUser.id &&
            inv.acceptedAt === null &&
            inv.supersededAt === null
          ) {
            inv.supersededAt = now;
          }
        }
      }

      if (previousStatus !== status) {
        recordPlatformAudit(
          status === "DISABLED"
            ? "TENANT_USER_DISABLED"
            : "TENANT_USER_ENABLED",
          org,
          targetUser,
          { email: targetUser.email, role: targetUser.role },
        );
      }
      persist();
      return clone({
        id: targetUser.id,
        email: targetUser.email,
        fullName: targetUser.fullName,
        role: targetUser.role,
        status: targetUser.status,
        createdAt: targetUser.createdAt,
      });
    },

    async createAdminRecoveryInvitation(
      organizationId: number,
      payload: { adminEmail: string },
    ): Promise<RecoveryInvitationResult> {
      requireRole(["SUPER_ADMIN"], "create admin recovery invitation");
      const org = state.platformOrganizations.find(
        (item) => item.id === organizationId,
      );
      if (!org) {
        throw new AppError({
          status: 404,
          code: "RESOURCE_NOT_FOUND",
          message: "Organization not found",
        });
      }

      const normalizedEmail = payload.adminEmail.trim().toLowerCase();
      const existing = state.platformUsers.find(
        (u) => u.email.toLowerCase() === normalizedEmail,
      );
      let targetUserId: number | null = null;
      let targetUser: MockPlatformUserRecord | null = null;
      if (existing) {
        if (existing.organizationId !== organizationId) {
          throw new AppError({
            status: 422,
            code: "DUPLICATE_EMAIL",
            message: "A user with this email already exists in another organization.",
          });
        }
        if (existing.status === "ACTIVE") {
          throw new AppError({
            status: 422,
            code: "USER_ALREADY_ACTIVE",
            message: "A user with this email is already active in this organization.",
          });
        }
        if (existing.role !== "ADMIN") {
          throw new AppError({
            status: 422,
            code: "TARGET_USER_NOT_ADMIN",
            message: "Existing disabled user does not hold ADMIN role.",
          });
        }
        targetUserId = existing.id;
        targetUser = existing;
      }

      const now = new Date().toISOString();
      for (const inv of state.platformInvitations) {
        if (
          inv.organizationId === organizationId &&
          inv.acceptedAt === null &&
          inv.supersededAt === null &&
          (inv.invitedEmail.toLowerCase() === normalizedEmail ||
            (targetUserId !== null && inv.targetUserId === targetUserId) ||
            (org.status === "PENDING" && inv.role === "ADMIN"))
        ) {
          inv.supersededAt = now;
        }
      }

      const nextInviteId =
        Math.max(...state.platformInvitations.map((i) => i.id), 100) + 1;
      const expiresAt = new Date(
        Date.now() + 7 * 24 * 60 * 60 * 1000,
      ).toISOString();
      const rawToken = `mock_recovery_${organizationId}_${nextInviteId}`;
      state.platformInvitations.unshift({
        id: nextInviteId,
        organizationId,
        invitedEmail: normalizedEmail,
        role: "ADMIN",
        purpose: "ADMIN_RECOVERY",
        targetUserId,
        invitationToken: rawToken,
        expiresAt,
        createdAt: now,
        acceptedAt: null,
        supersededAt: null,
      });

      recordPlatformAudit("ADMIN_RECOVERY_INVITED", org, targetUser, {
        invitedEmail: normalizedEmail,
        targetMode: targetUser ? "EXISTING_DISABLED_ADMIN" : "NEW_ADMIN_EMAIL",
      });
      persist();

      return {
        invitationId: nextInviteId,
        organizationId,
        invitedEmail: normalizedEmail,
        role: "ADMIN",
        purpose: "ADMIN_RECOVERY",
        targetUserId,
        invitationToken: rawToken,
        expiresAt,
        createdAt: now,
      };
    },

    async listPlatformAuditLogs(
      input: ListPlatformAuditInput,
    ): Promise<PageResult<PlatformAuditLogItem>> {
      requireRole(["SUPER_ADMIN"], "view platform audit logs");
      const filtered = state.platformAuditLogs.filter((log) => {
        if (input.action && log.action !== input.action) return false;
        if (
          input.targetOrganizationId !== undefined &&
          log.targetOrganizationId !== input.targetOrganizationId
        ) {
          return false;
        }
        const dateOnly = log.createdAt.slice(0, 10);
        if (input.fromDate && dateOnly < input.fromDate) return false;
        if (input.toDate && dateOnly > input.toDate) return false;
        return true;
      });
      const page = input.page ?? 1;
      const size = input.size ?? 20;
      const start = (page - 1) * size;
      return {
        page,
        size,
        totalElements: filtered.length,
        totalPages: Math.max(1, Math.ceil(filtered.length / size)),
        items: clone(filtered.slice(start, start + size)),
      };
    },
  };
};

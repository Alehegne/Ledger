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
import { UnsupportedInApiModeError } from "../../lib/errors";
import type { LedgerService } from "../interfaces/ledger-service";
import type {
  ListJournalEntriesInput,
  ListAuditInput,
} from "../interfaces/ledger-service";

type ApiHttp = (path: string, init?: RequestInit) => Promise<unknown> | unknown;

type ApiPage<T> = {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  items: T[];
};

type ApiJournalEntry = {
  id: number;
  organizationId: number;
  transactionDate: string;
  description: string;
  reference: string | null;
  status: JournalEntry["status"];
  createdByUserId: number;
  postedByUserId: number | null;
  postedAt: string | null;
  totalDebits: number | string;
  totalCredits: number | string;
  lines: Array<{
    id: number;
    accountId: number;
    accountCode: string;
    accountName: string;
    accountType: JournalEntry["lines"][number]["accountType"];
    side: JournalEntry["lines"][number]["side"];
    amount: number | string;
  }>;
  createdAt: string;
  updatedAt: string;
};

const toMoneyString = (value: number | string): string => String(value);

const toUiPage = (apiPage?: number) => (apiPage ?? 0) + 1;
const toApiPage = (uiPage?: number) => Math.max((uiPage ?? 1) - 1, 0);

const mapPageResult = <T>(page: ApiPage<T>): PageResult<T> => ({
  page: toUiPage(page.page),
  size: page.size,
  totalElements: page.totalElements,
  totalPages: page.totalPages,
  items: page.items,
});

const mapJournalLine = (value: unknown): JournalEntry["lines"][number] => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid journal entry response: malformed line payload.");
  }

  const validAccountTypes = [
    "ASSET",
    "LIABILITY",
    "EQUITY",
    "REVENUE",
    "EXPENSE",
  ] as const;
  const validSides = ["DEBIT", "CREDIT"] as const;

  if (typeof value.id !== "number" || !Number.isFinite(value.id)) {
    throw new Error("Invalid journal entry response: missing line id.");
  }

  if (
    typeof value.accountId !== "number" ||
    !Number.isFinite(value.accountId)
  ) {
    throw new Error("Invalid journal entry response: missing line accountId.");
  }

  if (
    typeof value.accountCode !== "string" ||
    value.accountCode.trim().length === 0
  ) {
    throw new Error(
      "Invalid journal entry response: missing line accountCode.",
    );
  }

  if (
    typeof value.accountName !== "string" ||
    value.accountName.trim().length === 0
  ) {
    throw new Error(
      "Invalid journal entry response: missing line accountName.",
    );
  }

  if (
    typeof value.accountType !== "string" ||
    !validAccountTypes.includes(
      value.accountType as (typeof validAccountTypes)[number],
    )
  ) {
    throw new Error(
      "Invalid journal entry response: missing or invalid line accountType.",
    );
  }

  if (
    typeof value.side !== "string" ||
    !validSides.includes(value.side as (typeof validSides)[number])
  ) {
    throw new Error(
      "Invalid journal entry response: missing or invalid line side.",
    );
  }

  if (typeof value.amount !== "number" && typeof value.amount !== "string") {
    throw new Error("Invalid journal entry response: missing line amount.");
  }

  return {
    id: value.id,
    accountId: value.accountId,
    accountCode: value.accountCode,
    accountName: value.accountName,
    accountType:
      value.accountType as JournalEntry["lines"][number]["accountType"],
    side: value.side as JournalEntry["lines"][number]["side"],
    amount: toMoneyString(value.amount),
  };
};

const mapGeneralLedgerLine = (
  value: unknown,
): GeneralLedgerReport["lines"][number] => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid general ledger response: malformed line payload.");
  }

  const validSides = ["DEBIT", "CREDIT"] as const;

  if (
    typeof value.journalEntryId !== "number" ||
    !Number.isFinite(value.journalEntryId)
  ) {
    throw new Error("Invalid general ledger response: missing journalEntryId.");
  }

  if (
    typeof value.transactionDate !== "string" ||
    value.transactionDate.trim().length === 0
  ) {
    throw new Error(
      "Invalid general ledger response: missing transactionDate.",
    );
  }

  if (
    typeof value.description !== "string" ||
    value.description.trim().length === 0
  ) {
    throw new Error("Invalid general ledger response: missing description.");
  }

  if (value.reference !== null && typeof value.reference !== "string") {
    throw new Error(
      "Invalid general ledger response: reference must be a string or null.",
    );
  }

  if (
    typeof value.side !== "string" ||
    !validSides.includes(value.side as (typeof validSides)[number])
  ) {
    throw new Error(
      "Invalid general ledger response: missing or invalid side.",
    );
  }

  if (typeof value.amount !== "number" && typeof value.amount !== "string") {
    throw new Error("Invalid general ledger response: missing amount.");
  }

  if (
    typeof value.runningBalance !== "number" &&
    typeof value.runningBalance !== "string"
  ) {
    throw new Error("Invalid general ledger response: missing runningBalance.");
  }

  return {
    journalEntryId: value.journalEntryId,
    transactionDate: value.transactionDate,
    description: value.description,
    reference: value.reference ?? null,
    side: value.side as GeneralLedgerReport["lines"][number]["side"],
    amount: toMoneyString(value.amount),
    runningBalance: toMoneyString(value.runningBalance),
  };
};

const mapOrganizationSettings = (value: unknown): OrganizationSettings => {
  if (!isPlainObject(value)) {
    throw new Error(
      "Invalid organization settings response: expected an object.",
    );
  }

  const { id, name, baseCurrency, createdAt } = value;

  if (typeof id !== "number" || !Number.isFinite(id)) {
    throw new Error("Invalid organization settings response: missing id.");
  }

  if (typeof name !== "string" || name.trim().length === 0) {
    throw new Error("Invalid organization settings response: missing name.");
  }

  if (typeof baseCurrency !== "string" || !/^[A-Z]{3}$/.test(baseCurrency)) {
    throw new Error(
      "Invalid organization settings response: invalid baseCurrency.",
    );
  }

  if (typeof createdAt !== "string" || createdAt.trim().length === 0) {
    throw new Error(
      "Invalid organization settings response: missing createdAt.",
    );
  }

  return {
    id,
    name,
    baseCurrency,
    createdAt,
  };
};

const mapInvitation = (value: unknown): Invitation => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid invitation response: expected an object.");
  }

  const validRoles = ["ADMIN", "ACCOUNTANT", "VIEWER"] as const;

  if (typeof value.id !== "number" || !Number.isFinite(value.id)) {
    throw new Error("Invalid invitation response: missing id.");
  }

  if (
    typeof value.organizationId !== "number" ||
    !Number.isFinite(value.organizationId)
  ) {
    throw new Error("Invalid invitation response: missing organizationId.");
  }

  if (
    typeof value.invitedEmail !== "string" ||
    value.invitedEmail.trim().length === 0
  ) {
    throw new Error("Invalid invitation response: missing invitedEmail.");
  }

  if (
    typeof value.role !== "string" ||
    !validRoles.includes(value.role as (typeof validRoles)[number])
  ) {
    throw new Error("Invalid invitation response: missing or invalid role.");
  }

  if (
    typeof value.invitationToken !== "string" ||
    value.invitationToken.trim().length === 0
  ) {
    throw new Error("Invalid invitation response: missing invitationToken.");
  }

  if (
    typeof value.expiresAt !== "string" ||
    value.expiresAt.trim().length === 0
  ) {
    throw new Error("Invalid invitation response: missing expiresAt.");
  }

  if (
    typeof value.createdAt !== "string" ||
    value.createdAt.trim().length === 0
  ) {
    throw new Error("Invalid invitation response: missing createdAt.");
  }

  return {
    id: value.id,
    organizationId: value.organizationId,
    invitedEmail: value.invitedEmail,
    role: value.role as Invitation["role"],
    invitationToken: value.invitationToken,
    expiresAt: value.expiresAt,
    createdAt: value.createdAt,
  };
};

const mapOrganizationUser = (value: unknown): OrganizationUser => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid organization user response: expected an object.");
  }

  const validRoles = ["ADMIN", "ACCOUNTANT", "VIEWER"] as const;
  const validStatuses = ["ACTIVE", "PENDING", "UNKNOWN"] as const;

  if (typeof value.id !== "number" || !Number.isFinite(value.id)) {
    throw new Error("Invalid organization user response: missing id.");
  }

  if (typeof value.email !== "string" || value.email.trim().length === 0) {
    throw new Error("Invalid organization user response: missing email.");
  }

  if (
    typeof value.fullName !== "string" ||
    value.fullName.trim().length === 0
  ) {
    throw new Error("Invalid organization user response: missing fullName.");
  }

  if (
    typeof value.role !== "string" ||
    !validRoles.includes(value.role as (typeof validRoles)[number])
  ) {
    throw new Error(
      "Invalid organization user response: missing or invalid role.",
    );
  }

  if (
    typeof value.invitationStatus !== "string" ||
    !validStatuses.includes(
      value.invitationStatus as (typeof validStatuses)[number],
    )
  ) {
    throw new Error(
      "Invalid organization user response: missing or invalid invitationStatus.",
    );
  }

  return {
    id: value.id,
    email: value.email,
    fullName: value.fullName,
    role: value.role as OrganizationUser["role"],
    invitationStatus:
      value.invitationStatus as OrganizationUser["invitationStatus"],
  };
};

const mapGeneralLedgerReport = (value: unknown): GeneralLedgerReport => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid general ledger response: expected an object.");
  }

  const validAccountTypes = [
    "ASSET",
    "LIABILITY",
    "EQUITY",
    "REVENUE",
    "EXPENSE",
  ] as const;

  if (
    typeof value.accountId !== "number" ||
    !Number.isFinite(value.accountId)
  ) {
    throw new Error("Invalid general ledger response: missing accountId.");
  }

  if (
    typeof value.accountCode !== "string" ||
    value.accountCode.trim().length === 0
  ) {
    throw new Error("Invalid general ledger response: missing accountCode.");
  }

  if (
    typeof value.accountName !== "string" ||
    value.accountName.trim().length === 0
  ) {
    throw new Error("Invalid general ledger response: missing accountName.");
  }

  if (
    typeof value.accountType !== "string" ||
    !validAccountTypes.includes(
      value.accountType as (typeof validAccountTypes)[number],
    )
  ) {
    throw new Error(
      "Invalid general ledger response: missing or invalid accountType.",
    );
  }

  if (!Array.isArray(value.lines)) {
    throw new Error("Invalid general ledger response: lines must be an array.");
  }

  if (typeof value.page !== "number" || !Number.isFinite(value.page)) {
    throw new Error("Invalid general ledger response: missing page.");
  }

  if (typeof value.size !== "number" || !Number.isFinite(value.size)) {
    throw new Error("Invalid general ledger response: missing size.");
  }

  if (
    typeof value.totalElements !== "number" ||
    !Number.isFinite(value.totalElements)
  ) {
    throw new Error("Invalid general ledger response: missing totalElements.");
  }

  if (
    typeof value.totalPages !== "number" ||
    !Number.isFinite(value.totalPages)
  ) {
    throw new Error("Invalid general ledger response: missing totalPages.");
  }

  if (
    typeof value.openingBalance !== "number" &&
    typeof value.openingBalance !== "string"
  ) {
    throw new Error("Invalid general ledger response: missing openingBalance.");
  }

  if (
    typeof value.closingBalance !== "number" &&
    typeof value.closingBalance !== "string"
  ) {
    throw new Error("Invalid general ledger response: missing closingBalance.");
  }

  return {
    accountId: value.accountId,
    accountCode: value.accountCode,
    accountName: value.accountName,
    accountType: value.accountType as GeneralLedgerReport["accountType"],
    lines: value.lines.map(mapGeneralLedgerLine),
    page: toUiPage(value.page),
    size: value.size,
    totalElements: value.totalElements,
    totalPages: Math.max(1, value.totalPages),
    openingBalance: toMoneyString(value.openingBalance),
    closingBalance: toMoneyString(value.closingBalance),
  };
};

const mapTrialBalanceAccount = (
  value: unknown,
): TrialBalanceReport["accounts"][number] => {
  if (!isPlainObject(value)) {
    throw new Error(
      "Invalid trial balance response: malformed account payload.",
    );
  }

  const validAccountTypes = [
    "ASSET",
    "LIABILITY",
    "EQUITY",
    "REVENUE",
    "EXPENSE",
  ] as const;

  if (
    typeof value.accountId !== "number" ||
    !Number.isFinite(value.accountId)
  ) {
    throw new Error("Invalid trial balance response: missing accountId.");
  }

  if (
    typeof value.accountCode !== "string" ||
    value.accountCode.trim().length === 0
  ) {
    throw new Error("Invalid trial balance response: missing accountCode.");
  }

  if (
    typeof value.accountName !== "string" ||
    value.accountName.trim().length === 0
  ) {
    throw new Error("Invalid trial balance response: missing accountName.");
  }

  if (
    typeof value.accountType !== "string" ||
    !validAccountTypes.includes(
      value.accountType as (typeof validAccountTypes)[number],
    )
  ) {
    throw new Error(
      "Invalid trial balance response: missing or invalid accountType.",
    );
  }

  if (
    typeof value.debitBalance !== "number" &&
    typeof value.debitBalance !== "string"
  ) {
    throw new Error("Invalid trial balance response: missing debitBalance.");
  }

  if (
    typeof value.creditBalance !== "number" &&
    typeof value.creditBalance !== "string"
  ) {
    throw new Error("Invalid trial balance response: missing creditBalance.");
  }

  return {
    accountId: value.accountId,
    accountCode: value.accountCode,
    accountName: value.accountName,
    accountType:
      value.accountType as TrialBalanceReport["accounts"][number]["accountType"],
    debitBalance: toMoneyString(value.debitBalance),
    creditBalance: toMoneyString(value.creditBalance),
  };
};

const mapTrialBalanceReport = (value: unknown): TrialBalanceReport => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid trial balance response: expected an object.");
  }

  if (
    typeof value.asOfDate !== "string" ||
    value.asOfDate.trim().length === 0
  ) {
    throw new Error("Invalid trial balance response: missing asOfDate.");
  }

  if (!Array.isArray(value.accounts)) {
    throw new Error(
      "Invalid trial balance response: accounts must be an array.",
    );
  }

  if (
    typeof value.totalDebits !== "number" &&
    typeof value.totalDebits !== "string"
  ) {
    throw new Error("Invalid trial balance response: missing totalDebits.");
  }

  if (
    typeof value.totalCredits !== "number" &&
    typeof value.totalCredits !== "string"
  ) {
    throw new Error("Invalid trial balance response: missing totalCredits.");
  }

  if (typeof value.balanced !== "boolean") {
    throw new Error("Invalid trial balance response: missing balanced flag.");
  }

  return {
    asOfDate: value.asOfDate,
    accounts: value.accounts.map(mapTrialBalanceAccount),
    totalDebits: toMoneyString(value.totalDebits),
    totalCredits: toMoneyString(value.totalCredits),
    balanced: value.balanced,
  };
};

const mapJournalEntry = (value: unknown): JournalEntry => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid journal entry response: expected an object.");
  }

  const validStatuses = ["DRAFT", "POSTED"] as const;

  if (typeof value.id !== "number" || !Number.isFinite(value.id)) {
    throw new Error("Invalid journal entry response: missing id.");
  }

  if (
    typeof value.organizationId !== "number" ||
    !Number.isFinite(value.organizationId)
  ) {
    throw new Error("Invalid journal entry response: missing organizationId.");
  }

  if (
    typeof value.transactionDate !== "string" ||
    value.transactionDate.trim().length === 0
  ) {
    throw new Error("Invalid journal entry response: missing transactionDate.");
  }

  if (
    typeof value.description !== "string" ||
    value.description.trim().length === 0
  ) {
    throw new Error("Invalid journal entry response: missing description.");
  }

  if (value.reference !== null && typeof value.reference !== "string") {
    throw new Error(
      "Invalid journal entry response: reference must be a string or null.",
    );
  }

  if (
    typeof value.status !== "string" ||
    !validStatuses.includes(value.status as (typeof validStatuses)[number])
  ) {
    throw new Error(
      "Invalid journal entry response: missing or invalid status.",
    );
  }

  if (
    typeof value.createdByUserId !== "number" ||
    !Number.isFinite(value.createdByUserId)
  ) {
    throw new Error("Invalid journal entry response: missing createdByUserId.");
  }

  if (
    value.postedByUserId !== null &&
    (typeof value.postedByUserId !== "number" ||
      !Number.isFinite(value.postedByUserId))
  ) {
    throw new Error(
      "Invalid journal entry response: postedByUserId must be a number or null.",
    );
  }

  if (
    value.postedAt !== null &&
    (typeof value.postedAt !== "string" || value.postedAt.trim().length === 0)
  ) {
    throw new Error(
      "Invalid journal entry response: postedAt must be an ISO string or null.",
    );
  }

  if (
    typeof value.totalDebits !== "number" &&
    typeof value.totalDebits !== "string"
  ) {
    throw new Error("Invalid journal entry response: missing totalDebits.");
  }

  if (
    typeof value.totalCredits !== "number" &&
    typeof value.totalCredits !== "string"
  ) {
    throw new Error("Invalid journal entry response: missing totalCredits.");
  }

  if (!Array.isArray(value.lines)) {
    throw new Error("Invalid journal entry response: lines must be an array.");
  }

  if (
    typeof value.createdAt !== "string" ||
    value.createdAt.trim().length === 0
  ) {
    throw new Error("Invalid journal entry response: missing createdAt.");
  }

  if (
    typeof value.updatedAt !== "string" ||
    value.updatedAt.trim().length === 0
  ) {
    throw new Error("Invalid journal entry response: missing updatedAt.");
  }

  return {
    id: value.id,
    organizationId: value.organizationId,
    transactionDate: value.transactionDate,
    description: value.description,
    reference: value.reference ?? null,
    status: value.status as JournalEntry["status"],
    createdByUserId: value.createdByUserId,
    postedByUserId: value.postedByUserId ?? null,
    postedAt: value.postedAt ?? null,
    totalDebits: toMoneyString(value.totalDebits),
    totalCredits: toMoneyString(value.totalCredits),
    lines: value.lines.map(mapJournalLine),
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  };
};

const buildQuery = (params: Record<string, string | number | undefined>) => {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    query.set(key, String(value));
  }
  const asString = query.toString();
  return asString ? `?${asString}` : "";
};

const isPlainObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const mapAccount = (value: unknown): Account => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid account response: expected an object.");
  }

  const validAccountTypes = [
    "ASSET",
    "LIABILITY",
    "EQUITY",
    "REVENUE",
    "EXPENSE",
  ] as const;
  const validStatuses = ["ACTIVE", "INACTIVE"] as const;

  const accountCode = value.accountCode;
  const name = value.name;
  const accountType = value.accountType;
  const status = value.status;
  const parentAccountId = value.parentAccountId;

  if (typeof value.id !== "number" || !Number.isFinite(value.id)) {
    throw new Error("Invalid account response: missing account id.");
  }

  if (
    typeof value.organizationId !== "number" ||
    !Number.isFinite(value.organizationId)
  ) {
    throw new Error("Invalid account response: missing organizationId.");
  }

  if (typeof accountCode !== "string" || accountCode.trim().length === 0) {
    throw new Error("Invalid account response: missing accountCode.");
  }

  if (typeof name !== "string" || name.trim().length === 0) {
    throw new Error("Invalid account response: missing name.");
  }

  if (
    typeof accountType !== "string" ||
    !validAccountTypes.includes(
      accountType as (typeof validAccountTypes)[number],
    )
  ) {
    throw new Error(
      "Invalid account response: missing or invalid accountType.",
    );
  }

  if (
    typeof status !== "string" ||
    !validStatuses.includes(status as (typeof validStatuses)[number])
  ) {
    throw new Error("Invalid account response: missing or invalid status.");
  }

  if (
    parentAccountId !== null &&
    (typeof parentAccountId !== "number" || !Number.isFinite(parentAccountId))
  ) {
    throw new Error(
      "Invalid account response: parentAccountId must be a number or null.",
    );
  }

  if (
    typeof value.createdAt !== "string" ||
    value.createdAt.trim().length === 0
  ) {
    throw new Error("Invalid account response: missing createdAt.");
  }

  if (
    typeof value.updatedAt !== "string" ||
    value.updatedAt.trim().length === 0
  ) {
    throw new Error("Invalid account response: missing updatedAt.");
  }

  return {
    id: value.id,
    organizationId: value.organizationId,
    accountCode,
    name,
    accountType: accountType as Account["accountType"],
    status: status as Account["status"],
    parentAccountId: parentAccountId as number | null,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  };
};

const mapAccountBalance = (value: unknown): AccountBalance => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid account balance response: expected an object.");
  }

  const validAccountTypes = [
    "ASSET",
    "LIABILITY",
    "EQUITY",
    "REVENUE",
    "EXPENSE",
  ] as const;

  if (
    typeof value.accountId !== "number" ||
    !Number.isFinite(value.accountId)
  ) {
    throw new Error("Invalid account balance response: missing accountId.");
  }

  if (
    typeof value.accountCode !== "string" ||
    value.accountCode.trim().length === 0
  ) {
    throw new Error("Invalid account balance response: missing accountCode.");
  }

  if (
    typeof value.accountType !== "string" ||
    !validAccountTypes.includes(
      value.accountType as (typeof validAccountTypes)[number],
    )
  ) {
    throw new Error(
      "Invalid account balance response: missing or invalid accountType.",
    );
  }

  if (typeof value.balance !== "number" && typeof value.balance !== "string") {
    throw new Error("Invalid account balance response: missing balance.");
  }

  return {
    accountId: value.accountId,
    accountCode: value.accountCode,
    accountType: value.accountType as AccountBalance["accountType"],
    balance: toMoneyString(value.balance),
  };
};

const mapLoginResponse = (value: unknown): AuthSession => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid login response: expected an object.");
  }

  const token = value.token;
  const expiresAt = value.expiresAt;
  const user = value.user;

  if (typeof token !== "string" || token.trim().length === 0) {
    throw new Error("Invalid login response: missing token.");
  }

  if (typeof expiresAt !== "string" || expiresAt.trim().length === 0) {
    throw new Error("Invalid login response: missing expiresAt.");
  }

  if (!isPlainObject(user)) {
    throw new Error("Invalid login response: missing user payload.");
  }

  const id = user.id;
  const email = user.email;
  const fullName = user.fullName;
  const role = user.role;
  const organizationId = user.organizationId;
  const organizationName =
    typeof user.organizationName === "string" &&
    user.organizationName.trim().length > 0
      ? user.organizationName.trim()
      : undefined;

  if (typeof id !== "number" || !Number.isFinite(id)) {
    throw new Error("Invalid login response: missing user id.");
  }

  if (typeof email !== "string" || email.trim().length === 0) {
    throw new Error("Invalid login response: missing user email.");
  }

  if (typeof fullName !== "string" || fullName.trim().length === 0) {
    throw new Error("Invalid login response: missing user fullName.");
  }

  const validRoles = ["ADMIN", "ACCOUNTANT", "VIEWER"] as const;
  if (
    typeof role !== "string" ||
    !validRoles.includes(role as (typeof validRoles)[number])
  ) {
    throw new Error("Invalid login response: missing or invalid user role.");
  }

  if (typeof organizationId !== "number" || !Number.isFinite(organizationId)) {
    throw new Error("Invalid login response: missing organizationId.");
  }

  return {
    token,
    expiresAt,
    user: {
      id,
      email,
      fullName,
      role: role as AuthSession["user"]["role"],
      organizationId,
      ...(organizationName ? { organizationName } : {}),
    },
  };
};

export const createApiService = (
  http: ApiHttp,
  currentSession?: AuthSession | null,
): LedgerService => {
  const request = async <T>(path: string, init?: RequestInit): Promise<T> =>
    (await http(path, init)) as T;

  return {
    async login(email, password): Promise<AuthSession> {
      const payload = await request<unknown>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      });

      return mapLoginResponse(payload);
    },

    async getOverview(): Promise<OverviewSnapshot> {
      const response = await request<unknown>("/api/dashboard/overview");
      if (!isPlainObject(response)) {
        throw new Error(
          "Invalid dashboard overview response: expected an object.",
        );
      }

      const asOfDate =
        typeof response.asOfDate === "string" ? response.asOfDate : "";
      const totalAssets =
        typeof response.totalAssets === "number" ||
        typeof response.totalAssets === "string"
          ? response.totalAssets
          : "0";
      const totalRevenue =
        typeof response.totalRevenue === "number" ||
        typeof response.totalRevenue === "string"
          ? response.totalRevenue
          : "0";
      const totalExpenses =
        typeof response.totalExpenses === "number" ||
        typeof response.totalExpenses === "string"
          ? response.totalExpenses
          : "0";
      const draftsCount =
        typeof response.draftsCount === "number" ? response.draftsCount : 0;
      const postedCount =
        typeof response.postedCount === "number" ? response.postedCount : 0;
      const recentEntries = Array.isArray(response.recentEntries)
        ? response.recentEntries.map((entry) => mapJournalEntry(entry))
        : [];

      return {
        asOfDate,
        totalAssets: toMoneyString(totalAssets),
        totalRevenue: toMoneyString(totalRevenue),
        totalExpenses: toMoneyString(totalExpenses),
        draftsCount,
        postedCount,
        recentEntries,
      };
    },

    async listAccounts(): Promise<Account[]> {
      const response = await request<unknown>("/accounts");
      if (!Array.isArray(response)) {
        throw new Error("Invalid account list response: expected an array.");
      }
      return response.map(mapAccount);
    },

    async getAccount(accountId): Promise<Account> {
      const response = await request<unknown>(`/accounts/${accountId}`);
      return mapAccount(response);
    },

    async getAccountBalance(accountId): Promise<AccountBalance> {
      const response = await request<unknown>(`/accounts/${accountId}/balance`);
      return mapAccountBalance(response);
    },

    async createAccount(): Promise<Account> {
      throw new UnsupportedInApiModeError(
        "Account creation is not supported by the backend contract.",
      );
    },

    async updateAccount(): Promise<Account> {
      throw new UnsupportedInApiModeError(
        "Account update is not supported by the backend contract.",
      );
    },

    async deactivateAccount(): Promise<Account> {
      throw new UnsupportedInApiModeError(
        "Account deactivation is not supported by the backend contract.",
      );
    },

    async deleteAccount(): Promise<void> {
      throw new UnsupportedInApiModeError(
        "Account deletion is not supported by the backend contract.",
      );
    },

    async listJournalEntries(
      input: ListJournalEntriesInput,
    ): Promise<PageResult<JournalEntry>> {
      const viewerForcedStatus =
        currentSession?.user.role === "VIEWER" ? "POSTED" : input.status;
      const path = `/journal-entries${buildQuery({
        status: viewerForcedStatus,
        fromDate: input.fromDate,
        toDate: input.toDate,
        accountId: input.accountId,
        page: toApiPage(input.page),
        size: input.size,
      })}`;
      const response = await request<ApiPage<ApiJournalEntry>>(path);
      return mapPageResult({
        ...response,
        items: response.items.map(mapJournalEntry),
      });
    },

    async getJournalEntry(entryId): Promise<JournalEntry> {
      const response = await request<ApiJournalEntry>(
        `/journal-entries/${entryId}`,
      );
      return mapJournalEntry(response);
    },

    async createJournalDraft(payload): Promise<JournalEntry> {
      const response = await request<unknown>("/journal-entries", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          transactionDate: payload.transactionDate,
          description: payload.description,
          reference: payload.reference?.trim() || undefined,
          lines: payload.lines.map((line) => ({
            accountId: Number(line.accountId),
            side: line.side,
            amount: line.amount,
          })),
        }),
      });

      return mapJournalEntry(response);
    },

    async updateJournalDraft(entryId, payload): Promise<JournalEntry> {
      const response = await request<unknown>(`/journal-entries/${entryId}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          transactionDate: payload.transactionDate,
          description: payload.description,
          reference: payload.reference?.trim() || undefined,
          lines: payload.lines.map((line) => ({
            accountId: Number(line.accountId),
            side: line.side,
            amount: line.amount,
          })),
        }),
      });

      return mapJournalEntry(response);
    },

    async postJournalEntry(entryId): Promise<JournalEntry> {
      const response = await request<unknown>(
        `/journal-entries/${entryId}/post`,
        {
          method: "POST",
        },
      );
      return mapJournalEntry(response);
    },

    async reverseJournalEntry(entryId): Promise<JournalEntry> {
      const response = await request<unknown>(
        `/journal-entries/${entryId}/reverse`,
        {
          method: "POST",
        },
      );
      return mapJournalEntry(response);
    },

    async deleteDraft(entryId): Promise<void> {
      await request<unknown>(`/journal-entries/${entryId}`, {
        method: "DELETE",
      });
    },

    async getGeneralLedger(input): Promise<GeneralLedgerReport> {
      const path = `/api/reports/general-ledger${buildQuery({
        accountId: input.accountId,
        fromDate: input.fromDate,
        toDate: input.toDate,
        page: toApiPage(input.page),
        size: input.size,
      })}`;

      const response = await request<unknown>(path);
      return mapGeneralLedgerReport(response);
    },

    async getTrialBalance(asOfDate): Promise<TrialBalanceReport> {
      const path = `/api/reports/trial-balance${buildQuery({ asOfDate })}`;
      const response = await request<unknown>(path);
      return mapTrialBalanceReport(response);
    },

    async createInvitation(payload): Promise<Invitation> {
      const response = await request<unknown>("/users/invite", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          email: payload.email,
          role: payload.role,
        }),
      });

      return mapInvitation(response);
    },

    async listOrganizationUsers(): Promise<OrganizationUser[]> {
      const response = await request<unknown>("/users");
      if (!Array.isArray(response)) {
        throw new Error(
          "Invalid organization user list response: expected an array.",
        );
      }
      return response.map(mapOrganizationUser);
    },

    async getOrganizationSettings(): Promise<OrganizationSettings> {
      const response = await request<unknown>("/organizations/me");
      return mapOrganizationSettings(response);
    },

    async updateOrganizationSettings(
      payload: Partial<{ name: string; baseCurrency: string }>,
    ): Promise<OrganizationSettings> {
      const nextPayload: Partial<{ name: string; baseCurrency: string }> = {};

      if (payload.name !== undefined) {
        nextPayload.name = payload.name;
      }

      if (payload.baseCurrency !== undefined) {
        nextPayload.baseCurrency = payload.baseCurrency;
      }

      const response = await request<unknown>("/organizations/me", {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(nextPayload),
      });

      return mapOrganizationSettings(response);
    },

    async listAuditLogs(
      input: ListAuditInput,
    ): Promise<PageResult<AuditLogItem>> {
      void input;
      throw new UnsupportedInApiModeError(
        "Audit log retrieval remains disabled pending a product decision on non-admin audit visibility.",
      );
    },
  } as LedgerService;
};

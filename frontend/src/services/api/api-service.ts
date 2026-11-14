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
  OrganizationKind,
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
  PlatformPendingInvitationSummary,
  ReconciliationCheckResult,
  ReconciliationDiscrepancy,
  ReconciliationRun,
  ReconciliationStatus,
  RecoveryInvitationResult,
  Role,
  TrialBalanceReport,
  UserStatus,
} from "../../types/domain";
import { UnsupportedInApiModeError } from "../../lib/errors";
import type {
  LedgerService,
  ListAuditInput,
  ListJournalEntriesInput,
  ListPlatformAuditInput,
  ListPlatformOrganizationsInput,
} from "../interfaces/ledger-service";

export type ApiHttp = (
  path: string,
  init?: RequestInit,
) => Promise<unknown> | unknown;

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

const createIdempotencyKey = (): string =>
  typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;

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

  const invitationToken =
    typeof value.invitationToken === "string" ? value.invitationToken : "";

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
    invitationToken,
    expiresAt: value.expiresAt,
    createdAt: value.createdAt,
  };
};

const mapOrganizationUser = (value: unknown): OrganizationUser => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid organization user response: expected an object.");
  }

  const validRoles = ["ADMIN", "ACCOUNTANT", "VIEWER"] as const;
  const validStatuses = ["ACTIVE", "DISABLED", "PENDING", "UNKNOWN"] as const;

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
    reversedByEntryId:
      typeof value.reversedByEntryId === "number" &&
      Number.isFinite(value.reversedByEntryId)
        ? value.reversedByEntryId
        : null,
    reversesEntryId:
      typeof value.reversesEntryId === "number" &&
      Number.isFinite(value.reversesEntryId)
        ? value.reversesEntryId
        : null,
    reversalReason:
      typeof value.reversalReason === "string" ? value.reversalReason : null,
  };
};

const buildQuery = (params: Record<string, string | number | undefined>) => {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === "") continue;
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

export const mapLoginResponse = (value: unknown): AuthSession => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid login response: expected an object.");
  }

  const token = value.token;
  const expiresAt = value.expiresAt;
  const refreshToken =
    typeof value.refreshToken === "string" &&
    value.refreshToken.trim().length > 0
      ? value.refreshToken
      : undefined;
  const refreshExpiresAt =
    typeof value.refreshExpiresAt === "string" &&
    value.refreshExpiresAt.trim().length > 0
      ? value.refreshExpiresAt
      : undefined;
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
  const baseCurrency =
    typeof user.baseCurrency === "string" &&
    user.baseCurrency.trim().length > 0
      ? user.baseCurrency.trim()
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

  const validRoles = ["ADMIN", "ACCOUNTANT", "VIEWER", "SUPER_ADMIN"] as const;
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
    ...(refreshToken ? { refreshToken } : {}),
    ...(refreshExpiresAt ? { refreshExpiresAt } : {}),
    user: {
      id,
      email,
      fullName,
      role: role as AuthSession["user"]["role"],
      organizationId,
      ...(organizationName ? { organizationName } : {}),
      ...(baseCurrency ? { baseCurrency } : {}),
    },
  };
};

const VALID_ORG_STATUSES: readonly OrganizationStatus[] = [
  "PENDING",
  "ACTIVE",
  "SUSPENDED",
];
const VALID_ORG_KINDS: readonly OrganizationKind[] = ["PLATFORM", "TENANT"];
const VALID_USER_STATUSES: readonly UserStatus[] = ["ACTIVE", "DISABLED"];
const VALID_ALL_ROLES: readonly Role[] = [
  "ADMIN",
  "ACCOUNTANT",
  "VIEWER",
  "SUPER_ADMIN",
];
const VALID_INVITE_PURPOSES: readonly InvitationPurpose[] = [
  "ONBOARDING",
  "TEAM_INVITE",
  "ADMIN_RECOVERY",
];

const requireNumberField = (
  obj: Record<string, unknown>,
  field: string,
  context: string,
): number => {
  const val = obj[field];
  if (typeof val !== "number" || !Number.isFinite(val)) {
    throw new Error(`Invalid ${context}: missing or invalid ${field}.`);
  }
  return val;
};

const requireStringField = (
  obj: Record<string, unknown>,
  field: string,
  context: string,
): string => {
  const val = obj[field];
  if (typeof val !== "string" || val.trim().length === 0) {
    throw new Error(`Invalid ${context}: missing ${field}.`);
  }
  return val;
};

const optionalStringField = (
  obj: Record<string, unknown>,
  field: string,
): string | null => {
  const val = obj[field];
  return typeof val === "string" && val.length > 0 ? val : null;
};

const optionalNumberField = (
  obj: Record<string, unknown>,
  field: string,
): number | null => {
  const val = obj[field];
  return typeof val === "number" && Number.isFinite(val) ? val : null;
};

const requireEnumField = <T extends string>(
  obj: Record<string, unknown>,
  field: string,
  allowed: readonly T[],
  context: string,
): T => {
  const val = obj[field];
  if (typeof val !== "string" || !allowed.includes(val as T)) {
    throw new Error(`Invalid ${context}: missing or invalid ${field}.`);
  }
  return val as T;
};

const mapPlatformOverview = (value: unknown): PlatformOverview => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid platform overview response: expected an object.");
  }
  const ctx = "platform overview response";
  return {
    totalTenantOrganizations: requireNumberField(
      value,
      "totalTenantOrganizations",
      ctx,
    ),
    activeTenantOrganizations: requireNumberField(
      value,
      "activeTenantOrganizations",
      ctx,
    ),
    pendingTenantOrganizations: requireNumberField(
      value,
      "pendingTenantOrganizations",
      ctx,
    ),
    suspendedTenantOrganizations: requireNumberField(
      value,
      "suspendedTenantOrganizations",
      ctx,
    ),
    totalTenantUsers: requireNumberField(value, "totalTenantUsers", ctx),
    disabledTenantUsers: requireNumberField(value, "disabledTenantUsers", ctx),
    totalTenantAccounts: requireNumberField(value, "totalTenantAccounts", ctx),
    totalPostedJournalEntries: requireNumberField(
      value,
      "totalPostedJournalEntries",
      ctx,
    ),
    unbalancedTenantOrganizations: requireNumberField(
      value,
      "unbalancedTenantOrganizations",
      ctx,
    ),
    lastPlatformActionAt: optionalStringField(value, "lastPlatformActionAt"),
  };
};

const mapPlatformOrganizationSummary = (
  value: unknown,
): PlatformOrganizationSummary => {
  if (!isPlainObject(value)) {
    throw new Error(
      "Invalid platform organization summary: expected an object.",
    );
  }
  const ctx = "platform organization summary";
  return {
    id: requireNumberField(value, "id", ctx),
    name: requireStringField(value, "name", ctx),
    baseCurrency: requireStringField(value, "baseCurrency", ctx),
    status: requireEnumField(value, "status", VALID_ORG_STATUSES, ctx),
    userCount: requireNumberField(value, "userCount", ctx),
    activeAdminCount: requireNumberField(value, "activeAdminCount", ctx),
    disabledUserCount: requireNumberField(value, "disabledUserCount", ctx),
    accountCount: requireNumberField(value, "accountCount", ctx),
    postedEntryCount: requireNumberField(value, "postedEntryCount", ctx),
    draftEntryCount: requireNumberField(value, "draftEntryCount", ctx),
    pendingInvitationCount: requireNumberField(
      value,
      "pendingInvitationCount",
      ctx,
    ),
    lastActivityAt: optionalStringField(value, "lastActivityAt"),
    createdAt: requireStringField(value, "createdAt", ctx),
    suspendedAt: optionalStringField(value, "suspendedAt"),
    suspendedReason: optionalStringField(value, "suspendedReason"),
  };
};

const mapPlatformPendingInvitation = (
  value: unknown,
): PlatformPendingInvitationSummary => {
  if (!isPlainObject(value)) {
    throw new Error(
      "Invalid platform pending invitation: expected an object.",
    );
  }
  const ctx = "platform pending invitation";
  return {
    id: requireNumberField(value, "id", ctx),
    invitedEmail: requireStringField(value, "invitedEmail", ctx),
    role: requireEnumField(value, "role", VALID_ALL_ROLES, ctx),
    purpose: requireEnumField(value, "purpose", VALID_INVITE_PURPOSES, ctx),
    expiresAt: requireStringField(value, "expiresAt", ctx),
    createdAt: requireStringField(value, "createdAt", ctx),
  };
};

const mapPlatformOrganizationDetail = (
  value: unknown,
): PlatformOrganizationDetail => {
  if (!isPlainObject(value)) {
    throw new Error(
      "Invalid platform organization detail: expected an object.",
    );
  }
  const ctx = "platform organization detail";
  if (
    typeof value.hasTransactions !== "boolean" ||
    typeof value.trialBalanceBalanced !== "boolean"
  ) {
    throw new Error(`Invalid ${ctx}: missing boolean flags.`);
  }
  if (!Array.isArray(value.pendingInvitations)) {
    throw new Error(`Invalid ${ctx}: pendingInvitations must be an array.`);
  }
  return {
    id: requireNumberField(value, "id", ctx),
    name: requireStringField(value, "name", ctx),
    baseCurrency: requireStringField(value, "baseCurrency", ctx),
    kind: requireEnumField(value, "kind", VALID_ORG_KINDS, ctx),
    status: requireEnumField(value, "status", VALID_ORG_STATUSES, ctx),
    suspendedAt: optionalStringField(value, "suspendedAt"),
    suspendedReason: optionalStringField(value, "suspendedReason"),
    userCount: requireNumberField(value, "userCount", ctx),
    activeUserCount: requireNumberField(value, "activeUserCount", ctx),
    disabledUserCount: requireNumberField(value, "disabledUserCount", ctx),
    activeAdminCount: requireNumberField(value, "activeAdminCount", ctx),
    accountCount: requireNumberField(value, "accountCount", ctx),
    postedEntryCount: requireNumberField(value, "postedEntryCount", ctx),
    draftEntryCount: requireNumberField(value, "draftEntryCount", ctx),
    hasTransactions: value.hasTransactions,
    trialBalanceBalanced: value.trialBalanceBalanced,
    pendingInvitations: value.pendingInvitations.map(
      mapPlatformPendingInvitation,
    ),
    createdAt: requireStringField(value, "createdAt", ctx),
    updatedAt: requireStringField(value, "updatedAt", ctx),
  };
};

const mapAdminCreateOrganizationResult = (
  value: unknown,
): AdminCreateOrganizationResult => {
  if (!isPlainObject(value)) {
    throw new Error(
      "Invalid create organization response: expected an object.",
    );
  }
  const ctx = "create organization response";
  return {
    organizationId: requireNumberField(value, "organizationId", ctx),
    organizationName: requireStringField(value, "organizationName", ctx),
    baseCurrency: requireStringField(value, "baseCurrency", ctx),
    status: requireEnumField(value, "status", VALID_ORG_STATUSES, ctx),
    invitationId: requireNumberField(value, "invitationId", ctx),
    invitedAdminEmail: requireStringField(value, "invitedAdminEmail", ctx),
    invitationToken: requireStringField(value, "invitationToken", ctx),
    invitationExpiresAt: requireStringField(value, "invitationExpiresAt", ctx),
    createdAt: requireStringField(value, "createdAt", ctx),
  };
};

const mapPlatformOrganizationUser = (
  value: unknown,
): PlatformOrganizationUser => {
  if (!isPlainObject(value)) {
    throw new Error(
      "Invalid platform organization user: expected an object.",
    );
  }
  const ctx = "platform organization user";
  return {
    id: requireNumberField(value, "id", ctx),
    email: requireStringField(value, "email", ctx),
    fullName: requireStringField(value, "fullName", ctx),
    role: requireEnumField(value, "role", VALID_ALL_ROLES, ctx),
    status: requireEnumField(value, "status", VALID_USER_STATUSES, ctx),
    createdAt: requireStringField(value, "createdAt", ctx),
  };
};

const mapRecoveryInvitationResult = (
  value: unknown,
): RecoveryInvitationResult => {
  if (!isPlainObject(value)) {
    throw new Error(
      "Invalid recovery invitation response: expected an object.",
    );
  }
  const ctx = "recovery invitation response";
  return {
    invitationId: requireNumberField(value, "invitationId", ctx),
    organizationId: requireNumberField(value, "organizationId", ctx),
    invitedEmail: requireStringField(value, "invitedEmail", ctx),
    role: requireEnumField(value, "role", VALID_ALL_ROLES, ctx),
    purpose: requireEnumField(value, "purpose", VALID_INVITE_PURPOSES, ctx),
    targetUserId: optionalNumberField(value, "targetUserId"),
    invitationToken: requireStringField(value, "invitationToken", ctx),
    expiresAt: requireStringField(value, "expiresAt", ctx),
    createdAt: requireStringField(value, "createdAt", ctx),
  };
};

const mapPlatformAuditLogItem = (value: unknown): PlatformAuditLogItem => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid platform audit log item: expected an object.");
  }
  const ctx = "platform audit log item";
  return {
    id: requireNumberField(value, "id", ctx),
    actor: optionalStringField(value, "actor") ?? "System",
    action: requireStringField(value, "action", ctx),
    targetOrganizationId: optionalNumberField(value, "targetOrganizationId"),
    targetOrganizationName: optionalStringField(
      value,
      "targetOrganizationName",
    ),
    targetUserId: optionalNumberField(value, "targetUserId"),
    targetUserEmail: optionalStringField(value, "targetUserEmail"),
    createdAt: requireStringField(value, "createdAt", ctx),
    metadata: typeof value.metadata === "string" ? value.metadata : "{}",
  };
};

const VALID_RECONCILIATION_STATUSES: readonly ReconciliationStatus[] = [
  "PASSED",
  "FAILED",
];

const requireMoneyField = (
  obj: Record<string, unknown>,
  field: string,
  context: string,
): string => {
  const val = obj[field];
  if (typeof val !== "number" && typeof val !== "string") {
    throw new Error(`Invalid ${context}: missing or invalid ${field}.`);
  }
  return toMoneyString(val);
};

const mapReconciliationDiscrepancy = (
  value: unknown,
): ReconciliationDiscrepancy => {
  if (!isPlainObject(value)) {
    throw new Error(
      "Invalid reconciliation discrepancy: expected an object.",
    );
  }
  const ctx = "reconciliation discrepancy";
  return {
    entityType: requireStringField(value, "entityType", ctx),
    entityId: requireStringField(value, "entityId", ctx),
    reference: typeof value.reference === "string" ? value.reference : "",
    expectedAmount: requireMoneyField(value, "expectedAmount", ctx),
    actualAmount: requireMoneyField(value, "actualAmount", ctx),
    variance: requireMoneyField(value, "variance", ctx),
    detail: requireStringField(value, "detail", ctx),
  };
};

const mapReconciliationCheckResult = (
  value: unknown,
): ReconciliationCheckResult => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid reconciliation check: expected an object.");
  }
  const ctx = "reconciliation check";
  if (typeof value.passed !== "boolean" || typeof value.blocking !== "boolean") {
    throw new Error(`Invalid ${ctx}: missing boolean flags.`);
  }
  if (!Array.isArray(value.discrepancies)) {
    throw new Error(`Invalid ${ctx}: discrepancies must be an array.`);
  }
  return {
    code: requireStringField(value, "code", ctx),
    name: requireStringField(value, "name", ctx),
    passed: value.passed,
    blocking: value.blocking,
    variance: requireMoneyField(value, "variance", ctx),
    summary: requireStringField(value, "summary", ctx),
    discrepancies: value.discrepancies.map(mapReconciliationDiscrepancy),
  };
};

const mapReconciliationRun = (value: unknown): ReconciliationRun => {
  if (!isPlainObject(value)) {
    throw new Error("Invalid reconciliation run: expected an object.");
  }
  const ctx = "reconciliation run";
  if (!Array.isArray(value.checks)) {
    throw new Error(`Invalid ${ctx}: checks must be an array.`);
  }
  return {
    id: requireNumberField(value, "id", ctx),
    organizationId: requireNumberField(value, "organizationId", ctx),
    status: requireEnumField(
      value,
      "status",
      VALID_RECONCILIATION_STATUSES,
      ctx,
    ),
    totalPostedDebits: requireMoneyField(value, "totalPostedDebits", ctx),
    totalPostedCredits: requireMoneyField(value, "totalPostedCredits", ctx),
    globalVariance: requireMoneyField(value, "globalVariance", ctx),
    postedEntryCount: requireNumberField(value, "postedEntryCount", ctx),
    accountCount: requireNumberField(value, "accountCount", ctx),
    checksPassed: requireNumberField(value, "checksPassed", ctx),
    checksFailed: requireNumberField(value, "checksFailed", ctx),
    triggeredByUserId: optionalNumberField(value, "triggeredByUserId"),
    triggeredByName: optionalStringField(value, "triggeredByName"),
    checks: value.checks.map(mapReconciliationCheckResult),
    createdAt: requireStringField(value, "createdAt", ctx),
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

    async refreshSession(refreshToken): Promise<AuthSession> {
      const payload = await request<unknown>("/auth/refresh", {
        method: "POST",
        body: JSON.stringify({ refreshToken }),
      });

      return mapLoginResponse(payload);
    },

    async logout(refreshToken): Promise<void> {
      await request<void>("/auth/logout", {
        method: "POST",
        body: refreshToken ? JSON.stringify({ refreshToken }) : undefined,
      });
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

    async createAccount(payload): Promise<Account> {
      const response = await request<unknown>("/accounts", {
        method: "POST",
        body: JSON.stringify({
          accountCode: payload.accountCode,
          name: payload.name,
          accountType: payload.accountType,
          status: payload.status,
          parentAccountId: payload.parentAccountId ?? null,
        }),
      });
      return mapAccount(response);
    },

    async updateAccount(accountId, payload): Promise<Account> {
      const response = await request<unknown>(`/accounts/${accountId}`, {
        method: "PATCH",
        body: JSON.stringify({
          ...(payload.accountCode !== undefined && {
            accountCode: payload.accountCode,
          }),
          ...(payload.name !== undefined && { name: payload.name }),
          ...(payload.accountType !== undefined && {
            accountType: payload.accountType,
          }),
          ...(payload.status !== undefined && { status: payload.status }),
          ...(payload.parentAccountId !== undefined && {
            parentAccountId: payload.parentAccountId ?? null,
          }),
        }),
      });
      return mapAccount(response);
    },

    async deactivateAccount(accountId): Promise<Account> {
      const response = await request<unknown>(
        `/accounts/${accountId}/deactivate`,
        {
          method: "POST",
        },
      );
      return mapAccount(response);
    },

    async deleteAccount(accountId): Promise<void> {
      void accountId;
      throw new UnsupportedInApiModeError(
        "Account deletion is not supported; accounts can only be deactivated.",
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

    async postJournalEntry(entryId, options): Promise<JournalEntry> {
      const idempotencyKey = options?.idempotencyKey ?? createIdempotencyKey();
      const response = await request<unknown>(
        `/journal-entries/${entryId}/post`,
        {
          method: "POST",
          headers: {
            "Idempotency-Key": idempotencyKey,
          },
        },
      );
      return mapJournalEntry(response);
    },

    async reverseJournalEntry(entryId, payload): Promise<JournalEntry> {
      const idempotencyKey = payload?.idempotencyKey ?? createIdempotencyKey();
      const headers: Record<string, string> = {
        "Idempotency-Key": idempotencyKey,
      };
      if (payload?.reason) {
        headers["Content-Type"] = "application/json";
      }

      const response = await request<unknown>(
        `/journal-entries/${entryId}/reverse`,
        {
          method: "POST",
          headers,
          body: payload?.reason
            ? JSON.stringify({ reason: payload.reason })
            : undefined,
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

    async listPendingInvitations(): Promise<Invitation[]> {
      const response = await request<unknown>("/invitations");
      if (!Array.isArray(response)) {
        throw new Error(
          "Invalid invitation list response: expected an array.",
        );
      }
      return response.map(mapInvitation);
    },

    async revokeInvitation(invitationId: number): Promise<void> {
      await request<unknown>(`/invitations/${invitationId}`, {
        method: "DELETE",
      });
    },

    async acceptInvitation(payload): Promise<{ message: string }> {
      const response = await request<unknown>("/invitations/accept", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (isPlainObject(response) && typeof response.message === "string") {
        return { message: response.message };
      }
      return { message: "Invitation accepted successfully." };
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

    async updateUserRole(
      userId: number,
      role: Role,
    ): Promise<OrganizationUser> {
      const response = await request<unknown>(`/users/${userId}/role`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ role }),
      });
      return mapOrganizationUser(response);
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
      const page = (input.page ?? 1) - 1;
      const size = input.size ?? 20;

      const path = `/api/audit-logs${buildQuery({
        actor: input.actor,
        action: input.action,
        entity: input.entity,
        fromDate: input.fromDate,
        toDate: input.toDate,
        page,
        size,
      })}`;

      const response = await request<unknown>(path);
      if (!isPlainObject(response)) {
        throw new Error("Invalid audit logs response: expected an object.");
      }

      const rawItems = Array.isArray(response.items) ? response.items : [];
      const items: AuditLogItem[] = rawItems.map((raw) => {
        if (!isPlainObject(raw)) {
          throw new Error("Invalid audit log item: expected an object.");
        }
        return {
          id: typeof raw.id === "number" ? raw.id : Number(raw.id),
          actor: typeof raw.actor === "string" ? raw.actor : "System",
          action: typeof raw.action === "string" ? raw.action : "",
          entityType: typeof raw.entityType === "string" ? raw.entityType : "",
          entityId: typeof raw.entityId === "string" ? raw.entityId : "",
          createdAt: typeof raw.createdAt === "string" ? raw.createdAt : "",
          metadata: typeof raw.metadata === "string" ? raw.metadata : "{}",
        };
      });

      return {
        page: typeof response.page === "number" ? response.page + 1 : 1,
        size: typeof response.size === "number" ? response.size : size,
        totalElements:
          typeof response.totalElements === "number"
            ? response.totalElements
            : items.length,
        totalPages:
          typeof response.totalPages === "number" ? response.totalPages : 1,
        items,
      };
    },

    async getPlatformOverview(): Promise<PlatformOverview> {
      const response = await request<unknown>("/admin/overview");
      return mapPlatformOverview(response);
    },

    async listPlatformOrganizations(
      input: ListPlatformOrganizationsInput,
    ): Promise<PageResult<PlatformOrganizationSummary>> {
      const size = input.size ?? 20;
      const path = `/admin/organizations${buildQuery({
        search: input.search?.trim() || undefined,
        status: input.status,
        page: toApiPage(input.page),
        size,
      })}`;
      const response = await request<unknown>(path);
      if (!isPlainObject(response) || !Array.isArray(response.items)) {
        throw new Error(
          "Invalid platform organizations response: expected a paginated list.",
        );
      }
      const items = response.items.map(mapPlatformOrganizationSummary);
      return mapPageResult({
        page: typeof response.page === "number" ? response.page : 0,
        size: typeof response.size === "number" ? response.size : size,
        totalElements:
          typeof response.totalElements === "number"
            ? response.totalElements
            : items.length,
        totalPages:
          typeof response.totalPages === "number" ? response.totalPages : 1,
        items,
      });
    },

    async createPlatformOrganization(payload: {
      organizationName: string;
      baseCurrency: string;
      adminEmail: string;
    }): Promise<AdminCreateOrganizationResult> {
      const response = await request<unknown>("/admin/organizations", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          organizationName: payload.organizationName,
          baseCurrency: payload.baseCurrency,
          adminEmail: payload.adminEmail,
        }),
      });
      return mapAdminCreateOrganizationResult(response);
    },

    async getPlatformOrganizationDetail(
      organizationId: number,
    ): Promise<PlatformOrganizationDetail> {
      const response = await request<unknown>(
        `/admin/organizations/${organizationId}`,
      );
      return mapPlatformOrganizationDetail(response);
    },

    async updatePlatformOrganization(
      organizationId: number,
      payload: Partial<{ name: string; baseCurrency: string }>,
    ): Promise<PlatformOrganizationDetail> {
      const nextPayload: Partial<{ name: string; baseCurrency: string }> = {};
      if (payload.name !== undefined) {
        nextPayload.name = payload.name;
      }
      if (payload.baseCurrency !== undefined) {
        nextPayload.baseCurrency = payload.baseCurrency;
      }
      const response = await request<unknown>(
        `/admin/organizations/${organizationId}`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(nextPayload),
        },
      );
      return mapPlatformOrganizationDetail(response);
    },

    async updatePlatformOrganizationStatus(
      organizationId: number,
      payload: { status: "ACTIVE" | "SUSPENDED"; reason?: string },
    ): Promise<PlatformOrganizationDetail> {
      const response = await request<unknown>(
        `/admin/organizations/${organizationId}/status`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            status: payload.status,
            ...(payload.reason !== undefined ? { reason: payload.reason } : {}),
          }),
        },
      );
      return mapPlatformOrganizationDetail(response);
    },

    async listPlatformOrganizationUsers(
      organizationId: number,
    ): Promise<PlatformOrganizationUser[]> {
      const response = await request<unknown>(
        `/admin/organizations/${organizationId}/users`,
      );
      if (!Array.isArray(response)) {
        throw new Error(
          "Invalid platform organization users response: expected an array.",
        );
      }
      return response.map(mapPlatformOrganizationUser);
    },

    async updatePlatformOrganizationUserStatus(
      organizationId: number,
      userId: number,
      status: UserStatus,
    ): Promise<PlatformOrganizationUser> {
      const response = await request<unknown>(
        `/admin/organizations/${organizationId}/users/${userId}/status`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ status }),
        },
      );
      return mapPlatformOrganizationUser(response);
    },

    async createAdminRecoveryInvitation(
      organizationId: number,
      payload: { adminEmail: string },
    ): Promise<RecoveryInvitationResult> {
      const response = await request<unknown>(
        `/admin/organizations/${organizationId}/recovery-invitation`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ adminEmail: payload.adminEmail }),
        },
      );
      return mapRecoveryInvitationResult(response);
    },

    async listPlatformAuditLogs(
      input: ListPlatformAuditInput,
    ): Promise<PageResult<PlatformAuditLogItem>> {
      const size = input.size ?? 20;
      const path = `/admin/audit-logs${buildQuery({
        action: input.action,
        targetOrganizationId: input.targetOrganizationId,
        from: input.fromDate,
        to: input.toDate,
        page: toApiPage(input.page),
        size,
      })}`;
      const response = await request<unknown>(path);
      if (!isPlainObject(response) || !Array.isArray(response.items)) {
        throw new Error(
          "Invalid platform audit logs response: expected a paginated list.",
        );
      }
      const items = response.items.map(mapPlatformAuditLogItem);
      return mapPageResult({
        page: typeof response.page === "number" ? response.page : 0,
        size: typeof response.size === "number" ? response.size : size,
        totalElements:
          typeof response.totalElements === "number"
            ? response.totalElements
            : items.length,
        totalPages:
          typeof response.totalPages === "number" ? response.totalPages : 1,
        items,
      });
    },

    async runReconciliation(): Promise<ReconciliationRun> {
      const response = await request<unknown>("/api/reconciliation/run", {
        method: "POST",
      });
      return mapReconciliationRun(response);
    },

    async listReconciliationHistory(input?: {
      page?: number;
      size?: number;
    }): Promise<PageResult<ReconciliationRun>> {
      const size = input?.size ?? 20;
      const path = `/api/reconciliation/history${buildQuery({
        page: toApiPage(input?.page),
        size,
      })}`;
      const response = await request<unknown>(path);
      if (!isPlainObject(response) || !Array.isArray(response.items)) {
        throw new Error(
          "Invalid reconciliation history response: expected a paginated list.",
        );
      }
      const items = response.items.map(mapReconciliationRun);
      return mapPageResult({
        page: typeof response.page === "number" ? response.page : 0,
        size: typeof response.size === "number" ? response.size : size,
        totalElements:
          typeof response.totalElements === "number"
            ? response.totalElements
            : items.length,
        totalPages:
          typeof response.totalPages === "number" ? response.totalPages : 1,
        items,
      });
    },

    async getReconciliationRun(runId: number): Promise<ReconciliationRun> {
      const response = await request<unknown>(`/api/reconciliation/${runId}`);
      return mapReconciliationRun(response);
    },
  };
};

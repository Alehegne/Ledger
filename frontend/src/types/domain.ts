export type Role = "ADMIN" | "ACCOUNTANT" | "VIEWER";
export type AccountType =
  | "ASSET"
  | "LIABILITY"
  | "EQUITY"
  | "REVENUE"
  | "EXPENSE";
export type AccountStatus = "ACTIVE" | "INACTIVE";
export type EntrySide = "DEBIT" | "CREDIT";
export type JournalEntryStatus = "DRAFT" | "POSTED";

export interface AuthUser {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  organizationId: number;
  organizationName?: string;
}

export interface AuthSession {
  token: string;
  expiresAt: string;
  user: AuthUser;
}

export interface ApiError {
  status: number;
  code: string;
  message: string;
  path?: string;
}

export interface Account {
  id: number;
  organizationId: number;
  accountCode: string;
  name: string;
  accountType: AccountType;
  status: AccountStatus;
  parentAccountId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface AccountBalance {
  accountId: number;
  accountCode: string;
  accountType: AccountType;
  balance: string;
}

export interface JournalLine {
  id: number;
  accountId: number;
  accountCode: string;
  accountName: string;
  accountType: AccountType;
  side: EntrySide;
  amount: string;
}

export interface JournalEntry {
  id: number;
  organizationId: number;
  transactionDate: string;
  description: string;
  reference: string | null;
  status: JournalEntryStatus;
  createdByUserId: number;
  postedByUserId: number | null;
  postedAt: string | null;
  totalDebits: string;
  totalCredits: string;
  lines: JournalLine[];
  createdAt: string;
  updatedAt: string;
}

export interface PageResult<T> {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  items: T[];
}

export interface GeneralLedgerLine {
  journalEntryId: number;
  transactionDate: string;
  description: string;
  reference: string | null;
  side: EntrySide;
  amount: string;
  runningBalance: string;
}

export interface GeneralLedgerReport {
  accountId: number;
  accountCode: string;
  accountName: string;
  accountType: AccountType;
  lines: GeneralLedgerLine[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  openingBalance: string;
  closingBalance: string;
}

export interface TrialBalanceAccountLine {
  accountId: number;
  accountCode: string;
  accountName: string;
  accountType: AccountType;
  debitBalance: string;
  creditBalance: string;
}

export interface TrialBalanceReport {
  asOfDate: string;
  accounts: TrialBalanceAccountLine[];
  totalDebits: string;
  totalCredits: string;
  balanced: boolean;
}

export interface Invitation {
  id: number;
  organizationId: number;
  invitedEmail: string;
  role: Role;
  invitationToken: string;
  expiresAt: string;
  createdAt: string;
}

export interface AuditLogItem {
  id: number;
  actor: string;
  action: string;
  entityType: string;
  entityId: string;
  createdAt: string;
  metadata: string;
}

export interface OverviewSnapshot {
  asOfDate: string;
  totalAssets: string;
  totalRevenue: string;
  totalExpenses: string;
  draftsCount: number;
  postedCount: number;
  recentEntries: JournalEntry[];
}

export interface OrganizationSettings {
  id: number;
  name: string;
  baseCurrency: string;
  createdAt: string;
  editable?: boolean;
}

export interface OrganizationUser {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  invitationStatus: "ACTIVE" | "PENDING" | "UNKNOWN";
}

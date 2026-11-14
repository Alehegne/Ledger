export type Role = "ADMIN" | "ACCOUNTANT" | "VIEWER" | "SUPER_ADMIN";
export type OrganizationKind = "PLATFORM" | "TENANT";
export type OrganizationStatus = "PENDING" | "ACTIVE" | "SUSPENDED";
export type UserStatus = "ACTIVE" | "DISABLED";
export type InvitationPurpose = "ONBOARDING" | "TEAM_INVITE" | "ADMIN_RECOVERY";
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
  baseCurrency?: string;
}

export interface AuthSession {
  token: string;
  expiresAt: string;
  refreshToken?: string;
  refreshExpiresAt?: string;
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
  reversedByEntryId?: number | null;
  reversesEntryId?: number | null;
  reversalReason?: string | null;
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
  invitationStatus: "ACTIVE" | "DISABLED" | "PENDING" | "UNKNOWN";
}

export interface PlatformOverview {
  totalTenantOrganizations: number;
  activeTenantOrganizations: number;
  pendingTenantOrganizations: number;
  suspendedTenantOrganizations: number;
  totalTenantUsers: number;
  disabledTenantUsers: number;
  totalTenantAccounts: number;
  totalPostedJournalEntries: number;
  unbalancedTenantOrganizations: number;
  lastPlatformActionAt: string | null;
}

export interface PlatformOrganizationSummary {
  id: number;
  name: string;
  baseCurrency: string;
  status: OrganizationStatus;
  userCount: number;
  activeAdminCount: number;
  disabledUserCount: number;
  accountCount: number;
  postedEntryCount: number;
  draftEntryCount: number;
  pendingInvitationCount: number;
  lastActivityAt: string | null;
  createdAt: string;
  suspendedAt: string | null;
  suspendedReason: string | null;
}

export interface PlatformPendingInvitationSummary {
  id: number;
  invitedEmail: string;
  role: Role;
  purpose: InvitationPurpose;
  expiresAt: string;
  createdAt: string;
}

export interface PlatformOrganizationDetail {
  id: number;
  name: string;
  baseCurrency: string;
  kind: OrganizationKind;
  status: OrganizationStatus;
  suspendedAt: string | null;
  suspendedReason: string | null;
  userCount: number;
  activeUserCount: number;
  disabledUserCount: number;
  activeAdminCount: number;
  accountCount: number;
  postedEntryCount: number;
  draftEntryCount: number;
  hasTransactions: boolean;
  trialBalanceBalanced: boolean;
  pendingInvitations: PlatformPendingInvitationSummary[];
  createdAt: string;
  updatedAt: string;
}

export interface AdminCreateOrganizationResult {
  organizationId: number;
  organizationName: string;
  baseCurrency: string;
  status: OrganizationStatus;
  invitationId: number;
  invitedAdminEmail: string;
  invitationToken: string;
  invitationExpiresAt: string;
  createdAt: string;
}

export interface PlatformOrganizationUser {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  status: UserStatus;
  createdAt: string;
}

export interface RecoveryInvitationResult {
  invitationId: number;
  organizationId: number;
  invitedEmail: string;
  role: Role;
  purpose: InvitationPurpose;
  targetUserId: number | null;
  invitationToken: string;
  expiresAt: string;
  createdAt: string;
}

export interface PlatformAuditLogItem {
  id: number;
  actor: string;
  action: string;
  targetOrganizationId: number | null;
  targetOrganizationName: string | null;
  targetUserId: number | null;
  targetUserEmail: string | null;
  createdAt: string;
  metadata: string;
}

export type ReconciliationStatus = "PASSED" | "FAILED";

export interface ReconciliationDiscrepancy {
  entityType: string;
  entityId: string;
  reference: string;
  expectedAmount: string;
  actualAmount: string;
  variance: string;
  detail: string;
}

export interface ReconciliationCheckResult {
  code: string;
  name: string;
  passed: boolean;
  blocking: boolean;
  variance: string;
  summary: string;
  discrepancies: ReconciliationDiscrepancy[];
}

export interface ReconciliationRun {
  id: number;
  organizationId: number;
  status: ReconciliationStatus;
  totalPostedDebits: string;
  totalPostedCredits: string;
  globalVariance: string;
  postedEntryCount: number;
  accountCount: number;
  checksPassed: number;
  checksFailed: number;
  triggeredByUserId: number | null;
  triggeredByName: string | null;
  checks: ReconciliationCheckResult[];
  createdAt: string;
}

import type {
  Account,
  AccountBalance,
  AdminCreateOrganizationResult,
  AuditLogItem,
  AuthSession,
  GeneralLedgerReport,
  Invitation,
  JournalEntry,
  JournalEntryStatus,
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
  ReconciliationRun,
  RecoveryInvitationResult,
  Role,
  TrialBalanceReport,
  UserStatus,
} from "../../types/domain";

export interface ListJournalEntriesInput {
  status?: JournalEntryStatus;
  fromDate?: string;
  toDate?: string;
  accountId?: number;
  page?: number;
  size?: number;
}

export interface CreateJournalLineInput {
  accountId: number;
  side: "DEBIT" | "CREDIT";
  amount: string;
}

export interface SaveJournalInput {
  transactionDate: string;
  description: string;
  reference?: string;
  lines: CreateJournalLineInput[];
}

export interface ListAuditInput {
  actor?: number;
  action?: string;
  entity?: string;
  fromDate?: string;
  toDate?: string;
  page?: number;
  size?: number;
}

export interface ListPlatformOrganizationsInput {
  search?: string;
  status?: OrganizationStatus;
  page?: number;
  size?: number;
}

export interface ListPlatformAuditInput {
  action?: string;
  targetOrganizationId?: number;
  fromDate?: string;
  toDate?: string;
  page?: number;
  size?: number;
}

export interface LedgerService {
  login(email: string, password: string): Promise<AuthSession>;
  refreshSession(refreshToken: string): Promise<AuthSession>;
  logout(refreshToken?: string): Promise<void>;

  getOverview(): Promise<OverviewSnapshot>;

  listAccounts(): Promise<Account[]>;
  getAccount(accountId: number): Promise<Account>;
  getAccountBalance(accountId: number): Promise<AccountBalance>;
  createAccount(payload: {
    accountCode: string;
    name: string;
    accountType: Account["accountType"];
    status: Account["status"];
    parentAccountId?: number | null;
  }): Promise<Account>;
  updateAccount(
    accountId: number,
    payload: Partial<{
      accountCode: string;
      name: string;
      accountType: Account["accountType"];
      status: Account["status"];
      parentAccountId: number | null;
    }>,
  ): Promise<Account>;
  deactivateAccount(accountId: number): Promise<Account>;
  deleteAccount(accountId: number): Promise<void>;

  listJournalEntries(
    input: ListJournalEntriesInput,
  ): Promise<PageResult<JournalEntry>>;
  getJournalEntry(entryId: number): Promise<JournalEntry>;
  createJournalDraft(payload: SaveJournalInput): Promise<JournalEntry>;
  updateJournalDraft(
    entryId: number,
    payload: SaveJournalInput,
  ): Promise<JournalEntry>;
  postJournalEntry(
    entryId: number,
    options?: { idempotencyKey?: string },
  ): Promise<JournalEntry>;
  reverseJournalEntry(
    entryId: number,
    payload?: { reason?: string; idempotencyKey?: string },
  ): Promise<JournalEntry>;
  deleteDraft(entryId: number): Promise<void>;

  getGeneralLedger(input: {
    accountId: number;
    fromDate?: string;
    toDate?: string;
    page?: number;
    size?: number;
  }): Promise<GeneralLedgerReport>;

  getTrialBalance(asOfDate?: string): Promise<TrialBalanceReport>;

  createInvitation(payload: { email: string; role: Role }): Promise<Invitation>;
  listPendingInvitations(): Promise<Invitation[]>;
  revokeInvitation(invitationId: number): Promise<void>;
  acceptInvitation(payload: {
    token: string;
    email: string;
    fullName: string;
    password: string;
  }): Promise<{ message: string }>;
  listOrganizationUsers(): Promise<OrganizationUser[]>;
  updateUserRole(userId: number, role: Role): Promise<OrganizationUser>;
  getOrganizationSettings(): Promise<OrganizationSettings>;
  updateOrganizationSettings(
    payload: Partial<{
      name: string;
      baseCurrency: string;
    }>,
  ): Promise<OrganizationSettings>;
  listAuditLogs(input: ListAuditInput): Promise<PageResult<AuditLogItem>>;

  getPlatformOverview(): Promise<PlatformOverview>;
  listPlatformOrganizations(
    input: ListPlatformOrganizationsInput,
  ): Promise<PageResult<PlatformOrganizationSummary>>;
  createPlatformOrganization(payload: {
    organizationName: string;
    baseCurrency: string;
    adminEmail: string;
  }): Promise<AdminCreateOrganizationResult>;
  getPlatformOrganizationDetail(
    organizationId: number,
  ): Promise<PlatformOrganizationDetail>;
  updatePlatformOrganization(
    organizationId: number,
    payload: Partial<{ name: string; baseCurrency: string }>,
  ): Promise<PlatformOrganizationDetail>;
  updatePlatformOrganizationStatus(
    organizationId: number,
    payload: { status: "ACTIVE" | "SUSPENDED"; reason?: string },
  ): Promise<PlatformOrganizationDetail>;
  listPlatformOrganizationUsers(
    organizationId: number,
  ): Promise<PlatformOrganizationUser[]>;
  updatePlatformOrganizationUserStatus(
    organizationId: number,
    userId: number,
    status: UserStatus,
  ): Promise<PlatformOrganizationUser>;
  createAdminRecoveryInvitation(
    organizationId: number,
    payload: { adminEmail: string },
  ): Promise<RecoveryInvitationResult>;
  listPlatformAuditLogs(
    input: ListPlatformAuditInput,
  ): Promise<PageResult<PlatformAuditLogItem>>;

  runReconciliation(): Promise<ReconciliationRun>;
  listReconciliationHistory(input?: {
    page?: number;
    size?: number;
  }): Promise<PageResult<ReconciliationRun>>;
  getReconciliationRun(runId: number): Promise<ReconciliationRun>;
}

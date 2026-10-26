import type {
  Account,
  AccountBalance,
  AuthSession,
  GeneralLedgerReport,
  Invitation,
  JournalEntry,
  OrganizationSettings,
  OrganizationUser,
  PageResult,
  TrialBalanceReport,
  AuditLogItem,
  OverviewSnapshot,
  Role,
  JournalEntryStatus,
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

export interface LedgerService {
  login(email: string, password: string): Promise<AuthSession>;

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
  postJournalEntry(entryId: number): Promise<JournalEntry>;
  reverseJournalEntry(entryId: number): Promise<JournalEntry>;
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
  listOrganizationUsers(): Promise<OrganizationUser[]>;
  getOrganizationSettings(): Promise<OrganizationSettings>;
  updateOrganizationSettings(
    payload: Partial<{
      name: string;
      baseCurrency: string;
    }>,
  ): Promise<OrganizationSettings>;
  listAuditLogs(input: ListAuditInput): Promise<PageResult<AuditLogItem>>;
}

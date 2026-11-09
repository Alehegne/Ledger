import type { ApiError } from "../types/domain";

export class AppError extends Error {
  status: number;
  code: string;

  constructor(payload: ApiError) {
    super(payload.message);
    this.status = payload.status;
    this.code = payload.code;
  }
}

export class UnsupportedInApiModeError extends Error {
  constructor(message: string) {
    super(message);
  }
}

export interface ActionableError {
  title: string;
  message: string;
  code?: string;
  status?: number;
}

const ERROR_CODE_TITLES: Record<string, string> = {
  VALIDATION_ERROR: "Validation Failed",
  UNBALANCED_JOURNAL: "Unbalanced Journal Entry",
  JOURNAL_ENTRY_UNBALANCED: "Unbalanced Journal Entry",
  JOURNAL_ENTRY_MISSING_SIDE: "Incomplete Journal Entry",
  ACCOUNT_INACTIVE: "Inactive Account Selected",
  BASE_CURRENCY_LOCKED: "Base Currency Locked",
  LAST_ADMIN_PROTECTION: "Administrator Required",
  CONCURRENT_MODIFICATION: "Concurrent Edit Conflict",
  DUPLICATE_ACCOUNT_CODE: "Duplicate Account Code",
  DUPLICATE_EMAIL: "Email Already Registered",
  DATA_INTEGRITY_VIOLATION: "Ledger Integrity Constraint",
  CROSS_ORGANIZATION_ACCOUNT: "Cross-Organization Access Blocked",
  INVALID_DATE_RANGE: "Invalid Date Range",
  INVALID_CURRENCY: "Invalid Currency Code",
  INVALID_INVITATION: "Invalid Invitation",
  INVITATION_EXPIRED: "Invitation Expired",
  INVITATION_ALREADY_USED: "Invitation Already Accepted",
  RESOURCE_NOT_FOUND: "Record Not Found",
  FORBIDDEN: "Permission Denied",
  UNAUTHORIZED: "Authentication Required",
};

const ERROR_CODE_GUIDANCE: Record<string, string> = {
  BASE_CURRENCY_LOCKED:
    "Base currency cannot be changed once journal transactions exist for the organization.",
  ACCOUNT_INACTIVE:
    "Deactivated accounts cannot accept new ledger postings. Choose an active account instead.",
  UNBALANCED_JOURNAL:
    "Total debits must exactly equal total credits before an entry can be posted.",
  JOURNAL_ENTRY_UNBALANCED:
    "Total debits must exactly equal total credits before an entry can be posted.",
  LAST_ADMIN_PROTECTION:
    "At least one active Administrator must remain assigned to the organization.",
  CONCURRENT_MODIFICATION:
    "Another user modified this record simultaneously. Reload the latest data and retry.",
  FORBIDDEN: "You do not have sufficient permissions for this operation.",
  UNAUTHORIZED: "Your session has expired. Please sign in again.",
};

export const formatActionableError = (
  error: unknown,
  fallbackMessage = "Something went wrong. Please try again.",
): ActionableError => {
  if (error instanceof AppError) {
    const title = ERROR_CODE_TITLES[error.code] ?? "Request Failed";
    const guidance = ERROR_CODE_GUIDANCE[error.code];
    const message =
      error.message.trim().length > 0
        ? error.message
        : (guidance ?? fallbackMessage);

    return {
      title,
      message,
      code: error.code,
      status: error.status,
    };
  }

  if (error instanceof UnsupportedInApiModeError) {
    return {
      title: "Unsupported Operation",
      message: error.message || fallbackMessage,
    };
  }

  if (error instanceof Error) {
    return {
      title: "Action Failed",
      message: error.message || fallbackMessage,
    };
  }

  return {
    title: "Action Failed",
    message: fallbackMessage,
  };
};

export const getErrorMessage = (
  error: unknown,
  fallbackMessage = "Something went wrong. Please try again.",
): string => formatActionableError(error, fallbackMessage).message;

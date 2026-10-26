# Ledger v1 — Software Requirements Specification (SRS)

**Version:** 1.0
**Status:** Draft / Requirements Locked
**System:** Ledger
**Technology Context:** Java + Spring Boot + PostgreSQL
**Primary Domain:** Double-entry accounting
**Scope:** Minimal multi-tenant organizational accounting system

---

# 1. Introduction

## 1.1 Purpose

Ledger is a multi-tenant accounting system that enables organizations to maintain their financial records using **double-entry bookkeeping**.

The system will allow organizations to:

- Manage users and roles.
- Maintain a chart of accounts.
- Create journal entries.
- Save journal entries as drafts.
- Validate and post journal entries.
- Maintain an immutable financial ledger after posting.
- Correct posted transactions through reversal/correction entries.
- View general ledger information.
- Generate trial balances.
- Maintain an audit trail of important actions.

Ledger v1 intentionally excludes advanced accounting features such as invoicing, inventory, payroll, taxation, banking integrations, and multi-currency accounting.

---

# 2. Goals

Ledger v1 has the following goals:

1. Implement a **correct double-entry accounting model**.
2. Ensure every posted journal entry is balanced.
3. Maintain an **immutable financial ledger**.
4. Support multiple users within an organization.
5. Enforce organization-level data isolation.
6. Provide basic accounting reports.
7. Maintain sufficient auditability to determine who performed important actions.
8. Provide a clean backend foundation for future accounting functionality.

---

# 3. Scope

## 3.1 In Scope

### Organization management

- Organizations
- Organization base currency
- Organization users
- User roles
- Organization-level data isolation

### Authentication

- Email/password authentication
- JWT-based authentication
- Email-based user invitation

### Chart of Accounts

- Account codes
- Account names
- Account types
- Parent/child accounts
- Active/inactive accounts
- Account balances
- Organization-specific charts of accounts

### Journal Entries

- Draft journal entries
- Multiple journal lines
- Debit and credit lines
- Journal entry validation
- Posting
- Immutable posted entries
- Reversal entries
- Corrective journal entries

### Ledger

- Posted transactions
- Account balances
- Running balances
- Immutable financial history

### Reporting

- General Ledger
- Trial Balance
- Date filtering
- Account filtering
- Pagination

### Audit

- Creation events
- Modification of drafts
- Posting events
- Reversal events
- Important account/organization actions

---

# 4. Out of Scope

The following are explicitly excluded from v1:

- Multi-currency transactions
- Currency conversion
- Accounting periods
- Fiscal-year management
- Invoices
- Customers
- Vendors
- Inventory
- Payroll
- Tax/VAT engine
- Bank integrations
- Payment processing
- Receipt/document attachments
- Complex notification systems
- Financial statement generation beyond the agreed reports
- Advanced budgeting
- Recurring transactions
- Bank reconciliation

These may be considered in future versions.

---

# 5. Actors and Roles

Ledger v1 has three organization-level roles.

## 5.1 Admin

The Admin manages the organization and has full accounting access.

Responsibilities include:

- Manage organization information.
- Invite users.
- Manage user roles.
- Manage chart of accounts.
- Create journal entries.
- Modify drafts.
- Post journal entries.
- Reverse posted entries.
- View reports.
- View audit information.

---

## 5.2 Accountant

The Accountant is responsible for day-to-day accounting operations.

The Accountant can:

- View accounts.
- Create journal entries.
- Modify drafts.
- Post journal entries.
- Create corrective entries.
- Reverse posted entries.
- View the ledger.
- View trial balances.

The Accountant cannot perform organization-level administrative operations unless explicitly granted such permissions in a future version.

---

## 5.3 Viewer

The Viewer has read-only access.

The Viewer can:

- View chart of accounts.
- View posted journal entries.
- View general ledger.
- View trial balance.

The Viewer cannot:

- Create journal entries.
- Modify drafts.
- Post entries.
- Reverse entries.
- Modify accounts.
- Manage users.

---

# 6. Organization Model

Ledger is multi-tenant.

Every organization has its own:

- Users
- Chart of accounts
- Journal entries
- Ledger
- Reports
- Audit records

Data belonging to one organization must never be accessible by another organization.

For example:

```text
Organization A
    ├── Users
    ├── Accounts
    ├── Journal Entries
    └── Ledger

Organization B
    ├── Users
    ├── Accounts
    ├── Journal Entries
    └── Ledger
```

A user cannot access Organization B's accounting information unless the system explicitly supports membership in that organization.

### v1 membership rule

A user belongs to **one organization only**.

---

# 7. Organization Currency

Each organization selects one base currency.

Example:

```text
Organization: Acme Ltd
Currency: ETB
```

Another organization could have:

```text
Organization: Example Inc.
Currency: USD
```

However, an organization cannot have multiple currencies in v1.

Currency conversion is outside the scope of v1.

---

# 8. Accounting Model

Ledger uses **double-entry bookkeeping**.

The fundamental accounting relationship is:

```text
Assets = Liabilities + Equity
```

Every posted journal entry must satisfy:

```text
Total Debits = Total Credits
```

---

# 9. Account Types

Ledger v1 supports five fundamental account types:

```text
ASSET
LIABILITY
EQUITY
REVENUE
EXPENSE
```

Each account type has a normal balance.

| Account Type | Normal Balance |
| ------------ | -------------- |
| Asset        | Debit          |
| Expense      | Debit          |
| Liability    | Credit         |
| Equity       | Credit         |
| Revenue      | Credit         |

This information is used when calculating account balances and financial reports.

---

# 10. Chart of Accounts

Each organization has its own Chart of Accounts.

Example:

```text
1000 Assets
    1100 Cash
    1200 Bank
    1300 Accounts Receivable

2000 Liabilities
    2100 Accounts Payable

3000 Equity
    3100 Owner's Equity

4000 Revenue
    4100 Sales Revenue

5000 Expenses
    5100 Rent
    5200 Salaries
    5300 Utilities
```

## 10.1 Account properties

An account should contain, at minimum:

- ID
- Organization
- Account code
- Name
- Account type
- Parent account, if applicable
- Status
- Creation timestamp

---

## 10.2 Account codes

Account codes are required.

Example:

```text
1000
1100
1110
5000
5100
```

Account codes must be unique within an organization.

Different organizations may use the same account code.

---

## 10.3 Account hierarchy

Accounts may have parent/child relationships.

Example:

```text
5000 Expenses
    ├── 5100 Operating Expenses
    │       ├── 5110 Rent
    │       └── 5120 Utilities
    │
    └── 5200 Salaries
```

The system must support hierarchical accounts but does not need sophisticated hierarchy-management functionality in v1.

---

# 11. Account Lifecycle

Accounts have two states:

```text
ACTIVE
INACTIVE
```

### Active

An active account can be used in new journal entries.

### Inactive

An inactive account cannot be used for new journal entries.

However, historical transactions involving the account remain accessible.

---

## 11.1 Account deletion

Accounts with accounting history must not be physically deleted.

The preferred mechanism is deactivation.

Therefore:

```text
Unused account
      ↓
Can potentially be removed/deactivated

Account with transactions
      ↓
Cannot be deleted
      ↓
Can be deactivated
```

This protects historical accounting data.

---

# 12. Journal Entry Model

The central accounting object is the **Journal Entry**.

A journal entry represents one accounting event.

Example:

> Paid October office rent of 2,000.

```text
Journal Entry
Date: 2026-10-01
Description: Paid October office rent

Debit:
    Rent Expense       2,000

Credit:
    Cash               2,000
```

---

# 13. Journal Entry Lines

A journal entry contains **two or more journal lines**.

Each line contains:

- Account
- Debit or credit direction
- Amount

Example:

```text
Journal Entry #1001

Line 1
Account: Rent Expense
Debit:   2,000

Line 2
Account: Cash
Credit:  2,000
```

A journal entry may contain more than two lines.

Example:

```text
Debit   Salary Expense       10,000
Debit   Tax Expense            2,000
Credit  Bank                  10,000
Credit  Tax Payable            2,000
```

---

# 14. Amount Rules

Amounts must:

- Be greater than zero.
- Never be negative.
- Belong to the organization's currency.

The debit/credit direction determines accounting movement.

For example:

```text
Debit 100
```

is valid.

```text
Debit -100
```

is invalid.

---

# 15. Journal Entry Metadata

Every journal entry contains:

- ID
- Organization
- Transaction date
- Description
- Optional reference
- Status
- Created by
- Created timestamp
- Posted by
- Posted timestamp
- Journal lines

Example:

```text
Journal Entry
---------------------------
ID:              JE-1001
Date:            2026-10-01
Description:     October rent
Reference:       RENT-001
Status:           POSTED
Created By:       user-1
Created At:       2026-10-08 10:00
Posted By:        user-2
Posted At:        2026-10-08 10:05
```

---

# 16. Journal Entry Lifecycle

The primary lifecycle is:

```text
        ┌──────────┐
        │  DRAFT   │
        └────┬─────┘
             │
             │ POST
             ▼
        ┌──────────┐
        │  POSTED  │
        └──────────┘
             │
             │ REVERSE
             ▼
        ┌──────────┐
        │ REVERSED │
        └──────────┘
```

The important distinction is:

### Draft

Can be modified.

### Posted

Cannot be modified or deleted.

### Reversed

The original financial entry remains unchanged, but a new journal entry reverses its financial effect.

---

# 17. Draft Entries

Draft entries do **not** affect account balances.

Example:

```text
Draft:
Debit  Rent Expense  1,000
Credit Cash          1,000
```

The ledger does not recognize this transaction until posting.

Therefore:

```text
Draft
  ↓
Not included in ledger balance
```

---

# 18. Posting Rules

A journal entry may only be posted if all validation requirements pass.

The system must verify:

1. Entry is currently a draft.
2. Entry has at least two lines.
3. Every line references a valid account.
4. Every account belongs to the same organization.
5. Every referenced account is active.
6. Every amount is greater than zero.
7. No amount is negative.
8. At least one debit exists.
9. At least one credit exists.
10. Total debits equal total credits.
11. Required journal-entry information is present.
12. Transaction date is valid.

Only after successful validation does the system post the entry.

---

# 19. Immutability

Once posted:

```text
POSTED
```

financial data cannot be:

- Edited
- Deleted

The original journal entry remains permanently available.

This is a fundamental Ledger requirement.

---

# 20. Correcting Posted Transactions

If a posted transaction is incorrect, the original transaction is not modified.

Instead, a new journal entry is created.

Example:

Original:

```text
JE-100

Debit  Rent Expense     1,000
Credit Cash             1,000
```

If it needs to be completely reversed:

```text
JE-101

Debit  Cash              1,000
Credit Rent Expense      1,000
```

The system should automatically generate the reversal entry when the user requests a reversal.

The reversal should maintain a relationship:

```text
JE-100
  │
  └── reversed by → JE-101
```

---

# 21. Corrective Entries

Partial or other corrections are performed by creating a new journal entry.

The system does not need a special "edit posted transaction" mechanism.

For example, if an expense was understated by 100:

```text
Debit  Expense     100
Credit Cash        100
```

The accountant creates the correction as a new journal entry.

---

# 22. Opening Balances

Ledger v1 does not require a special opening-balance feature.

Initial balances are established through a normal journal entry.

Example:

```text
Debit   Cash              100,000
Credit  Owner's Equity    100,000
```

This preserves the double-entry model.

---

# 23. Accounting Dates

Ledger distinguishes between:

```text
transactionDate
createdAt
postedAt
```

Example:

```text
Transaction Date:
2026-10-01

Created At:
2026-10-08 14:30

Posted At:
2026-10-08 14:35
```

The transaction date represents when the accounting event occurred.

The timestamps represent when the system/user actions occurred.

---

# 24. General Ledger

The General Ledger provides account-level transaction history.

Example:

```text
Account: Cash

Date       Description       Debit    Credit    Balance
--------------------------------------------------------
Oct 01     Initial Capital    10,000             10,000
Oct 02     Office Rent                  2,000     8,000
Oct 03     Customer Payment    5,000             13,000
```

The system must calculate a running balance.

Only posted entries affect the General Ledger.

---

# 25. Trial Balance

The system must provide a Trial Balance.

Example:

```text
Trial Balance

Account              Debit       Credit
-----------------------------------------
Cash                 13,000
Rent Expense          2,000
Sales Revenue                     15,000
Owner's Equity                     0
-----------------------------------------
Total                15,000       15,000
```

The fundamental requirement is:

```text
Total Debits = Total Credits
```

The Trial Balance should include **every account**, including accounts with zero balances, according to the decision made for v1.

---

# 26. Reporting Filters

Reports should support, at minimum:

- Date range
- Account filtering where applicable
- Pagination

Transaction history should support pagination.

Example:

```text
?page=0&size=20
```

---

# 27. Authentication

Ledger v1 uses:

```text
Email + Password
```

with:

```text
JWT authentication
```

Authentication is required for protected API operations.

Passwords must never be stored in plaintext.

---

# 28. Organization Invitations

The organization Admin can invite users through email.

The invitation process is conceptually:

```text
Admin
  │
  │ invite email
  ▼
Invitation
  │
  ▼
User accepts
  │
  ▼
Account created/activated
  │
  ▼
Organization membership
```

The exact email infrastructure and invitation-token implementation can be specified during technical design.

---

# 29. Audit Log

Ledger v1 maintains an audit trail for important actions.

Examples include:

```text
User created journal entry
User modified draft
User posted journal entry
User reversed journal entry
Admin created account
Admin deactivated account
Admin invited user
```

An audit record should identify, where applicable:

- Actor
- Organization
- Action
- Target entity
- Target ID
- Timestamp
- Relevant metadata

The audit log is separate from the financial ledger.

### Important distinction

```text
Financial Ledger
    ↓
What financially happened?

Audit Log
    ↓
Who performed the system action?
```

---

# 30. Data Isolation

Every organization-owned entity must be associated with an organization.

Conceptually:

```text
Organization
     │
     ├── Users
     ├── Accounts
     ├── Journal Entries
     ├── Journal Lines
     └── Audit Logs
```

All queries involving organization-owned resources must enforce organization boundaries.

A request authenticated as a member of Organization A must never retrieve Organization B's data by manipulating an ID.

---

# 31. Core Functional Requirements

| ID     | Requirement                                                           |
| ------ | --------------------------------------------------------------------- |
| FR-001 | System shall support organizations.                                   |
| FR-002 | Each organization shall have one base currency.                       |
| FR-003 | System shall support Admin, Accountant and Viewer roles.              |
| FR-004 | Users shall authenticate using email and password.                    |
| FR-005 | Authentication shall use JWT.                                         |
| FR-006 | Admin shall invite users by email.                                    |
| FR-007 | Each organization shall have its own chart of accounts.               |
| FR-008 | Accounts shall support five account types.                            |
| FR-009 | Accounts shall support codes.                                         |
| FR-010 | Accounts shall support parent/child relationships.                    |
| FR-011 | Accounts shall support active/inactive status.                        |
| FR-012 | System shall support draft journal entries.                           |
| FR-013 | Journal entries shall contain at least two lines.                     |
| FR-014 | Journal entries shall support multiple debit/credit lines.            |
| FR-015 | Debit and credit totals must balance before posting.                  |
| FR-016 | Draft entries shall not affect ledger balances.                       |
| FR-017 | Posted entries shall be immutable.                                    |
| FR-018 | Posted entries shall not be deleted.                                  |
| FR-019 | Posted entries shall be reversible.                                   |
| FR-020 | Reversals shall create new journal entries.                           |
| FR-021 | Corrective transactions shall be represented by new journal entries.  |
| FR-022 | System shall maintain a General Ledger.                               |
| FR-023 | General Ledger shall provide running balances.                        |
| FR-024 | System shall provide Trial Balance.                                   |
| FR-025 | Trial Balance shall include every account.                            |
| FR-026 | Reports shall support date filtering.                                 |
| FR-027 | Transaction/report endpoints shall support pagination.                |
| FR-028 | System shall maintain audit records.                                  |
| FR-029 | Organization data shall be isolated.                                  |
| FR-030 | Opening balances shall be represented through normal journal entries. |

---

# 32. Non-Functional Requirements

## 32.1 Data Integrity

Financial data integrity is the highest priority.

The system must prevent:

- Unbalanced posted entries
- Negative transaction amounts
- Posting to inactive accounts
- Cross-organization journal entries
- Modification of posted entries
- Deletion of posted financial records

---

## 32.2 Transactional Integrity

Posting a journal entry must be **atomic**.

Either:

```text
Journal Entry
+
All Journal Lines
+
Ledger effect
+
Relevant audit event
```

are successfully persisted, or none of them are.

There must not be a state where only some journal lines are persisted.

---

## 32.3 Concurrency

The system must account for concurrent operations.

For example, two users attempting to post or modify the same draft must not produce inconsistent financial records.

This will be addressed during technical design using appropriate database transactions and concurrency controls.

---

## 32.4 Security

The system must:

- Hash passwords.
- Authenticate protected requests.
- Authorize operations based on role.
- Enforce organization boundaries.
- Validate input.
- Prevent unauthorized access to financial records.
- Avoid exposing sensitive authentication information.

---

## 32.5 Auditability

Important accounting actions must be traceable to an authenticated user.

---

# 33. Core Domain Model

At the conceptual level:

```text
Organization
│
├── User
│    └── Role
│
├── Chart of Accounts
│    └── Account
│         └── Parent Account
│
├── Journal Entry
│    ├── Journal Line
│    │    └── Account
│    │
│    └── Journal Entry Metadata
│
└── Audit Log
```

A simplified relationship:

```text
Organization
     │
     ├─────────────── User
     │
     ├─────────────── Account
     │
     ├─────────────── JournalEntry
     │                       │
     │                       └── JournalLine ──→ Account
     │
     └─────────────── AuditLog
```

---

# 34. Key Accounting Invariants

These are some of the most important rules in the entire system.

### Invariant 1 — Balanced Entries

```text
SUM(debits) = SUM(credits)
```

for every posted journal entry.

### Invariant 2 — Positive Amounts

```text
amount > 0
```

### Invariant 3 — Minimum Lines

```text
journalEntry.lines >= 2
```

### Invariant 4 — Organization Ownership

Every account referenced by a journal entry must belong to the same organization as the journal entry.

### Invariant 5 — Active Accounts

New journal entries cannot use inactive accounts.

### Invariant 6 — Posted Immutability

```text
POSTED → cannot UPDATE
POSTED → cannot DELETE
```

### Invariant 7 — Draft Isolation

```text
DRAFT → does not affect balances
```

### Invariant 8 — Reversal

A reversal is a new financial event, not an alteration of the original event.

### Invariant 9 — Organization Isolation

```text
Organization A ≠ Organization B
```

No accounting data crosses tenant boundaries.

---

# 35. Example End-to-End Flow

Consider an organization that wants to record a $1,000 rent payment.

### Step 1 — Accountant creates draft

```text
POST /journal-entries
```

```text
Description: October office rent

Debit:
    Rent Expense     1,000

Credit:
    Cash             1,000
```

Status:

```text
DRAFT
```

No balance changes occur.

---

### Step 2 — Accountant posts

```text
POST /journal-entries/{id}/post
```

System validates:

```text
2 lines                 ✓
Positive amounts        ✓
Active accounts         ✓
Correct organization    ✓
Debit exists            ✓
Credit exists           ✓
10,000?                 no
1,000 = 1,000           ✓
```

Entry becomes:

```text
POSTED
```

---

### Step 3 — Ledger updates

Cash:

```text
-1,000
```

Rent Expense:

```text
+1,000
```

---

### Step 4 — Accountant discovers an error

The original entry cannot be edited.

Instead, the accountant either:

- reverses the original entry, or
- creates a separate corrective journal entry.

---

# 36. Suggested v1 API Surface

This is an initial API boundary, not yet a detailed API specification.

### Authentication

```text
POST /auth/login
POST /auth/refresh
```

### Organizations

```text
GET  /organizations/me
```

### Users

```text
GET    /users
POST   /users/invitations
PATCH  /users/{id}/role
```

### Accounts

```text
GET    /accounts
POST   /accounts
GET    /accounts/{id}
PATCH  /accounts/{id}
POST   /accounts/{id}/deactivate
```

### Journal Entries

```text
GET    /journal-entries
POST   /journal-entries
GET    /journal-entries/{id}
PATCH  /journal-entries/{id}
POST   /journal-entries/{id}/post
POST   /journal-entries/{id}/reverse
```

### Reports

```text
GET /reports/general-ledger
GET /reports/trial-balance
```

### Audit

```text
GET /audit-logs
```

These endpoints should be refined during the API design phase rather than treated as final contracts.

---

# 37. v1 Acceptance Criteria

Ledger v1 can be considered functionally complete when the following scenario works:

### Organization

An Admin can create/configure an organization with a base currency.

### Users

The Admin can invite users and assign:

```text
ADMIN
ACCOUNTANT
VIEWER
```

### Chart of Accounts

The organization can create:

```text
Assets
Liabilities
Equity
Revenue
Expenses
```

and individual accounts under them.

### Journal

An Accountant can create:

```text
DRAFT
```

journal entries with multiple lines.

### Validation

The system rejects:

```text
Unbalanced entries
Negative amounts
Zero amounts
Single-line entries
Inactive accounts
Accounts belonging to another organization
```

### Posting

A valid draft can be posted.

After posting:

```text
Ledger updated
Account balances updated
Audit event generated
```

### Immutability

Attempting to modify/delete a posted entry fails.

### Reversal

A posted entry can be reversed.

The system creates a new reversal journal entry while retaining the original.

### General Ledger

The system displays account transactions and running balances.

### Trial Balance

The system generates a trial balance where:

```text
Total Debits = Total Credits
```

### Security

A user cannot access another organization's accounting data.

---

# 38. v1 Architectural Boundary

The intended conceptual architecture is:

```text
                REST API
                   │
                   ▼
            Application Layer
                   │
                   ▼
             Domain Layer
                   │
          ┌────────┴────────┐
          │                 │
     Accounting         Organization
       Rules                Rules
          │
          ▼
     Persistence
          │
          ▼
      PostgreSQL
```

The accounting rules should not depend directly on HTTP or database implementation details.

This leaves room for the implementation to use a modular/clean architecture without making the SRS unnecessarily coupled to a specific code structure.

---

# 39. Future Expansion

The v1 domain should leave room for future capabilities such as:

```text
v2+
├── Invoicing
├── Customers
├── Vendors
├── Accounts Receivable
├── Accounts Payable
├── Financial Statements
├── Accounting Periods
├── Fiscal Years
├── Multi-currency
├── Tax/VAT
├── Bank Integration
├── Bank Reconciliation
├── Recurring Journal Entries
├── Attachments
├── Advanced Permissions
└── Notifications
```

These are **future possibilities, not v1 requirements**.

---

# 40. Final v1 Definition

The essence of Ledger v1 is:

> **A multi-tenant organizational double-entry accounting backend where users maintain a chart of accounts, create and post balanced journal entries, maintain an immutable financial ledger, correct mistakes through new entries/reversals, and generate General Ledger and Trial Balance reports.**

The most important design principle is:

````text
                 DRAFT
                   │
                   │ validate + post
                   ▼
                POSTED
                   │
          ┌────────┴────────┐
          │                 │
       immutable         reversal
          │                 │
          │                 ▼
          │            NEW ENTRY
          │
          ▼
    GENERAL LEDGER
          │
          ▼
    TRIAL BALANCE```
````

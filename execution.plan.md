# Ledger v1 — 5-Day Execution Plan

**Duration:** 5 days
**Goal:** Build a functional backend MVP of Ledger v1 from project foundation through accounting, reporting, security, testing, and final verification.

### Overall progression

```text
DAY 1
Foundation + Architecture + Database
        ↓
DAY 2
Authentication + Organization + Users + Accounts
        ↓
DAY 3
Journal Entries + Double-Entry Accounting
        ↓
DAY 4
Ledger + Trial Balance + Reversal + Audit
        ↓
DAY 5
Testing + Security + API Documentation + Final Hardening
```

The important principle is:

> **Do not build everything superficially. Build the accounting core correctly first, then expose and harden it.**

---

# Day 1 — Project Foundation, Architecture & Database

## Goal

Establish the technical foundation of Ledger so that the remaining four days can focus on business functionality.

By the end of Day 1, the application should:

- Start successfully.
- Connect to PostgreSQL.
- Have the initial architecture in place.
- Have the core database schema represented.
- Run migrations successfully.
- Have basic health checking.
- Have a consistent error-response structure.
- Have the project ready for feature development.

---

## 1. Project setup

### Configure

- Spring Boot
- Java
- Maven
- PostgreSQL
- Spring Data JPA
- Validation
- Actuator
- Testing dependencies

Suggested package structure:

```text
com.ledger
│
├── LedgerApplication
│
├── config
│
├── common
│   ├── exception
│   ├── response
│   └── validation
│
├── auth
├── organization
├── user
├── account
├── journal
├── ledger
├── report
└── audit
```

You don't need to implement every module yet.

The purpose is to establish a clean boundary.

---

# 2. Database configuration

Set up PostgreSQL.

Example conceptual database:

```text
ledger_db
```

Configure:

```text
spring.datasource.url
spring.datasource.username
spring.datasource.password
```

Verify:

```text
Application
      ↓
Spring Data JPA
      ↓
Hibernate
      ↓
PostgreSQL
```

---

# 3. Database migration strategy

Choose a migration mechanism such as:

- Flyway

Then establish the first migration.

For example:

```text
V1__initial_schema.sql
```

Don't manually change production-like schemas once migrations are established.

---

# 4. Core domain schema

Create the initial entities/tables needed by the system.

At minimum:

```text
Organization
User
Account
JournalEntry
JournalLine
AuditLog
Invitation
```

Conceptually:

```text
Organization
     │
     ├── User
     │
     ├── Account
     │
     ├── JournalEntry
     │       │
     │       └── JournalLine
     │
     ├── Invitation
     │
     └── AuditLog
```

Don't implement complex business behavior yet.

Day 1 is about getting the foundation right.

---

# 5. Enums

Establish domain enums.

For example:

```text
AccountType
    ASSET
    LIABILITY
    EQUITY
    REVENUE
    EXPENSE
```

```text
AccountStatus
    ACTIVE
    INACTIVE
```

```text
JournalEntryStatus
    DRAFT
    POSTED
```

```text
EntrySide
    DEBIT
    CREDIT
```

```text
Role
    ADMIN
    ACCOUNTANT
    VIEWER
```

---

# 6. Base entity strategy

Decide how common fields are represented.

For example:

```text
id
createdAt
updatedAt
```

Do not automatically add soft-delete fields to everything.

Remember that financial records are immutable after posting.

---

# 7. Global exception handling

Establish consistent API errors.

Example:

```json
{
  "timestamp": "...",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Journal entry is not balanced",
  "path": "/api/journal-entries"
}
```

Create the foundation for:

- Validation exceptions
- Resource-not-found exceptions
- Unauthorized exceptions
- Forbidden exceptions
- Business-rule exceptions
- Database constraint errors

---

# 8. Health endpoint

Expose:

```text
GET /actuator/health
```

Expected:

```json
{
  "status": "UP"
}
```

Verify that PostgreSQL connectivity works.

---

# 9. Day 1 testing

Write basic tests for:

- Application startup
- Database connectivity
- Entity persistence
- Basic repository operations

Don't write hundreds of tests yet.

---

## Day 1 Deliverables

By the end of Day 1:

```text
✓ Spring Boot application starts
✓ PostgreSQL connected
✓ Database migration works
✓ Core entities established
✓ Enums established
✓ Base entity strategy established
✓ Global exception handling foundation
✓ Actuator health check
✓ Basic repository tests
✓ Git repository clean/organized
```

### Day 1 Done When

You can run:

```bash
./mvnw spring-boot:run
```

and successfully reach:

```text
GET /actuator/health
```

with the application connected to PostgreSQL.

---

# Day 2 — Authentication, Organization & Chart of Accounts

## Goal

Build the identity and organizational foundation.

At the end of Day 2, a real user should be able to:

```text
Login
  ↓
JWT
  ↓
Organization
  ↓
Role
  ↓
Accounts
```

---

# 1. User management

Implement:

```text
User
```

Fields conceptually:

```text
id
organizationId
email
passwordHash
name
role
createdAt
updatedAt
```

Ensure email uniqueness according to the v1 organizational model.

---

# 2. Password security

Never store:

```text
password
```

directly.

Store:

```text
passwordHash
```

Use a proper password hashing mechanism.

---

# 3. JWT authentication

Implement:

```text
POST /auth/login
```

Flow:

```text
Email + Password
       ↓
Authenticate
       ↓
Generate JWT
       ↓
Return token
```

Protected endpoints should require authentication.

---

# 4. Authentication filter/security configuration

Establish:

```text
Request
   ↓
JWT extraction
   ↓
JWT validation
   ↓
Authenticated user
   ↓
Controller
```

The authenticated user's organization and role should be available to downstream application logic.

---

# 5. Role authorization

Implement:

```text
ADMIN
ACCOUNTANT
VIEWER
```

Example:

```text
POST /accounts
```

should not be accessible to:

```text
VIEWER
```

while:

```text
GET /accounts
```

can be available to all authenticated users.

---

# 6. Organization

Implement organization creation/configuration.

An organization should have:

```text
id
name
currency
createdAt
```

The organization's currency is selected during setup.

---

# 7. Invitation foundation

Implement the basic invitation mechanism.

Conceptually:

```text
Admin
 ↓
Invite email
 ↓
Invitation record
 ↓
Accept invitation
 ↓
User account
```

You don't need sophisticated email infrastructure on Day 2.

The core invitation lifecycle is enough.

---

# 8. Chart of Accounts

Implement CRUD operations for accounts.

For example:

```text
POST   /accounts
GET    /accounts
GET    /accounts/{id}
PATCH  /accounts/{id}
POST   /accounts/{id}/deactivate
```

---

# 9. Account validation

Implement:

### Account code uniqueness

```text
organization + accountCode
```

must be unique.

### Account type

Must be one of:

```text
ASSET
LIABILITY
EQUITY
REVENUE
EXPENSE
```

### Parent account

If supplied:

```text
parentAccount.organizationId
==
account.organizationId
```

---

# 10. Account lifecycle

Implement:

```text
ACTIVE
INACTIVE
```

Inactive accounts:

```text
GET → yes
Reports → yes
Historical transactions → yes
New journal entry → no
```

---

# 11. Organization isolation

This is critical.

Test:

```text
Organization A
    Account A1

Organization B
    Account B1
```

Authenticated as Organization A:

```text
GET Account B1
```

must fail.

Don't leave tenant isolation until the end.

---

## Day 2 Testing

Test:

### Authentication

```text
Valid credentials → success
Invalid password → failure
Unknown email → failure
Missing JWT → unauthorized
Invalid JWT → unauthorized
```

### Authorization

```text
Viewer → cannot create account
Accountant → can create account
Admin → can create account
```

### Tenant isolation

```text
Org A → cannot access Org B
```

### Accounts

```text
Create
Read
Update
Deactivate
Duplicate code rejection
Cross-org parent rejection
```

---

## Day 2 Deliverables

```text
✓ User authentication
✓ JWT
✓ Role authorization
✓ Organization
✓ Organization currency
✓ User invitation foundation
✓ Chart of Accounts
✓ Account hierarchy
✓ Account status
✓ Organization isolation
✓ Authentication tests
✓ Authorization tests
✓ Account tests
```

### Day 2 Done When

You can demonstrate:

```text
Admin logs in
       ↓
Creates account
       ↓
Accountant logs in
       ↓
Views account
       ↓
Viewer logs in
       ↓
Viewer can read but cannot modify
```

---

# Day 3 — Journal Entries & Double-Entry Accounting

## Goal

This is the **most important day**.

By the end of Day 3, Ledger should actually behave like an accounting system.

---

# 1. Journal Entry creation

Implement:

```text
POST /journal-entries
```

Example request:

```json
{
  "transactionDate": "2026-10-08",
  "description": "Office rent",
  "reference": "RENT-001",
  "lines": [
    {
      "accountId": "...",
      "side": "DEBIT",
      "amount": 2000
    },
    {
      "accountId": "...",
      "side": "CREDIT",
      "amount": 2000
    }
  ]
}
```

Create it as:

```text
DRAFT
```

---

# 2. Draft editing

Implement:

```text
PATCH /journal-entries/{id}
```

Only drafts may be edited.

Attempting to edit:

```text
POSTED
```

must fail.

---

# 3. Draft deletion

Allow draft deletion.

Do not allow:

```text
POSTED → DELETE
```

---

# 4. Journal validation

Implement the complete validation layer.

### Minimum lines

```text
lines >= 2
```

### Amount

```text
amount > 0
```

### Debit

At least one debit.

### Credit

At least one credit.

### Account

Every account must exist.

### Organization

Every account must belong to the same organization.

### Account status

Every account must be active.

### Balance

```text
SUM(debits) == SUM(credits)
```

### Status

Only:

```text
DRAFT
```

can be posted.

---

# 5. Posting

Implement:

```text
POST /journal-entries/{id}/post
```

The operation must be transactional.

Conceptually:

```text
BEGIN TRANSACTION

Validate journal entry

Mark entry POSTED

Record postedBy
Record postedAt

Persist accounting effect

Create audit event

COMMIT
```

If anything fails:

```text
ROLLBACK
```

---

# 6. Accounting calculations

Implement the account balance logic.

For example:

### Asset

```text
Debit → increases
Credit → decreases
```

### Liability

```text
Credit → increases
Debit → decreases
```

### Equity

```text
Credit → increases
Debit → decreases
```

### Revenue

```text
Credit → increases
Debit → decreases
```

### Expense

```text
Debit → increases
Credit → decreases
```

---

# 7. Transactional integrity

This is where Spring transactions become important.

Posting must not result in:

```text
Journal entry posted
but
journal lines missing
```

or:

```text
Journal entry posted
but
audit event missing
```

Use proper transaction boundaries.

---

# 8. Core accounting scenarios

Implement and test scenarios such as:

### Cash investment

```text
Debit  Cash             100,000
Credit Owner's Equity   100,000
```

### Rent

```text
Debit  Rent Expense       5,000
Credit Cash               5,000
```

### Revenue

```text
Debit  Cash               10,000
Credit Sales Revenue      10,000
```

### Purchase on credit

```text
Debit  Equipment          20,000
Credit Accounts Payable   20,000
```

---

# 9. Journal entry history

Implement:

```text
GET /journal-entries
GET /journal-entries/{id}
```

with pagination.

Example:

```text
?page=0&size=20
```

Only organization-owned entries should be returned.

---

# 10. Journal entry filters

Support appropriate filtering such as:

```text
date range
account
status
```

while ensuring posted financial reporting only uses posted entries.

---

## Day 3 Testing

This should be your strongest test suite so far.

Test:

```text
✓ Balanced entry
✓ Unbalanced entry
✓ Zero amount
✓ Negative amount
✓ One-line entry
✓ Debit-only entry
✓ Credit-only entry
✓ Inactive account
✓ Nonexistent account
✓ Cross-organization account
✓ Draft editing
✓ Draft deletion
✓ Posting
✓ Posted modification rejection
✓ Posted deletion rejection
✓ Transaction rollback
✓ Multiple-line entries
```

---

## Day 3 Deliverables

```text
✓ Journal Entry creation
✓ Journal Lines
✓ Draft lifecycle
✓ Posting
✓ Double-entry validation
✓ Account balance calculations
✓ Transactional posting
✓ Posted immutability
✓ Journal history
✓ Pagination
✓ Journal filtering
✓ Comprehensive accounting tests
```

### Day 3 Done When

You can perform:

```text
Create accounts
      ↓
Create journal entry
      ↓
Save draft
      ↓
Edit draft
      ↓
Validate
      ↓
Post
      ↓
Account balances change
      ↓
Entry becomes immutable
```

At this point, you have the **core accounting engine**.

---

# Day 4 — General Ledger, Trial Balance, Reversal & Audit

## Goal

Turn the accounting engine into a usable accounting system.

Day 3 records transactions.

Day 4 makes those transactions **observable, reportable, reversible, and auditable**.

---

# 1. General Ledger

Implement:

```text
GET /reports/general-ledger
```

It should show posted transactions grouped/filtered by account.

Example:

```text
Cash

Date       Description       Debit    Credit    Balance
--------------------------------------------------------
Oct 01     Initial Capital   100,000            100,000
Oct 02     Rent                         5,000     95,000
Oct 03     Customer Payment   20,000            115,000
```

---

# 2. Running balance

Calculate the balance according to account normal balance.

For example:

### Asset

```text
Opening balance
+ Debit
- Credit
= Current balance
```

### Liability

```text
Opening balance
+ Credit
- Debit
= Current balance
```

---

# 3. Date filtering

General Ledger should support date filtering.

Example:

```text
from=2026-10-01
to=2026-10-31
```

---

# 4. Account filtering

Example:

```text
accountId=...
```

This allows the user to inspect one account.

---

# 5. Pagination

Do not return thousands of journal lines in one response.

Support:

```text
page
size
```

and appropriate sorting.

---

# 6. Trial Balance

Implement:

```text
GET /reports/trial-balance
```

For each account:

```text
Account
Debit Balance
Credit Balance
```

Every account should be represented, including zero-balance accounts.

---

# 7. Trial Balance validation

The report itself should verify:

```text
Total Debit Balances
=
Total Credit Balances
```

This becomes an important system integrity check.

---

# 8. Reversal

Implement:

```text
POST /journal-entries/{id}/reverse
```

When a posted entry is reversed:

```text
Original:

Debit  Cash       10,000
Credit Revenue    10,000
```

System generates:

```text
Reversal:

Debit  Revenue    10,000
Credit Cash        10,000
```

The original entry remains untouched.

---

# 9. Reversal relationship

Maintain the relationship between:

```text
Original Journal Entry
        ↕
Reversal Journal Entry
```

This allows users to understand why the reversal exists.

---

# 10. Prevent duplicate reversal

A journal entry should not accidentally be reversed multiple times.

For example:

```text
JE-100
  ↓
Reversed by JE-101
```

Attempting another reversal should be rejected unless the domain rules explicitly permit it.

---

# 11. Audit logging

Implement the actual audit mechanism.

Events should include things such as:

```text
USER_INVITED
ACCOUNT_CREATED
ACCOUNT_DEACTIVATED
JOURNAL_CREATED
JOURNAL_UPDATED
JOURNAL_POSTED
JOURNAL_REVERSED
```

Example:

```json
{
  "actor": "user-123",
  "action": "JOURNAL_POSTED",
  "entityType": "JOURNAL_ENTRY",
  "entityId": "je-100",
  "timestamp": "..."
}
```

---

# 12. Audit queries

Implement:

```text
GET /audit-logs
```

with appropriate authorization.

Filtering can include:

```text
actor
action
entity
date
```

Keep this simple in v1.

---

# 13. Report correctness tests

Test:

```text
Create transactions
       ↓
Post transactions
       ↓
Generate General Ledger
       ↓
Verify running balance
       ↓
Generate Trial Balance
       ↓
Verify debit = credit
```

---

## Day 4 Deliverables

```text
✓ General Ledger
✓ Running balances
✓ Account filtering
✓ Date filtering
✓ Pagination
✓ Trial Balance
✓ Zero-balance account inclusion
✓ Trial Balance validation
✓ Automatic reversal
✓ Reversal relationship
✓ Duplicate reversal protection
✓ Audit log
✓ Audit queries
✓ Reporting tests
```

### Day 4 Done When

You can demonstrate the complete accounting lifecycle:

```text
Account
   ↓
Journal Entry
   ↓
Post
   ↓
Ledger
   ↓
Trial Balance
   ↓
Reverse
   ↓
Ledger changes
   ↓
Audit history
```

---

# Day 5 — Testing, Security, Documentation & Production Hardening

## Goal

Day 5 is **not another feature-development day**.

It is for turning the previous four days of implementation into a reliable v1.

---

# 1. Full integration testing

Run the entire application against PostgreSQL.

Test realistic workflows rather than isolated methods only.

Example:

```text
Create organization
        ↓
Create users
        ↓
Login
        ↓
Create accounts
        ↓
Create journal
        ↓
Post
        ↓
Generate ledger
        ↓
Generate trial balance
        ↓
Reverse
        ↓
Generate reports again
```

---

# 2. Security testing

Test:

### Authentication

```text
No token → 401
Invalid token → 401
Expired token → 401
```

### Authorization

```text
Viewer attempting POST → 403
Viewer attempting DELETE → 403
```

### Tenant isolation

Extensively test:

```text
Org A token
    ↓
Org B resource ID
    ↓
Must not expose Org B
```

This is one of the most important security tests in the project.

---

# 3. Accounting invariant tests

Create dedicated tests for the fundamental accounting rules.

For example:

```text
Every POSTED journal entry:
    debit == credit
```

```text
Every journal line:
    amount > 0
```

```text
Every posted journal:
    lines >= 2
```

```text
Every journal account:
    same organization
```

```text
Every posted journal:
    immutable
```

---

# 4. Transaction failure testing

Intentionally cause failures during posting.

Verify that the entire operation rolls back.

For example:

```text
BEGIN
 ↓
Journal update
 ↓
Line persistence
 ↓
Simulated failure
 ↓
ROLLBACK
```

Then verify the database contains no partial transaction.

---

# 5. Concurrency testing

Test scenarios such as:

```text
User A ──┐
         ├── attempts to post JE-100
User B ──┘
```

Verify that the system doesn't allow inconsistent state or duplicate posting.

You don't need an elaborate distributed concurrency framework for v1.

Focus on database transaction boundaries and appropriate locking/versioning.

---

# 6. API documentation

Document the API using:

```text
OpenAPI / Swagger
```

Document:

- Authentication
- Organizations
- Users
- Accounts
- Journal entries
- Posting
- Reversal
- General Ledger
- Trial Balance
- Audit logs
- Error responses

---

# 7. README

The README should explain:

```text
Ledger
│
├── What it is
├── Architecture
├── Tech stack
├── Features
├── Setup
├── Environment variables
├── Database
├── Running locally
├── Testing
└── API documentation
```

---

# 8. Database documentation

Document the core relationships.

For example:

```text
Organization
    │
    ├── User
    ├── Account
    ├── JournalEntry
    │       └── JournalLine
    └── AuditLog
```

A simple ER diagram would be useful.

---

# 9. API error consistency

Review every endpoint.

Make sure errors follow a consistent structure.

Examples:

```text
400 Bad Request
401 Unauthorized
403 Forbidden
404 Not Found
409 Conflict
422 Validation Error
500 Internal Server Error
```

Do not expose stack traces or database internals.

---

# 10. Database constraints review

Review whether important invariants are protected at the database level where appropriate.

For example:

```text
Organization
    ↓
Account organization ownership

JournalEntry
    ↓
JournalLine
```

Check:

- Foreign keys
- Unique constraints
- Not-null constraints
- Appropriate indexes

---

# 11. Performance review

You don't need premature optimization.

But review obvious query patterns.

Especially:

```text
General Ledger
Trial Balance
Journal history
Account lookup
Organization filtering
```

Ensure important lookup/filter columns have appropriate indexes.

---

# 12. Code quality review

Review:

### Controllers

Should primarily handle:

```text
HTTP
 ↓
DTO
 ↓
Application service
```

### Services

Contain application/business orchestration.

### Domain logic

Accounting rules should not be scattered across controllers.

### Repositories

Handle persistence.

Avoid:

```text
Controller
   ↓
repository.save()
```

for complex accounting operations.

---

# 13. Remove scope creep

On Day 5 explicitly verify that you did **not accidentally implement unnecessary v2 features**.

Remove or defer things like:

```text
Invoices
VAT
Inventory
Payroll
Multi-currency
Banking
Notifications
Recurring transactions
Complex fiscal periods
```

This is important because a five-day project can easily become unfinished because of scope creep.

---

# 14. Final acceptance test

Perform one complete scenario.

### Setup

```text
Organization:
Acme

Currency:
ETB
```

Create:

```text
1000 Cash
3000 Owner's Equity
4000 Sales Revenue
5000 Rent Expense
```

---

### Transaction 1

Initial capital:

```text
Debit  Cash             100,000
Credit Owner's Equity   100,000
```

---

### Transaction 2

Sales:

```text
Debit  Cash              20,000
Credit Sales Revenue     20,000
```

---

### Transaction 3

Rent:

```text
Debit  Rent Expense       5,000
Credit Cash                5,000
```

Expected cash:

```text
100,000 + 20,000 - 5,000
=
115,000
```

---

### Trial Balance

Expected:

```text
Cash                  115,000 Debit
Rent Expense            5,000 Debit
Owner's Equity                     100,000 Credit
Sales Revenue                       20,000 Credit
--------------------------------------------------
Total                 120,000       120,000
```

If that works, your core accounting engine is behaving correctly.

---

# Day 5 Deliverables

```text
✓ Integration test suite
✓ Security tests
✓ Tenant-isolation tests
✓ Accounting invariant tests
✓ Transaction rollback tests
✓ Basic concurrency tests
✓ Swagger/OpenAPI
✓ README
✓ ER/domain documentation
✓ Error handling review
✓ Database constraint review
✓ Index review
✓ Code quality review
✓ Final acceptance test
✓ Scope review
✓ v1 release candidate
```

---

# Final 5-Day Checklist

## Day 1 — Foundation

```text
[ ] Spring Boot setup
[ ] PostgreSQL
[ ] Flyway/migrations
[ ] Core entities
[ ] Enums
[ ] Base entity strategy
[ ] Exception handling
[ ] Actuator
[ ] Database tests
```

## Day 2 — Identity & Accounts

```text
[ ] Organization
[ ] User
[ ] Password hashing
[ ] JWT
[ ] Roles
[ ] Authorization
[ ] Invitations
[ ] Chart of Accounts
[ ] Account hierarchy
[ ] Active/inactive
[ ] Tenant isolation
[ ] Security tests
```

## Day 3 — Accounting Engine

```text
[ ] Journal Entry
[ ] Journal Lines
[ ] Draft
[ ] Draft editing
[ ] Draft deletion
[ ] Posting
[ ] Debit/credit validation
[ ] Balance validation
[ ] Account validation
[ ] Transactional posting
[ ] Posted immutability
[ ] Journal history
[ ] Pagination
[ ] Accounting tests
```

## Day 4 — Ledger & Reporting

```text
[ ] General Ledger
[ ] Running balances
[ ] Date filtering
[ ] Account filtering
[ ] Trial Balance
[ ] Zero-balance accounts
[ ] Reversal
[ ] Reversal relationship
[ ] Duplicate reversal protection
[ ] Audit log
[ ] Audit queries
[ ] Report tests
```

## Day 5 — Hardening

```text
[ ] Integration tests
[ ] Security tests
[ ] Tenant-isolation tests
[ ] Accounting invariant tests
[ ] Rollback tests
[ ] Concurrency tests
[ ] Swagger
[ ] README
[ ] ER diagram
[ ] Database constraints
[ ] Index review
[ ] Error-response review
[ ] Code-quality review
[ ] Final acceptance test
[ ] Scope review
```

---

# What You Should Have After 5 Days

The final system should be demonstrable as:

```text
                    LEDGER
                      │
          ┌───────────┴───────────┐
          │                       │
    Authentication            Organization
          │                       │
       JWT/RBAC              Chart of Accounts
                                  │
                                  ▼
                         Journal Entries
                                  │
                         ┌────────┴────────┐
                         │                 │
                       DRAFT             POST
                                           │
                                           ▼
                                      POSTED ENTRY
                                           │
                          ┌────────────────┼────────────────┐
                          │                │                │
                       Ledger          Trial Balance     Audit Log
                          │
                     Running Balance
                          │
                       Reverse
                          │
                    New Journal Entry
```

# Ledger API Documentation

## 1. Overview

This document describes the HTTP API that is actually implemented in the current Ledger codebase through Day 4. It is grounded in the current controllers, DTOs, service logic, security configuration, migrations, and automated tests.

Ledger is a multi-tenant accounting backend built with Java 25, Spring Boot 4.1.1, Spring Security, Spring Data JPA, Flyway, and PostgreSQL.

### 1.1 Implementation status

Implemented today:

- Day 1 foundation and health checks
- Day 2 login with JWT
- Day 2 organization creation
- Day 2 invitation creation and acceptance
- Day 2 organization-scoped chart-of-accounts management
- Day 3 journal draft creation, retrieval, update, deletion, listing, filtering, and posting
- Day 3 posted-only account balance calculation
- Day 4 general ledger report with opening/closing balances and running balances per line
- Day 4 trial balance report with all accounts (including zero-balance) and debit/credit columns
- Day 4 dashboard overview summary with current-org totals, counts, and recent activity feed
- Day 4 journal entry reversal with concurrent duplicate prevention via pessimistic locking
- Day 4 audit log recording and querying with filtering and pagination

Partially implemented today:

- user management exists only through invitation create and invitation accept flows
- organization settings are exposed through the authenticated current-organization routes, but broader user-role management remains out of scope for v1

Not implemented over HTTP:

- user management endpoints beyond invitations
- there is no mail-delivery integration for invitations

### 1.2 Endpoint index

| Method   | Path                                 | Purpose                                                  | Section |
| -------- | ------------------------------------ | -------------------------------------------------------- | ------- |
| `GET`    | `/actuator/health`                   | Application health status                                | 7.1     |
| `POST`   | `/auth/login`                        | Exchange email/password for a JWT                        | 8.1     |
| `POST`   | `/organizations`                     | Create an organization                                   | 8.2     |
| `GET`    | `/organizations/me`                  | Read the current organization settings                   | 8.3     |
| `PATCH`  | `/organizations/me`                  | Update the current organization settings                 | 8.4     |
| `POST`   | `/users/invite`                      | Create an invitation in the current organization         | 9.1     |
| `POST`   | `/invitations/accept`                | Accept an invitation and create a user                   | 9.2     |
| `POST`   | `/accounts`                          | Create an account in the current organization            | 10.1    |
| `GET`    | `/accounts`                          | List accounts in the current organization                | 10.2    |
| `GET`    | `/accounts/{accountId}`              | Retrieve one account in the current organization         | 10.3    |
| `GET`    | `/accounts/{accountId}/balance`      | Compute a posted-only account balance                    | 10.4    |
| `PATCH`  | `/accounts/{accountId}`              | Update one account in the current organization           | 10.5    |
| `POST`   | `/accounts/{accountId}/deactivate`   | Mark an account inactive                                 | 10.6    |
| `POST`   | `/journal-entries`                   | Create a draft journal entry                             | 11.1    |
| `GET`    | `/journal-entries`                   | List journal entries with pagination and filters         | 11.2    |
| `GET`    | `/journal-entries/{entryId}`         | Retrieve one journal entry                               | 11.3    |
| `PATCH`  | `/journal-entries/{entryId}`         | Update a draft journal entry                             | 11.4    |
| `DELETE` | `/journal-entries/{entryId}`         | Delete a draft journal entry                             | 11.5    |
| `POST`   | `/journal-entries/{entryId}/post`    | Post a balanced draft journal entry                      | 11.6    |
| `POST`   | `/journal-entries/{entryId}/reverse` | Reverse a posted journal entry                           | 12.1    |
| `GET`    | `/api/dashboard/overview`            | Dashboard totals, draft/post counts, and recent activity | 13.1    |
| `GET`    | `/api/reports/general-ledger`        | General ledger with running balances                     | 13.2    |
| `GET`    | `/api/reports/trial-balance`         | Trial balance report with debit/credit columns           | 13.3    |
| `GET`    | `/api/audit-logs`                    | Query audit logs with filtering and pagination           | 13.4    |

### 1.3 Source-of-truth rules

- The SRS defines intended product scope.
- The implementation defines actual API behavior today.
- When the SRS is broader than the implementation, this document records the gap instead of presenting future functionality as available.

## 2. Local Setup and Configuration

### 2.1 Runtime prerequisites

- Java 25
- Maven wrapper via `./mvnw`
- PostgreSQL 16 or compatible
- `curl` for manual API checks
- `jq` for the root-level shell API runner in [api-test.sh](/home/alexoo/notes/backend/Ledger/api-test.sh)

### 2.2 Default application configuration

Default values come from [src/main/resources/application.properties](/home/alexoo/notes/backend/Ledger/src/main/resources/application.properties):

- datasource URL: `jdbc:postgresql://localhost:5433/Ledger_DB`
- datasource username: `myuser`
- datasource password: `mypassword`
- JWT issuer: `ledger`
- JWT expiration: `3600000` ms
- bootstrap admin email: `admin@ledger.test`
- bootstrap admin password: `Admin123!`
- bootstrap organization name: `Ledger Bootstrap`
- bootstrap organization currency: `ETB`

No `server.port` is configured, so the default local base URL is:

```text
http://localhost:8080
```

### 2.3 Local database startup

The repository includes [docker-compose.yml](/home/alexoo/notes/backend/Ledger/docker-compose.yml):

```bash
cd /home/alexoo/notes/backend/Ledger
docker compose up -d db
```

This starts PostgreSQL on `localhost:5433` and creates the default `Ledger_DB` database.

### 2.4 Application startup

```bash
cd /home/alexoo/notes/backend/Ledger
./mvnw spring-boot:run
```

### 2.5 Bootstrap behavior

On startup, [OrganizationBootstrap.java](/home/alexoo/notes/backend/Ledger/src/main/java/com/example/Ledger/common/security/OrganizationBootstrap.java) creates a default organization and admin user only when the database contains no organizations.

Actual behavior:

- if `organization.count() > 0`, bootstrap does nothing
- the bootstrap user is created only if no existing user matches the configured email case-insensitively
- the bootstrap user is assigned role `ADMIN`

This is startup logic, not a public registration endpoint.

## 3. API Conventions

### 3.1 Transport conventions

- JSON request and response bodies are used for all business endpoints
- the API has no version prefix today
- the actuator health endpoint is exposed under `/actuator/health`
- successful list endpoints return either a bare array or a paginated object, depending on the endpoint
- invitation acceptance returns `{ "message": "Invitation accepted" }`

### 3.2 Authentication header

Protected endpoints require:

```http
Authorization: Bearer <jwt>
```

### 3.3 Timestamp format

Timestamps are serialized as ISO-8601 instants through Jackson and Java `Instant` fields.

### 3.4 Resource identifiers

- path IDs are numeric `Long` identifiers
- the current organization is derived from the authenticated principal, not from request JSON
- protected request DTOs do not expose an `organizationId` override field

### 3.5 Validation conventions

- Spring validation failures return `400 Bad Request`
- service-layer business rule failures usually return `422 Unprocessable Content`
- duplicate account code is a special case mapped to `409 Conflict`

## 4. Authentication and Authorization

### 4.1 Authentication model

Authentication uses email/password login and stateless JWT bearer tokens.

### 4.2 JWT claims

The JWT generated by `JwtService` contains:

- `sub`: user ID
- `iss`: issuer
- `iat`: issued-at
- `exp`: expiration
- `email`: user email
- `organizationId`: current organization ID
- `role`: `ADMIN`, `ACCOUNTANT`, or `VIEWER`

### 4.3 Authorization model

Actual roles in [Role.java](/home/alexoo/notes/backend/Ledger/src/main/java/com/example/Ledger/common/enums/Role.java):

- `ADMIN`
- `ACCOUNTANT`
- `VIEWER`

Actual behavior through Day 3:

- `ADMIN` can create organizations, invite users, manage accounts, and perform journal write operations
- `ACCOUNTANT` can manage accounts and perform journal write operations
- `VIEWER` can read accounts, balances, and journal history, but cannot modify them
- invitation acceptance is public because the invited user is not authenticated yet

### 4.4 `401` vs `403`

- `401 Unauthorized`: missing or invalid authentication
- `403 Forbidden`: authenticated user lacks permission

## 5. Standard Request and Response Formats

### 5.1 Standard error response

The shared error payload is produced by [GlobalExceptionHandler.java](/home/alexoo/notes/backend/Ledger/src/main/java/com/example/Ledger/common/exception/GlobalExceptionHandler.java) and the security handlers:

```json
{
  "timestamp": "2026-10-12T12:00:00Z",
  "status": 401,
  "code": "UNAUTHORIZED",
  "message": "Authentication required",
  "path": "/accounts"
}
```

### 5.2 Common status codes

- `200 OK`: successful request
- `400 Bad Request`: validation or malformed request payload
- `401 Unauthorized`: authentication missing or invalid
- `403 Forbidden`: insufficient role
- `404 Not Found`: resource not found in the current organization scope
- `409 Conflict`: duplicate account code in the same organization
- `422 Unprocessable Content`: business rule failure other than duplicate account code
- `500 Internal Server Error`: unhandled exception

## 6. Shared Business Rules

### 6.1 Organization scoping

- accounts and journal entries are organization-scoped
- organization context always comes from the authenticated user
- cross-organization account access and cross-organization journal access are rejected

### 6.2 Monetary precision

- journal line amounts are represented with `BigDecimal`
- PostgreSQL uses exact numeric storage for journal amounts
- posted account balances are computed from posted journal lines only

## 7. Day 1 — Foundation and Health

### 7.1 Health endpoint

**Method:** `GET`
**Path:** `/actuator/health`
**Purpose:** Report application health.
**Authentication:** Not required.
**Authorization:** None.
**Organization scope:** Not organization-scoped.

#### Successful response

Status: `200 OK`

```json
{
  "status": "UP"
}
```

#### cURL example

```bash
curl -i http://localhost:8080/actuator/health
```

## 8. Day 2 — Authentication and Organization

### 8.1 Login

**Method:** `POST`
**Path:** `/auth/login`
**Purpose:** Authenticate a user and return a JWT plus user summary.
**Authentication:** Not required.
**Authorization:** None.
**Organization scope:** The returned token carries the user organization ID and drives protected organization-scoped operations.

#### Request

```json
{
  "email": "admin@ledger.test",
  "password": "Admin123!"
}
```

#### Successful response

Status: `200 OK`

```json
{
  "token": "<jwt>",
  "expiresAt": "2026-10-12T12:00:00Z",
  "user": {
    "id": 1,
    "email": "admin@ledger.test",
    "fullName": "System Administrator",
    "role": "ADMIN",
    "organizationId": 1
  }
}
```

#### Error responses

- `400 Bad Request` for missing or invalid email/password fields
- `401 Unauthorized` with code `UNAUTHORIZED` for invalid credentials

#### Identity note

- user email is globally unique (case-insensitive) across all organizations
- login does not include an organization selector; identity is resolved by unique email + password

### 8.2 Create organization

**Method:** `POST`
**Path:** `/organizations`
**Purpose:** Create a new organization.
**Authentication:** Required.
**Authorization:** `ADMIN` only.
**Organization scope:** Organization creation itself is not scoped to an existing organization ID in the request.

#### Request

```json
{
  "name": "Acme Org",
  "baseCurrency": "ETB"
}
```

#### Successful response

Status: `200 OK`

```json
{
  "id": 2,
  "name": "Acme Org",
  "baseCurrency": "ETB",
  "createdAt": "2026-10-12T12:00:00Z"
}
```

#### Error responses

- `400 Bad Request` for validation failures
- `401 Unauthorized` for missing or invalid token
- `403 Forbidden` for authenticated non-admin users
- `422 Unprocessable Content` with code `ORGANIZATION_NAME_EXISTS` when the name already exists

### 8.3 Read current organization settings

**Method:** `GET`
**Path:** `/organizations/me`
**Purpose:** Read the currently authenticated organization’s settings for the admin configuration screen.
**Authentication:** Required.
**Authorization:** `ADMIN` only.
**Organization scope:** Derived from the authenticated principal; the caller cannot request another tenant’s organization.

#### Successful response

Status: `200 OK`

```json
{
  "id": 1,
  "name": "Acme Org",
  "baseCurrency": "ETB",
  "createdAt": "2026-10-12T12:00:00Z"
}
```

#### Validation and contract notes

- the response is the current organization’s identity and configuration only
- no secrets or internal organization metadata are exposed
- the target organization is resolved from the JWT, not any client-supplied `organizationId`

### 8.4 Update current organization settings

**Method:** `PATCH`
**Path:** `/organizations/me`
**Purpose:** Update the current organization’s editable configuration.
**Authentication:** Required.
**Authorization:** `ADMIN` only.
**Organization scope:** Tenant-scoped to the authenticated principal.

#### Request

```json
{
  "name": "Northstar Co.",
  "baseCurrency": "USD"
}
```

#### Successful response

Status: `200 OK`

```json
{
  "id": 1,
  "name": "Northstar Co.",
  "baseCurrency": "USD",
  "createdAt": "2026-10-12T12:00:00Z"
}
```

#### Validation rules

- `name`, when supplied, is trimmed and must not be blank
- `baseCurrency`, when supplied, is trimmed and uppercased
- `baseCurrency` must match `^[A-Z]{3}$`
- unspecified fields are preserved during partial updates
- duplicate organization names are rejected case-insensitively

#### Error responses

- `400 Bad Request` for malformed input or validation failures
- `403 Forbidden` for authenticated non-admin users
- `422 Unprocessable Content` when the name is blank or a duplicate name is attempted

## 9. Day 2 — User Management and Invitations

User management is partially implemented. The current HTTP API exposes invitation create and invitation accept flows, but not user listing, user updates, role reassignment, or user deletion.

### 9.1 Create invitation

**Method:** `POST`
**Path:** `/users/invite`
**Purpose:** Create an invitation for a new user in the authenticated organization.
**Authentication:** Required.
**Authorization:** `ADMIN` only.
**Organization scope:** The invitation is created in the current authenticated organization.

#### Request

```json
{
  "email": "viewer@acme.test",
  "role": "VIEWER"
}
```

#### Successful response

Status: `200 OK`

```json
{
  "id": 1,
  "organizationId": 1,
  "invitedEmail": "viewer@acme.test",
  "role": "VIEWER",
  "invitationToken": "<raw invitation token>",
  "expiresAt": "2026-10-19T12:00:00Z",
  "createdAt": "2026-10-12T12:00:00Z"
}
```

#### Error responses

- `400 Bad Request` for validation failures
- `401 Unauthorized` for missing or invalid token
- `403 Forbidden` for authenticated non-admin users
- `422 Unprocessable Content` with code `DUPLICATE_EMAIL` when any existing user already has the same email

### 9.2 Accept invitation

**Method:** `POST`
**Path:** `/invitations/accept`
**Purpose:** Accept an invitation and create a user in the invited organization.
**Authentication:** Not required.
**Authorization:** None.
**Organization scope:** The accepted user is created in the organization attached to the invitation.

#### Request

```json
{
  "token": "<raw invitation token>",
  "email": "viewer@acme.test",
  "fullName": "Acme Viewer",
  "password": "Secret123!"
}
```

#### Successful response

Status: `200 OK`

```json
{
  "message": "Invitation accepted"
}
```

#### Error responses

- `400 Bad Request` for validation failures
- `422 Unprocessable Content` with codes including `INVALID_INVITATION`, `INVITATION_ALREADY_USED`, `INVITATION_EXPIRED`, or `DUPLICATE_EMAIL`

### 9.3 List organization users

**Method:** `GET`
**Path:** `/users`
**Purpose:** List the current organization’s active members in a deterministic order for the admin user directory.
**Authentication:** Required.
**Authorization:** `ADMIN` only.
**Organization scope:** The response is filtered to the authenticated principal’s organization and never accepts a client-supplied organization ID.

#### Successful response

Status: `200 OK`

```json
[
  {
    "id": 1,
    "email": "admin@acme.test",
    "fullName": "Acme Admin",
    "role": "ADMIN",
    "invitationStatus": "ACTIVE"
  },
  {
    "id": 2,
    "email": "viewer@acme.test",
    "fullName": "Acme Viewer",
    "role": "VIEWER",
    "invitationStatus": "ACTIVE"
  }
]
```

#### Notes

- The response includes only the fields required by the organization directory UI.
- Password hashes, JWTs, invitation tokens, and other secrets are never serialized.
- This endpoint exposes current organization members only; pending invitations are not listed as directory members in v1.
- Ordering is deterministic: by `fullName` ascending, then `email` ascending.

#### Error responses

- `401 Unauthorized` for missing or invalid token
- `403 Forbidden` for non-admin users

## 10. Day 2 and Day 3 — Chart of Accounts

### 10.1 Create account

**Method:** `POST`
**Path:** `/accounts`
**Purpose:** Create an account in the current organization.
**Authentication:** Required.
**Authorization:** `ADMIN` or `ACCOUNTANT`.
**Organization scope:** The created account belongs to the authenticated user organization.

#### Request

```json
{
  "accountCode": "1000",
  "name": "Cash",
  "accountType": "ASSET",
  "status": "ACTIVE",
  "parentAccountId": null
}
```

#### Successful response

Status: `200 OK`

```json
{
  "id": 4,
  "organizationId": 1,
  "accountCode": "1000",
  "name": "Cash",
  "accountType": "ASSET",
  "status": "ACTIVE",
  "parentAccountId": null,
  "createdAt": "2026-10-12T12:00:00Z",
  "updatedAt": "2026-10-12T12:00:00Z"
}
```

#### Error responses

- `400 Bad Request` for validation failures
- `401 Unauthorized` for missing or invalid token
- `403 Forbidden` for authenticated users without create permission
- `409 Conflict` with code `DUPLICATE_ACCOUNT_CODE`

### 10.2 List accounts

**Method:** `GET`
**Path:** `/accounts`
**Purpose:** List accounts for the current organization.
**Authentication:** Required.
**Authorization:** `ADMIN`, `ACCOUNTANT`, or `VIEWER`.
**Organization scope:** Only accounts for the authenticated organization are returned.

### 10.3 Get account

**Method:** `GET`
**Path:** `/accounts/{accountId}`
**Purpose:** Retrieve one account in the current organization.
**Authentication:** Required.
**Authorization:** `ADMIN`, `ACCOUNTANT`, or `VIEWER`.
**Organization scope:** The lookup is restricted to `id + organizationId`.

### 10.4 Get posted-only account balance

**Method:** `GET`
**Path:** `/accounts/{accountId}/balance`
**Purpose:** Compute the signed balance for one account using posted journal lines only.
**Authentication:** Required.
**Authorization:** `ADMIN`, `ACCOUNTANT`, or `VIEWER`.
**Organization scope:** The account lookup and journal aggregation are restricted to the current organization.

#### Successful response

Status: `200 OK`

```json
{
  "accountId": 4,
  "accountCode": "5100",
  "accountType": "EXPENSE",
  "balance": 1000.0
}
```

#### Validation and business rules

- draft journal entries do not affect this value
- `ASSET` and `EXPENSE` balances are computed as debit minus credit
- `LIABILITY`, `EQUITY`, and `REVENUE` balances are computed as credit minus debit

### 10.5 Update account

**Method:** `PATCH`
**Path:** `/accounts/{accountId}`
**Purpose:** Update mutable account fields in the current organization.
**Authentication:** Required.
**Authorization:** `ADMIN` or `ACCOUNTANT`.
**Organization scope:** The account must belong to the authenticated organization.

### 10.6 Deactivate account

**Method:** `POST`
**Path:** `/accounts/{accountId}/deactivate`
**Purpose:** Mark an account inactive.
**Authentication:** Required.
**Authorization:** `ADMIN` or `ACCOUNTANT`.
**Organization scope:** The account must belong to the authenticated organization.

## 11. Day 3 — Journal Entries and Posting

### 11.1 Create draft journal entry

**Method:** `POST`
**Path:** `/journal-entries`
**Purpose:** Create a journal entry in `DRAFT` status.
**Authentication:** Required.
**Authorization:** `ADMIN` or `ACCOUNTANT`.
**Organization scope:** The journal entry organization is derived from the authenticated user.

#### Request

```json
{
  "transactionDate": "2026-10-08",
  "description": "Office rent",
  "reference": "RENT-001",
  "lines": [
    {
      "accountId": 10,
      "side": "DEBIT",
      "amount": 2000.0
    },
    {
      "accountId": 11,
      "side": "CREDIT",
      "amount": 2000.0
    }
  ]
}
```

#### Successful response

Status: `200 OK`

```json
{
  "id": 5,
  "organizationId": 1,
  "transactionDate": "2026-10-08",
  "description": "Office rent",
  "reference": "RENT-001",
  "status": "DRAFT",
  "createdByUserId": 2,
  "postedByUserId": null,
  "postedAt": null,
  "totalDebits": 2000.0,
  "totalCredits": 2000.0,
  "lines": [
    {
      "id": 20,
      "accountId": 10,
      "accountCode": "5100",
      "accountName": "Rent Expense",
      "accountType": "EXPENSE",
      "side": "DEBIT",
      "amount": 2000.0
    },
    {
      "id": 21,
      "accountId": 11,
      "accountCode": "1100",
      "accountName": "Cash",
      "accountType": "ASSET",
      "side": "CREDIT",
      "amount": 2000.0
    }
  ],
  "createdAt": "2026-10-12T12:00:00Z",
  "updatedAt": "2026-10-12T12:00:00Z"
}
```

#### Error responses

- `400 Bad Request` for DTO validation failures such as missing transaction date, missing description, missing account ID, invalid enum values, or a one-line request
- `401 Unauthorized` for missing or invalid token
- `403 Forbidden` for authenticated users without journal write permission
- `404 Not Found` when a referenced account does not exist
- `422 Unprocessable Content` with codes such as `ACCOUNT_INACTIVE` or `CROSS_ORGANIZATION_ACCOUNT`

#### Validation and business rules

- `transactionDate` is required
- `description` is required and limited to 500 characters
- `reference` is optional and limited to 120 characters
- request `lines` must contain at least two items
- each line requires `accountId`, `side`, and a strictly positive `amount`
- draft creation validates account existence, organization ownership, active status, debit presence, and credit presence
- draft creation does not require the draft to be balanced; balance is enforced at posting time

### 11.2 List journal entries

**Method:** `GET`
**Path:** `/journal-entries`
**Purpose:** List journal entries for the current organization with pagination and optional filters.
**Authentication:** Required.
**Authorization:** `ADMIN`, `ACCOUNTANT`, or `VIEWER`.
**Organization scope:** Every result is restricted to the authenticated organization.

#### Query parameters

- `status`: optional `DRAFT` or `POSTED`
- `fromDate`: optional ISO date
- `toDate`: optional ISO date
- `accountId`: optional account filter
- `page`: defaults to `0`; negative values are normalized to `0`
- `size`: defaults to `20`; values are normalized into `1..100`

#### Successful response

Status: `200 OK`

```json
{
  "page": 0,
  "size": 20,
  "totalElements": 3,
  "totalPages": 1,
  "items": [
    {
      "id": 5,
      "organizationId": 1,
      "transactionDate": "2026-10-08",
      "description": "Office rent",
      "reference": "RENT-001",
      "status": "DRAFT",
      "createdByUserId": 2,
      "postedByUserId": null,
      "postedAt": null,
      "totalDebits": 2000.0,
      "totalCredits": 2000.0,
      "lines": [],
      "createdAt": "2026-10-12T12:00:00Z",
      "updatedAt": "2026-10-12T12:00:00Z"
    }
  ]
}
```

#### Validation and business rules

- stable ordering is `transactionDate desc, id desc`
- `fromDate > toDate` is rejected with `422 INVALID_DATE_RANGE`
- account filtering returns only entries that contain a matching line and uses a distinct query shape to avoid duplicates

### 11.3 Get journal entry

**Method:** `GET`
**Path:** `/journal-entries/{entryId}`
**Purpose:** Retrieve one journal entry in the current organization.
**Authentication:** Required.
**Authorization:** `ADMIN`, `ACCOUNTANT`, or `VIEWER`.
**Organization scope:** The lookup is restricted to `id + organizationId`.

#### Error responses

- `401 Unauthorized` for missing or invalid token
- `404 Not Found` when the entry does not exist in the current organization

### 11.4 Update draft journal entry

**Method:** `PATCH`
**Path:** `/journal-entries/{entryId}`
**Purpose:** Update mutable fields of a draft journal entry.
**Authentication:** Required.
**Authorization:** `ADMIN` or `ACCOUNTANT`.
**Organization scope:** The journal entry must belong to the authenticated organization.

#### Request

All fields are optional:

```json
{
  "transactionDate": "2026-10-09",
  "description": "Updated office rent",
  "reference": "RENT-002",
  "lines": [
    {
      "accountId": 10,
      "side": "DEBIT",
      "amount": 2100.0
    },
    {
      "accountId": 11,
      "side": "CREDIT",
      "amount": 2100.0
    }
  ]
}
```

#### Error responses

- `400 Bad Request` for DTO validation failures
- `401 Unauthorized` for missing or invalid token
- `403 Forbidden` for authenticated users without journal write permission
- `404 Not Found` when the entry does not exist in the current organization
- `422 Unprocessable Content` with code `JOURNAL_ENTRY_NOT_DRAFT` when the entry has already been posted

### 11.5 Delete draft journal entry

**Method:** `DELETE`
**Path:** `/journal-entries/{entryId}`
**Purpose:** Delete a draft journal entry.
**Authentication:** Required.
**Authorization:** `ADMIN` or `ACCOUNTANT`.
**Organization scope:** The journal entry must belong to the authenticated organization.

#### Successful response

Status: `200 OK`

#### Error responses

- `401 Unauthorized` for missing or invalid token
- `404 Not Found` when the entry does not exist in the current organization
- `422 Unprocessable Content` with code `JOURNAL_ENTRY_NOT_DRAFT` when the entry has already been posted

### 11.6 Post journal entry

**Method:** `POST`
**Path:** `/journal-entries/{entryId}/post`
**Purpose:** Validate and post a balanced draft journal entry transactionally.
**Authentication:** Required.
**Authorization:** `ADMIN` or `ACCOUNTANT`.
**Organization scope:** The journal entry and all referenced accounts must belong to the authenticated organization.

#### Successful response

Status: `200 OK`

The response uses the same `JournalEntryResponse` shape as other journal endpoints, with these key differences:

- `status` becomes `POSTED`
- `postedByUserId` is set to the authenticated user ID
- `postedAt` is set by the server

#### Error responses

- `401 Unauthorized` for missing or invalid token
- `403 Forbidden` for authenticated users without journal write permission
- `404 Not Found` when the entry does not exist in the current organization
- `422 Unprocessable Content` with codes such as `JOURNAL_ENTRY_NOT_DRAFT`, `JOURNAL_ENTRY_MIN_LINES`, `JOURNAL_ENTRY_MISSING_SIDE`, `JOURNAL_ENTRY_UNBALANCED`, `ACCOUNT_INACTIVE`, or `CROSS_ORGANIZATION_ACCOUNT`

#### Validation and business rules

- only `DRAFT` entries can be posted
- posting revalidates the journal entry even if it was valid when created as a draft
- total debits must equal total credits
- posting records `postedByUserId` and `postedAt`
- posting creates a `JOURNAL_ENTRY_POSTED` audit log inside the same transaction
- account balances are derived from posted journal lines only; posting changes balances exactly once

### 11.7 Reverse journal entry

**Method:** `POST`
**Path:** `/journal-entries/{entryId}/reverse`
**Purpose:** Reverse a posted journal entry atomically with duplicate prevention via pessimistic locking.
**Authentication:** Required.
**Authorization:** `ADMIN` or `ACCOUNTANT`.
**Organization scope:** The journal entry must belong to the authenticated organization.

#### Successful response

Status: `200 OK`

The response uses the `JournalEntryResponse` shape with the reversal entry details:

```json
{
  "id": 42,
  "organizationId": 1,
  "transactionDate": "2026-10-12",
  "description": "Reversal of: Original description",
  "reference": "REV-ORIG-REF",
  "status": "POSTED",
  "createdByUserId": 2,
  "postedByUserId": 2,
  "postedAt": "2026-10-12T12:00:00Z",
  "totalDebits": 500.0,
  "totalCredits": 500.0,
  "lines": [
    {
      "accountId": 5,
      "side": "CREDIT",
      "amount": 500.0
    },
    {
      "accountId": 4,
      "side": "DEBIT",
      "amount": 500.0
    }
  ],
  "createdAt": "2026-10-12T12:00:00Z",
  "updatedAt": "2026-10-12T12:00:00Z"
}
```

#### Error responses

- `401 Unauthorized` for missing or invalid token
- `403 Forbidden` for authenticated users without journal write permission
- `404 Not Found` when the entry does not exist in the current organization
- `422 Unprocessable Content` with codes:
  - `JOURNAL_ENTRY_NOT_POSTED`: entry has not been posted yet
  - `JOURNAL_ENTRY_ALREADY_REVERSED`: entry has already been reversed

#### Validation and business rules

- only `POSTED` entries can be reversed
- reversal atomically creates a new journal entry with opposite lines, posts it immediately, and links the relationship
- reversal lines have opposite sides (DEBIT ↔ CREDIT) with same amounts
- reversal description is prefixed with "Reversal of: " plus the original description
- reversal reference is prefixed with "REV-" plus the original reference (if present)
- reversal transaction date is set to the current date
- duplicate reversal prevention is enforced via pessimistic row-level locking; concurrent attempts to reverse the same entry will have only one succeed
- reversals create two audit log entries in the same transaction:
  - `JOURNAL_REVERSED` on the original entry
  - `JOURNAL_POSTED` on the reversal entry
- original entry balances are reversed by the reversal entry on the date of reversal

## 12. Day 4 — Reports and Reversals

### 12.1 General Ledger report

**Method:** `GET`
**Path:** `/api/reports/general-ledger`
**Purpose:** Retrieve paginated posted journal lines for an account with opening/closing balances and running balance per line.
**Authentication:** Required.
**Authorization:** `ADMIN`, `ACCOUNTANT`, or `VIEWER`.
**Organization scope:** Results are restricted to the authenticated organization.

#### Query parameters

- `accountId`: required; numeric long account ID
- `fromDate`: optional ISO date; filters lines with `transactionDate >= fromDate`
- `toDate`: optional ISO date; filters lines with `transactionDate <= toDate`
- `page`: defaults to `0`; negative values are normalized to `0`
- `size`: defaults to `20`; values are normalized into `1..100`

#### Successful response

Status: `200 OK`

```json
{
  "accountId": 5,
  "accountCode": "5100",
  "accountName": "Rent Expense",
  "accountType": "EXPENSE",
  "openingBalance": 500.0,
  "closingBalance": 1200.0,
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1,
  "lines": [
    {
      "journalEntryId": 10,
      "transactionDate": "2026-10-12",
      "description": "October rent payment",
      "reference": "RENT-OCT",
      "side": "DEBIT",
      "amount": 700.0,
      "runningBalance": 1200.0
    },
    {
      "journalEntryId": 9,
      "transactionDate": "2026-10-11",
      "description": "Advance rent",
      "reference": "RENT-ADV",
      "side": "DEBIT",
      "amount": 700.0,
      "runningBalance": 500.0
    }
  ]
}
```

#### Validation and business rules

- opening balance is calculated from all posted lines through `(fromDate - 1 day)` if `fromDate` is specified, otherwise zero
- paginated lines are ordered by `transactionDate DESC, journalEntryId DESC`, with an internal journal-line ID tie-breaker for stable pagination (newest first)
- running balance is computed over the full filtered window and remains continuous across pages
- each returned line shows the account balance immediately after that line's transaction in chronological accounting terms
- running-balance updates use account type normal balance convention:
  - `ASSET` and `EXPENSE` (debit-normal): debits increase, credits decrease
  - `LIABILITY`, `EQUITY`, and `REVENUE` (credit-normal): credits increase, debits decrease
- closing balance is calculated as opening balance plus all debits/credits in the filtered date range
- account must belong to the current organization; cross-organization access returns `404 Not Found`

#### Error responses

- `400 Bad Request` for missing `accountId` or invalid date formats
- `401 Unauthorized` for missing or invalid token
- `404 Not Found` when the account does not exist in the current organization

### 12.2 Trial Balance report

**Method:** `GET`
**Path:** `/api/reports/trial-balance`
**Purpose:** Retrieve all accounts in the current organization with running balances through an as-of date, with debit/credit columns and total verification.
**Authentication:** Required.
**Authorization:** `ADMIN`, `ACCOUNTANT`, or `VIEWER`.
**Organization scope:** Results include all accounts in the authenticated organization.

#### Query parameters

- `asOfDate`: optional ISO date; defaults to today; balances reflect all posted lines through (and including) this date

#### Successful response

Status: `200 OK`

```json
{
  "asOfDate": "2026-10-12",
  "totalDebits": 10500.0,
  "totalCredits": 10500.0,
  "balanced": true,
  "accounts": [
    {
      "accountId": 1,
      "accountCode": "1100",
      "accountName": "Cash",
      "accountType": "ASSET",
      "debitBalance": 5000.0,
      "creditBalance": 0.0
    },
    {
      "accountId": 4,
      "accountCode": "4000",
      "accountName": "Sales Revenue",
      "accountType": "REVENUE",
      "debitBalance": 0.0,
      "creditBalance": 5000.0
    },
    {
      "accountId": 5,
      "accountCode": "5100",
      "accountName": "Rent Expense",
      "accountType": "EXPENSE",
      "debitBalance": 5500.0,
      "creditBalance": 0.0
    },
    {
      "accountId": 2,
      "accountCode": "2000",
      "accountName": "Accounts Payable",
      "accountType": "LIABILITY",
      "debitBalance": 0.0,
      "creditBalance": 5500.0
    }
  ]
}
```

#### Validation and business rules

- all accounts in the current organization are included, even those with zero balance
- account balance is calculated from posted lines through (and including) `asOfDate`
- debit and credit columns are populated based on account type normal balance:
  - `ASSET` and `EXPENSE`: positive balances appear in the debit column
  - `LIABILITY`, `EQUITY`, and `REVENUE`: positive balances appear in the credit column
- `totalDebits` is the sum of all positive balances in the debit column
- `totalCredits` is the sum of all positive balances in the credit column
- `balanced` is `true` when `totalDebits == totalCredits`; this is the fundamental accounting equation for a balanced trial balance
- if `asOfDate` is not specified, it defaults to the current date

#### Error responses

- `401 Unauthorized` for missing or invalid token

### 12.3 Audit logs query

**Method:** `GET`
**Path:** `/api/audit-logs`
**Purpose:** Query organization audit logs with optional filtering by actor, action, entity type, and date range with pagination.
**Authentication:** Required.
**Authorization:** `ADMIN`, `ACCOUNTANT`, or `VIEWER`.
**Organization scope:** Results are restricted to the authenticated organization's audit logs.

#### Query parameters

- `actor`: optional numeric user ID; filters logs where `actorUser.id == actor`
- `action`: optional string action name (e.g., `JOURNAL_POSTED`, `JOURNAL_REVERSED`, `ACCOUNT_CREATED`); exact match
- `entity`: optional string entity type (e.g., `JOURNAL_ENTRY`, `ACCOUNT`); exact match
- `fromDate`: optional ISO date; filters logs with `createdAt >= startOfDay(fromDate)`
- `toDate`: optional ISO date; filters logs with `createdAt < startOfDay(toDate + 1 day)`
- `page`: defaults to `0`; negative values are normalized to `0`
- `size`: defaults to `20`; values are normalized into `1..100`

#### Successful response

Status: `200 OK`

```json
{
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3,
  "items": [
    {
      "id": 101,
      "actor": 2,
      "action": "JOURNAL_POSTED",
      "entityType": "JOURNAL_ENTRY",
      "entityId": "10",
      "createdAt": "2026-10-12T12:00:00Z",
      "metadata": {
        "status": "POSTED",
        "totalDebits": 500.0
      }
    },
    {
      "id": 100,
      "actor": 2,
      "action": "JOURNAL_CREATED",
      "entityType": "JOURNAL_ENTRY",
      "entityId": "10",
      "createdAt": "2026-10-12T11:00:00Z",
      "metadata": null
    }
  ]
}
```

#### Validation and business rules

- results are ordered by `createdAt DESC` (newest first)
- results include all filters specified in the query; filters are combined with AND logic
- actor is displayed as the user ID (or `null` for system-initiated actions)
- metadata is displayed as JSON or `null` if no metadata was recorded
- page and size validation follows standard conventions
- organization scope is enforced server-side; users cannot query other organizations' audit logs

#### Error responses

- `401 Unauthorized` for missing or invalid token

## 13. Endpoint Permission Matrix

| Endpoint                                  | ADMIN | ACCOUNTANT | VIEWER | Public |
| ----------------------------------------- | ----- | ---------- | ------ | ------ |
| `GET /actuator/health`                    | Yes   | Yes        | Yes    | Yes    |
| `POST /auth/login`                        | Yes   | Yes        | Yes    | Yes    |
| `POST /organizations`                     | Yes   | No         | No     | No     |
| `POST /users/invite`                      | Yes   | No         | No     | No     |
| `POST /invitations/accept`                | N/A   | N/A        | N/A    | Yes    |
| `POST /accounts`                          | Yes   | Yes        | No     | No     |
| `GET /accounts`                           | Yes   | Yes        | Yes    | No     |
| `GET /accounts/{accountId}`               | Yes   | Yes        | Yes    | No     |
| `GET /accounts/{accountId}/balance`       | Yes   | Yes        | Yes    | No     |
| `PATCH /accounts/{accountId}`             | Yes   | Yes        | No     | No     |
| `POST /accounts/{accountId}/deactivate`   | Yes   | Yes        | No     | No     |
| `POST /journal-entries`                   | Yes   | Yes        | No     | No     |
| `GET /journal-entries`                    | Yes   | Yes        | Yes    | No     |
| `GET /journal-entries/{entryId}`          | Yes   | Yes        | Yes    | No     |
| `PATCH /journal-entries/{entryId}`        | Yes   | Yes        | No     | No     |
| `DELETE /journal-entries/{entryId}`       | Yes   | Yes        | No     | No     |
| `POST /journal-entries/{entryId}/post`    | Yes   | Yes        | No     | No     |
| `POST /journal-entries/{entryId}/reverse` | Yes   | Yes        | No     | No     |
| `GET /api/reports/general-ledger`         | Yes   | Yes        | Yes    | No     |
| `GET /api/reports/trial-balance`          | Yes   | Yes        | Yes    | No     |
| `GET /api/audit-logs`                     | Yes   | Yes        | Yes    | No     |

## 13. End-to-End Workflows

### 13.1 Account and journal lifecycle

1. Start PostgreSQL and the application.
2. Call `GET /actuator/health`.
3. Authenticate with `POST /auth/login`.
4. Create accounts with `POST /accounts`.
5. Create a draft journal entry with `POST /journal-entries`.
6. Retrieve or update the draft with `GET /journal-entries/{id}` and `PATCH /journal-entries/{id}`.
7. Post the journal entry with `POST /journal-entries/{id}/post`.
8. Verify the resulting posted-only balance with `GET /accounts/{accountId}/balance`.

### 13.2 Invitation-based viewer provisioning

1. Authenticate as an admin.
2. Create an invitation with `POST /users/invite`.
3. Accept the invitation with `POST /invitations/accept`.
4. Log in as the invited user with `POST /auth/login`.
5. Confirm the invited `VIEWER` can read accounts, balances, and journal history but cannot modify them.

### 13.3 Reporting and audit workflow

1. Authenticate as an accountant.
2. Create and post several journal entries across different dates.
3. Query the general ledger with `GET /api/reports/general-ledger?accountId=X&fromDate=Y&toDate=Z` to verify running balances and date filtering.
4. Query the trial balance with `GET /api/reports/trial-balance?asOfDate=Z` to verify all accounts are included and debits equal credits.
5. Reverse a posted entry with `POST /journal-entries/{entryId}/reverse`.
6. Verify the reversal is posted and the account balance is restored.
7. Query audit logs with `GET /api/audit-logs?action=JOURNAL_POSTED` to see recorded events.

### 13.4 Automated verification

The root-level shell runner [api-test.sh](/home/alexoo/notes/backend/Ledger/api-test.sh) exercises implemented Day 1–4 flows against a running application.

## 14. Known Limitations and Deferred Features

### 14.1 SRS vs current implementation

The SRS is broader than the current HTTP API.

Resolved gaps in Day 4:

- general ledger and trial-balance report endpoints are now exposed
- reversal workflows are now exposed
- audit-log query endpoints are now exposed

Remaining gaps:

- user management is limited to invitation create and accept flows
- there is no mail-delivery integration for invitations
- there is no `GET /organizations` or organization update endpoint

### 14.2 Verified limitations and caveats

- the current implementation allows unbalanced drafts to be saved but rejects them at posting time; this preserves draft workflow while enforcing accounting correctness before a posted entry can affect balances
- invitation endpoints remain a separate unstable slice: earlier isolated end-to-end verification reproduced a runtime `500` from `POST /users/invite`, so invitation behavior should be revalidated after any related fixes
- tenant isolation beyond same-organization scoping is only partially testable through the public API because the API does not expose a safe HTTP-only workflow for provisioning a second authenticated organization user

## 15. Adding Documentation for a New Endpoint

When a new endpoint is implemented:

1. Add it to the endpoint index in Section 1.2.
2. Place it in the business-capability section that matches the feature, or create a new stable section if the feature area is new.
3. Use the same endpoint template used in Sections 7 through 11.
4. Document only request fields, response fields, status codes, and business rules that are present in the implementation.
5. Update the permission matrix in Section 12.
6. Add a corresponding runnable case to [api-test.sh](/home/alexoo/notes/backend/Ledger/api-test.sh).

Future sections that fit naturally into this structure once implemented include:

- Day 4 ledger and trial balance queries
- Day 4 or Day 5 reversal APIs
- audit-log query endpoints

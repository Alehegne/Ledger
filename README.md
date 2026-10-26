# Ledger

Ledger is a multi-tenant, double-entry accounting backend built with Java 25, Spring Boot 4.1.1, PostgreSQL, Flyway, and Spring Security.

## Purpose

The project implements the core v1 accounting workflow:

- organization-scoped data isolation
- chart of accounts and account status management
- draft journal entry creation and validation
- posting and reversal of balanced entries
- immutable posted ledger records
- general ledger and trial balance reporting
- audit logging for important accounting events

## Architecture summary

- Java 25 + Spring Boot 4.1.1
- Spring Security with JWT bearer authentication
- Spring Data JPA and PostgreSQL
- Flyway schema migrations
- Maven wrapper for builds and tests

## Prerequisites

- Java 25
- Docker for PostgreSQL if you want the provided local database
- Maven wrapper or a compatible Maven installation

## Local environment

The default project configuration is stored in [src/main/resources/application.properties](src/main/resources/application.properties).

Local PostgreSQL defaults:

- host: localhost
- port: 5433
- database: Ledger_DB
- username: myuser
- password: mypassword

The project also includes [docker-compose.yml](docker-compose.yml) to start the local database.

## Database startup

```bash
cd /home/alexoo/notes/backend/Ledger
docker compose up -d db
```

## Application startup

```bash
cd /home/alexoo/notes/backend/Ledger
./mvnw spring-boot:run
```

## Flyway behavior

Flyway migrations run automatically on startup when the application connects to PostgreSQL.

Important constraints:

- migration scripts must not silently rewrite production accounting data
- global uniqueness checks for email must be safe for existing data
- for legacy duplicate identities, operational cleanup is required before a hard unique index can be enforced without risk

See [src/main/resources/db/migration](src/main/resources/db/migration) and [api-docs.md](api-docs.md) for the current migration and API behavior.

## Authentication flow

Authentication is email/password based and returns a JWT.

Example:

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@ledger.test",
    "password": "Admin123!"
  }'
```

Use the returned token as a bearer token for protected endpoints:

```bash
curl http://localhost:8080/accounts \
  -H "Authorization: Bearer <jwt>"
```

## Roles

- ADMIN: full organization and accounting access
- ACCOUNTANT: operational accounting access, including posting and reversals
- VIEWER: read-only access to accounts, reports, and journal history

## Validation and tests

Run the project tests with:

```bash
cd /home/alexoo/notes/backend/Ledger
./mvnw test
```

Focused regression tests for security and accounting behavior:

```bash
./mvnw -q -Dtest=Day2AuthenticationAndAccountTests,Day4ReportAndReversalTests test
```

## Known limitations

- v1 does not include multi-currency, bank integrations, payroll, or tax processing
- user management beyond invitation flows is intentionally limited in the current backend surface
- public invitation acceptance is allowed by design before the invited user is authenticated
- production cleanup is required if legacy duplicate emails already exist before a permanent unique index can be made unconditional

## API reference

The implementation-level endpoint documentation is maintained in [api-docs.md](api-docs.md). The SRS remains the product-level source of truth for scope and business rules.

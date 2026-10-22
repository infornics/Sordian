# Sordian --- Technology Stack

> **Purpose:** Define a practical, zero-license-cost technology stack
> for Sordian, a Sordian that validates Git pushes before
> forwarding accepted changes to GitHub and helps enforce pull-request
> merge policy.
>
> **Frontend:** Next.js\
> **Backend:** Java 21 LTS + Spring Boot\
> **Database:** PostgreSQL\
> **Cost principle:** Use free/open-source software; remember that
> hosting, domains, backups, bandwidth, and CI overages may still cost
> money.

## 1. Product architecture

Sordian sits in the Git write path between developers and canonical
GitHub repositories.

1.  A developer runs a normal `git push` to Sordian.
2.  Sordian receives Git objects into a temporary/quarantined area.
3.  It validates proposed ref updates against repository policy.
4.  Invalid pushes are rejected with a clear reason.
5.  Valid pushes are forwarded to GitHub.
6.  The client sees success only after GitHub confirms the update.
7.  Temporary objects are cleaned up safely; uncertain upstream outcomes
    are tracked and reconciled before retrying.

GitHub remains the canonical source of truth. Sordian stores policy,
audit, validation, and recovery state---not a separate permanent source
of truth for repository contents.

**Security boundary:** Only writes that pass through Sordian are
governed by it. Anyone who retains direct write access to GitHub may
bypass gateway checks. Restrict direct write access where possible, and
detect/alert on permission drift. Organization owners and administrators
may retain powers that Sordian cannot absolutely prevent.

## 2. Stack overview

---

Area Choice Purpose

---

Frontend Next.js App Router, Dashboard and
React, TypeScript configuration UI

UI Tailwind CSS, shadcn/ui Styling and reusable
components

Forms React Hook Form + Zod, if Typed form handling and
needed validation

Backend Java 21 LTS + Spring Boot REST API, policy
engine, integration
orchestration

API contract OpenAPI Documented
frontend/backend API

Database PostgreSQL Users, workspaces,
repositories, policies,
audit and recovery
state

Persistence Spring Data Database access
JPA/Hibernate; SQL where  
 appropriate

Migrations Flyway Community Versioned schema
changes

Git transport Git CLI and Receive, inspect,
receive-pack-compatible validate, and forward
handling Git pushes

GitHub GitHub App, REST API, Installation auth,
webhooks metadata,
PR/review/check/merge
workflows

Build Maven Java dependencies and
builds

Backend tests JUnit 5, Mockito, Spring Unit and integration
Boot Test testing

API tests REST Assured HTTP/API behavior

GitHub API simulation WireMock Simulate failures,
timeouts, rate limits
and webhooks

DB integration tests Testcontainers (optional) Real PostgreSQL in
disposable test
environments

Frontend E2E Playwright Critical user journeys

Local infrastructure Docker Engine + Docker Reproducible local
Compose services

CI GitHub Actions Build, lint, test and
package

Observability Structured logs first; Diagnose and monitor
Prometheus/Grafana later service

Load testing JMeter later, if Performance testing
justified

Development OS Pop!\_OS/Linux; Linux for Developer and server
deployment environment

---

Pin supported stable dependency versions when the project is created.
Prefer an LTS Java version and a compatible, supported Spring Boot
release.

## 3. Frontend

### Required technologies

- Next.js App Router
- React and TypeScript
- Tailwind CSS
- shadcn/ui
- Centralized typed API client or generated client from OpenAPI
- React Hook Form + Zod if policy forms become complex

### Main screens

1.  **Overview:** connected installations, repository/policy status,
    recent push attempts, failures needing recovery.
2.  **Repositories:** GitHub canonical URL, Sordian push URL,
    integration status, write-path configuration.
3.  **Policy editor:** protected branch patterns, direct-push
    restrictions, non-fast-forward restrictions, deletion/tag rules,
    approval thresholds and required checks.
4.  **Pull requests:** review/check status, reasons merge is blocked,
    authorized merge action.
5.  **Audit log:** actor, repository, ref, decision, reason, timestamp,
    correlation ID and upstream outcome.
6.  **Integration settings:** App installation, webhook health,
    permissions/configuration drift, retention settings.

### Frontend security rules

- The frontend is not a security boundary; authorize every action in
  the backend.
- Never bundle GitHub App private keys or long-lived secrets into
  browser code.
- Avoid storing sensitive tokens in `localStorage`.
- Distinguish rejected, pending/reconciling and confirmed pushes.
- Do not imply a repository is protected merely because it appears
  connected.

## 4. Backend

### Required

- Java 21 LTS
- Spring Boot: Spring Web, Spring Security, Spring Validation
- Spring Data JPA/Hibernate and PostgreSQL JDBC driver
- Flyway Community
- Maven
- Jackson
- GitHub API client or a carefully tested HTTP integration layer
- Structured logging with correlation IDs

### Responsibilities

- Manage users, workspaces, repositories and policy configuration.
- Authenticate dashboard requests and authorize operations.
- Authenticate to GitHub as an installed GitHub App.
- Verify and process webhooks.
- Evaluate push/ref-update policies.
- Coordinate Git reception, validation, forwarding and cleanup.
- Persist push attempts and upstream outcomes.
- Read PR reviews and check statuses; enforce merge eligibility.
- Record audit events.
- Detect permission drift, webhook failures and stuck operations.
- Expose health/readiness and operational diagnostics.

### Suggested package boundaries

```text
com.Sordian
├── auth
├── organizations
├── repositories
├── policies
├── git
│   ├── transport
│   ├── receive
│   ├── validation
│   ├── forwarding
│   └── cleanup
├── github
│   ├── app
│   ├── webhooks
│   ├── pullrequests
│   └── checks
├── merges
├── audit
├── operations
└── shared
```

Treat these as conceptual boundaries, not a mandate to create every
package immediately. Start with a **modular monolith**. Do not split
into microservices without measured operational or scaling needs.

## 5. Git push gateway --- highest-risk subsystem

The Git transport layer is the most technically sensitive part of
Sordian. Prototype it early, before spending much time on the dashboard.

### Required behavior

- Accept authenticated pushes over explicitly supported Git
  transport(s).
- Receive Git objects into an isolated temporary/quarantine area.
- Inspect proposed ref updates before publishing them.
- Reject direct updates to protected branches when policy requires
  PRs.
- Reject non-fast-forward updates as the practical "no force-push"
  rule.
- Define separate rules for branch deletion and tag updates.
- Forward accepted updates to GitHub.
- Return success only after GitHub confirms the intended update.
- Track timeouts and ambiguous upstream outcomes for reconciliation.
- Clean temporary objects safely.
- Handle retries and concurrent updates without corrupting state.

### Force-push nuance

Git's server-side receive protocol does not reliably report that the
user literally typed `git push --force`. Enforce the update semantics:
reject non-fast-forward ref updates. Define deletion and delete/recreate
behavior separately.

### Quarantine, forwarding and recovery

Git receive-pack/pre-receive primitives help inspect proposed ref
updates and isolate incoming objects, but they do not automatically
provide an atomic end-to-end transaction across Sordian and GitHub.
Handle: - GitHub rejecting an update after gateway validation - Timeouts
where GitHub may already have accepted the update - Duplicate client
retries - Concurrent pushes to the same ref - Gateway restart during
forwarding - Cleanup after rejection - Recovery when upstream outcome is
unknown

Persist a push-attempt state machine in PostgreSQL, for example:

```text
RECEIVED
  -> VALIDATING
  -> REJECTED
  -> FORWARDING
  -> CONFIRMED
  -> FAILED_RETRYABLE / OUTCOME_UNKNOWN
  -> RECONCILING
  -> CONFIRMED or FAILED_FINAL
```

Keep enough metadata to recover after restart, but do not retain
source-code objects indefinitely. Set strict filesystem permissions,
disk quotas, retention rules and cleanup monitoring.

### Implementation rules

- Use established Git tools/protocol behavior; do not implement Git
  wire protocol from scratch.
- Do not assume that simply wrapping `git-receive-pack` solves safe
  forwarding, identity mapping, ref validation, concurrency or
  recovery.
- Invoke Git processes without shell-string interpolation. Constrain
  executable paths, environment, repositories and filesystem
  locations.
- Validate repository identity and every requested ref; never trust a
  client-provided filesystem path.
- Coordinate concurrent updates per repository/ref where needed.
- Test using real temporary Git repositories and bare remotes.
- Never report success based only on local validation.

## 6. GitHub integration

Use a **GitHub App** for repository access, installation-scoped
credentials and webhooks.

Expected uses: - Discover installed organizations and accessible
repositories. - Read repository metadata/default branches. - Receive PR,
review, check-suite/check-run and relevant repository events. - Read
approval and check status. - Create/update check runs if Sordian reports
its own validation. - Coordinate approved PR merges through the API. -
Detect permission/integration drift.

### Webhook requirements

- Verify signatures before processing.
- Make processing idempotent.
- Store delivery IDs and processing outcomes.
- Handle retries, duplicate and out-of-order delivery, and rate
  limits.
- Keep webhook handlers short; persist events before heavier
  processing.
- Provide a way to inspect failed deliveries.

**Critical limitation:** A GitHub App and webhook cannot universally
intercept raw Git pushes. Webhooks arrive after an event and cannot
retroactively reject the push that caused it. Sordian must control the
write path for pre-publication enforcement. Restrict direct GitHub
writes as far as the organization's permissions and plan allow.

GitHub Free capabilities for branch protection/rulesets vary by
visibility and plan. Verify current GitHub plan documentation at
rollout. Do not base Sordian's claims on paid native enforcement being
available, and explain any remaining bypass path to administrators.

## 7. Database and data model

Use PostgreSQL as the only required application database initially.

Potential tables: - `users` - `workspaces` / `organizations` -
`workspace_members` - `github_installations` - `repositories` -
`repository_policies` - `branch_rules` - `push_attempts` -
`push_ref_updates` - `pull_request_snapshots` (only if caching is
useful) - `webhook_deliveries` - `merge_attempts` - `audit_events` -
`integration_health` - `outbox_events` (only if reliable asynchronous
delivery requires it)

Principles: - Use Flyway migrations from the beginning. - Add foreign
keys, unique constraints and query-driven indexes. - Store timestamps in
UTC. - Use stable GitHub IDs, not only mutable repository names. - Keep
secrets out of ordinary tables; use a protected runtime secret store
where possible. - Minimize stored webhook payloads and define
retention. - Use correlation IDs to trace push attempts. - Define
deletion/retention rules for temporary Git data and personal data.

## 8. Identity, authorization and secrets

Keep these identities distinct: 1. Dashboard user. 2. GitHub App
installation identity. 3. Git pusher identity. 4.
Operating-system/service identity.

Initial approach: - GitHub sign-in/OAuth or another supported identity
flow for dashboard users. - Secure, short-lived sessions/cookies. -
GitHub App installation tokens for API actions. - A defined mapping from
authenticated Git pusher to a human/audit actor. - Backend role-based
authorization (e.g. owner, administrator, developer, auditor). -
Least-privilege GitHub App permissions.

Secrets: - Never commit App private keys, OAuth secrets, webhook secrets
or database credentials. - Use ignored local `.env` files or mounted
secret files for development; use an appropriate secret store in hosted
production. - Rotate/revoke credentials and document the process. -
Redact tokens from logs. - Do not claim reliable human attribution if
credentials are shared or not mapped to individuals.

## 9. Testing strategy

### Backend unit tests

Use JUnit 5, Mockito and Spring Boot Test for: - Protected branch
decisions - Non-fast-forward rejection - Deletion/tag policies -
Authorization - Webhook signature verification - Idempotency and retry
rules - Push state transitions - Merge eligibility - GitHub API error
handling

### API tests

Use REST Assured for: - Success/rejection cases - Authentication and
authorization failures - Cross-workspace/repository access - Invalid
policies - Pagination/filtering - Error response contracts - Rate
limiting where implemented

### Git protocol integration tests

Use real temporary Git repos and bare repositories: - Normal
feature-branch push - Protected branch direct-push rejection -
Non-fast-forward rejection - Deletion/tag behavior - Upstream
rejection - Timeout and ambiguous-outcome recovery - Concurrent pushes
to the same ref - Restart during forwarding - Temporary-object cleanup -
Correct Git client messages

Prioritize these before optional services.

### GitHub integration tests

Use WireMock for API errors, rate limits, invalid credentials, timeouts,
duplicate/out-of-order webhooks, PR review/check changes, merge
conflicts and rejected merge attempts. Use a dedicated test GitHub
repository for a small number of end-to-end tests; never run destructive
tests on production repositories.

### Database tests

Use PostgreSQL in development and CI. Testcontainers is optional but
recommended for realistic disposable PostgreSQL integration tests. Avoid
relying exclusively on in-memory database substitutes.

### Frontend tests

Use Playwright for installation/repository selection, policy editing,
rejected-push explanations, audit history, recovery status and
authorization-critical flows.

JUnit reports are enough initially. Allure is optional if richer test
reporting helps the team.

## 10. CI/CD and repository layout

Use GitHub Actions to run on pull requests and relevant pushes: 1.
Formatting/static analysis. 2. Frontend lint and TypeScript checks. 3.
Backend unit tests. 4. Git integration tests. 5. Selected frontend E2E
tests. 6. Build frontend/backend artifacts. 7. Build container images. 8. Dependency/image security checks using free tools where practical.

Pin third-party actions to reviewed versions or immutable SHAs where
appropriate. Do not expose deployment secrets to untrusted PR workflows.

A monorepo is sensible initially:

```text
Sordian/
├── apps/
│   └── web/              # Next.js frontend
├── services/
│   └── api/              # Spring Boot API and policy engine
├── deploy/
│   └── compose.yaml
├── docs/
│   ├── architecture.md
│   ├── security.md
│   ├── git-gateway.md
│   └── operations.md
├── .github/workflows/
├── .env.example
└── README.md
```

The Git transport process may need separate runtime isolation, but can
remain in the same repository. Avoid splitting repos just to mimic
microservices.

## 11. Docker and local development

Use Docker Engine + Docker Compose; Kubernetes is not required
initially.

Potential services: - PostgreSQL - Backend API - Frontend (optional
while running Next.js directly for development) - Git gateway process
once its runtime boundary is defined

Guidelines: - Run frontend/backend directly on Pop!\_OS if that improves
the development loop. - Use Compose for PostgreSQL and services needing
isolation. - Persist database data in a named volume. - Do not expose
PostgreSQL publicly. - Keep secrets in an ignored `.env`. - Set CPU,
memory, disk and Git-object storage limits. - Document reset commands
carefully.

## 12. Observability and operations

Start with: - Structured logs and correlation IDs - Health/readiness
endpoints - Counters for push attempts/outcomes - Validation and
upstream-forwarding latency - Webhook delivery/processing status -
Temporary Git object/disk usage - Detection of stuck push attempts and
unknown outcomes

Add Prometheus/Grafana only when dashboards and alerting are justified.
JMeter is for later load testing. Do not add Redis, Kafka or RabbitMQ
simply because they are familiar tools; PostgreSQL-backed state and a
simple worker/process may be enough initially.

## 13. Use now vs. defer

### Use in the first version

- Java 21 LTS, Spring Boot
- OOP, collections, exceptions and debugging
- PostgreSQL, SQL, Flyway
- Maven
- JUnit 5, Mockito, Spring Boot Test
- REST Assured
- GitHub Actions
- Docker Compose
- Linux/shell
- Git fundamentals and Git protocol investigation
- TypeScript, React, Next.js

### Use selectively

- **WireMock:** GitHub API reliability tests
- **Testcontainers:** realistic PostgreSQL integration tests
- **Playwright:** critical frontend journeys
- **Prometheus/Grafana:** operational dashboards when needed
- **Allure:** optional test reporting
- **JMeter:** later performance tests
- **Redis:** only for a demonstrated caching/coordination need
- **Kafka/RabbitMQ:** only if durable async processing and throughput
  justify the operational burden

### Defer

- Selenium/TestNG if Playwright + JUnit cover the initial strategy
- Redis as a default dependency
- Kafka/RabbitMQ
- Kubernetes
- Microservices split
- Distributed tracing stack
- Analytics warehouse
- Paid monitoring platforms

## 14. Cost model

The following can generally be used without software license fees:
OpenJDK, Spring Boot, Maven, PostgreSQL, Flyway Community, Next.js,
React, TypeScript, Tailwind CSS, shadcn/ui, Git, GitHub App
functionality, Docker Engine/Compose, JUnit, Mockito, REST Assured,
WireMock, Playwright and GitHub Actions within its applicable included
limits.

Verify current licenses and usage terms before commercial distribution,
especially for optional dependencies.

Potential costs remain: - Server/VPS or other reliable hosting -
Domain/DNS - Backups and off-site storage - Bandwidth and persistent
disk - Monitoring/alerting - GitHub plan requirements for specific
native controls - CI minutes/storage above included quotas - Operational
time and incident response

**Free software is not free, reliable production hosting.** Sordian is
in the Git write path, so downtime can prevent developers from pushing.
Local/private hosting may be suitable for a prototype or small internal
trial. Team-wide or customer-facing use needs reliable hosting, backups,
monitoring and documented recovery, which may eventually cost money.

## 15. Security and reliability release requirements

- TLS outside a trusted local environment.
- Authentication/authorization for dashboard and Git operations.
- Least-privilege GitHub App permissions.
- Webhook signature verification and idempotent processing.
- No secrets in source, browser bundles, logs or error responses.
- Safe Git process invocation and filesystem isolation.
- Repository/ref validation and path-traversal defenses.
- Temporary object storage quotas and cleanup.
- Concurrency protection for ref updates.
- Persistent forwarding/recovery state.
- Explicit handling of timeouts and unknown upstream outcomes.
- Database backups and a tested restore procedure.
- Audit history for policy changes and push/merge decisions.
- Permission/configuration drift detection.
- Rate/resource limits on untrusted entry points.
- Dependency updates and security scanning.
- Incident procedure for leaked credentials or inconsistent state.

Do not claim rejected data is instantly and perfectly erased from every
disk/cache layer. Define retention, storage locations and how cleanup is
verified.

## 16. Implementation order

### Phase 1 --- Git proof of concept

- Create local client and bare repositories.
- Receive a push and inspect proposed ref updates.
- Reject protected-branch direct updates.
- Reject non-fast-forward updates.
- Forward an allowed branch to a test GitHub repository.
- Report success only after upstream confirmation.
- Test timeouts and restart recovery.

**Exit criterion:** transport/policy correctness is demonstrated before
dashboard work becomes the focus.

### Phase 2 --- Core backend

- Create Spring Boot project.
- Add PostgreSQL, migrations and policy models.
- Add authentication/authorization and audit events.
- Add durable push-attempt states.
- Add GitHub App installation and webhook verification.
- Add integration tests.

### Phase 3 --- Frontend

- Build Next.js dashboard.
- Add installation/repository selection.
- Add policy editor.
- Add push-attempt and audit-log views.
- Show pending, rejected, confirmed and recovery states clearly.

### Phase 4 --- PR governance

- Read PR reviews and required checks.
- Define approval counts and eligible reviewers.
- Restrict normal merge authority to the intended workflow as far as
  GitHub permissions allow.
- Re-check review/check state immediately before merging.
- Handle stale approvals, changed commits, conflicts and API errors.

### Phase 5 --- Internal rollout and hardening

- Add permission-drift detection.
- Add metrics, alerts, backups and restore tests.
- Test concurrency, large pushes, timeouts and restarts.
- Document direct-push bypass risk and recovery.
- Pilot with a test repository before production repositories.

## 17. Decisions to settle before implementation

1.  Which Git transport protocols are supported first, and how do
    developers authenticate?
2.  How is the Git pusher mapped to a human GitHub identity?
3.  Which branch patterns are protected? Are tags governed? Are
    deletions allowed?
4.  Is every non-fast-forward update rejected, with any explicit
    exceptions?
5.  How many PR approvals are required? Are code-owner/team approvals
    required? Do approvals expire when the commit changes?
6.  Which CI/check statuses are mandatory?
7.  Which roles may merge, and how are direct GitHub merges restricted
    or detected?
8.  What happens when Sordian or GitHub is unavailable? Safe default:
    fail closed for governed writes, with a documented recovery path.
9.  How long are audit records, webhook payloads and temporary Git
    objects retained?
10. Is deployment local/private first or remote/team-wide?
11. How are ambiguous upstream outcomes reconciled without losing or
    duplicating updates?
12. Which repository types and GitHub plans must the first release
    support?

## 18. Final recommendation

Build Sordian with a **Next.js + TypeScript frontend and Java 21 +
Spring Boot modular backend, backed by PostgreSQL**. Treat Git
receive/forward handling as a carefully isolated subsystem, and use a
GitHub App for API integration and PR governance.

Use Docker Compose for local infrastructure, Maven for Java builds,
JUnit/Mockito for backend tests, REST Assured for API tests, WireMock
for GitHub API simulation, Playwright for critical frontend flows, and
GitHub Actions for CI.

Do not introduce Redis, Kafka/RabbitMQ, Kubernetes or microservices in
v1 unless a measured requirement demands them. Prioritize Git transport
correctness, safe upstream forwarding, identity, recovery and the fact
that direct GitHub writes can bypass gateway checks if permissions are
not controlled.

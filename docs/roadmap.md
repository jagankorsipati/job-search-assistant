# Roadmap

Each milestone should be independently reviewable and committable.

## Phase 0 — Foundation (complete)

- Product boundaries, requirements, architecture, security, integrity policy, and decisions

## Phase 1 — Runnable skeleton (complete)

- Phase 1A complete: Java 21 Spring Boot backend, eight closed Spring Modulith modules, health endpoint, Maven wrapper, and automated context/boundary tests
- Phase 1B complete: local PostgreSQL Compose service, persistent volume, Flyway-only schema foundation, and Docker-backed integration testing
- Phase 1C complete: Node.js 24 LTS, Vite React/TypeScript shell, backend health proxy/client, responsive accessible styling, and frontend quality gates
- Phase 1D complete: SHA-pinned GitHub Actions for backend, frontend, and PostgreSQL infrastructure; repository formatting conventions; and a Windows foundation verifier

## Phase 2 — Identity and isolation (complete)

- Phase 2A complete: framework-independent account and invitation lifecycles, constrained identity tables, and documented security boundaries
- Phase 2B complete: transactional first-administrator bootstrap, Argon2id credentials, MEMBER invitations, JDBC persistence, and generic credential verification
- Phase 2C (complete)
  - Phase 2C1 complete: secure HTTP login/logout/current-session endpoints, PostgreSQL-backed sessions, CSRF, rotation, idle and absolute expiry, per-request account validation, bounded rate limiting, and minimal authentication audit events
  - Phase 2C2 complete: invitation HTTP flows, offline compromised-password screening, anonymous-session exhaustion protection, and accessible frontend authentication
- Phase 2D complete: server-derived actor context, narrow `identity::actor` named interface, owner-scoped SQL contract, and reusable PostgreSQL cross-user isolation tests using test-only resources
- Phase 2E (complete)
  - Phase 2E1 complete: MEMBER-only administration, immediate UUID-indexed session revocation, credential-version invalidation, bounded authentication-audit retention, and minimal accessible administrator UI
  - Phase 2E2 complete: final threat model, immutable blocklist provenance, secure-cookie regression coverage, disposable full-stack Playwright identity verification, hosted browser CI, deployment checklist, and release-readiness matrix
- Minimal audit events with bounded retention

## Phase 3 — Candidate profile (complete)

- Phase 3A complete: owner-scoped candidate-profile and structured career-fact domain model, truthful confirmation lifecycle, PostgreSQL schema foundation, and privacy/ownership decisions
- Phase 3B complete: authenticated owner-scoped profile and career-fact APIs, optimistic locking, explicit confirmation attestation, archival lifecycle transitions, and PostgreSQL-backed API isolation tests
- Phase 3C complete: accessible `/profile` frontend workspace for manual candidate-profile editing, career-fact creation and filtering, confirmation attestation, archive/restore, safe API errors, and optimistic-conflict reloads without browser persistence
- Phase 3D complete: real-browser candidate-profile lifecycle, career-fact truthfulness lifecycle, cross-user isolation, optimistic-conflict, CSRF/session, privacy, and sanitized-diagnostic verification
- Phase 3E complete: owner-scoped base résumé upload, metadata inspection, replacement, attachment download, V7 schema, local storage abstraction, validation, privacy, and real-browser verification
- Manual profile management before automated extraction remains the Phase 3 posture

## Phase 4 — Job workspace

- Phase 4A complete: owner-scoped captured-job, immutable job-description snapshot, job-application, and application-status-history domain/schema foundation
- Phase 4B complete: authenticated owner-scoped job capture, metadata update, immutable description-snapshot, and archive/restore APIs
- Phase 4C next: authenticated application status, next-action, and append-only status-history APIs
- Later Phase 4: duplicate warnings, search, and job/application frontend UI

## Phase 5 — Deterministic fit analysis

- Requirement model
- Evidence-linked matches and visible gaps
- Explainable scoring without AI dependency

## Phase 6 — Truthful document tailoring

- Proposal and evidence model
- Before/after review and approval
- DOCX fidelity spike, export, and regression tests

## Phase 7 — Optional AI assistance

- Replaceable provider interface
- Minimum-data requests and prompt-injection defenses
- Grounded drafting plus deterministic integrity validation

## Phase 8 — Household deployment

- ARM64 images, Raspberry Pi resource testing
- Tailscale access, backups, restore drill, retention

## Later

- Permitted ATS adapters, alerts, browser-assisted import, interview preparation, analytics

Automated application submission requires a separate product and risk decision and is not implied by this roadmap.

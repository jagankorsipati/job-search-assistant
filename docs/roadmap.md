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

## Phase 4 — Job workspace (complete)

- Phase 4A complete: owner-scoped captured-job, immutable job-description snapshot, job-application, and application-status-history domain/schema foundation
- Phase 4B complete: authenticated owner-scoped job capture, metadata update, immutable description-snapshot, and archive/restore APIs
- Phase 4C complete: authenticated owner-scoped application tracking, next-action metadata, explicit status transitions, append-only status-history APIs, optimistic locking, and application archive/restore
- Phase 4D complete: authenticated `/jobs` and `/applications` frontend workspaces with active/archived lists, job capture, immutable snapshot display/append, draft application creation, explicit status transitions, status history, notes, next actions, archive/restore, session-expiry handling, and optimistic-conflict recovery without browser persistence
- Phase 4E complete: deterministic local duplicate warnings, job/application search and filter refinement, and real-browser job/application lifecycle, isolation, conflict, CSRF, privacy, and sanitized-diagnostic verification

## Phase 5 — Deterministic fit analysis

- Phase 5A complete: owner-scoped job requirements attached to exact immutable job-description snapshots, explicit requirement category/importance/review status, user-controlled links to existing confirmed candidate evidence, optimistic locking, bounded APIs, and documented truthfulness boundaries
- Phase 5B complete: internal deterministic fit scoring policy over confirmed requirements and explicit evidence links, with support and coverage scores, per-importance breakdowns, structured gaps and contradictions, and no persistence or public analysis route
- Phase 5C complete: authenticated read-only fit-analysis API for one owned immutable job-description snapshot, computed on demand with `DETERMINISTIC_FIT_V1`, no-store responses, structured explanations, bounded-analysis refusal, and no score persistence
- Phase 5D complete: authenticated Jobs-integrated frontend workflow for exact-snapshot requirement review, explicit evidence linking, server-authoritative support and coverage presentation, neutral gaps and contradictions, optimistic-conflict recovery, accessibility coverage, and no browser persistence
- Phase 5E in progress: real-browser fit-analysis workflow, isolation, conflict, CSRF/session, privacy, diagnostics, ordering, and release-readiness verification

## Phase 6 — Truthful document tailoring

- Phase 6A complete: owner-scoped backend draft proposal foundation for manually proposed résumé changes, exact source résumé version/checksum attribution, explicit confirmed career-fact evidence links or missing-evidence state, optimistic locking, bounded reads, and no approval/export/public API/AI/document editing
- Phase 6B complete: authenticated no-store backend APIs for owner-scoped tailoring proposal CRUD, read-only before/after review tokens, explicit owner approval attestation, rejection, append-only decision history, transactional source/evidence revalidation, stale-approval detection, and no export/document editing/AI/frontend
- Phase 6C complete: authenticated Profile-integrated manual tailoring proposal workspace with bounded lists, confirmed-fact selection, source attribution, before/after review, exact-token explicit attestation, approval/rejection, conflict recovery, deletion-history refusal, and in-memory privacy controls. Backend verification, frontend checks, and all four existing browser journeys passed.
- Phase 6D in progress: test-scoped bounded DOCX replacement, synthetic structural/security fixtures, and ADR-018 export/anchoring design. Local rendering is unavailable; layout fidelity verification is incomplete and the spike is not marked complete. See [fidelity matrix](docx-fidelity-matrix.md).
- Remaining Phase 6 work: rendered DOCX fidelity verification, controlled export, and tailoring browser security/release verification. Phase 6 remains open.

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

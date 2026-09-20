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

## Phase 5 — Deterministic fit analysis (complete)

- Phase 5A complete: owner-scoped job requirements attached to exact immutable job-description snapshots, explicit requirement category/importance/review status, user-controlled links to existing confirmed candidate evidence, optimistic locking, bounded APIs, and documented truthfulness boundaries
- Phase 5B complete: internal deterministic fit scoring policy over confirmed requirements and explicit evidence links, with support and coverage scores, per-importance breakdowns, structured gaps and contradictions, and no persistence or public analysis route
- Phase 5C complete: authenticated read-only fit-analysis API for one owned immutable job-description snapshot, computed on demand with `DETERMINISTIC_FIT_V1`, no-store responses, structured explanations, bounded-analysis refusal, and no score persistence
- Phase 5D complete: authenticated Jobs-integrated frontend workflow for exact-snapshot requirement review, explicit evidence linking, server-authoritative support and coverage presentation, neutral gaps and contradictions, optimistic-conflict recovery, accessibility coverage, and no browser persistence
- Phase 5E complete: real-browser fit-analysis workflow, isolation, conflict, CSRF/session, privacy, diagnostics, ordering, and release-readiness verification

## Phase 6 — Truthful document tailoring

- Phase 6A complete: owner-scoped proposal/evidence foundation with exact source version and checksum.
- Phase 6B complete: explicit wording review, attestation, rejection and historical attribution.
- Phase 6C complete: authenticated manual proposal review workspace.
- Phase 6D complete locally: bounded DOCX replacement spike has structural tests plus rendered LibreOffice evidence for all 21 synthetic fixture outputs; see the [fidelity matrix](docx-fidelity-matrix.md).
- Phase 6E complete locally: single-proposal DOCX download with actual source-target review, fresh resolved-target attestation, V13 binding, final transactional revalidation, inherited bounded replacement rules, and rendered HTTP export evidence.
- Phase 6F complete; Phase 6 released as `v0.6.0-truthful-tailoring`. Independent tailoring browser security/download tests, repeated browser stability matrix, full foundation verifier, release documentation reconciliation, and rendered-layout verification cover the supported DOCX boundary.

## Phase 7 — Optional AI assistance

- Phase 7A complete: internal single-paragraph grounded drafting contract, disabled runtime provider, deterministic test-only fake, minimum-data projections, owner-scoped local evidence aliases, exact revision binding and structural response validation. Focused tests, backend verify, full foundation verification, package inspection and disposable-resource cleanup passed; see the [verification record](security/phase-7a-verification.md). AI contracts exist; live AI drafting is not available. See [ADR-020](decisions/ADR-020-optional-grounded-drafting-contracts.md).
- Phase 7B complete: administrator-controlled OpenAI and Anthropic Claude protocols behind the existing interface, extensible shared bounded HTTP transport, disabled default, required external model/credential settings, local-mock tests and no public drafting caller. Focused tests, backend verify, full foundation verification, artifact review and disposable-resource cleanup passed. See [ADR-021](decisions/ADR-021-opt-in-grounded-drafting-adapter.md), [configuration](ai-drafting-configuration.md) and [verification record](security/phase-7b-verification.md), including the initial browser timeout and successful unchanged-code rerun. No SDK, migration or live user-data call is added.
- Phase 7 remains open: deployment model qualification and processing/privacy/cost decisions; exact-data preview/consent and cancellation UI; explicit suggestion import with transactional stale checks; claim-level integrity validation; security, browser and release verification. OpenAI and Claude are supported; no default model or pricing is chosen. Live user drafting is not available.

## Phase 8 — Household deployment

- ARM64 images, Raspberry Pi resource testing
- Tailscale access, backups, restore drill, retention

## Later

- Permitted ATS adapters, alerts, browser-assisted import, interview preparation, analytics

Automated application submission requires a separate product and risk decision and is not implied by this roadmap.

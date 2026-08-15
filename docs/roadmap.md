# Roadmap

Each milestone should be independently reviewable and committable.

## Phase 0 — Foundation (complete)

- Product boundaries, requirements, architecture, security, integrity policy, and decisions

## Phase 1 — Runnable skeleton (complete)

- Phase 1A complete: Java 21 Spring Boot backend, eight closed Spring Modulith modules, health endpoint, Maven wrapper, and automated context/boundary tests
- Phase 1B complete: local PostgreSQL Compose service, persistent volume, Flyway-only schema foundation, and Docker-backed integration testing
- Phase 1C complete: Node.js 24 LTS, Vite React/TypeScript shell, backend health proxy/client, responsive accessible styling, and frontend quality gates
- Phase 1D complete: SHA-pinned GitHub Actions for backend, frontend, and PostgreSQL infrastructure; repository formatting conventions; and a Windows foundation verifier

## Phase 2 — Identity and isolation

- Phase 2A complete: framework-independent account and invitation lifecycles, constrained identity tables, and documented security boundaries
- Phase 2B complete: transactional first-administrator bootstrap, Argon2id credentials, MEMBER invitations, JDBC persistence, and generic credential verification
- Phase 2C (in progress)
  - Phase 2C1 complete: secure HTTP login/logout/current-session endpoints, PostgreSQL-backed sessions, CSRF, rotation, idle and absolute expiry, per-request account validation, bounded rate limiting, and minimal authentication audit events
  - Phase 2C2 next: invitation HTTP flows and frontend authentication screens
- Owner-scoped persistence and authorization integration tests
- Minimal audit events

## Phase 3 — Candidate profile

- Career-fact model, verification lifecycle, provenance
- Base résumé upload with safe storage
- Manual profile management before automated extraction

## Phase 4 — Job workspace

- Pasted-description and URL-reference capture
- Metadata editing, snapshots, duplicates, and search
- Application pipeline and history

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

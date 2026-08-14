# Roadmap

Each milestone should be independently reviewable and committable.

## Phase 0 — Foundation (complete)

- Product boundaries, requirements, architecture, security, integrity policy, and decisions

## Phase 1 — Runnable skeleton (in progress)

- Phase 1A complete: Java 21 Spring Boot backend, eight closed Spring Modulith modules, health endpoint, Maven wrapper, and automated context/boundary tests
- Phase 1B complete: local PostgreSQL Compose service, persistent volume, Flyway-only schema foundation, and Docker-backed integration testing
- React shell (not started)
- Formatting and CI

## Phase 2 — Identity and isolation

- Accounts, password authentication, secure sessions
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

# ADR-001: Use a Modular Monolith

- Status: Accepted
- Date: 2026-08-03

## Context

The application needs clear identity, profile, job, document, and application boundaries but will initially serve a small household on a Raspberry Pi. Distributed services would add deployment and failure complexity without a demonstrated scaling need.

## Decision

Build a Spring Boot modular monolith with explicit module interfaces and module-owned persistence. Use React as the web client and PostgreSQL as the deployed database.

## Consequences

- Development, testing, deployment, backup, and diagnostics remain simple.
- Module boundaries must be tested and reviewed to prevent a tangled monolith.
- Expensive or specialized document work may later move to a worker through a stable interface.
- A service may be extracted only after measured operational or scaling evidence.

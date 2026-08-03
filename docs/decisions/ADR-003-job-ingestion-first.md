# ADR-003: Start With Manual Job Ingestion

- Status: Accepted
- Date: 2026-08-03

## Context

Browser scraping is fragile, source-specific, and subject to access restrictions. The product's primary value—fit analysis, truthful documents, and tracking—does not require automated discovery.

## Decision

V1 accepts pasted job descriptions and manually supplied URLs. The normalized job model is independent of its source. Future permitted sources use adapters. LinkedIn scraping is optional, experimental, and disabled by default.

## Consequences

- V1 can deliver reliable value without depending on a job board.
- Users perform a small manual capture step.
- The model remains ready for Greenhouse, Lever, company sites, approved APIs, or email import.
- Source-specific failures do not block the core workflow.

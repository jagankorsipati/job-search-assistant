# ADR-002: Enforce Evidence-Backed Résumé Content

- Status: Accepted
- Date: 2026-08-03

## Context

Naive tailoring systems copy requirements into résumés or add generic skills. This creates misleading applications and damages trust.

## Decision

Every generated claim must reference verified candidate facts. Job requirements and AI output cannot become candidate facts automatically. Missing evidence is shown as a gap. Export requires deterministic integrity validation followed by user review and approval.

## Consequences

- Users must establish and maintain a verified profile.
- Some desirable wording will be blocked until evidence is verified.
- Provenance and validation become core domain concepts, not optional UI features.
- AI improves phrasing but never acts as the authority for truth.

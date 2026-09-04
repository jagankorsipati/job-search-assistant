# ADR-014: Establish job requirement and candidate evidence foundations

## Status

Accepted

## Context

Fit analysis must compare a user-reviewed interpretation of one immutable job-description snapshot with existing owner-confirmed candidate evidence. Phase 5A must not score fit, infer satisfaction, extract requirements automatically, parse resumes, create candidate facts, or call AI.

## Decision

Add two owner-scoped Fit module records:

- `job_requirement` stores one editable user-reviewed requirement for exactly one `captured_job` and one immutable `job_description_snapshot`.
- `job_requirement_evidence_link` stores one user-controlled relationship between a requirement and an existing evidence reference.

Requirements use constrained categories (`SKILL`, `EXPERIENCE`, `EDUCATION`, `CERTIFICATION`, `DOMAIN_KNOWLEDGE`, `RESPONSIBILITY`, `LOCATION`, `WORK_AUTHORIZATION`, `OTHER`), importance (`REQUIRED`, `PREFERRED`, `UNSPECIFIED`), and review status (`DRAFT`, `CONFIRMED`, `REJECTED`). Requirements are editable with `expectedVersion`; updates never reassign the owning job or snapshot.

Evidence links use constrained evidence types (`CAREER_FACT`, `PROFILE_FIELD`, `RESUME_VERSION`) and relationships (`SUPPORTS`, `PARTIALLY_SUPPORTS`, `CONTRADICTS`, `NOT_DEMONSTRATED`). Link targets are immutable after creation; users may update only the relationship and note with optimistic locking.

`CAREER_FACT` links require an existing owner-scoped `CONFIRMED` career fact. `PROFILE_FIELD` links use stable field identifiers for the owner profile singleton. `RESUME_VERSION` links reference the current owner-scoped base resume document metadata row; Phase 5A treats the resume as an owner-controlled source document, not independently verified facts.

## Integrity tradeoff

A single polymorphic evidence-link table keeps the Phase 5A model small and avoids a premature generic entity framework. PostgreSQL constrains the evidence type and duplicate equivalent links, but cannot enforce a normal foreign key across multiple evidence tables. The Fit service therefore validates evidence existence, ownership, and eligibility transactionally before insert or update. Cross-owner, unsupported, draft, and missing evidence references return safe not-found or validation responses.

## Security and truthfulness

Ownership is always derived from the authenticated server-side actor. Repository reads, lists, updates, and deletes include owner predicates; administrators have no private-resource bypass. Browser-supplied owner fields are ignored by request contracts and are not returned in responses.

Requirements are interpretations of job text, not candidate facts. Evidence links are user assertions about how existing evidence relates to a requirement. Phase 5A does not produce fit scores, match percentages, rankings, recommendations, inferred satisfaction states, automatic links, resume bullets, or tailored documents.

Audit logging for Phase 5A remains content-free. The existing authentication audit table is intentionally constrained to identity events, so Phase 5A does not widen it to store domain events. A future dedicated operations audit stream can record minimal Fit mutation metadata without requirement text, excerpts, notes, resume content, career facts, profile fields, request bodies, or UUID collections.

## Consequences

Phase 5B can compute deterministic explanations over confirmed requirements and explicit evidence links, while excluding rejected requirements and preserving snapshot attribution. Future scoring must remain derived and reproducible from these records rather than mutating source requirements or candidate evidence.

# ADR-013: Owner-scoped job and application foundation

## Status

Accepted

## Context

Phase 4 introduces the job workspace. The system needs to preserve the difference between a captured opportunity, the job description text available at a point in time, and the owner's declared application progress. These records are private career data and must follow the owner-scoped authorization contract established in ADR-009.

## Decision

Captured jobs belong to the `jobs` module. Applications and status history belong to the `applications` module. Phase 4A adds framework-independent domain types and PostgreSQL schema only; no HTTP APIs, scraping, URL fetching, AI analysis, reminders, submission automation, production repositories, or frontend flows are introduced.

A captured job is an owner-scoped opportunity record with immutable ownership, company, title, optional location, optional HTTP/HTTPS posting URL, source type, optional employment metadata, timestamps, optimistic version, and archival timestamp. Posting URLs are references only. Fragments are removed by domain normalization, credentials are rejected, and the server does not retrieve URL content.

Job descriptions are append-only snapshots. Editing or replacing a description creates a new row with owner, parent job, sequence, source type, canonical LF-normalized text, SHA-256 digest of the stored canonical content, and captured timestamp. The description limit is 100,000 characters, which is large enough for typical pasted job descriptions while bounding storage and validation cost. Remote HTML, scripts, cookies, credentials, and browser session data are not stored.

An application is the owner's progress for one captured job. The schema enforces one application per owner/job. Status is separate from archival so final outcomes remain visible after records are hidden from active lists. The allowed statuses are `DRAFT`, `READY_TO_APPLY`, `APPLIED`, `INTERVIEWING`, `OFFER`, `ACCEPTED`, `REJECTED`, and `WITHDRAWN`; `ACCEPTED`, `REJECTED`, and `WITHDRAWN` are terminal.

Allowed transitions:

| From | To |
| ---- | -- |
| DRAFT | READY_TO_APPLY, WITHDRAWN |
| READY_TO_APPLY | DRAFT, APPLIED, WITHDRAWN |
| APPLIED | INTERVIEWING, OFFER, REJECTED, WITHDRAWN |
| INTERVIEWING | INTERVIEWING, OFFER, REJECTED, WITHDRAWN |
| OFFER | ACCEPTED, REJECTED, WITHDRAWN |
| ACCEPTED | none |
| REJECTED | none |
| WITHDRAWN | none |

`INTERVIEWING -> INTERVIEWING` represents another meaningful interview stage and requires event context in the domain model to avoid accidental duplicate history. `APPLIED` means the owner explicitly recorded submission; it is never inferred from job capture, resume generation, download, or AI output. `OFFER` and `ACCEPTED` must never be inferred by AI. Future automation may propose but must not execute application-status transitions without owner approval.

Status history is append-only domain history containing only event id, owner, application id, previous status, new status, effective timestamp, optional bounded note, and recorded timestamp. It is not a security audit log and does not store actor IDs, sessions, IP addresses, user agents, or credentials.

Database ownership integrity uses immutable `owner_account_id`, owner FKs to `user_account ON DELETE RESTRICT`, owner-aware parent/child foreign keys, owner-first indexes, and owner-change triggers. Future repositories must derive ownership from `CurrentActorProvider`, predicate individual operations by resource id plus owner id, filter collections by owner, and treat non-owned and nonexistent rows identically. Administrators have no private job/application bypass.

## Consequences

Phase 4B can add job-capture and snapshot APIs without changing the schema foundation. Applications may later reference jobs through a deliberately exposed jobs application interface if runtime coordination becomes necessary, but Phase 4A avoids speculative cross-module Java dependencies.

Duplicate detection, resume-version linkage, reminders, search, job-board integrations, URL content retrieval, and application submission remain deferred. Account deletion remains restricted while owner-scoped job/application rows exist.

## Phase 4B API Decision

The jobs module owns the production job and snapshot API layer under `/api/jobs`. Controllers do not accept trusted owner fields. `JobService` derives the owner from `CurrentActorProvider.currentActor()` and every repository method requires the owner UUID. The module still depends only on `identity::actor`.

Job lists are owner-filtered, bounded to 100, active by default, and ordered by `metadata_updated_at DESC, id DESC`, matching the Phase 4A owner-first active index shape. Snapshot lists are owner/job-filtered, bounded to 50, and returned oldest-first by `snapshot_sequence ASC, id ASC`.

`POST /api/jobs` can atomically create a captured job and initial snapshot. `PASTED_DESCRIPTION` requires nonblank `descriptionText`; `URL_REFERENCE` requires a valid normalized posting URL; `MANUAL` may omit both. URL references are never fetched.

Snapshot append locks the owner-scoped parent job row with `FOR UPDATE`, rejects archived jobs, canonicalizes text, checks only the latest owner/job snapshot digest for duplicate canonical content, allocates the next sequence, and inserts the immutable row in one transaction. Duplicate latest canonical content returns `409 duplicate_snapshot`.

Metadata updates, archive, and restore require `expectedVersion`, update only owner-scoped active/archived rows as appropriate, and return safe `409` conflicts for stale versions or invalid lifecycle state. Nonexistent and non-owned resources return the same safe `404`.

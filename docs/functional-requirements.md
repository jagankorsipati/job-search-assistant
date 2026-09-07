# Functional Requirements

## Identity and isolation

- **FR-001:** Users can create and authenticate to local accounts.
- **FR-002:** Passwords are stored only as modern salted hashes.
- **FR-003:** Every user-owned record and file is authorized by owner identity server-side.
- **FR-004:** Authentication, logout, expiry, and failed-login events are auditable without logging secrets.
- **FR-005:** New accounts require a single-use household invitation created by an administrator.
- **FR-006:** Administrators can invite, disable, or recover accounts but cannot read user-owned career content by default.
- **FR-007:** Sessions identify exactly one user; switching users requires logout and reauthentication.
- **FR-008:** Non-owned and nonexistent private resources return the same not-found response; private collections include only the authenticated owner's rows.
- **FR-009:** Administrative roles authorize only explicit administrative operations and never bypass private-resource ownership.

## Candidate profile

- **FR-010:** Users can manage employment, education, projects, skills, certifications, and accomplishments.
- **FR-011:** Facts have draft, confirmed, or archived status. Confirmed means account-owner attested, not independently verified.
- **FR-012:** Users can upload, inspect metadata for, replace, and download one current PDF or DOCX base resume per account without parsing or extracting facts.
- **FR-013:** AI or imported output cannot mark a fact confirmed automatically.
- **FR-014:** Profile and career-fact APIs are owner-scoped, optimistic-lock protected, and require explicit confirmation attestation before a draft fact becomes confirmed.
- **FR-015:** Archived career facts are retained history and are excluded from new generated content; physical deletion is deferred.
- **FR-016:** The authenticated frontend profile workspace allows owners, including administrators acting only for themselves, to create and edit their profile, manage draft/confirmed/archived career facts, and recover from optimistic conflicts without autosave or client-side persistence of profile data.
- **FR-017:** Confirming a career fact in the UI requires an explicit, non-persisted accuracy attestation. Editing a confirmed fact returns it to draft before later generated content can use it again.
- **FR-018:** Real-browser verification proves candidate-profile ownership, truthfulness lifecycle, optimistic conflict handling, CSRF/session behavior, browser privacy, and safe diagnostics against a disposable PostgreSQL-backed full-stack environment.
- **FR-019:** Real-browser verification proves base resume upload, replacement, download headers and bytes, cross-user isolation, stale replacement conflict handling, browser privacy, and safe diagnostics against disposable PostgreSQL and filesystem storage.

## Jobs and analysis

- **FR-020:** Users can save pasted job text and manually supplied URLs.
- **FR-021:** Users can edit job title, company, location, source, and description.
- **FR-022:** The frontend warns about likely duplicates within the currently loaded owner-visible job collection using deterministic normalized URL, external posting ID, and company/title comparisons. Warnings are non-blocking and do not perform cross-owner lookup.
- **FR-023:** Fit analysis stores user-reviewed job requirements for exact immutable job-description snapshots with explicit category, importance, and review status.
- **FR-024:** Fit analysis links requirements only to explicit owner-selected candidate evidence relationships; unknown or missing evidence remains visible without being treated as proof that the candidate lacks a skill.
- **FR-025:** Captured jobs are owner-scoped opportunities and do not imply that the owner applied.
- **FR-026:** Job descriptions are retained as immutable append-only snapshots so later edits or external posting changes do not rewrite history.
- **FR-027:** Stored posting URLs are references only; the server does not fetch URL content during Phase 4A.
- **FR-028:** Authenticated job APIs derive ownership server-side, never accept trusted owner identifiers, and keep non-owned and nonexistent jobs indistinguishable.
- **FR-029:** Description snapshot appends are immutable, owner-scoped, sequence-ordered, bounded, and reject the latest canonical duplicate content for the same owner/job.
- **FR-030:** Authenticated application APIs create at most one owner-scoped application per owner/job, reject archived or non-owned jobs safely, and never accept trusted owner, initial status, applied timestamp, or history fields from the browser.
- **FR-031:** Application reads, lists, notes, next actions, transitions, history, archive, and restore are owner-scoped, no-store, optimistic-lock protected where mutated, and provide no administrator cross-user bypass.
- **FR-032:** Application status transitions follow the documented matrix, append exactly one immutable history event atomically with each accepted status change, and never infer application progress from job capture, resume/document actions, AI, or downloads.
- **FR-033:** `READY_TO_APPLY -> APPLIED` establishes a truthful `appliedAt`; later status changes preserve it. `WITHDRAWN` can occur before or after submission without fabricating or erasing `appliedAt`.
- **FR-034:** Terminal application states clear next actions and reject new next actions while preserving private notes, final status, applied timestamp when present, and status history.
- **FR-035:** The authenticated frontend job workspace lets owners capture jobs, edit metadata, view active/archived jobs, archive/restore jobs, view oldest-first immutable description snapshots, append new snapshots, filter/search the bounded loaded collection, and review non-blocking duplicate warnings without sending owner identifiers or fetching posting URLs.
- **FR-036:** The authenticated frontend application workspace lets owners create DRAFT applications for active captured jobs, filter active/archived applications by exact status plus local text/due-state filters, edit notes and next actions, record only allowed explicit status transitions, view oldest-first status history, and archive/restore applications without sending owner identifiers or inferring status.
- **FR-037:** Real-browser verification proves job/application ownership, lifecycle behavior, duplicate-warning behavior, optimistic conflict handling, CSRF/session behavior, browser privacy, and safe diagnostics against a disposable PostgreSQL-backed full-stack environment.
- **FR-048:** Phase 5A requirement and evidence APIs are owner-scoped, no-store, optimistic-lock protected where mutated, bounded, and provide no administrator cross-user bypass.
- **FR-049:** Phase 5A does not create scores, inferred satisfaction states, automatic requirement extraction, automatic evidence links, resume tailoring, generated bullets, URL fetching, scraping, AI calls, or application submission.
- **FR-054:** Phase 5B computes internal fit analysis on demand from confirmed requirements and explicit evidence links only, excluding draft and rejected requirements from denominators while reporting their counts.
- **FR-055:** Phase 5B reports separate evidence-support and evidence-coverage scores using fixed requirement weights, fixed assessment credits, exact decimal arithmetic, and half-up whole-number rounding.
- **FR-056:** Phase 5B reports structured per-requirement assessments, per-importance breakdowns, gaps, partial gaps, contradictions, and policy version without copying private requirement text, source excerpts, resume content, career facts, profile values, or evidence notes into scoring output.
- **FR-057:** Phase 5B adds no public analysis route, frontend screen, persisted score/result row, AI call, automatic extraction, automatic evidence-link creation, candidate ranking, recommendation, scraping, or resume tailoring.
- **FR-058:** Phase 5C exposes a read-only authenticated fit-analysis endpoint for exactly one owned job-description snapshot. It derives ownership from the session, returns safe not-found for foreign or mismatched job/snapshot identifiers, and provides no administrator bypass.
- **FR-059:** Phase 5C responses include policy version, non-scorable status, distinct evidence-support and evidence-coverage scores, calculation inputs, per-importance breakdowns, per-confirmed-requirement explanations, minimal evidence-link summaries, neutral gap findings, contradiction findings, and no owner identifiers.
- **FR-060:** Phase 5C analysis is computed fresh, not cached or persisted, uses no AI/inference/matching/scraping, uses no-store HTTP responses, and refuses oversized input sets rather than scoring truncated requirements or evidence links.
- **FR-061:** Phase 5D exposes a Jobs-integrated frontend route for one exact immutable snapshot where owners manually create, edit, confirm, reject, return to draft, and delete requirements using explicit constrained controls and optimistic versions.
- **FR-062:** Phase 5D lets owners link only eligible existing evidence through existing owner-scoped APIs, requires exactly one explicitly selected relationship, preserves duplicate/conflict form state, and never infers or preselects support.
- **FR-063:** Phase 5D displays the server's evidence-support and review-coverage results separately, handles non-scorable snapshots without percentages, explains each confirmed requirement once, separates gaps, partial evidence, contradictions, and conflicts, and avoids hiring-probability or qualification claims.
- **FR-064:** Phase 5D stores no fit workspace data, filters, URLs, requirements, evidence, or analysis results in browser storage, cookies, IndexedDB, query parameters, or fragments, and adds no Phase 5 browser E2E until Phase 5E.
- **FR-065:** Phase 5E real-browser verification proves the fit-review lifecycle, requirement and evidence-link lifecycle, deterministic scoring examples, truthfulness copy, cross-user and ADMIN isolation, optimistic conflicts, CSRF/session behavior, browser privacy, network boundaries, accessibility, responsive behavior, ordering independence, safe diagnostics, and cleanup against the disposable PostgreSQL-backed full stack.
- **FR-066:** Phase 5E release readiness does not add product features, persisted fit scores, new migrations, AI, automatic extraction, automatic evidence matching, rankings, recommendations, hiring predictions, resume tailoring, generated content, URL fetching, scraping, application submission, notifications, telemetry, test-only production endpoints, permissive CORS, or weakened authentication controls.

## Documents

- **FR-038:** Tailoring uses only verified facts.
- **FR-039:** Users see original and proposed content before approval.
- **FR-040:** Exports record their source resume, job, approved changes, and creation time.
- **FR-041:** Users can generate and edit grounded cover-letter drafts.
- **FR-042:** The system preserves the original resume.

## Applications

- **FR-043:** Users can manage application stage, notes, dates, contacts, and follow-ups.
- **FR-044:** Stage changes form an immutable history.
- **FR-045:** The system does not submit an application in V1.
- **FR-046:** Application status is user-declared operational state; `APPLIED`, `INTERVIEWING`, `OFFER`, and `ACCEPTED` require explicit owner-recorded transitions and are never inferred from AI, job capture, resume generation, or downloads.
- **FR-047:** Application archival is separate from status so final outcomes remain visible in history.

## Data control and operations

- **FR-050:** Users can export their structured data and documents.
- **FR-051:** Users can delete their account data after explicit confirmation.
- **FR-052:** Health endpoints reveal no personal information.
- **FR-053:** Backups and restores preserve user isolation.

## Quality attributes

- All authorization rules require integration tests.
- Core profile, fit, and integrity rules work without AI.
- AI failures degrade to deterministic/manual workflows.
- V1 supports current Chromium-based desktop browsers.

# Data Ownership

## Sources of truth

| Data                         | Source of truth                      | Owner                 |
| ---------------------------- | ------------------------------------ | --------------------- |
| Account and session          | Identity module                      | Individual user       |
| Career fact and verification | Profile module                       | Individual user       |
| Base résumé, tailoring proposals, and exports | Documents module                     | Individual user       |
| Captured job snapshot        | Jobs module                          | Individual user       |
| Job requirement and evidence link | Fit module, owner-reviewed interpretation and relationship | Individual user       |
| Fit result                   | Fit module, derived and reproducible | Individual user       |
| Application state            | Applications module                  | Individual user       |
| External job posting         | External source                      | External publisher    |
| AI response                  | No authority; proposal only          | Not a source of truth |

## Rules

- Every user-owned row carries an immutable owner identifier.
- Repositories require owner scope; controller-supplied owner IDs are not trusted.
- Stored files use generated identifiers, not user-provided paths.
- Derived analysis records the inputs and policy/model version used only when a future persistence decision explicitly requires it; Phase 5B/5C fit scoring is computed on demand and not persisted.
- Job snapshots are preserved so later source changes do not rewrite application history.
- Deletion removes active records and files, then expires them from backups according to documented retention.
- Household membership does not imply access to another member's records.
- Administrator authority permits identity administration only; it does not imply access to user-owned career content.
- Authentication does not authorize a resource by itself. Ownership is derived from trusted server-side identity, never a browser-supplied owner ID.
- Candidate profiles and career facts are owned by exactly one account. Profile ownership is immutable, one profile is allowed per owner, and career facts must be queried through owner-scoped predicates.
- Confirmed career facts are account-owner attestations only. Confirmation does not mean third-party or application verification, and imported or AI-generated text remains draft until the owner confirms it.
- Base resume metadata and stored files are owned by exactly one account. Upload derives ownership from `CurrentActorProvider`, uses a server-generated opaque storage key, and never trusts browser-supplied owner IDs, filenames as paths, storage keys, or filesystem paths.
- Captured jobs, job-description snapshots, job applications, and application status history are owned by exactly one account. Child rows use owner-aware parent references so snapshots, applications, and history cannot be attached across owners. Capturing a job, storing a URL reference, or downloading a resume never proves or changes application status.
- Job requirements and requirement-evidence links are owned by exactly one account. Requirements use owner-aware job and snapshot references so a reviewed interpretation remains attached to the exact immutable snapshot it came from. Evidence links are explicit user assertions and can reference only owner-visible supported evidence after transactional validation.
- Résumé tailoring proposals are owned by exactly one account. Drafts pin the exact current base résumé document ID, version, and checksum at creation and do not follow later replacement. Proposal evidence links can reference only current owner-confirmed career facts after transactional validation; absent evidence is stored as an explicit missing-evidence state.

## Owner-scoped persistence contract

- `owner_account_id` is an immutable UUID assigned from `CurrentActorProvider` during creation.
- Browser-supplied owner fields are rejected or ignored; they never affect ownership.
- Individual reads use `WHERE id = ? AND owner_account_id = ?`.
- Updates and deletes use the same two predicates and treat zero affected rows as not found.
- Collections and bulk operations always filter `owner_account_id`.
- User-local unique constraints include `owner_account_id` where appropriate.
- Owner-scoped indexes generally begin with `owner_account_id`.
- Background work carries an explicit owner or separately reviewed system authority.
- Profile API requests never include trusted owner fields. Creation, update, collection, and lifecycle-transition services derive ownership from `CurrentActorProvider.currentActor()` and pass the owner UUID explicitly into repository methods.
- Candidate-profile and career-fact responses do not return `owner_account_id`; ownership is enforced beneath the response boundary.
- Base resume responses do not return `owner_account_id`, checksums, storage keys, or filesystem paths. Download is owner-scoped and attachment-only.
- Job and application APIs derive `owner_account_id` from `CurrentActorProvider`, never accept trusted browser owner IDs, and use `WHERE id = ? AND owner_account_id = ?` for individual reads and mutations. Collections filter by owner. Cross-user and nonexistent job/application resources remain indistinguishable.
- Fit APIs derive `owner_account_id` from `CurrentActorProvider`, never accept trusted browser owner IDs, and use owner predicates for requirements, evidence links, snapshots, and candidate evidence validation. Cross-user and nonexistent requirements, links, snapshots, and evidence targets remain indistinguishable.
- Documents tailoring services derive `owner_account_id` from `CurrentActorProvider`, never accept trusted browser owner IDs, and use owner predicates for proposals, source base resumes, and candidate evidence validation. Cross-user and nonexistent proposals, resumes, and evidence targets remain indistinguishable.
- The frontend profile workspace displays only the authenticated user's profile and career facts. It never accepts or submits owner identifiers, and administrator accounts use the same owner-scoped profile route for their own data only.
- Profile and career-fact data is held in React memory for the current page lifetime only. It is not written to browser storage, URL query parameters, URL fragments, IndexedDB, or client-readable cookies.

Non-owned and nonexistent individual resources both return `404`; no preliminary unscoped lookup reveals ownership. Owner-filtered collections return an empty result when there are no visible rows. `ADMIN` has no private-resource bypass. Explicit administrative operations use separate role-protected APIs. PostgreSQL row-level security remains deferred pending a demonstrated operational need and a reviewed connection-pooling design.

Every future owned-resource module must prove these rules with PostgreSQL repository and cross-user HTTP integration tests before its endpoints are accepted. ADR-009 defines the reusable fixture and acceptance contract.

Phase 3D adds real-browser evidence that the profile workspace preserves these ownership rules across independent administrator and member browser contexts. The browser suite verifies own-profile rendering, owner-filtered fact collections, direct cross-user fact access returning the same safe not-found shape as a nonexistent UUID, rejected cross-user mutations, and unchanged owner data after attempted cross-user operations.

Phase 3E adds real-browser evidence that base resume metadata and downloads preserve these ownership rules. The browser suite verifies synthetic PDF upload, metadata reload, exact-byte download, administrator/member isolation, replacement, stale replacement conflict, and no browser-storage or URL persistence of resume data.

Household account administration changes only identity access state. Disabling or reactivating a member neither transfers, deletes, reads, nor exposes that member's private rows or files. Administrator account-management authority remains separate from owner-scoped career-data authorization.

Archived career facts remain owned history and are excluded from new generated content. They require an explicit restoration transition before modification or reconfirmation.

Frontend archive and restore flows wait for server confirmation before changing the saved representation. Restore returns a fact to draft and does not recreate the owner's prior attestation.

Phase 4A adds database-level ownership integrity for `captured_job`, `job_description_snapshot`, `job_application`, and `application_status_history`. Account deletion remains restricted while these rows exist. Archiving a job or application hides it from active collections without deleting snapshots, applications, or status history.

Phase 4B adds production job and snapshot APIs. `JobService` derives the owner from `CurrentActorProvider`, repository methods require owner UUIDs, and individual job and snapshot operations predicate by owner plus resource identifiers. Snapshot append locks the owner-scoped parent job row before sequence allocation. Latest duplicate canonical content for the same owner/job returns `409 duplicate_snapshot`; no cross-owner digest lookup is performed.

Phase 4C adds production application and status-history APIs. `ApplicationService` derives the owner from `CurrentActorProvider`, validates referenced jobs only through the narrow `jobs::application` interface, and repository methods require owner UUIDs for every application and history operation. The database keeps one application per owner/job and owner-aware foreign keys prevent cross-owner history. Status transitions lock the owner-scoped application, update the row, and insert one history event in a single transaction. History is append-only in production code; archive/restore hides or restores application rows without changing status or erasing history. `ADMIN` users use the same owner-scoped application APIs for their own data only.

Phase 4D adds frontend job and application workspaces that consume owner-scoped APIs without accepting, storing, or submitting owner/account identifiers. Job, snapshot, application, and status-history records remain in memory only while the authenticated session is active. Application creation and status changes are owner declarations made through explicit UI actions; the frontend does not infer application state from job capture, URL references, resume/document actions, AI, or downloads.

Phase 4E adds local bounded search/filtering and duplicate warnings over owner-visible job/application collections. Duplicate checks do not create a shared index, do not perform cross-owner lookup, do not fetch posting URLs, and do not block intentional capture.

Phase 5A adds owner-scoped `job_requirement` and `job_requirement_evidence_link` records. A requirement is a user-reviewed interpretation of one immutable job snapshot, not a candidate fact. Requirement updates use optimistic locking and cannot reassign the job or snapshot. Evidence links use one polymorphic table with constrained evidence types; because PostgreSQL cannot foreign-key one column to multiple target tables, `FitService` validates owner-scoped existence and eligibility transactionally before insert or update. Confirmed career facts, supported profile fields, and the current base resume metadata row are the only supported evidence sources. Rejected requirements are distinguishable so future analysis can exclude them.

Phase 5B adds no owned scoring table. `FitService` derives the owner from `CurrentActorProvider`, verifies the owner-scoped job snapshot, loads owner-scoped requirements and evidence links, and computes the deterministic result in memory. The result contains structured scoring metadata and resource identifiers only; display text remains in the source owner-scoped records.

Phase 5C adds a read-only owner-scoped analysis API over the same derived result. The endpoint analyzes exactly the requested owned job snapshot, refuses oversized requirement or evidence-link sets before scoring, and returns no owner/account identifiers. Requirement text, source excerpts, and user notes appear only as owner-visible explanation fields in the response and are not copied into a score table, cache, audit event, or background job.

Phase 5D adds a frontend fit-review workspace under the Jobs route. It consumes only the existing owner-scoped job, snapshot, profile, confirmed career-fact, base-resume metadata, requirement, evidence-link, and fit-analysis APIs. The browser sends no owner/account fields and stores no job, requirement, evidence, analysis, or filter data in localStorage, sessionStorage, IndexedDB, cookies, query parameters, or fragments. The route carries only job and snapshot identifiers, while mutable review state remains in React memory until the server accepts or rejects a deliberate user action. The browser never recalculates support or coverage locally.

Phase 5E adds real-browser and direct authenticated API verification for those ownership rules. The new fit-analysis browser spec creates independent runtime owners, proves owner collections and analysis remain isolated, proves ADMIN cannot read or mutate another owner's fit records, rejects browser-supplied owner/account transfer attempts, and verifies foreign evidence IDs cannot be linked to the current owner's requirements. It adds no new ownership mechanism or persisted derived score.

Phase 6A adds internal owner-scoped draft résumé tailoring proposals. Proposal drafts record user-authored original/proposed text, a bounded target reference, exact source résumé metadata, explicit missing evidence or linked career facts, timestamps, and an optimistic version. Future approval eligibility validates current source résumé and evidence state; it is invalidated by source replacement and by deleted, archived, draft, or cross-owner evidence. No Phase 6A public controller, approval, export readiness, document mutation, or fit-link reuse exists.

Phase 6B adds authenticated owner-scoped proposal APIs and append-only decision history. Proposal review, approval, rejection, lifecycle updates, source resume checks, and evidence checks all use the current actor and owner predicates; ADMIN users have no private-resource bypass. Approval locks and revalidates the proposal, pinned source resume, evidence links, and current confirmed career facts in one transaction. Foreign and nonexistent proposal, source, and evidence references keep equivalent safe failures, and later proposal/source/evidence changes make historical approvals stale without transferring or erasing ownership.

# Data Ownership

## Sources of truth

| Data                         | Source of truth                      | Owner                 |
| ---------------------------- | ------------------------------------ | --------------------- |
| Account and session          | Identity module                      | Individual user       |
| Career fact and verification | Profile module                       | Individual user       |
| Base résumé and exports      | Documents module                     | Individual user       |
| Captured job snapshot        | Jobs module                          | Individual user       |
| Fit result                   | Fit module, derived and reproducible | Individual user       |
| Application state            | Applications module                  | Individual user       |
| External job posting         | External source                      | External publisher    |
| AI response                  | No authority; proposal only          | Not a source of truth |

## Rules

- Every user-owned row carries an immutable owner identifier.
- Repositories require owner scope; controller-supplied owner IDs are not trusted.
- Stored files use generated identifiers, not user-provided paths.
- Derived analysis records the inputs and policy/model version used.
- Job snapshots are preserved so later source changes do not rewrite application history.
- Deletion removes active records and files, then expires them from backups according to documented retention.
- Household membership does not imply access to another member's records.
- Administrator authority permits identity administration only; it does not imply access to user-owned career content.
- Authentication does not authorize a resource by itself. Ownership is derived from trusted server-side identity, never a browser-supplied owner ID.
- Candidate profiles and career facts are owned by exactly one account. Profile ownership is immutable, one profile is allowed per owner, and career facts must be queried through owner-scoped predicates.
- Confirmed career facts are account-owner attestations only. Confirmation does not mean third-party or application verification, and imported or AI-generated text remains draft until the owner confirms it.
- Base resume metadata and stored files are owned by exactly one account. Upload derives ownership from `CurrentActorProvider`, uses a server-generated opaque storage key, and never trusts browser-supplied owner IDs, filenames as paths, storage keys, or filesystem paths.
- Captured jobs, job-description snapshots, job applications, and application status history are owned by exactly one account. Child rows use owner-aware parent references so snapshots, applications, and history cannot be attached across owners. Capturing a job, storing a URL reference, or downloading a resume never proves or changes application status.

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
- Future job and application APIs must derive `owner_account_id` from `CurrentActorProvider`, never accept trusted browser owner IDs, and use `WHERE id = ? AND owner_account_id = ?` for individual reads and mutations. Collections must filter by owner. Cross-user and nonexistent job/application resources must remain indistinguishable.
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

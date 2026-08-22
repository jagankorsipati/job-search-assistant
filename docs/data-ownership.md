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

## Owner-scoped persistence contract

- `owner_account_id` is an immutable UUID assigned from `CurrentActorProvider` during creation.
- Browser-supplied owner fields are rejected or ignored; they never affect ownership.
- Individual reads use `WHERE id = ? AND owner_account_id = ?`.
- Updates and deletes use the same two predicates and treat zero affected rows as not found.
- Collections and bulk operations always filter `owner_account_id`.
- User-local unique constraints include `owner_account_id` where appropriate.
- Owner-scoped indexes generally begin with `owner_account_id`.
- Background work carries an explicit owner or separately reviewed system authority.

Non-owned and nonexistent individual resources both return `404`; no preliminary unscoped lookup reveals ownership. Owner-filtered collections return an empty result when there are no visible rows. `ADMIN` has no private-resource bypass. Explicit administrative operations use separate role-protected APIs. PostgreSQL row-level security remains deferred pending a demonstrated operational need and a reviewed connection-pooling design.

Every future owned-resource module must prove these rules with PostgreSQL repository and cross-user HTTP integration tests before its endpoints are accepted. ADR-009 defines the reusable fixture and acceptance contract.

Household account administration changes only identity access state. Disabling or reactivating a member neither transfers, deletes, reads, nor exposes that member's private rows or files. Administrator account-management authority remains separate from owner-scoped career-data authorization.

Archived career facts remain owned history and are excluded from new generated content. They require an explicit restoration transition before modification or reconfirmation.

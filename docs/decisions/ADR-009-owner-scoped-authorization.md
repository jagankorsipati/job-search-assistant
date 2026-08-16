# ADR-009: Enforce Owner-scoped Authorization

- Status: Accepted
- Date: 2026-08-15

## Context

Authentication establishes who is acting but does not authorize access to every row in a household installation. Future profile, job, document, fit, and application resources are private by default. Controller-only checks are insufficient because alternate application paths and later background work must preserve the same isolation.

## Decision

Every user-owned row has an immutable UUID `owner_account_id`. Creation obtains it from the identity module's `CurrentActorProvider`; browser account identifiers are rejected or ignored and never trusted. Individual reads, updates, and deletes use a single owner-scoped query with both resource ID and owner ID. Collections and bulk operations always filter by owner. User-local uniqueness includes `owner_account_id` where appropriate, and indexes generally begin with `owner_account_id`.

The identity module exposes the named interface `identity::actor`, containing only `AuthenticatedActor`, `ActorRole`, `CurrentActorProvider`, and a generic unauthenticated failure. Its internal adapter accepts no caller-provided identity. It reads the authenticated internal session principal and revalidates account status, role, and credential version before returning the actor. Identity repositories, sessions, credentials, invitations, HTTP objects, and Spring Security types remain internal.

Private-resource access does not gain an `ADMIN` bypass. Administrative authority is expressed through separate, explicitly role-protected operations. Administrator-created private resources belong to that administrator. Any future delegated or shared access requires a separate decision and explicit grant.

Nonexistent and non-owned individual resources both return `404` using the same owner-scoped query. Collections return only the current owner's rows and therefore may be empty. `401` means unauthenticated; `403` is reserved for an authenticated caller lacking an explicit administrative role. Implementations avoid an unscoped ownership lookup before denial so they do not disclose the true owner or add an avoidable timing distinction.

Background operations must carry explicit owner or reviewed system authority. They may not invent a browser-like actor or infer ownership from payload data. PostgreSQL row-level security is deferred until connection-pooling, migrations, backup, and operations requirements justify the added complexity.

Each future owned-resource module must add PostgreSQL integration tests covering two members, an administrator, cross-user creation/read/update/delete/collection behavior, CSRF, unauthenticated requests, and direct repository owner predicates before its endpoints are accepted. Phase 2D demonstrates this contract with a test-only table and HTTP fixture; it adds no production table or endpoint. This is a reusable testing pattern, not a speculative generic CRUD or reflection-based authorization framework.

## Required query shapes

```sql
INSERT INTO resource (id, owner_account_id, ...) VALUES (?, :current_actor_id, ...);
SELECT ... FROM resource WHERE id = ? AND owner_account_id = ?;
UPDATE resource SET ... WHERE id = ? AND owner_account_id = ?;
DELETE FROM resource WHERE id = ? AND owner_account_id = ?;
SELECT ... FROM resource WHERE owner_account_id = ?;
```

## Consequences

- SQL predicates provide defense in depth beneath HTTP authorization.
- UUID account and owner identifiers remain opaque and non-sequential.
- Administrator identity operations stay distinct from private career data access.
- Bulk and asynchronous features require explicit ownership design rather than inheriting ambient authority.
- Every future domain persistence milestone carries mandatory cross-user regression tests.

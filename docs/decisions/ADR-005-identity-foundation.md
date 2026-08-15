# ADR-005: Establish the Identity Persistence and Lifecycle Foundation

- Status: Accepted
- Date: 2026-08-15

## Context

ADR-004 chose invite-only individual household accounts and private workspaces. Phase 2A must make the account and invitation invariants concrete without prematurely exposing registration, authentication, recovery, or administrator operations.

## Decision

Each person has an individual account identified by a UUID. Public registration is prohibited. A later registration flow will require an unexpired, single-use invitation created by an active administrator.

Accounts have `ADMIN` or `MEMBER` authority and move through `PENDING_ACTIVATION`, `ACTIVE`, and `DISABLED` lifecycle states. Login names are trimmed, normalized to lowercase with locale-independent rules, limited to a documented safe ASCII syntax, and unique in normalized form. Password hashes are opaque outputs from a reviewed password hasher: plaintext passwords are never persisted, logged, returned, or handled as domain values.

Invitations store only a one-way cryptographic token hash. They have an intended role, expiry, explicit pending/consumed/revoked persistence state, a creator account reference, and an optimistic version. Consumption must atomically change pending state to consumed exactly once and record a valid consumption time. Expiration is derived from the current time rather than mutating rows merely because time passed.

The first administrator will be established in Phase 2B through an explicit, one-time bootstrap boundary that requires operator intent and refuses to run after any account exists. It will not use seeded credentials, a checked-in password, an implicit default account, or an ordinary public registration path.

Later browser authentication will use server-managed sessions in Secure, HttpOnly, SameSite cookies with session rotation and CSRF protection for state-changing requests. Exact session duration, persistence, password hashing parameters, and CSRF-token delivery remain Phase 2B decisions.

Administrators may invite members, disable accounts, and initiate a recovery-assistance process. Administrator authority does not grant access to another member's profile, résumé, jobs, documents, applications, or files. Authentication establishes who is acting; it never by itself authorizes access to user-owned resources. Resource ownership will be derived server-side from the authenticated context, and browser-supplied owner identifiers will never be trusted.

Account recovery and delegated access are explicitly deferred. Any future delegated access must be an explicit, revocable grant from the data owner and requires a separate architecture decision.

## Consequences

- Database checks and framework-independent domain types enforce the same lifecycle vocabulary.
- The identity schema can support safe password/session invalidation through a credential version without implementing sessions yet.
- Optimistic versions support atomic account changes and single-use invitation consumption.
- A foreign key proves that an invitation creator is an existing account; application authorization must additionally prove the creator is active and has administrator authority at the time of creation.
- Phase 2B must threat-model bootstrap, password hashing, sessions, CSRF, recovery assistance, and rate limiting before exposing endpoints.

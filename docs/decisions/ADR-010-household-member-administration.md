# ADR-010: Limit Household Member Administration

- Status: Accepted
- Date: 2026-08-15

## Context

A household administrator must be able to stop and restore a member's access without gaining access to that member's private career workspace. Disabling only future logins is insufficient because existing PostgreSQL-backed sessions would otherwise remain usable until their next validation or expiry. Security audit data also needs bounded retention appropriate to a small private installation.

## Decision

An active `ADMIN` may list minimal account-management metadata and disable or reactivate `MEMBER` accounts. The list is deterministically ordered, capped at 100 household accounts, and contains only UUID, normalized login name, display name, role, status, and creation time. It exposes no credential version, hash, invitation, session, audit history, or career content.

Only `ACTIVE MEMBER -> DISABLED MEMBER` and `DISABLED MEMBER -> ACTIVE MEMBER` transitions are supported. Atomic status predicates serialize concurrent requests. Disable increments both credential version and optimistic version; reactivate increments only optimistic version and never reduces credential version. Administrators cannot target any `ADMIN`, including themselves, preventing HTTP account-administration lockout. Creating administrators, role changes, deletion, password recovery, and administrator-role delegation remain deferred.

After the disable transaction commits, the application finds every Spring Session indexed by the target account UUID and deletes it. `SessionPrincipal` implements `Principal` with the UUID as its name so Spring Session never indexes by login name. Session identifiers are never returned or audited. Session deletion deliberately occurs after the status commit: if deletion fails, the account remains disabled and the existing per-request active-status and credential-version validation blocks any surviving session. Revocation failure is contained and audited. Reactivation creates or restores no session; the member must log in again.

Account administration records minimal events: `ACCOUNT_DISABLED`, `ACCOUNT_REACTIVATED`, `ACCOUNT_SESSIONS_REVOKED`, and rejected administration attempts. Events contain event type, outcome, acting account UUID, optional target account UUID, and timestamp only.

Authentication audit events are retained for 90 days by default, configurable from 30 through 365 days. Cleanup runs every six hours by default, deletes at most 500 ordered expired rows per run, and is configurable from 50 through 5,000 rows. A `(occurred_at, event_id)` index supports stable batches. Cleanup errors are logged without sensitive values and never interrupt authentication, sessions, accounts, or private data.

Administrator authority remains an identity-management capability. ADR-009 owner scoping is unchanged: `ADMIN` does not authorize profile, job, document, fit, application, résumé, or file access.

## Consequences

- Disablement revokes indexed sessions immediately under normal operation and remains safe if revocation storage fails.
- Credential-version increments protect against copied or unexpectedly surviving session state.
- Invalid, nonexistent, repeated, concurrent, and administrator-target transitions share a safe conflict response.
- Audit storage remains bounded without adding a broad operations framework or deletion endpoint.
- Recovery, account deletion, additional administrators, role management, and delegated access require later decisions.

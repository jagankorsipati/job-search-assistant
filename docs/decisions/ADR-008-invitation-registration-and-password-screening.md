# ADR-008: Complete Invite-only Browser Registration

- Status: Accepted
- Date: 2026-08-15

## Context

Phase 2C1 established stateful authentication but deliberately left invitation transport and password-setting off HTTP. Browser registration must not leak invitation tokens, permit public account creation, accept known compromised passwords, or allow anonymous CSRF requests to exhaust PostgreSQL sessions.

## Decision

An active authenticated administrator may issue only MEMBER invitations through `POST /api/admin/invitations`. The server derives the creator from the validated session and returns the token and expiration once. Acceptance uses anonymous, CSRF-protected `POST /api/invitations/accept`; valid acceptance creates an ACTIVE account transactionally but never authenticates it. Requiring normal login preserves a clear authentication boundary and session rotation.

Malformed, unknown, expired, revoked, and consumed tokens share one response. Duplicate login names may receive a non-sensitive unavailable result and leave the invitation pending. Acceptance is limited by direct source address and a stored-in-memory token digest before lookup or Argon2 work.

Passwords are checked offline against sorted SHA-256 digests derived from the MIT-licensed SecLists 2026.1 `10k-most-common.txt` source, plus case-insensitive login, display-name, and product-name context checks. The source checksum and deterministic generator are tracked. Startup validates version, format, sorting, uniqueness, and minimum coverage and fails closed if the required resource is missing or corrupt. No password data is sent externally.

Anonymous `GET /api/auth/csrf` requests are rate-limited before Spring Security resolves a token or creates a session. Requests with an existing session continue normally. Forwarded addresses remain untrusted and all limiter storage is bounded and expiring; this remains a single-instance control.

The frontend puts invitation tokens only in URL fragments. It reads the fragment once, immediately removes it with history replacement, retains sensitive values only in component memory, and never uses browser storage. Leaving the invitation-creation view clears its one-time token.

Account listing, disabling/reactivation APIs, recovery, and delegated access remain deferred.

## Consequences

- Registration is invite-only and does not silently sign a new member in.
- A local digest resource adds a reviewed update procedure and approximately 645 KiB to the backend artifact.
- Anonymous abuse is bounded without Redis, but restarts reset counters and multiple instances would require a shared design.

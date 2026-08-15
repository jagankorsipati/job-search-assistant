# ADR-007: Use Stateful Browser Authentication

- Status: Accepted
- Date: 2026-08-15

## Context

The React client needs a small, private-household authentication boundary. Browser-held bearer tokens would add token storage, revocation, and rotation risks without solving a deployment need. Account disablement and credential changes must take effect promptly, and authentication failures must not disclose whether an account exists.

## Decision

Use Spring Security with opaque, PostgreSQL-backed Spring Session identifiers. The browser receives only the `JSA_SESSION` cookie; the session stores a minimal account ID, role, and credential version. Successful authentication changes the existing anonymous CSRF session ID. Every authenticated request reloads the account and invalidates the session when the account is absent, non-active, has a changed role, or has a changed credential version.

Sessions expire after 30 minutes idle and have an independently enforced 12-hour absolute lifetime. Both durations are configurable, must be positive, and retain production-safe defaults. Cookies are HttpOnly, SameSite=Strict, and Secure by default. Direct local HTTP development is the sole documented reason to set `SESSION_COOKIE_SECURE=false`; no cookie domain is configured.

CSRF protection remains enabled. `GET /api/auth/csrf` places Spring Security's session-backed CSRF token in an explicit response body with its header and parameter names. The session cookie remains unreadable to JavaScript. Login and logout require that token. CORS is not broadened; Vite proxies same-origin development requests.

Authentication responses are JSON and deliberately generic. A bounded, expiring in-memory limiter checks direct remote address and a SHA-256 digest of normalized login before Argon2 verification. Forwarded headers are not trusted. This design is limited to the initial single-instance household deployment; scaling to multiple application instances requires a shared limiter and a new decision.

Minimal audit rows record login success/failure/rate limiting, logout, and forced invalidation. Rows contain event ID, type, outcome, optional known account ID, and time only. Audit persistence is best-effort: its failure is warned without sensitive values and does not change authentication outcome. Retention remains an explicit later operations decision.

Invitation creation/acceptance HTTP endpoints and all authentication frontend screens are deferred to Phase 2C2.

## Consequences

- Flyway exclusively creates Spring Session and authentication-event tables in `job_search_assistant`; Spring Session schema initialization is disabled.
- Session invalidation is immediate on the next authenticated request, at the cost of one bounded account query per request.
- The login limiter protects expensive password verification but resets on process restart and is not a distributed control.
- Secure cookies do not work over plain HTTP unless the operator makes the explicit local-only override.

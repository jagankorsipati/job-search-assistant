# Security and Privacy

## Sensitive data

Résumés, contact details, work history, education, notes, job activity, credentials, session tokens, and generated documents are sensitive. Secrets and password hashes are restricted security data.

## Initial controls

- Invite-only household registration with expiring, single-use invitations
- Argon2id or an equivalently reviewed password hash
- Secure, HttpOnly, SameSite cookies and CSRF protection
- Server-side authorization on every record and file operation
- Per-user storage namespace with generated filenames
- File type, size, and archive validation
- Parameterized persistence and output encoding
- Rate limits on authentication and expensive analysis
- Secrets supplied outside source control
- Logs exclude document content, tokens, passwords, and personal details
- Explicit confirmation for deletion and export
- Dependency and container scanning in CI

## Identity authorization boundary

- Public registration is prohibited; new household accounts require an expiring, single-use administrator invitation.
- Invitation tokens are persisted only as one-way cryptographic hashes. Password hashes are opaque restricted values; neither passwords nor hashes may be logged or exposed.
- Administrators may invite, disable, and assist with recovery, but cannot read another member's profile, résumé, jobs, documents, applications, or files by default.
- Authentication establishes actor identity and never implies authorization to user-owned resources.
- Owner identity is derived from the authenticated server-side context. Browser-supplied owner identifiers are never trusted.
- Domain modules obtain the current UUID and role only through the `identity::actor` named interface. Session principals, credential state, repositories, HTTP sessions, and Spring Security objects remain identity internals.
- Owner-scoped SQL is mandatory for reads, updates, deletes, collections, and bulk operations. Non-owned and nonexistent resources share `404` behavior; administrators do not bypass private ownership.
- Background operations require explicit owner or reviewed system authority.
- The first administrator requires an explicit one-time operator bootstrap that will be designed in Phase 2B; no administrator or credential is seeded.
- Account recovery and delegated access remain deferred. Delegation requires an explicit, revocable owner grant and a separate decision.
- Browser sessions are opaque and PostgreSQL-backed. Cookies are Secure by default, HttpOnly, and SameSite=Strict; successful login rotates the anonymous CSRF session ID. Direct local HTTP development must explicitly set `SESSION_COOKIE_SECURE=false`.
- Sessions expire after 30 minutes idle and an independently enforced 12-hour absolute lifetime. Every authenticated request revalidates active status, role, and credential version against PostgreSQL.
- The SPA obtains a session-backed token from `/api/auth/csrf` and sends it in the returned header. The session cookie is never JavaScript-readable.

## Credential and invitation controls

- Passwords use self-describing Argon2id hashes with a 16-byte salt, 32-byte output, 19 MiB memory, two iterations, and parallelism one.
- Passwords must contain 15 through 128 Unicode code points. They are not normalized or trimmed, and no character-class composition rules apply.
- First-administrator bootstrap is disabled by default, requires explicit process-scoped operator configuration, serializes concurrent attempts, and permanently refuses after the first account exists.
- Invitations contain 256 random bits, persist only a versioned SHA-256 digest, default to 24 hours, and are bounded between 15 minutes and seven days.
- Invitation issue and acceptance are transactional. Acceptance locks the invitation and atomically creates the account and consumes the invitation.
- Unknown users perform a dummy password verification. Unknown, incorrect, pending, and disabled credentials share one generic failure.
- Login is rate-limited before Argon2 by direct source address and a digest of normalized login. The bounded in-memory limiter is suitable only for the initial single-instance household deployment and deliberately ignores forwarded headers.
- Authentication audit rows contain only event ID, type, outcome, optional known account ID, and time. They never contain login names, passwords, tokens, session IDs, user agents, or network addresses. Persistence is best-effort and retention remains an operations decision.
- MEMBER invitations are created only by an authenticated active ADMIN and returned once. Browser invitation links carry tokens in URL fragments, which are removed immediately and never persisted in browser storage.
- Invitation acceptance is CSRF-protected, rate-limited before lookup and Argon2, and never authenticates automatically. Invalid invitation states share a generic response.
- Password setting checks a validated offline digest blocklist derived from SecLists 2026.1 plus account-context terms. Missing or corrupt blocklist data prevents startup; no password information leaves the application.
- Anonymous CSRF issuance is bounded before session creation. Existing sessions are not charged as new anonymous sessions.

## AI privacy

- AI integration is optional and disabled without configuration.
- The UI identifies what data will leave the home system before a request.
- Send the minimum required text; do not send entire document archives by default.
- API credentials remain server-side.
- Provider responses are treated as untrusted proposals.
- Job descriptions are delimited as data to reduce prompt-injection risk.

## Network posture

V1 binds to the private network only. Remote access uses Tailscale or an equivalent private overlay. Direct router port forwarding is prohibited by the deployment guide.

## Threats requiring tests

- Cross-user IDOR access
- Filename/path traversal
- Malicious DOCX or oversized upload
- Session fixation and CSRF
- Prompt injection embedded in a job posting
- AI-generated fabricated claim
- Sensitive data in logs or health endpoints
- Incomplete account deletion

## Open decisions

- Account-recovery proof
- Backup encryption mechanism
- Authentication-event retention
- Multi-instance/shared authentication rate limiting if deployment topology changes

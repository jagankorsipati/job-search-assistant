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
- Locked dependencies, immutable CI action pins, and reviewed informational dependency audits

## Identity authorization boundary

- Public registration is prohibited; new household accounts require an expiring, single-use administrator invitation.
- Invitation tokens are persisted only as one-way cryptographic hashes. Password hashes are opaque restricted values; neither passwords nor hashes may be logged or exposed.
- Administrators may invite, disable, and assist with recovery, but cannot read another member's profile, résumé, jobs, documents, applications, or files by default.
- Authentication establishes actor identity and never implies authorization to user-owned resources.
- Owner identity is derived from the authenticated server-side context. Browser-supplied owner identifiers are never trusted.
- Domain modules obtain the current UUID and role only through the `identity::actor` named interface. Session principals, credential state, repositories, HTTP sessions, and Spring Security objects remain identity internals.
- Owner-scoped SQL is mandatory for reads, updates, deletes, collections, and bulk operations. Non-owned and nonexistent resources share `404` behavior; administrators do not bypass private ownership.
- Background operations require explicit owner or reviewed system authority.
- Candidate profiles and career facts carry immutable owner UUIDs and must use owner-scoped SQL. Administrators have no bypass into another member's profile or career facts.
- `/api/profile/**` endpoints require authentication. POST and PUT lifecycle operations require CSRF through the existing browser-session protection.
- Profile and career-fact APIs use safe JSON errors: `400` for malformed input, `401` for unauthenticated access, `404` for nonexistent or non-owned private resources, and `409` for uniqueness, stale-version, or lifecycle conflicts.
- The `/profile` frontend route restores authenticated sessions through `/api/auth/me`. If profile requests return `401`, the UI clears only in-memory authenticated state and returns to login. It does not persist identity, profile, career-fact, or authorization data in browser storage, URL query parameters, URL fragments, IndexedDB, or client-readable cookies.
- Frontend profile and career-fact writes are explicit user actions. The UI never autosaves, never sends `ownerAccountId`, never assigns career-fact status directly, and preserves unsaved form values when optimistic locking returns `409`.
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
- Account administration is restricted to active administrators and MEMBER targets. Disabling atomically increments credential and optimistic versions, then revokes every UUID-indexed Spring Session; reactivation never restores a session.
- Administrators cannot disable or reactivate ADMIN accounts. Recovery, deletion, role changes, and additional administrator creation remain deferred.
- Account-administration audits contain only event type, outcome, acting UUID, optional target UUID, and time. Authentication events default to 90-day retention, bounded to 30â€“365 days, with failure-contained cleanup in batches of 50â€“5,000.

## AI privacy

- AI integration is optional and disabled without configuration.
- The UI identifies what data will leave the home system before a request.
- Send the minimum required text; do not send entire document archives by default.
- API credentials remain server-side.
- Provider responses are treated as untrusted proposals.
- Job descriptions are delimited as data to reduce prompt-injection risk.
- Imported or AI-generated career text is always draft. It cannot become confirmed or eligible for generated documents until the account owner explicitly attests that it is accurate.

## Candidate-profile privacy

The profile module stores professional-display and matching-oriented information only. It must not store passwords, credentials, Social Security numbers, immigration document numbers, birth dates, full home addresses, government identification, salary history, or references' private contact information. Work authorization may be captured only as a user-authored statement, not as document numbers or images.

Candidate-profile `careerSummary` is owner-authored presentation text. It must not override confirmed career facts or serve as evidence for unsupported generated claims.

The frontend explains that confirmed career facts are owner attestations only, not independent verification by the application or third parties. Confirmation requires a deliberate accuracy checkbox and sends `confirmedAccurate: true` with the expected version. Editing a confirmed fact returns it to draft. Archive and restore require explicit confirmation; restore returns the fact to draft and does not reconfirm it.

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

## Phase 2 final threat-model review

Classification is intentionally conservative: “mitigated” means controls and repeatable evidence exist, not that risk is eliminated. Detailed implementation and test links are in the [Phase 2 verification matrix](security/phase-2-verification.md).

Phase 3 candidate-profile verification evidence is recorded in the [Phase 3 verification matrix](security/phase-3-verification.md). Browser verification now covers profile creation/editing, career-fact confirmation/archive/restore, owner isolation between administrator and member accounts, direct cross-user API rejection, optimistic conflicts, CSRF rejection, session-expiration handling, and browser-storage/cookie/URL privacy.

| Threat | Asset and boundary | Mitigation and verification evidence | Residual risk | Classification |
| --- | --- | --- | --- | --- |
| Credential stuffing / guessing | Credentials; anonymous login boundary | Argon2id, generic failures, dummy verification, bounded source/login limiter; unit, PostgreSQL HTTP, and browser disabled-login tests | In-memory limits reset and are per instance; Argon2 remains a DoS cost | Mitigated for one instance; distributed control deferred |
| Login-name enumeration | Household identities; login and invitation acceptance | Generic login response/timing work and no public directory; HTTP tests | Invitation acceptance can report an unavailable login after possession of a valid invite | Mitigated |
| Invitation guessing, reuse, leakage, expiration | One-time bearer token; administrator-to-browser transfer | 256-bit tokens, hash-only persistence, expiry/locking/single use, fragment removal, generic rejection; lifecycle, HTTP, and browser tests | Recipient/channel or compromised browser can leak plaintext before use | Mitigated; private transfer required |
| CSRF | Authenticated browser session | SameSite=Strict cookie plus session-backed CSRF token; HTTP and browser missing-token tests | Browser compromise defeats browser controls | Mitigated |
| Session fixation | Session identifier | Spring Security rotation plus browser pre/post-login cookie comparison | Stolen post-login session remains usable until invalidated/expired | Mitigated |
| Session theft | Opaque session cookie | HttpOnly, Secure default, SameSite=Strict, server-side state, idle/absolute expiry, logout | TLS and host security are deployment responsibilities | Deployment prerequisite |
| Stale or disabled-account sessions | Account/session state | Per-request status/role/credential-version validation and UUID-indexed revocation; integration and multi-context browser tests | Database outage causes application unavailability | Mitigated |
| Privilege escalation | ADMIN operations | Server session role, ADMIN endpoint rules, MEMBER-only transition invariant; controller/integration/browser tests | Compromised administrator can administer access | Mitigated within designed role |
| Administrator abuse | Member privacy | ADMIN has no ownership bypass; narrow actor interface and owner-scoped repositories/tests | Administrator controls host/database and can access raw storage | Accepted for trusted household operator; host hardening required |
| Cross-user private-resource access | Future career data | Server-derived actor UUID, owner predicate contract, identical missing/non-owned result; PostgreSQL isolation tests | Each future repository must adopt the contract; no PostgreSQL RLS | Mitigated contract; RLS deferred |
| Browser-supplied owner IDs | Authorization boundary | Ownership fixture ignores/rejects caller identity and derives actor server-side | Future code regression remains possible | Mitigated by architecture tests and reusable fixture |
| Sensitive values in URLs, logs, errors, audit, storage | Tokens, credentials, sessions, personal data | Fragment capture/removal, redacted request records, generic errors, minimal audit columns, no browser storage; code review and browser assertions | Browser history before script execution and operator-level process inspection | Mitigated; private transfer remains required |
| Blocklist tampering | Password policy resource / upstream supply chain | Immutable 40-character commit, SHA-256, exact counts, sorted/unique validation, fail-closed startup | SHA-256 pin update review can be compromised | Mitigated with manual update review |
| Forwarded-header spoofing | Rate-limit source identity | Uses direct servlet remote address and does not enable forwarded-header trust | Reverse proxy deployment needs an explicit trusted-proxy design | Mitigated locally; deployment prerequisite |
| Database exposure | Credentials, sessions, audit | Compose binds PostgreSQL to loopback; deployment guide forbids LAN/public port | Host compromise and weak external credentials | Deployment prerequisite |
| Insecure-cookie deployment | Session confidentiality | Secure defaults to true and configuration-level regression test; local E2E override is process-only | HTTP deployment becomes unusable rather than silently secure | Mitigated default; HTTPS prerequisite |
| Audit overcollection / unlimited retention | Security metadata | Minimal UUID/type/outcome/time schema, 90-day default, bounded/batched stable cleanup and index; retention tests/review | Cleanup failure can temporarily grow data | Mitigated with operator monitoring |
| Multi-instance limiter limitations | Authentication availability | Bounded in-memory keys and documented single-instance topology | No coordinated limits across instances | Deferred |
| Dependency / supply-chain risk | Build and runtime | Lockfile/wrappers, SHA-pinned Actions, deterministic builds, informational audits | Registries and upstream artifacts remain trusted; advisory services can be unavailable | Accepted with update process |
| Argon2/session-creation denial of service | CPU, memory, database | Pre-Argon2 rate limits, bounded anonymous CSRF issuance and key maps | Distributed attacks and Raspberry Pi capacity are untested | Accepted for private single instance; Pi benchmark prerequisite |

## Open decisions

- Account-recovery proof
- Backup encryption mechanism
- Multi-instance/shared authentication rate limiting if deployment topology changes

## Audit-retention operations

Authentication security events default to 90 days; configuration is rejected outside 30–365 days. Cleanup defaults to 500 rows per run and is rejected outside 50–5,000. The scheduled task defaults to every six hours, deletes in stable `(occurred_at, event_id)` order, uses `authentication_security_event_retention_ix`, and contains failures so cleanup cannot stop the application. Its SQL targets only `authentication_security_event`; accounts, invitations, sessions, and future private data cannot be deleted by it.

The table contains event UUID, constrained type/outcome, optional actor/target account UUIDs, and time only—never passwords, hashes, invitation or CSRF tokens, session IDs, IP addresses, user agents, bodies, login names, or display names. Operators can check health without personal data:

```sql
SELECT count(*) AS expired_event_count,
       min(occurred_at) AS oldest_event_time
FROM job_search_assistant.authentication_security_event
WHERE occurred_at < now() - interval '90 days';
```

Investigate repeated cleanup warnings and a growing expired count. Do not add sensitive request metadata for observability.

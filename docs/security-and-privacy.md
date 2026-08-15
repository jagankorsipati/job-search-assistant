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
- The first administrator requires an explicit one-time operator bootstrap that will be designed in Phase 2B; no administrator or credential is seeded.
- Account recovery and delegated access remain deferred. Delegation requires an explicit, revocable owner grant and a separate decision.
- Later browser sessions will use Secure, HttpOnly, SameSite cookies, rotation, and CSRF protection. Exact session and CSRF mechanics remain Phase 2B work.

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

- Exact invitation lifetime and account-recovery proof
- Backup encryption mechanism
- Session duration and reauthentication thresholds
- First-administrator bootstrap mechanism and password hashing parameters

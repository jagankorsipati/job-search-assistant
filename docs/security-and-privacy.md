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

- Exact invitation expiry and account-recovery proof
- Backup encryption mechanism
- Session duration and reauthentication thresholds
- Whether administrators can disable accounts without reading content

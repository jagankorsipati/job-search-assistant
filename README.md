# Job Search Assistant

A private, self-hosted household workspace for finding jobs, evaluating fit, tailoring truthful application materials, and tracking applications.

> The system may reorganize, emphasize, and reword verified experience. It must never invent a skill, accomplishment, employer, title, certification, responsibility, education item, or metric.

## Status

Phase 5B is implemented as an internal backend milestone: the Fit module can compute deterministic, explainable evidence-support and evidence-coverage scores over confirmed job requirements and explicit candidate-evidence links. Public analysis routes, frontend review screens, persisted scores, AI analysis, automatic extraction, automatic evidence matching, scraping, resume tailoring, reminders, and application submission are not included.

## Planned capabilities

- Separate household accounts and private candidate workspaces
- Candidate profiles containing owner-confirmed career facts
- Job capture from pasted text and URLs
- Explainable fit analysis and gap reporting
- Reviewed résumé and cover-letter drafts
- Application pipeline tracking
- Raspberry Pi deployment through Docker Compose

Automated application submission and dependable LinkedIn scraping are not part of V1.

## Proposed stack

- React and TypeScript frontend
- Spring Boot modular-monolith backend
- PostgreSQL database
- Private document storage
- Optional Python document worker, introduced only if Java document tooling is insufficient
- Optional AI provider behind a replaceable interface

## Documentation

- [Product vision](docs/product-vision.md)
- [V1 scope](docs/v1-scope.md)
- [Non-goals](docs/non-goals.md)
- [User journeys](docs/user-journeys.md)
- [Functional requirements](docs/functional-requirements.md)
- [System architecture](docs/system-architecture.md)
- [Data ownership](docs/data-ownership.md)
- [Security and privacy](docs/security-and-privacy.md)
- [Job-source strategy](docs/job-source-strategy.md)
- [Résumé integrity policy](docs/resume-integrity-policy.md)
- [Deployment strategy](docs/deployment-strategy.md)
- [Roadmap](docs/roadmap.md)
- [Architecture decisions](docs/decisions/README.md)

## Local development

Prerequisites:

- JDK 21
- Docker Desktop with Linux containers
- Node.js 24 LTS (the project pins 24.18.0 in `frontend/.nvmrc` and `frontend/.node-version`)
- npm 11 (the exact package-manager version is recorded in `frontend/package.json`)

Maven is downloaded automatically by the backend wrapper. Node.js 24 is the selected supported LTS line; do not use Node.js Current for this project.

Create the ignored local environment file from the tracked example:

```powershell
Copy-Item .env.example .env
```

The example values are intentionally limited to local development. Change `DB_PASSWORD` before using this configuration anywhere else; `.env` must never be committed.

Start PostgreSQL and wait for its health check:

```powershell
docker compose up -d --wait
docker compose ps
docker compose exec postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Compose binds PostgreSQL to `127.0.0.1` only so the backend can run directly on Windows without exposing the database on LAN interfaces.

Load `.env` into a second PowerShell process and run the backend:

```powershell
cd backend
Get-Content ..\.env | Where-Object { $_ -match '^[^#][^=]*=' } | ForEach-Object { $name, $value = $_ -split '=', 2; Set-Item -Path "Env:$name" -Value $value }
.\mvnw.cmd spring-boot:run
```

For direct local HTTP development only, set the explicit cookie exception before starting the backend:

```powershell
$env:SESSION_COOKIE_SECURE = 'false'
```

Secure cookies default to `true` and must remain enabled behind HTTPS. Sessions expire after 30 minutes idle and after 12 hours absolute by default. Optional operational overrides are `SESSION_IDLE_TIMEOUT` and `SESSION_ABSOLUTE_TIMEOUT`; both must be positive. Do not add authentication secrets to `.env`.

Administrators can manage MEMBER access at `/admin/accounts`. Disablement preserves private data, increments the credential version, and revokes all sessions; reactivation always requires a new login. Authentication audit events default to 90-day retention. Optional bounds-checked settings are `AUTH_AUDIT_RETENTION_PERIOD` (30â€“365 days), `AUTH_AUDIT_RETENTION_BATCH_SIZE` (50â€“5,000), and `AUTH_AUDIT_RETENTION_INTERVAL`.

The SPA authentication handshake is `GET /api/auth/csrf`, followed by `POST /api/auth/login` with the returned CSRF header. `GET /api/auth/me` inspects the current session and `POST /api/auth/logout` requires CSRF. Login failure bodies do not distinguish unknown, incorrect-password, pending, or disabled accounts.

Local household setup proceeds in this order: bootstrap the first administrator, restart normally, sign in as that administrator, create a MEMBER invitation, transfer the one-time fragment link privately, accept it without automatic login, then sign in as the new member. Invitation tokens and passwords must never be copied into source files, logs, `.env`, or command history. The frontend removes invitation fragments immediately and stores no authentication secrets in browser storage.

Compromised-password screening is entirely offline. Provenance and the reviewed update command are documented in [the blocklist guide](docs/security/compromised-password-blocklist.md).

The final identity threat model and evidence are recorded in the [Phase 2 verification matrix](docs/security/phase-2-verification.md). Candidate-profile and base-resume verification evidence is recorded in the [Phase 3 verification matrix](docs/security/phase-3-verification.md). Job/application verification evidence is recorded in the [Phase 4 verification matrix](docs/security/phase-4-verification.md). Future household deployment must pass the separate [deployment security checklist](docs/security/deployment-checklist.md).

Profile API endpoints live under `/api/profile`. They derive ownership from the validated server-side actor, never from request JSON. `GET /api/profile/career-facts` supports exact `category` and `status` enum filters plus a bounded `limit` of 1 through 100. State-changing requests require CSRF. Stale versions and invalid fact transitions return safe conflicts; nonexistent and non-owned resources share not-found behavior.

Base résumé API endpoints live under `/api/documents/base-resume`. Each owner can store one current PDF or DOCX up to 5 MiB. Uploading stores the source document only; it does not parse, extract, confirm, import, invoke AI, or change career facts. `POST` creates the initial document, `PUT` replaces it with `expectedVersion`, and `/download` streams it as an attachment. Metadata responses never expose owner IDs, checksums, storage keys, or filesystem paths. Configure storage with `BASE_RESUME_STORAGE_ROOT`; the local default is for development only and must be treated as sensitive data.

Job API endpoints live under `/api/jobs`. They derive ownership from the validated server-side actor, never from request JSON. `GET /api/jobs` returns only the actor's jobs, defaults to active rows, supports `archived=true`, and caps `limit` at 100. `POST /api/jobs` captures manual, pasted-description, or URL-reference opportunities and can atomically create initial snapshot sequence 1. `GET`/`PUT /api/jobs/{jobId}` read and update owner-scoped metadata with optimistic locking. `/archive` and `/restore` are explicit versioned transitions; hard deletion is not implemented.

Snapshot endpoints live under `/api/jobs/{jobId}/snapshots`. Lists are oldest-first by sequence and capped at 50. Appending locks the owner-scoped parent job, rejects archived jobs, canonicalizes LF line endings, SHA-256 digests the canonical text, and inserts the next sequence atomically. Reposting canonical duplicate content as the latest snapshot returns `409 duplicate_snapshot`. Posting URLs are stored only as HTTP/HTTPS references, fragments are removed, credentials are rejected, and the server never fetches URL content.

Application API endpoints live under `/api/applications`. They derive ownership from the validated server-side actor, never from request JSON, and return no owner identifiers. `GET /api/applications` returns only the actor's applications, defaults to active rows, supports `archived=true`, exact `status`, and a bounded `limit` of 1 through 100. `POST /api/applications` creates one DRAFT application for an active owner-scoped captured job and atomically writes the initial DRAFT history event. `GET`/`PUT /api/applications/{applicationId}` read and update private notes plus next action metadata with optimistic locking; updates do not change status or append history. `/transitions` performs explicit versioned status changes and appends one immutable history event in the same transaction. `/history` is oldest-first and capped at 100. `/archive` and `/restore` are explicit versioned archival operations and do not change status or history.

Application status is user-declared. `READY_TO_APPLY -> APPLIED` establishes `appliedAt` from a truthful user-provided timestamp or the server clock, rejects values more than five minutes in the future, and later transitions preserve it. `WITHDRAWN` may happen before or after submission; pre-application withdrawal keeps `appliedAt` absent, while post-application withdrawal preserves it. Terminal outcomes clear next actions but preserve notes and history. No job capture, resume action, document generation, AI output, or download implies an application status.

Fit foundation API endpoints live under `/api/jobs/{jobId}/snapshots/{snapshotId}/requirements`, `/api/job-requirements/{requirementId}`, `/api/job-requirements/{requirementId}/evidence-links`, and `/api/job-requirement-evidence/{linkId}`. Requirements are editable with `expectedVersion` but remain attached to their original job snapshot. Requirement category, importance, and review status are explicit. Evidence links are user-created relationships to existing owner-visible candidate evidence only: confirmed career facts, supported profile fields, or the current base resume metadata row. Lists are capped at 100, responses are no-store, and owner identifiers are omitted.

Phase 5A deliberately does not infer whether a requirement is satisfied. It never converts job-description text into candidate facts, never treats resume text as independently verified, never infers duration, proficiency, recency, work authorization, education, or certification equivalence, and never creates evidence links automatically.

Phase 5B adds an internal deterministic scoring policy, `DETERMINISTIC_FIT_V1`. Only confirmed requirements participate; draft and rejected requirements are excluded from denominators but counted in the result. Required requirements have weight 2, preferred and unspecified requirements have weight 1. Demonstrated evidence receives credit 1.0, partial support receives 0.5, and not-demonstrated, contradicted, conflicting, and unassessed requirements receive 0.0. Support and coverage scores use exact decimal arithmetic and half-up whole-number rounding. Contradictions, conflicting evidence, gaps, partial gaps, and per-importance breakdowns are returned as structured reason codes without copying requirement text, source excerpts, resume content, career facts, profile values, or evidence notes. Results are computed on demand and never persisted.

The frontend profile workspace is available at `/profile` after sign-in. Refreshing that path restores the existing session and reopens the workspace; unauthenticated or expired sessions return to the login screen. The browser keeps profile, career-fact, identity, and authorization data only in memory. It does not write that data to `localStorage`, `sessionStorage`, IndexedDB, URL query parameters, URL fragments, or client-readable cookies.

The profile form never autosaves. It sends the current version on update and preserves unsaved edits when a `409` conflict indicates the server changed elsewhere. Career facts are created as draft. Confirmed means the account owner explicitly attested that the fact is accurate; it is not independent verification by an employer, school, certification authority, or the application. Editing a confirmed fact returns it to draft. Draft and confirmed facts can be archived after confirmation; archived facts can be restored to draft, but cannot be edited until restored. Hard deletion remains deferred.

The job and application frontend workspaces are available at `/jobs` and `/applications` after sign-in through the lightweight in-app navigation. Refreshing either path restores the existing session and reopens the workspace; unauthenticated or expired sessions return to login. The browser keeps job, snapshot, application, and status-history data only in memory and never submits owner/account identifiers. Job posting URLs are displayed as references only; the frontend does not fetch, scrape, or submit to them.

The job workspace supports active and archived lists, manual/pasted/URL-reference capture, metadata edits with expected versions, archive/restore confirmation, oldest-first immutable snapshot display, snapshot append, bounded in-memory search/filtering, and non-blocking duplicate warnings based on normalized URL, external posting ID with matching context, or company/title similarity. The application workspace supports active and archived lists, status/text/due-state filtering, DRAFT creation for active captured jobs, notes and next-action edits, explicit allowed status transitions with history, and archive/restore confirmation. `409` conflicts preserve unsaved form values until the owner reloads the latest server state. Status text remains truthful: creating an application does not submit it, and status changes are recorded only after deliberate owner action.

While the application is running, check `http://localhost:8080/actuator/health`. Only the health actuator endpoint is exposed, and health details are suppressed.

Install and run the frontend from a third PowerShell process, after PostgreSQL and the backend are healthy:

```powershell
cd frontend
npm.cmd install
npm.cmd run dev
```

Open the URL printed by Vite (normally `http://localhost:5173`). The frontend requests the relative path `/actuator/health`; Vite proxies that path to `http://localhost:8080`, so permissive backend CORS is neither needed nor configured. Never place secrets in `VITE_*` variables because Vite exposes those values to browser code.

### Database lifecycle

```powershell
# Stop without deleting data
docker compose stop

# Stop and remove the container/network; keep the named volume
docker compose down

```

Both commands preserve the named PostgreSQL volume. Deliberate volume deletion is omitted from routine development instructions to protect local data.

Flyway is the only schema-management mechanism. Its managed/default schema and history table are both inside `job_search_assistant`, so future unqualified migration objects resolve there rather than in `public`. The initial migration establishes only that schema foundation and creates no business tables.

### First-administrator bootstrap

Bootstrap works only while the account table is empty and is disabled by default. Start PostgreSQL, then use a temporary PowerShell process from `backend/`:

```powershell
$env:IDENTITY_BOOTSTRAP_ENABLED = 'true'
$env:IDENTITY_BOOTSTRAP_LOGIN = 'household.admin'
$env:IDENTITY_BOOTSTRAP_DISPLAY_NAME = 'Household Administrator'
$bootstrapSecret = Read-Host 'Administrator passphrase' -AsSecureString
$bootstrapPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($bootstrapSecret)
try {
    $env:IDENTITY_BOOTSTRAP_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bootstrapPointer)
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bootstrapPointer)
}
.\mvnw.cmd spring-boot:run
```

After startup succeeds, stop the process immediately with Ctrl+C. Clear the process-scoped bootstrap data and restart normally:

```powershell
Remove-Item Env:IDENTITY_BOOTSTRAP_ENABLED, Env:IDENTITY_BOOTSTRAP_LOGIN, Env:IDENTITY_BOOTSTRAP_DISPLAY_NAME, Env:IDENTITY_BOOTSTRAP_PASSWORD
Remove-Variable bootstrapSecret, bootstrapPointer -ErrorAction SilentlyContinue
.\mvnw.cmd spring-boot:run
```

Never put bootstrap values in `.env`, command history, source control, or a reusable script. A second bootstrap attempt is refused, including concurrent attempts.

### Tests and verification

From the repository root, run the complete Windows foundation check:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-foundation.ps1
```

The script resolves the repository root from its own location, so it also works when invoked by absolute path from another directory. It stops at the first failure and always runs backend fast tests, frontend quality checks, and Compose configuration validation. When Docker is available, it also runs full backend verification and the disposable PostgreSQL-backed Playwright identity suite; otherwise it reports a partial verification. Browser E2E uses generated process-only credentials, sets `SESSION_COOKIE_SECURE=false` only for local loopback HTTP, and never uses or deletes the developer database volume. That cookie override is unsuitable for any LAN-accessible or deployed environment.

```powershell
# Fast context and module-boundary tests; Docker is not required
cd backend
.\mvnw.cmd test

# Unit tests plus PostgreSQL/Testcontainers integration tests; Docker is required
.\mvnw.cmd verify

# From the repository root, validate Compose interpolation
cd ..
docker compose config

# Frontend tests, lint, strict type-check, formatting check, and production build
cd frontend
npm.cmd run test:run
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run format:check
npm.cmd run build

# Full-stack browser identity security suite; Docker and installed Chromium are required
npx.cmd playwright install chromium
cd ..
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-browser-e2e.ps1
```

GitHub Actions repeats these checks in parallel backend, frontend, PostgreSQL Compose smoke, and browser identity E2E jobs. The browser job uses a tmpfs-backed disposable database and process-only test credentials. Playwright screenshots, traces, videos, browser profiles, raw error contexts, and HTML reports are disabled or discarded because identity-flow failure artifacts could retain invitation fragments, cookies, or credentials. On failure, CI retains only sanitized text containing process/readiness state, filtered startup errors, PostgreSQL health and active-administrator count, and safe Playwright status/locator context. All Actions are pinned to immutable commit SHAs.

## Next milestone

Phase 5C begins fit-analysis presentation and review workflows over the internal deterministic result. Recovery, deletion, role changes, additional administrators, delegated access, AI, document parsing, malware scanning, URL fetching, job scraping, notifications, and application submission remain out of scope.

# Job Search Assistant

A private, self-hosted household workspace for finding jobs, evaluating fit, tailoring truthful application materials, and tracking applications.

> The system may reorganize, emphasize, and reword verified experience. It must never invent a skill, accomplishment, employer, title, certification, responsibility, education item, or metric.

## Status

Phase 1C — frontend foundation complete. The React shell connects to backend health through the local Vite proxy, marks all unfinished product areas as upcoming, and preserves the project's privacy and résumé-integrity promises. Authentication, business entities and APIs, and integrations remain unimplemented.

## Planned capabilities

- Separate household accounts and private candidate workspaces
- Candidate profiles containing verified career facts
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

# Permanently delete the local database volume and start fresh
docker compose down --volumes
```

Flyway is the only schema-management mechanism. Its managed/default schema and history table are both inside `job_search_assistant`, so future unqualified migration objects resolve there rather than in `public`. The initial migration establishes only that schema foundation and creates no business tables.

### Tests and verification

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
```

## Next milestone

Continue Phase 1 with repository-wide formatting and CI as separately reviewable increments. Authentication and candidate/domain behavior begin only in their documented phases; AI and job scraping remain out of scope.

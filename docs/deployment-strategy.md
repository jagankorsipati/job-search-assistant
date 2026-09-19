# Deployment Strategy

## Phase 6 release status, 2026-09-19

Implemented local DOCX downloads do not establish household deployment readiness. Local Phase 6 rendering and browser release verification are complete for the current supported DOCX boundary, but exact-commit hosted CI, target-host performance and backup/restore evidence remain independent deployment gates. Ephemeral output is not backed up as a server artifact; source storage, database and approval attribution remain sensitive. See [Phase 6 evidence](security/phase-6-verification.md).

## Environments

- **Local development:** frontend, backend, and PostgreSQL on a developer machine
- **Household deployment:** Docker Compose on a 64-bit Raspberry Pi OS host
- **Remote access:** private Tailscale network only

## Intended containers

- Reverse proxy serving the web client and forwarding API requests
- Spring Boot application
- PostgreSQL
- Optional document worker only if justified later

User documents and database data live in explicit persistent volumes outside container layers.

## Operational requirements

- Multi-architecture images supporting `linux/arm64`
- Health checks and restart policies
- CPU and memory limits appropriate for a Raspberry Pi 4
- Database migrations executed once and safely
- Encrypted, tested backups to owner-controlled storage
- Restore procedure tested before household adoption
- Configuration and secrets outside images and Git
- Log rotation and retention

## Exposure rules

- Do not expose PostgreSQL outside the Docker network.
- Do not use the Spring development server or expose internal management endpoints.
- Do not use router port forwarding.
- Bind locally/private-network first and use Tailscale for remote access.

## Delivery stages

1. Run locally on Windows.
2. Build and test ARM64 images.
3. Deploy on the home LAN with test data.
4. Validate backup, restore, isolation, and resource use.
5. Add household users.

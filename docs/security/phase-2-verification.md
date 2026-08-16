# Phase 2 identity verification matrix

This matrix is the Phase 2 release-readiness record. Browser evidence uses the real React application, Spring Boot security/session stack, Flyway, and disposable PostgreSQL. `foundation.yml` keeps backend, frontend, infrastructure, and browser jobs separate.

| Requirement / decision | Implementation | Unit / architecture evidence | PostgreSQL integration evidence | Browser E2E evidence | CI job | Limitation / residual risk |
| --- | --- | --- | --- | --- | --- | --- |
| Invite-only ADMIN/MEMBER identity (ADR-005) | `identity` lifecycle types; V2 migration | `AccountLifecycleTests`, Modulith verification | `IdentityServicesIT` | Admin creates invitation; member accepts | backend, browser-e2e | Recovery/deletion deferred |
| Argon2id and one-time bootstrap (ADR-006) | password hasher, bootstrap runner/service | `PasswordSecurityTests`, lifecycle tests | `IdentityServicesIT` | Runner bootstraps exactly once, restarts disabled | backend, browser-e2e | Pi benchmark prerequisite |
| Stateful browser auth (ADR-007) | Spring Security, Session JDBC, auth controller | controller/filter tests; secure-default context test | `AuthenticationSecurityIT` | login, restoration, rotation, cookie attributes, logout | backend, browser-e2e | TLS validation prerequisite |
| Invitations and offline screening (ADR-008) | invitation controller/services; immutable digest list | invitation and blocklist tests | `InvitationHttpIT` | fragment removal, no storage, acceptance/no auto-login, generic reuse | backend, browser-e2e | Secure private invitation transfer required |
| Owner-scoped authorization (ADR-009) | `identity::actor`; owner SQL contract | architecture/current-actor tests | `OwnerIsolationIT` | MEMBER receives 403 for ADMIN endpoint | backend, browser-e2e | Future repositories must adopt contract; no RLS |
| Member administration (ADR-010) | administration service/controller/revoker | service/controller tests | `AccountAdministrationIT` | ADMIN controls absent; two sessions revoked; reactivation needs fresh login | backend, browser-e2e | ADMIN host/database power remains |
| CSRF and generic failure | security chain/controllers | controller/filter tests | authentication/invitation HTTP tests | missing-token rejection; generic disabled/invalid results | backend, browser-e2e | Browser compromise remains |
| Minimal audit and bounded retention | audit service/retention service; V3–V5 | settings/service tests | account administration audit assertions | Identity journeys generate real events | backend, browser-e2e | Operator must monitor cleanup warnings |
| Database/Flyway security foundation | application properties; V1–V5 | context/module tests do not require Docker | `PostgreSqlInfrastructureIT` | disposable database migrates before app startup | backend, browser-e2e | Backup/restore and deployed exposure unverified |
| Supply-chain/configuration posture | wrappers, lockfile, SHA-pinned Actions, Compose loopback | deterministic quality gates | clean and retained migration checks | pinned Playwright Chromium install | all jobs | Online advisories are informational and service-dependent |

## Deferred without blocking the Phase 2 identity foundation

- Account recovery and account deletion.
- Delegated private-data access.
- Shared/distributed rate limiting and multi-instance session/rate-limit operations.
- Raspberry Pi Argon2id benchmarking.
- Production TLS and trusted reverse-proxy validation.
- Public-internet deployment hardening; household-private access remains mandatory.
- PostgreSQL row-level security.

Phase 2 can be declared complete only when backend unit and PostgreSQL integration tests, frontend checks, local browser E2E, migration regressions, and repository hygiene checks pass with no unresolved Critical or High issue in scope. Hosted CI must then repeat the four job groups after push. Production hostname, TLS, reverse proxy, Raspberry Pi performance, firewall, and restore evidence remain deployment prerequisites and are not claimed by this Windows checkpoint.

## Dependency and configuration audit checkpoint

The 2026-08-15 local checkpoint completed Maven dependency resolution, locked `npm ci`, and `npm audit` with zero reported vulnerabilities. No Critical or High issue was found in the deterministic build/configuration review. Maven has no advisory scanner configured; adding a network-backed scanner to the deterministic CI gate was intentionally avoided because advisory availability is external state. Dependency advisory review remains an operator update task rather than a claim that all upstream risk is absent.

The production JAR contains no test ownership fixture or test endpoint. All workflow action references are 40-character commit SHAs. `.env`, dependencies, builds, browser profiles, Playwright output, screenshots, traces, and video/report directories are ignored. PostgreSQL remains loopback-bound in development; only health is exposed by Actuator with details suppressed; Secure cookies remain the default; no forwarded-header trust or permissive CORS is configured; and Flyway remains the sole schema manager.

The test run emits Mockito's forward-looking warning about dynamic agent attachment on a future JDK. It is test-only, does not affect Java 21 results or the production JAR, and should be revisited when upgrading the JDK/test stack.

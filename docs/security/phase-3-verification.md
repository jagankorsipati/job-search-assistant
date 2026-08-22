# Phase 3 Verification Matrix

Status: Phase 3E complete. Phase 3 is complete: candidate profiles, truthful career facts, and one owner-scoped current base resume upload/download path are implemented and covered by backend, frontend, PostgreSQL, and real-browser verification.

| Requirement | Domain tests | API/PostgreSQL tests | Frontend tests | Browser evidence | CI evidence | Residual limitation |
| --- | --- | --- | --- | --- | --- | --- |
| Owner-scoped profile and facts | `CandidateProfileTests`, `CareerFactTests` | `ProfileApiIT`, owner predicates, admin no-bypass checks | `App.test.tsx` owner field omission checks | `profile-security.spec.ts` verifies admin/member isolation and cross-user 404s | Browser job runs all Playwright specs in one disposable environment | PostgreSQL RLS remains deferred |
| Truthfulness lifecycle | `CareerFactTests` | `ProfileApiIT` confirm/archive/restore transitions | Confirmation, archive, restore, confirmed-edit warning tests | Draft creation, attestation-gated confirmation, confirmed edit to draft, archive/restore | Foundation workflow browser job | Independent employer/school verification is out of scope |
| Optimistic locking | Domain version invariants | Stale profile/fact update and lifecycle conflicts | `409` preserves unsaved UI values and reload action | Two browser contexts verify stale fact edit conflict and authoritative reload | Backend and browser jobs | No automatic merge workflow yet |
| CSRF and session handling | Identity session tests | Missing CSRF and unauthenticated profile API checks | `401` returns UI to login | Anonymous `/profile`, anonymous API `401`, state-changing request without CSRF `403` | Browser job and backend verify | Complete account revocation remains covered by identity E2E |
| Browser privacy | Not applicable | Responses omit owners and use `no-store` | No `localStorage`/`sessionStorage`, no owner rendering/submission | Storage empty, no profile data in URL, readable cookies, or app-created IndexedDB | Browser job | Operator/browser compromise remains outside app control |
| Safe diagnostics | Not applicable | Safe API error shapes | Safe UI failure messages | Runner removes raw Playwright artifacts and retains sanitized text only on failure | Failure-only sanitized artifact upload | Backend debug logs are filtered, not retained as raw artifacts |
| Structured limits and invalid input | Domain validation | API malformed/invalid input tests | Client required/max/date validation tests | Browser invalid profile input blocks save | Frontend and browser jobs | Backend remains final authority |
| Archived history | Domain transition rules | Archive/restore API tests | Archived facts are read-only in UI | Browser verifies archived facts cannot be edited and restore returns draft | Browser job | Hard deletion and retention policy are deferred |
| Base resume storage | `BaseResumeValidationTests` filename, PDF/DOCX, size, checksum, storage containment, publish, cleanup checks | `BaseResumeApiIT`, V7 constraints, owner immutability, one-current-resume, CSRF/401/404/409, exact download bytes | Base resume empty/upload/download/replace/conflict/session tests | Browser uploads synthetic PDF, reloads metadata, downloads exact bytes, verifies admin/member isolation and stale replacement conflict | Backend, frontend, and browser jobs | Malware scanning, hard deletion, retention automation, and AV quarantine are deferred |

## Real-browser Scenarios

- Existing identity security lifecycle remains in `identity-security.spec.ts`.
- Candidate-profile lifecycle, career-fact lifecycle, cross-user isolation, optimistic conflict, CSRF/session, and browser privacy evidence live in `profile-security.spec.ts`.
- Both specs run against the real React frontend, Spring Boot backend, Spring Security, Spring Session, Flyway through V7, a tmpfs-backed disposable PostgreSQL database, and a disposable local base-resume storage root.
- Playwright is configured for one worker because the environment uses a one-time administrator bootstrap contract; each spec creates its own member data and must remain order-independent.

## Deferred Scope

- Resume extraction review, evidence/document linkage, AI-generated suggestions, job matching, generated resume content, hard deletion, retention automation, malware scanning, and Raspberry Pi deployment validation remain deferred.

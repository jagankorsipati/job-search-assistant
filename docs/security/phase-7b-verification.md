# Phase 7B verification record

Status: Phase 7B complete locally on 2026-09-19 (America/Los_Angeles). Focused tests, backend verify, full foundation verification, artifact review and disposable-resource cleanup passed. Phase 7 remains open. No staging, commit, tag or hosted-CI claim is included in this record.

## Design and scope

The owner selected both OpenAI and Anthropic Claude. [ADR-021](../decisions/ADR-021-opt-in-grounded-drafting-adapter.md) records shared transport, internal protocol extension, fixed destinations, minimum-data serialization, structured output and privacy references. [Configuration guidance](../ai-drafting-configuration.md) restricts provider/model/credential selection to administrator/operator process settings. Disabled is still the default; there is no selected default model, public drafting route, frontend, consent bypass, import, migration, SDK or dependency addition.

## Checks

| Check | Result |
| --- | --- |
| Focused drafting/configuration/module tests | 32 passed |
| Focused tailoring service/export integration tests | 25 passed |
| Backend `mvnw verify` | 126 fast tests and 97 integration tests passed |
| Full `scripts/verify-foundation.ps1` | Passed on clean unchanged-code rerun: 126 fast backend tests, 97 integration tests, Compose validation, locked frontend install, formatting/lint/typecheck/build, 85 component tests and all 7 browser tests. Initial browser timeout retained below |
| Packaged artifacts, dependency/migration diff, sensitive-data review | Passed: both protocols and disabled implementation packaged; no fake/mock/test classes, test libraries or provider SDK; same 13 migrations, no dependency/lockfile changes, no credential/private-key matches or generated files in changes; changed-document links and diff whitespace checks pass |
| Disposable resource cleanup | Passed: no verification containers, E2E volumes/networks, current-run temporary roots, Java/browser helpers or browser reports remain. Existing development PostgreSQL, unrelated Node process and older temporary directories preserved |

Focused command: `mvnw.cmd --batch-mode --no-transfer-progress -Dtest=*Drafting*Tests,ModularArchitectureTests -Dit.test=ResumeTailoringServiceIT,ResumeTailoringExportIT -Ddebug=false -Dlogging.level.org.springframework=INFO verify`. Backend command: `mvnw.cmd --batch-mode --no-transfer-progress -Ddebug=false -Dlogging.level.org.springframework=INFO verify`.

Local mock coverage runs both production wire protocols through the real bounded transport: exact outbound envelope/DTO allowlists, instruction-like text kept in user data, schema and authentication headers, valid drafts, refusals, malformed/duplicate/trailing/deep JSON, unknown/duplicate/missing evidence aliases, extra fields, unsupported/truncated variants, oversized/chunked bodies, content-type/encoding refusal, authentication/throttling/server errors, redirects, connection failure without retry, timeout before/after headers, interruption, shutdown, two-call concurrency and permit release. Maximum-size input remains within the serialized byte bound. Error bodies and synthetic credentials never become outcomes. No paid external provider calls are used.

Configuration tests cover disabled startup without resolving credentials/models or constructing transport, explicit selection of either provider, generic protocol registration, duplicate/invalid configuration and unsafe JVM retry/diagnostic/TLS settings. Test fixtures alone can reroute HTTPS destinations to loopback and use synthetic credentials; no production URL override exists.

Database-backed tests run each real adapter and assert outbound data omits actual owner/proposal/source/fact identifiers and checksums, and that proposal/source/fact state and decision history do not change. Existing tests retain selected-confirmed-only facts, missing/foreign/changed evidence, alias/version binding, before/after stale checks, module boundaries, approval and export independence. No provider result can authorize a DOCX download.

## Existing advisories and limitations

Backend verification passes despite the previously recorded closed-test-database scheduler warnings and fork JVM shutdown timeout message. These predate Phase 7B and are not a drafting test failure. Sensitive synthetic request/credential markers are absent from the focused, backend and foundation logs.

The unchanged frontend lockfile still reports two moderate development-tool advisories, `vitest` and `@vitest/mocker`, associated with [GHSA-82fw-gwwq-j7x9](https://github.com/advisories/GHSA-82fw-gwwq-j7x9). `npm audit` reports no high/critical findings and suggests Vitest 4.1.11. These were already recorded in Phase 7A; dependency maintenance remains separate and no upgrade was applied.

The initial foundation browser run timed out at the existing job/application test's `Save application notes` click (`job-application-security.spec.ts:384`), after opening and filling the notes form. The other six browser cases passed, including all tailoring cases. Application frontend and browser test sources are unchanged in 7B. The committed application selection refresh can close an edit form; this is a possible race explanation, not a proven diagnosis. A clean unchanged-code full foundation rerun passed all seven browser cases (job/application in 19 seconds; full browser suite in 1.4 minutes). This records a remaining intermittent browser-workflow concern, not a claim that the first failure was fixed. No unrelated UI/test change or automatic test retry was introduced.

Temporary verification logs and audit output were removed after recording this sanitized evidence. Standard ignored build/test outputs remain outside the change set. Package inspection found the same 13 application migrations, both real protocols and the disabled implementation, with no test-only mock/fake provider or provider SDK. All 23 changed/new files are source, tests, configuration or documentation; none is a generated artifact.

No real provider credential, paid call or live user data was used. Local mocks verify the documented protocol and failure contract, not live model availability or factual quality. Deployment model qualification, current account/region retention and cost decisions, exact-data preview/consent and cancellation UI, transactional import, claim validation and Phase 7 security/browser/release work remain open. Structure and known citations do not prove factual support; selected text is not anonymized.

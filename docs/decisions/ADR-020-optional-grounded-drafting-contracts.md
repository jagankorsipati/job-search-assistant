# ADR-020: Optional grounded drafting contracts and privacy boundaries

## Status

Accepted for Phase 7A. Phase 6 is released as `v0.6.0-truthful-tailoring`. AI contracts exist; live AI drafting is not available. Phase 7 remains open.

## Decision and scope

Use one internal `GroundedDraftingProvider.suggest(Request)` port in the Integrations `drafting` named interface. Documents depends only on this contract. The only runtime implementation always returns `DISABLED`, without a configuration switch. A deterministic test-only fake copies the first selected fact. Neither has network access or a model dependency. There is no public endpoint, frontend, automatic proposal creation, migration, new dependency, provider/model selection, credential, or pricing policy.

The sole task suggests replacement wording for one selected paragraph using explicitly selected owner-confirmed career facts. Selecting an existing manual proposal selects its `originalText`: this remains user-supplied paragraph text, **not checked against the source file**. Preparation requires the expected proposal version and each explicitly selected fact's expected version. Selection is independent of existing proposal evidence links; those links are never automatically included. Job context is unnecessary for this task and has no contract field.

## Minimum-data and ownership boundary

Only the dedicated immutable `Request` DTO can cross the provider boundary: a fixed task enum with application-owned bounded instructions, selected paragraph text, and 1–10 selected fact contents labeled `E1`–`E10` in selection order. Lists are defensively copied. Database entities are never serialized to a provider. No full résumé bytes, unrelated profile/history, account IDs, login details, storage paths, checksums, approval tokens or credentials enter the DTO. Free text can still contain names, contact details or other personal data; local aliases do **not** anonymize it.

Documents derives the owner from `identity::actor` and extends its existing owner-scoped tailoring repository with a read-only confirmed-fact content/version projection. Reads include owner and `CONFIRMED` predicates; missing, foreign, draft and archived facts use the same safe not-found failure. ADMIN has no bypass. The alias-to-record/version map stays in a private, immutable in-memory binding alongside owner, exact proposal version/update time/status and source ID/version/checksum. It is not an approval token and never crosses the provider boundary.

Preparation has no transmission side effect. The internal invocation seam currently reaches only the disabled bean; there is no runtime caller. A future live invocation must first show the exact outgoing paragraph and selected fact contents, explain personal-data exposure and the chosen provider's retention/processing policy, then require a deliberate per-request consent action. Consent must bind the exact preview and expire on edits, selection changes or stale source state. Configuration alone is not consent. No consent UI or live transmission is implemented here.

## Untrusted response and integrity boundary

The response is either bounded draft text plus request-local evidence aliases, or a typed failure: `DISABLED`, `UNAVAILABLE`, `TIMEOUT`, `CANCELLED`, `REFUSED`, `INVALID_RESPONSE`. The validator rejects null/blank/oversized text, missing support, null/unknown/duplicate aliases and excessive reference counts. Arbitrary provider exception details are discarded without logging and become `UNAVAILABLE`. Future wire adapters must reject malformed schemas, unknown fields and oversized raw responses before allocating/parsing unbounded data, mapping failures to `INVALID_RESPONSE`.

These are structural checks. They **cannot prove every claim is factually supported**, nor detect every misleading upgrade in seniority, responsibility, proficiency, duration or impact. Even a structurally valid response remains untrusted proposed wording. No result changes a proposal, confirms a fact, records approval, authorizes export or submits an application. Claim-level integrity validation and explicit human review remain later work; Phase 6's manual attestation and target-bound export checks still apply.

Read-only freshness checks before and after invocation reject changed/missing proposal, source or selected evidence and owner changes. Binding survives with the prepared request only in memory; there is no cache, persistence or retry. These checks are **not** an atomic import authorization: a future import must reacquire owner-scoped locks and compare the exact binding in the same transaction as any deliberate draft update. It must invalidate prior review/approval as the manual update path does. No import operation exists in 7A.

## Instructions, limits and lifecycle

Trusted instructions are fixed application text from the task enum; paragraph and fact fields are separate untrusted data, including instruction-like text. Future adapter prompt delimiters are guidance, not a complete injection defense. No provider tools, browsing, file access, code execution or secret access may be offered. Job text, if separately justified and explicitly selected in a later contract, is also untrusted data.

Limits count Java UTF-16 code units: paragraph 4,000, each fact 2,000, at most 10 facts (24,000 total selected text), output 4,000, and at most one reference per selected alias. Nothing is silently truncated. Instructions are fixed and aliases bounded. These are data bounds, not tokenizer or billing estimates.

The future transport deadline is 30 seconds total, including connection and response reading. A live adapter must enforce it, honor cancellation by aborting transport, preserve interruption, discard late output and return `TIMEOUT`/`CANCELLED` without retries. The internal seam checks thread interruption before and after invocation. There is no executor/transport cancellation machinery or live timeout enforcement in this contract-only phase; neither current implementation blocks. Adapter cancellation and wire-size tests are required before live integration.

Never log prompts, responses, selected text, evidence, exception payloads or sensitive mappings. Request/evidence/draft/context diagnostic strings are redacted; there is no payload telemetry, audit record, cache or persistence. Keep the operation's in-memory context only for its lifecycle and discard it on completion/cancellation. Manual operations have no dependency on the drafting provider.

## Remaining decisions and release work

- Select and review an adapter/provider/model only in a later milestone, including processing location, retention, training use, credentials, costs and rate limits; no hardcoded pricing here.
- Design exact-data preview/consent, cancellation UI, safe failure presentation and explicit suggestion import with transactional freshness revalidation.
- Design claim-level integrity validation without treating alias checks or a model's own assurance as proof; retain owner attestation and separate export authorization.
- Verify bounded wire parsing, transport deadlines/cancellation, injection resistance, no tools, privacy-safe diagnostics and deployment configuration before enabling live drafting.
- Complete Phase 7 browser/integration/security and release gates separately. 7A verification is recorded in [the verification record](../security/phase-7a-verification.md); no Phase 7 release is implied.

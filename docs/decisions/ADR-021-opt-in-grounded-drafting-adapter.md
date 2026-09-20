# ADR-021: Administrator-controlled grounded drafting adapters

## Status

Accepted. The owner selected both OpenAI and Anthropic Claude, with provider/model control restricted to the administrator. Phase 7B implements internal adapters; Phase 7 remains open. There is no public or manual-workflow caller and live user drafting is not available.

## Decision

Keep `GroundedDraftingProvider` and Documents' owner-scoped preparation, private revision bindings and before/after freshness checks unchanged. Inside the closed Integrations module, a shared bounded Java 21 HTTP transport delegates wire encoding, authentication and decoding to a small package-private `DraftingProtocol`. Reviewed protocol beans supply fixed destinations. Adding another provider requires code, tests and privacy review, but no change to Documents or the public contract. No SDK or dependency is added.

Supported protocols are OpenAI Responses at `https://api.openai.com/v1/responses` and Anthropic Messages at `https://api.anthropic.com/v1/messages` with `anthropic-version: 2023-06-01`. One is active per process. Default selection remains disabled; explicit administrator/operator process configuration supplies enablement, provider, model and credential. No model or pricing is hardcoded. Enabled startup validates configuration without probing the provider. Disabled startup does not resolve model/credential settings or construct an HTTP client.

Administrator control means deployment configuration in this milestone, not a new application ADMIN screen or endpoint. End users cannot select a provider, model, credential or URL. ADMIN still has no cross-owner data access. There is no dynamic configuration, per-user selection, fallback or retry. See [configuration guidance](../ai-drafting-configuration.md).

## Transport and trust boundaries

- Use direct HTTPS, normal TLS/hostname validation, HTTP/1.1 and no redirects, cookies, proxy credentials or request-supplied destinations. Mock destinations exist only in test sources.
- Bound serialized requests to 256 KiB, raw responses to 128 KiB, connection establishment to five seconds, total work to 30 seconds and concurrent requests to two per process. Reject excess capacity immediately without a queue. Bound streamed/chunked bodies before parsing; reject compression and non-JSON content types. Caller interruption cancels the HTTP operation/body subscription and preserves the interrupt flag. Shutdown aborts transport; every path releases its permit.
- Require JVM startup flag `-Djdk.httpclient.disableRetryConnect=true` when enabled. Reject all-method retries, HTTP/TLS diagnostics and disabled hostname verification. Do not mutate global production JVM properties. This requirement avoids the JDK's default connection retry behavior and duplicate-charge risk.
- Serialize only the approved immutable DTO (`task`, `paragraph`, `evidence`, each evidence item containing `alias` and `content`) into untrusted user input. Trusted task instructions live in OpenAI `instructions` or Claude `system`. No job context, domain object, database/account ID, internal revision mapping, checksum, approval token, storage path or credential enters that content. Authentication is confined to the required HTTP header. Selected text may itself contain personal data; aliases do not anonymize it.
- Request structured JSON with exactly `text` and `evidenceAliases`. Use the providers' common schema subset and enforce all length/count/alias limits locally. Offer no tools, browsing, files, code execution or secret access. Prompt structure cannot prevent all prompt injection.
- OpenAI sends `store: false`, `background: false`, `stream: false`, `truncation: disabled` and a 4,096 output-token cap. Claude sends `stream: false` and `max_tokens: 4096`. No conversation, previous-response, prompt-cache or tool controls are provided. A token cap may truncate otherwise valid text; truncation refuses rather than retrying.
- Accept one completed assistant text result (OpenAI may precede it with an empty reasoning marker). Refuse incomplete, tool, multi-result and unsupported variants. Reject duplicate JSON keys, trailing content, excessive nesting, malformed schema, extra draft fields, missing/unknown/duplicate aliases and oversized drafts. Provider metadata is never evidence.
- Map refusal to `REFUSED`, malformed/truncated/unsupported output to `INVALID_RESPONSE`, deadline expiry to `TIMEOUT`, interruption/cancellation to `CANCELLED`, and authentication, throttling, capacity and provider/transport failures to `UNAVAILABLE`; retain `DISABLED`. Discard error bodies without reading or exposing their messages. No sensitive logging, cache, persistence, import or retry is added.

Valid structure and citations cannot prove factual support. Results remain untrusted wording and cannot mutate facts/proposals, grant approval or authorize export. No migration, public drafting endpoint, frontend or consent bypass is introduced. There are no live user-data calls in this phase; tests use synthetic data and a local mock server only.

## Provider documentation and privacy

Official references checked on 2026-09-19:

- OpenAI [Responses API](https://developers.openai.com/api/reference/java/resources/responses/methods/create), [structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs), and [API data controls](https://developers.openai.com/api/docs/guides/your-data).
- Anthropic [Messages API](https://platform.claude.com/docs/en/api/messages/create), [API versioning](https://platform.claude.com/docs/en/api/versioning), [structured outputs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs), and [API retention](https://platform.claude.com/docs/en/manage-claude/api-and-data-retention).
- Anthropic [commercial-data retention](https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-my-organization-s-data) and [model-training policy](https://privacy.claude.com/en/articles/7996885-how-do-you-use-personal-data-in-model-training).
- Java 21 [networking properties](https://docs.oracle.com/en/java/javase/21/core/java-networking.html) and [HTTP client module](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html).

OpenAI API data is not used for training by default unless opted in; default abuse-monitoring logs may retain customer content for up to 30 days, subject to exceptions. `store: false` disables response storage but is not a zero-retention guarantee; model, caching and account controls also matter. Anthropic commercial API inputs/outputs are normally deleted within 30 days, with policy, legal and contractual exceptions; training is not enabled by default without applicable opt-in/agreement. Structured-output schema caching and account/model eligibility can affect retention controls. The schema here is static and contains no selected text. Neither adapter promises zero retention. Administrators must assess current provider/account/model terms before future transmission.

## Verification and remaining work

The [Phase 7B verification record](../security/phase-7b-verification.md) records local-mock transport tests, ownership/no-mutation regressions, module boundaries, backend and full foundation checks, artifact inspection and cleanup. Acceptance of this design is separate from those implementation gates.

Remaining Phase 7 work: select and qualify deployment model IDs and processing/cost terms; exact-data preview, explicit per-request consent and cancellation UI; deliberate transactional suggestion import with locked freshness checks; claim-level integrity validation; security/browser verification and release work. Enabling an adapter alone never authorizes transmission of user data.

# Optional grounded drafting configuration

Phase 7B supports internal OpenAI and Anthropic Claude adapters. AI is disabled by default. There is no drafting endpoint, frontend or production/manual-workflow caller; live user drafting is not available. Configuration alone does not provide consent or authorize transmitting user data. See [ADR-021](decisions/ADR-021-opt-in-grounded-drafting-adapter.md) and the [verification record](security/phase-7b-verification.md).

## Administrator control

Only the administrator/operator controlling the backend process configures an adapter. Application users have no provider/model setting. This phase adds no application ADMIN settings UI/API and grants no ADMIN ownership bypass. One provider and model are active per process; changing them requires a controlled restart. There is no automatic fallback.

| Environment variable | Disabled default | Enabled requirement |
| --- | --- | --- |
| `AI_DRAFTING_ENABLED` | `false` | Exactly `true` to opt in |
| `AI_DRAFTING_PROVIDER` | Empty | `openai` or `anthropic` (Claude) |
| `AI_DRAFTING_MODEL` | Empty | Administrator-selected model ID; 1–128 ASCII letters/digits/period/underscore/colon/hyphen, starting with a letter or digit |
| `AI_DRAFTING_API_KEY` | Empty | Credential for that provider, supplied by a secret manager or protected process environment; 1–4,096 printable non-whitespace ASCII characters |

Corresponding Spring properties are `ai.drafting.enabled`, `ai.drafting.provider`, `ai.drafting.model` and `ai.drafting.api-key`. Never put credentials in repository files, `.env`, examples, command-line arguments/history, logs or chat. Protect process environment access and deployment diagnostics. No credential is needed for tests or normal disabled operation.

When enabled, start the backend JVM with `-Djdk.httpclient.disableRetryConnect=true`. Keep `jdk.httpclient.enableAllMethodRetry` unset or `false`. These are JVM startup properties, not Spring settings; set them before HTTP-client initialization. Configuration refuses HTTP/TLS diagnostics (`jdk.httpclient.HttpClient.log`, `jdk.internal.httpclient.debug`, `javax.net.debug`) and disabled hostname verification. Normal disabled startup requires none of these settings and does not resolve model/key properties. Invalid enabled configuration fails with `invalid_ai_drafting_configuration` without echoing settings or chaining sensitive property-resolution exceptions.

There is no default model or pricing table. Choose a model documented to support the selected API's structured output and supported single-answer response form. Configuration validates syntax only, without a paid network probe or capability discovery. Unsupported models/configurations fail safely when called; they do not trigger fallback. Reassess current provider retention, training, region, account and model conditions using the official links in ADR-021 before enabling any future user-facing caller.

## Bounds and response behavior

Production destinations are fixed HTTPS URLs in reviewed protocol code; there is no base-URL setting, redirect following or proxy routing. New providers require a package-private `DraftingProtocol` implementation, bean registration, local transport tests and privacy review. The public drafting interface and Documents ownership logic stay unchanged. Test-only HTTP client wrappers reroute the fixed destinations to loopback; they are absent from production packages.

The shared transport limits requests to 256 KiB, responses to 128 KiB, connection establishment to five seconds, total work to 30 seconds and simultaneous calls to two per process. Busy calls return `UNAVAILABLE` immediately. Both APIs cap generation at 4,096 output tokens, which does not guarantee room for every valid 4,000-character draft. Truncated results are invalid. Requests contain at most a 4,000-character paragraph and ten selected facts of at most 2,000 characters each; character limits count Java UTF-16 units. There are no automatic retries, caches, queues or persisted results.

Refusals, invalid output, timeout, cancellation, disabled mode and unavailability use existing typed failures. Authentication errors, throttling and provider failures return safe `UNAVAILABLE`, without raw messages. Cancellation uses caller interruption and aborts the HTTP operation; shutdown aborts owned transport. Do not enable wire/TLS logging, capture prompts/responses in telemetry or log selected text, credentials or internal mappings.

Selected free text can contain personal data and is not anonymized. `store: false` on OpenAI is not a zero-retention promise. Claude's normal API retention and account-specific exceptions still apply. Structured output and known aliases are structural checks, not proof that every claim is supported. Manual fact, proposal, approval and export workflows remain independent of AI.

## Remaining gates

Future exact-data preview must identify the configured provider/model, selected content and applicable processing information and obtain explicit per-request consent before transmission. A cancellation UI must propagate cancellation. Any deliberate import must lock and revalidate owner/proposal/source/evidence versions transactionally and remain a draft. Claim-level validation, model qualification, operational cost/privacy decisions and Phase 7 release verification remain open.

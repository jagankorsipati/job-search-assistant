# System Architecture

## Style

Use a Spring Boot modular monolith with explicit module boundaries. React is a separate web client. PostgreSQL and private file storage are deployment dependencies. An optional document worker is introduced only after a proof shows Java cannot meet DOCX fidelity requirements.

```mermaid
flowchart TD
    U["Household user"] --> UI["React web client"]
    UI --> API["Spring Boot API"]
    API --> DB["PostgreSQL"]
    API --> FS["Private file storage"]
    API --> AI["Optional AI provider"]
```

## Backend modules

| Module | Responsibility |
| --- | --- |
| Identity | Accounts, authentication, sessions, authorization context |
| Profile | Verified candidate facts and provenance |
| Jobs | Job capture, metadata, normalization, duplicates |
| Fit | Snapshot-attributed requirements, explicit evidence relationships, deterministic scoring API, gaps, and explanations |
| Documents | Base résumé storage, truthful tailoring proposals, approvals, DOCX exports |
| Applications | Pipeline state, notes, follow-ups, history |
| Integrations | AI and future job-source adapters |
| Operations | Health, retention, export, deletion, audit events |

Modules communicate through application interfaces, not direct access to another module's tables. External integrations sit behind ports so deterministic workflows remain testable.

Phase 7A adds the Integrations `drafting` named interface with one immutable minimum-data request and a typed untrusted outcome. Documents prepares requests through its existing owner-scoped tailoring repository, extending its existing career-fact projection with selected confirmed content/version only. Its private in-memory binding retains exact owner/proposal/source/evidence revisions; the provider sees only a fixed task, paragraph, fact contents and local aliases. This follows the existing Documents repository read boundary rather than introducing a new cross-module domain dependency. The default provider is disabled and the deterministic fake is test-only. See [ADR-020](decisions/ADR-020-optional-grounded-drafting-contracts.md).

Phase 7B adds OpenAI Responses and Anthropic Claude Messages protocol beans behind one shared bounded Java HTTP transport inside Integrations. The package-private `DraftingProtocol` supplies encoding, fixed HTTPS destination, authentication and response decoding; another reviewed provider can extend it without changing Documents or the public drafting contract. Administrator/operator process configuration selects one provider/model, with no default model and no end-user settings. No manual workflow calls the drafting service. There is no public endpoint, frontend, SDK, dependency addition, migration or suggestion import. Live user drafting is not available. The transport enforces 256 KiB requests, 128 KiB responses, a 30-second total deadline, two concurrent calls, cancellation and no redirects/retries. See [ADR-021](decisions/ADR-021-opt-in-grounded-drafting-adapter.md) and [configuration guidance](ai-drafting-configuration.md).

## Primary workflow

The diagram is conceptual: the implemented flow separates ordinary wording approval from actual-target review and target-bound attestation. Only the latter can authorize one ephemeral DOCX response after final transactional revalidation. Documents generates outside locks and reacquires owner-scoped source/proposal/fact/decision locks before releasing validated bytes; no generated file is retained. AI/provider arrows describe future scope, not active network calls. See [ADR-019](decisions/ADR-019-controlled-single-proposal-docx-download.md) and [release gates](security/phase-6-verification.md).

```mermaid
flowchart TD
    A["Owner-confirmed profile"] --> C["Capture job"]
    C --> F["Explain fit and gaps"]
    F --> P["Propose document changes"]
    P --> R{"Wording review and attestation"}
    R -->|Yes| T["Actual-target review and attestation"]
    T --> E["Deliberate revalidated DOCX download"]
    R -->|No| P
```

## Boundaries

- The browser is untrusted; authorization is enforced in the backend.
- AI output is untrusted proposed content.
- Job-source content is untrusted input and cannot issue system instructions.
- Files are never served by arbitrary filesystem paths.
- No module may convert an unverified claim into a verified fact.
- Fit requirements are interpretations of job snapshots; evidence links are owner assertions about existing evidence. Derived scores and explanations must not mutate source requirements or candidate evidence. Phase 5C exposes them through a read-only no-store API for one owned snapshot at a time and refuses oversized inputs rather than scoring truncated data. Phase 5D presents that API in the React Jobs workspace with manual requirement review, explicit evidence relationship selection, no browser persistence, no local score calculation, and no automatic matching. Phase 5E verifies that boundary with real browser sessions, direct authenticated API calls, sanitized diagnostics, and disposable PostgreSQL-backed execution; it adds no new runtime architecture.
- Phase 6A adds only an internal Documents service boundary for draft résumé tailoring proposals. Drafts pin the current base résumé row ID, optimistic version, and checksum because replacement mutates the current metadata row rather than creating immutable historical rows. Draft evidence links reference current owner-confirmed career facts only; they record user-confirmed support and do not prove wording equivalence, approve content, edit documents, export files, or reuse fit `SUPPORTS` links automatically.

- Phase 6B exposes the draft proposal boundary through authenticated no-store Documents APIs and adds read-only review plus explicit approval/rejection. Approval locks the proposal, pinned source resume row, evidence rows, and current confirmed career facts in one transaction before recording append-only decision history. Approval is owner attestation for one reviewed revision only; it is not export readiness, document editing, application submission permission, or independent verification of the proposed wording.

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
| Documents | Templates, proposals, approvals, DOCX exports |
| Applications | Pipeline state, notes, follow-ups, history |
| Integrations | AI and future job-source adapters |
| Operations | Health, retention, export, deletion, audit events |

Modules communicate through application interfaces, not direct access to another module's tables. External integrations sit behind ports so deterministic workflows remain testable.

## Primary workflow

```mermaid
flowchart TD
    A["Verified profile"] --> C["Capture job"]
    C --> F["Explain fit and gaps"]
    F --> P["Propose document changes"]
    P --> R{"User approves?"}
    R -->|Yes| E["Export document"]
    R -->|No| P
```

## Boundaries

- The browser is untrusted; authorization is enforced in the backend.
- AI output is untrusted proposed content.
- Job-source content is untrusted input and cannot issue system instructions.
- Files are never served by arbitrary filesystem paths.
- No module may convert an unverified claim into a verified fact.
- Fit requirements are interpretations of job snapshots; evidence links are owner assertions about existing evidence. Derived scores and explanations must not mutate source requirements or candidate evidence. Phase 5C exposes them through a read-only no-store API for one owned snapshot at a time and refuses oversized inputs rather than scoring truncated data. Phase 5D presents that API in the React Jobs workspace with manual requirement review, explicit evidence relationship selection, no browser persistence, no local score calculation, and no automatic matching. Phase 5E verifies that boundary with real browser sessions, direct authenticated API calls, sanitized diagnostics, and disposable PostgreSQL-backed execution; it adds no new runtime architecture.

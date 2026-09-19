# Architecture Decision Records

## Phase 6 release status, 2026-09-19

ADR-016 through ADR-019 remain historical decisions. ADR-019 implements ephemeral single-proposal download and supersedes ADR-018's proposed persisted export publication, not its supported-edit boundaries. Local Phase 6 release gates, including LibreOffice-container rendered evidence for the supported DOCX boundary, are complete; target Word fidelity and hosted CI for the resulting commit remain separate evidence. See [Phase 6 evidence](../security/phase-6-verification.md).

ADRs record consequential decisions and their tradeoffs. Accepted decisions are changed by a new superseding ADR rather than rewriting history.

| ADR                                                      | Decision                                                    | Status   |
| -------------------------------------------------------- | ----------------------------------------------------------- | -------- |
| [ADR-001](ADR-001-modular-monolith.md)                   | Use a modular monolith                                      | Accepted |
| [ADR-002](ADR-002-resume-truthfulness.md)                | Enforce evidence-backed résumé content                      | Accepted |
| [ADR-003](ADR-003-job-ingestion-first.md)                | Start with manual job ingestion                             | Accepted |
| [ADR-004](ADR-004-household-identity.md)                 | Use invite-only accounts with private workspaces            | Accepted |
| [ADR-005](ADR-005-identity-foundation.md)                | Establish the identity persistence and lifecycle foundation | Accepted |
| [ADR-006](ADR-006-identity-credentials-and-bootstrap.md) | Secure identity credentials, bootstrap, and invitations     | Accepted |
| [ADR-007](ADR-007-stateful-browser-authentication.md)    | Use stateful PostgreSQL-backed browser authentication        | Accepted |
| [ADR-008](ADR-008-invitation-registration-and-password-screening.md) | Complete invite-only browser registration       | Accepted |
| [ADR-009](ADR-009-owner-scoped-authorization.md)       | Enforce owner-scoped authorization                           | Accepted |
| [ADR-010](ADR-010-household-member-administration.md)  | Limit household member administration                       | Accepted |
| [ADR-011](ADR-011-candidate-profile-and-career-fact-foundation.md) | Establish candidate profile and career-fact foundation | Accepted |
| [ADR-012](ADR-012-secure-local-base-resume-storage.md) | Store owner-scoped base resumes securely on local filesystem | Accepted |
| [ADR-013](ADR-013-owner-scoped-job-and-application-foundation.md) | Establish owner-scoped job and application tracking foundation | Accepted |
| [ADR-014](ADR-014-job-requirement-and-evidence-foundation.md) | Establish job requirement and candidate evidence foundations | Accepted |
| [ADR-015](ADR-015-deterministic-fit-scoring-policy.md) | Use deterministic fit scoring policy | Accepted |
| [ADR-016](ADR-016-truthful-resume-tailoring-proposal-foundation.md) | Establish truthful resume tailoring proposal foundation | Accepted |
| [ADR-017](ADR-017-resume-tailoring-review-and-approval-integrity.md) | Require explicit resume tailoring review and approval integrity | Accepted |
| [ADR-018](ADR-018-bounded-docx-replacement-and-export-design.md) | Bound the DOCX experiment and define future anchor/export gates; local LibreOffice layout evidence recorded | Accepted |
| [ADR-019](ADR-019-controlled-single-proposal-docx-download.md) | Require resolved-target approval and final download revalidation; local rendering evidence recorded | Accepted |

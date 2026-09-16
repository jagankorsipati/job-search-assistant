# V1 Scope

V1 proves a safe end-to-end workflow for multiple household users.

## Planned V1 scope, not a current capability inventory

Current implementation has manually confirmed facts, manual requirement/evidence review, deterministic fit scoring, manual tailoring proposals and bounded single-proposal DOCX downloads. Automatic extraction/suggestions, cover-letter drafting, bulk data export/deletion and household deployment below remain planned. See [Phase 6 gates](security/phase-6-verification.md).

- Local household accounts and authenticated sessions
- Per-user candidate profile and verified career facts
- Upload and storage of a base DOCX résumé
- Job capture by pasted description or manually supplied URL
- Job metadata editing and duplicate detection
- Requirement extraction and explainable fit comparison
- Explicit display of matched evidence and missing requirements
- Truth-constrained résumé suggestions
- Before/after review and approval
- DOCX export
- Cover-letter draft based only on verified facts
- Application stages, notes, deadlines, and history
- Data export and account-data deletion
- Docker Compose deployment for a Raspberry Pi

## V1 completion criteria

A second household user can create an account, store an isolated profile, capture a job, review fit, approve a truthful tailored résumé, export it, record an application, and later delete or export their data without accessing another user's information.

## Deferred

- Automated job discovery
- Email ingestion
- Browser extensions
- Mobile applications
- Public internet hosting
- Automated application submission

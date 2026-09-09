# ADR-016: Truthful Resume Tailoring Proposal Foundation

## Status

Accepted

## Context

Base resume replacement keeps the same document metadata row while changing the row's checksum, storage key, update time, and optimistic version. A tailoring proposal therefore cannot treat the resume row ID alone as a stable historical source.

Phase 6A needs a backend-only foundation for manually proposed resume changes. It must preserve truthfulness boundaries established by candidate facts and fit analysis without adding AI drafting, resume parsing, document editing, approval, export readiness, public endpoints, or frontend workflows.

## Decision

Store resume tailoring proposals as owner-scoped drafts in the Documents module. Each proposal pins the exact source base resume identity available at draft creation: base resume document ID, source document optimistic version, and source content SHA-256 digest. Later base resume replacement does not retarget existing proposals; it makes them ineligible for a future approval milestone until a user creates or edits against the current source.

Each proposal records a bounded target section, bounded target reference, user-supplied proposed text, optional user-supplied original text, timestamps, and an optimistic version. Phase 6A does not parse the base resume, so original text is not independently verified by the system.

Supporting evidence links are explicit owner assertions that selected current confirmed career facts support the proposed wording. Links are not proof of semantic equivalence, and fit-analysis `SUPPORTS` links are not reused as automatic approval for proposal text. A proposal with no linked evidence records explicit missing evidence instead of inferring support from job requirements.

Future approval eligibility must validate current state, not only creation-time state. A draft is ineligible when its source base resume is missing, replaced, or checksum/version mismatched; when it has no supporting evidence; or when linked evidence is no longer an owned current confirmed career fact. Approval, export readiness, and generated document production remain later decisions.

All reads and writes derive ownership from the authenticated actor context. Administrators receive no bypass for another member's proposals, resumes, or evidence. Nonexistent and foreign references fail through the same safe not-found behavior. Collection reads are bounded and fail explicitly on overflow rather than silently truncating.

## Consequences

- Phase 6A can safely create, edit, list, inspect, and remove draft proposals without inventing immutable resume history.
- Proposal text, original text, evidence notes, and private evidence content remain outside logs and public APIs.
- The schema has redundant owner-aware constraints for base resume and career fact references so cross-owner rows are rejected by PostgreSQL as well as by services.
- Later milestones must add explicit before/after approval, export eligibility, document generation, and frontend review on top of this draft foundation.

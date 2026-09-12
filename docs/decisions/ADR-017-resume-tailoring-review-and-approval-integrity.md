# ADR-017: Resume Tailoring Review and Approval Integrity

## Status

Accepted

## Context

Phase 6A stores owner-scoped draft resume tailoring proposals pinned to an exact source resume row version and checksum. Phase 6B needs browser-session APIs for reviewing and approving those proposals without adding parsing, export, document editing, AI drafting, or automatic truth validation.

Approval is an owner attestation about one reviewed proposal revision. It must not silently survive source resume replacement, proposal edits, evidence edits, or candidate fact changes. It also must not claim that linked facts independently prove every proposed claim.

## Decision

Expose dedicated authenticated Documents APIs for proposal CRUD, read-only review, explicit approval, and rejection. Responses are no-store and omit owner IDs, storage keys, filesystem paths, credentials, unrelated evidence, and request payload logging.

The review response returns the draft text, target, user-supplied original text, exact source resume document ID/version/checksum, linked career-fact IDs and current versions, eligibility, and a review revision token. The token is a SHA-256 digest over the proposal ID/version, source resume ID/version/checksum, and sorted current supporting career-fact IDs/versions. It is not a secret; it is an optimistic integrity binding for the exact reviewed state.

Approval requires expected proposal version, exact review revision token, and explicit owner attestation that the proposed wording was reviewed and accurately represents their experience without unsupported claims. Approval revalidates eligibility in one transaction while locking the proposal row, source resume row, proposal evidence rows, and referenced current confirmed career-fact rows with `FOR UPDATE`. This prevents concurrent source or fact changes from committing between validation and decision persistence.

Approval persists append-only decision history with the proposal revision, hashed review token, source resume attribution, attestation flag, decision time, and supporting career-fact versions. The proposal lifecycle status changes to `APPROVED` without changing the proposal content version, so the approved revision does not become stale because of the approval itself. Any later proposal wording/evidence edit returns the proposal to `DRAFT` and increments the proposal version, making the prior approval stale on evaluation while preserving historical attribution.

Rejection is also append-only and sets lifecycle status to `REJECTED`. It records only the proposal/source revision and time. Rejection never means the candidate is unqualified.

Draft deletion remains physical only while no decision history exists. Once a proposal has approval or rejection history, delete is rejected so approval attribution is not erased accidentally. Export readiness, document generation, and application submission remain future milestones.

## Consequences

Phase 6C implementation clarification: `approval_stale` describes historical approval validity and must not permanently block a fresh attestation. Approval may proceed when that is the only eligibility reason, after the existing transactional source/evidence checks and exact reviewed-token comparison. Source changes and unavailable evidence still block approval. The owner-scoped base-resume metadata response includes its checksum so the existing creation contract can be used without downloading or parsing the document.

- A stale review, stale expected version, changed source resume, missing evidence, unconfirmed/archived/deleted/foreign evidence, or changed fact version blocks approval with a safe conflict.
- Historical approvals remain auditable without claiming they are still current after later source/evidence changes.
- The API can support future frontend review without giving the browser authority over ownership or export readiness.
- Later export work must re-evaluate current approval state before producing documents.

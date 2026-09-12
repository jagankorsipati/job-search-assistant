# Resume tailoring review workspace

Phase 6C adds `/profile/tailoring`, reached from Profile beside the base resume. The authenticated shell and shared CSRF/no-store client enforce the existing session model. Administrators use the same owner-scoped resources as members.

## Manual workflow

1. Store a base resume and confirm career facts in Profile.
2. Create a proposal with a target section/reference, proposed wording, optional original text, and explicitly selected confirmed facts. Support notes are optional. Original text is user-supplied and not checked against the document; no facts are inferred.
3. Save the draft. Source document ID, version, and checksum remain pinned across edits. Replacement requires a new proposal against the current source.
4. Load a fresh review. The workspace displays the server's before/after text, attribution, linked fact versions, and eligibility reasons. Fact content is fetched by exact linked ID and checked against the review versions before presenting approval.
5. Check the initially unchecked attestation and deliberately approve. The exact in-memory review revision and expected proposal version go to the server. Server success alone changes the displayed decision state. Approval records user attestation, not independent verification, document editing, export readiness, or submission permission.

Editing wording, notes, or evidence clears review and attestation. Saving an approved proposal returns it to draft; historical approval remains recorded. A fresh review and attestation can replace stale historical approval when current source/evidence are eligible. Source replacement, unavailable evidence, and unsupported proposals still block approval. Approval validity is a last-reviewed observation, not a live guarantee.

## Conflicts and bounds

Stale approval never retries automatically. Load a fresh review and attest again. Failed edits preserve unsaved values; reload explicitly confirms discarding them. Rejection is an explicit action and says nothing about candidate qualifications. Deletion requires confirmation and is refused when approval or rejection history exists.

Proposal lists request 100 and surface server overflow errors. Confirmed-fact selection is bounded to 100 and warns when potentially incomplete; existing links remain visible and removable. Each proposal supports at most 25 fact links. Reviews fetch only those linked facts. Selection changes unmount the old editor, discard its tokens, and ignore outstanding responses. Unsaved text is not retained across leaving the selection or workspace.

## Backend compatibility corrections

The committed creation API required a checksum unavailable in base-resume metadata. Phase 6C adds `sha256Checksum` to that existing owner-scoped response, with privacy/isolation assertions retained. No storage path or owner ID is exposed.

The committed approval service treated `approval_stale` as a permanent blocker, preventing reapproval after edits. Approval now allows that historical reason alone while still locking/revalidating current source/evidence and requiring the exact fresh token. Review continues reporting historical staleness until a successful new approval. Domain and PostgreSQL API tests cover this correction. No migration changes are needed.

## Verification boundary

Phase 6C verification passed: 85 backend fast tests, 79 PostgreSQL integration tests, all frontend format/lint/typecheck/build checks, and the full foundation verifier with four existing browser journeys. Final frontend recovery coverage brings the component/client suite to 71 passing tests. Packaged JAR inspection and diff checks passed; V1-V12 are unchanged. Disposable browser/Testcontainers resources are cleaned by their runners; developer volumes are preserved.

Focused component/client tests exercise manual CRUD, evidence choice, attestation, exact tokens, stale/failed decisions, unsaved conflicts, decision history, session expiry, old responses, and private payload boundaries. The existing four foundation browser journeys remain unchanged in scope; their base-resume checksum assertion reflects the additive metadata contract. A dedicated tailoring browser security/release milestone remains open, along with the DOCX fidelity spike and controlled export.

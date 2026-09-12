# Résumé Integrity Policy

## Non-negotiable rule

The system may reorganize, emphasize, and reword verified experience. It must never invent a skill, accomplishment, employer, title, certification, responsibility, education item, or metric.

## Fact states

- **Draft:** entered or extracted but not approved by the user
- **Confirmed:** explicitly attested by the account owner and eligible for tailoring
- **Archived:** historically retained but excluded from new suggestions

Only confirmed facts can appear in generated application documents. Confirmed does not mean the employer, school, certification authority, or application independently verified the claim.

## Allowed transformations

- Reorder confirmed bullets or skills for relevance.
- Shorten or clarify wording without changing meaning.
- Select a subset of confirmed facts.
- Combine compatible confirmed facts while retaining provenance.
- Suggest questions asking the user to verify a possible missing fact.

## Prohibited transformations

- Convert a job requirement into a candidate skill.
- Add tools based only on a job title or industry.
- Create numerical impact not present in verified evidence.
- Upgrade proficiency, responsibility, seniority, or duration.
- Present a draft, archived fact, or AI inference as fact.
- Hide a known gap by ambiguous wording.

## Enforcement

Every proposed claim carries references to one or more confirmed fact IDs. Export is blocked when a claim lacks evidence. The review screen shows additions, removals, wording changes, and evidence. User approval is recorded, but approval cannot override the requirement for confirmed evidence; the user must first update and confirm the profile fact.

Phase 6A stores only proposal drafts. A draft may explicitly have missing evidence, but it is not approvable or export-ready. Draft evidence links are user-confirmed support references to current confirmed career facts, not proof that the proposed wording is equivalent to the evidence. Because the base résumé metadata row is replaced in place, each draft records the source row ID, source version, and source checksum; replacement invalidates future approval eligibility rather than rewriting the draft's source.

## AI behavior

AI may propose wording and identify possible matches. Deterministic validation decides whether every claim is backed by eligible evidence. Missing requirements are reported separately as gaps.

Phase 6B records explicit owner approval attestation for one reviewed proposal revision. The review revision binds the proposal version, source resume ID/version/checksum, and supporting confirmed fact IDs/versions. Approval does not independently verify original text, does not prove every proposed claim from linked facts, and does not authorize export or application submission. Editing wording or evidence returns the proposal to draft, while source resume replacement or fact version/status changes make any historical approval stale on later evaluation.

Phase 6C presents that boundary in a manual before/after workspace. Original text is explicitly user-supplied and unchecked against the document. Linked confirmed facts are support assertions, not proof of every claim. Approval needs an initially unchecked attestation and a deliberate action using the exact server review revision. Edits and conflicts invalidate the browser's review; fresh review and attestation are mandatory. Stale historical approval alone does not prevent a new approval after current source/evidence validation. Rejection concerns the proposal, never candidate qualification. DOCX fidelity, controlled export, and tailoring browser security/release verification remain separate work.

# ADR-018: Bounded DOCX Replacement and Controlled Export Design

## Status

Accepted for the test-scoped experiment and future export constraints. Layout fidelity verification is incomplete; production export remains pending.

## Context and evidence

ADR-016 pins proposals to source resume ID, version, and checksum because replacement mutates the metadata row and removes the previous stored file after commit. ADR-017 approves user-attested wording and evidence versions; it does not approve a document location or layout. Current `targetReference` is free-form and `originalText` is unchecked user input. Neither may become a trusted document anchor merely because a proposal is approved.

The repository has no existing OOXML editing dependency. The experiment uses Java 21 `ZipFile`, `ZipInputStream`, namespace-aware DOM, and identity XML serialization. No dependency, runtime service, public endpoint, schema, or frontend is added. A high-level document library is not needed to test this single-paragraph operation. Avoiding whole-document object-model reconstruction also lets tests compare every unrelated part's expanded bytes exactly. This is a narrow engineering choice, not a claim that JDK APIs implement the complete OOXML standard or replace a layout engine.

Reproducible tests exercise plain and styled paragraphs, identical-format text split across runs, bullets and numbering, unrelated tables/header/footer/hyperlink/section/field/tracked-change/text-box content, and shorter/longer/page-boundary fixtures. See [fidelity matrix](../docx-fidelity-matrix.md). Structural checks have passed. Word/LibreOffice are unavailable in the inspected local environment; no rendered layout, pagination, clipping, or font-substitution claim is supported.

## Supported experimental operation

`DocxReplacementSpike.resolve(sourceBytes, expectedSha256, exactOriginal)` returns an anchor containing the exact source digest, main-body child index, and digest of the selected paragraph's serialized XML. `replace(sourceBytes, anchor, exactOriginal, replacement)` revalidates the source and anchor and returns a new byte array only after all checks pass. These methods exist only in test sources and accept no account, database, storage-key, or uploaded-file reference.

Only a unique, complete paragraph in the Transitional OOXML main document body is editable. Matching is literal and case-sensitive: no whitespace normalization, substring replacement, fuzzy matching, or cross-paragraph replacement. All Word paragraph text in package XML is considered when checking uniqueness, including unsupported locations. Zero matches and multiple matches refuse. A matching paragraph in a table, header/footer, content control, hyperlink, tracked change, or text box is not promoted into a supported target.

The target paragraph may contain preserved paragraph properties and direct runs consisting of one text node plus optional run properties. Every run must have structurally identical direct formatting. A single styled run and multiple identically styled runs are supported. Differently styled runs refuse even when their visual appearance might coincide. Replacement text is distributed using the existing text-node lengths, with the final run taking the remaining text; shorter replacements leave empty trailing runs. Runs and properties are retained. `xml:space="preserve"` is set on edited text nodes. Surrogate pairs are not split. Newlines, tabs, empty text, invalid XML characters, and text over 4,000 UTF-16 units refuse.

Paragraph/run property changes, directly hidden text, fields, hyperlinks, bookmarks, drawings, tabs/breaks, and section properties inside the target refuse. Unsupported structures elsewhere are retained without interpretation. Styles, numbering, header/footer parts, relationships, and all other unrelated expanded part bytes remain identical. Unrelated main-document subtrees remain DOM-equivalent. ZIP metadata/compression and XML lexical serialization are not byte-preserved. The original source bytes are never changed.

## Security and package bounds

- Input/output archive: 5 MiB; at most 512 entries; at most 8 MiB per expanded part and 25 MiB total; at most 2 MiB per XML part; at most 100:1 expansion ratio per entry; XML depth at most 128.
- Actual bounded reads, CRC validation, and both central-directory and local-header checks prevent trusting declared lengths alone. Duplicate, unsafe, directory, malformed, and encrypted entries refuse. No entry is extracted to its named path.
- A temporary private snapshot binds ZIP reads to the copied and hashed input bytes. It is removed in `finally`. No output is returned on refusal; exceptions contain fixed codes without original parser errors or content. The spike has no logging.
- DTDs/entities, external schema/stylesheet resolution, XInclude, and external non-hyperlink relationships refuse. External hyperlink relationships are preserved as inert data; nothing fetches or activates them.
- Macro, embedded-object, ActiveX, signature, attached-template, and alternative-content import cases covered by the package checks refuse. Root document relationship, ordinary DOCX main content type, and internal relationship targets must exist. Unknown untouched parts are not executed. This is bounded structural validation, not full OOXML schema validation, malware scanning, or certification of arbitrary uploaded packages.

## Required future anchoring workflow

Resolve an exact location from validated source bytes, display the extracted paragraph, its surrounding context, section, and formatting limitations to the owner, and ask the owner to confirm that location. The experiment's body index is meaningful only within that exact source digest; it is never a stable anchor after replacement or resaving. A future persisted anchor must include policy version, source ID/version/digest, part URI, paragraph path/index, paragraph digest, and exact extracted text (or its digest alongside the reviewed text).

Bind that resolution to the proposal's optimistic revision and approval review token. Existing Phase 6B/6C approvals do not include a resolved anchor and must not silently become export approvals. If resolution changes the target, original text, proposed text, or evidence, update the proposal revision and require fresh review and attestation. Even unchanged wording needs a new review of the resolved location before a later export milestone can treat the anchor as owner-confirmed. Do not rewrite old approval attribution.

## Required future export transaction

1. Derive owner from the current actor without an ADMIN bypass. Lock the proposal, current source resume, links, referenced facts in a consistent order, and relevant decision state. Require current lifecycle `APPROVED`, the exact latest applicable approval decision, an anchor-aware reviewed revision, and unchanged confirmed evidence versions. A historical approval alone, or a later rejection, is insufficient; the current generic eligibility helper alone is not an export gate.
2. Read a bounded source-byte snapshot while source replacement is excluded by the row lock. Verify its digest against both source metadata and approval attribution. Refuse missing bytes, source replacement, mismatched anchor, or changed evidence. Do not assume a row ID identifies historical bytes.
3. Generate a separate temporary candidate output from that snapshot. Never overwrite or republish under the uploaded source storage key. Keep expensive transformation/rendering outside database locks; discard output on every failure.
4. Reacquire the same owner-scoped locks and revalidate proposal version, resolved anchor, latest decision identity/lifecycle, source metadata/digest, and fact IDs/versions before atomically publishing a distinct export record/file. Preserve exact source, approval, anchor policy, and output digests. Coordinate file publication/rollback cleanup with the existing storage transaction conventions. A separate freshness check is needed when retrieving an old export; a generated file cannot promise continuing approval after later edits.

Approval continues to mean user attestation, not proof of claims, layout fidelity, export readiness, or permission to submit an application. No part of this design authorizes automatic submission.

## Remaining decisions and gates

Run the reproducible fixtures through an available local renderer, record exact renderer/build and actual font substitutions, and inspect every before/after page. Compare the target Word environment separately before making Word-fidelity claims. Longer text can wrap, grow tables or lists, move page/section boundaries, and change page count even when OOXML is preserved. Choose explicit preview/layout acceptance and refusal criteria before controlled export. Keep export schema/endpoints, anchor confirmation UI, retention/download semantics, renderer isolation/timeouts, and tailoring browser security/release verification for later work.

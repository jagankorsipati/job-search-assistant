# DOCX Fidelity Spike Verification

## Phase 6F inspection, 2026-09-14

No subsequent reproducible rendering evidence or installed Word/LibreOffice renderer was found. The browser release spec inspects actual downloaded bytes structurally, not visually. Renderer/font versions, page counts, shorter/longer reflow and page-boundary defects remain unverified. Phase 6 stays open; see [release verification](security/phase-6-verification.md). Historical phase results below are not evidence for the resulting Phase 6F commit.

## Scope and reproducibility

Phase 6D implements a test-scoped byte-snapshot replacement experiment. No production document is read or edited. Fixtures are generated from synthetic OOXML in `DocxSpikeFixtures`; no binary fixtures or generated exports/renders are committed.

From `backend`, run:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=DocxReplacementSpikeTests' test
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=DocxReplacementSpikeTests' '-Ddocx.spike.fixtures=true' test
```

The optional second command generates 21 files under ignored `backend/target/docx-spike`: before, short, and long variants for seven supported fixture families. Each run rebuilds synthetic inputs. Java 21 ZIP/XML APIs are the only implementation dependencies. Fixtures request Arial 11 pt through document defaults; styled runs and heading properties provide explicit formatting controls. Font availability/substitution is not established without rendering.

## Structural evidence

| Fixture or check | Expected result | Observed structural evidence | Layout evidence |
| --- | --- | --- | --- |
| Plain paragraph | Unique whole-paragraph replacement | Exact replacement text; source bytes unchanged | Not rendered |
| Styled single run | Keep paragraph style and run properties | Target subtree equal after restoring text; properties retained | Not rendered |
| Text split across identical styled runs | Distribute text; retain all runs | Short, long, literal XML characters, whitespace and Unicode round-trip | Not rendered |
| Differently styled runs | Refuse | `mixed_run_formatting`; no replacement bytes | Not applicable |
| Repeated text, including unsupported locations | Refuse ambiguous resolution | `ambiguous_match` | Not applicable |
| Zero exact whole-paragraph matches | Refuse | `no_match` | Not applicable |
| Bullets and decimal numbering | Edit body paragraph only | `numPr` and numbering part retained exactly | Indentation/wrapping not rendered |
| Unrelated table | Preserve; edits inside table refuse | Main-document subtree equal; `unsupported_target` for table edit | Row height/width not rendered |
| Headers and footers | Preserve; edits there refuse | Part bytes identical; unsupported-location/ambiguity tests | Placement not rendered |
| Hyperlink | Preserve inert relationship and subtree; target edit refuses | Relationship bytes unchanged; external resource never requested | Link appearance not rendered |
| Section breaks | Preserve untouched; section-bearing target refuses | Section subtree equal; target refusal | Page/section transitions not rendered |
| Tracked insert/delete/property changes | Preserve unrelated; relevant edit refuses | Preserved subtree and fixed-code refusals | Revision display not rendered |
| Simple/complex fields, text box, content control, bookmark, tab/break | Preserve unrelated; relevant edit refuses | Subtree preservation or explicit unsupported/ambiguous refusal | Field recalculation/shape placement not rendered |
| Short and long replacements near page boundary | Structural replacement only | Filler paragraphs and trailing sentinel retained | Pagination, overflow and page count unverified |
| Source mismatch or changed location/digest | Refuse | `source_changed`/`stale_anchor` | Not applicable |
| Failed replacement | No export emitted | Caller export path absent; original file bytes unchanged | Not applicable |
| Archive and XML limits | Refuse | Input, entry count, expanded part/total, XML size, ratio and depth tested | Not applicable |
| Encryption, truncation, CRC, unsafe names, malformed XML | Refuse | Fixed errors with no document content/cause | Not applicable |
| Entities, XInclude, active content, external resources | Refuse relevant package | Hardened parser/relationship/package checks | No resources activated |

Preservation means expanded bytes for unrelated package parts and DOM equivalence for unrelated main-document subtrees. It does not mean identical ZIP bytes, identical XML serialization, or identical rendered pages. Empty trailing runs after shorter replacements are intentional.

## Renderer availability and limits

Verification resumed on 2026-09-13: the complete foundation verifier passed with 98 backend fast tests, 90 PostgreSQL integration tests, 85 frontend tests, all frontend quality/build checks, and all four existing browser journeys. A new component regression verifies that an approval conflict clears review/attestation without retrying or reporting success. The initial browser startup exposed Windows PowerShell treating expected pre-migration query stderr as a terminating error; bounded bootstrap polling now treats that query failure as not ready. The full rerun passed after this fix. Maven reported a test-JVM shutdown timeout after successful test execution; no test cases failed. The developer servers were paused for verification to avoid port and dependency-file conflicts.

The manually downloaded synthetic plain-paragraph export was also inspected read-only: the expected replacement matched, all eight package entry names were retained, all XML parsed with external resolution disabled, unrelated parts were byte-identical, and the main document was structurally equivalent after undoing only the intended text replacement. This is not rendered-layout evidence. No renderer was installed, no real candidate content was used for this comparison, and Phase 6F's dedicated tailoring browser journey remains pending.

Phase 6E reuses this same engine in the internal Documents module. Its HTTP integration test compares the complete returned DOCX bytes against the fixture engine's output and verifies that the stored original is unchanged. This is structural/output evidence only. The owner reconfirmed that no renderer is available; representative HTTP exports have not been rendered. Phase 6E rendering verification therefore remains incomplete alongside Phase 6D.

Phase 6E automated verification passed on 2026-09-12: 98 backend fast tests (including the 13 spike cases), 90 PostgreSQL integration tests (including export races and V12-to-V13 upgrade), 84 frontend tests, format/lint/typecheck/build, and all four existing foundation browser journeys. An initial overnight run lost a connection in an existing Applications test; a fresh complete foundation rerun passed. Packaged-JAR inspection confirmed the runtime engine and V13 while excluding fixture classes. Existing migrations and dependency manifests are unchanged. These results do not replace the still-missing rendering evidence or the pending Phase 6F browser export journey.

On 2026-09-11, inspection found no `soffice`/`libreoffice` command, LibreOffice installation in standard Windows program directories, Word COM registration/standard Word executable, or local renderer Docker image. Available local images were PostgreSQL, Testcontainers cleanup, and hello-world. The document skill supplies a Python rendering wrapper, but a wrapper is not a layout renderer. No DOCX was rendered and no page image was visually inspected. No renderer or font version can therefore be reported as tested.

**Fidelity verification is incomplete. Phase 6D must remain open.** Structural evidence supports a narrow candidate approach only. Once a local renderer is available, render all 21 synthetic files, record renderer/build, OS, actual fonts and substitutions, inspect every page for clipping/overlap/wrapping/section/header/footer changes, and record before/after page counts. A second comparison in the target Word version is required before claiming Word fidelity. Do not treat browser HTML or extracted text as DOCX layout evidence.

The historical export constraints and approval/anchor implications are recorded in [ADR-018](decisions/ADR-018-bounded-docx-replacement-and-export-design.md). ADR-019 implements ephemeral controlled export. Rendered-layout and final tailoring release verification remain open; consult the dated Phase 6F status above instead of interpreting historical milestone exclusions as current capability limits.

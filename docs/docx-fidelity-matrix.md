# DOCX Fidelity Spike Verification

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

On 2026-09-11, inspection found no `soffice`/`libreoffice` command, LibreOffice installation in standard Windows program directories, Word COM registration/standard Word executable, or local renderer Docker image. Available local images were PostgreSQL, Testcontainers cleanup, and hello-world. The document skill supplies a Python rendering wrapper, but a wrapper is not a layout renderer. No DOCX was rendered and no page image was visually inspected. No renderer or font version can therefore be reported as tested.

**Fidelity verification is incomplete. Phase 6D must remain open.** Structural evidence supports a narrow candidate approach only. Once a local renderer is available, render all 21 synthetic files, record renderer/build, OS, actual fonts and substitutions, inspect every page for clipping/overlap/wrapping/section/header/footer changes, and record before/after page counts. A second comparison in the target Word version is required before claiming Word fidelity. Do not treat browser HTML or extracted text as DOCX layout evidence.

The proposed export constraints and approval/anchor implications are recorded in [ADR-018](decisions/ADR-018-bounded-docx-replacement-and-export-design.md). Controlled export and tailoring browser security/release verification remain pending.

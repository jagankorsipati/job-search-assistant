# DOCX Fidelity Spike Verification

## Phase 6F rendered verification, 2026-09-19

The previous 2026-09-14 status below remains historical: no local desktop Word or LibreOffice install exists. For this release gate, a disposable Docker renderer image was built from Debian 12.11 packages and used only with synthetic documents, no network during conversion, a temporary LibreOffice profile, bounded CPU/memory, and read-only input plus write-only output mounts.

Renderer evidence:

- Image: `job-search-assistant-docx-renderer:phase6`, local manifest `sha256:ab579d62a964c22bba8f349602779578e8a934380ae4151af23f8f02ee584956`, based on `debian:12.11-slim@sha256:b1a741487078b369e78119849663d7f1a5341ef2768798f7b7406c4240f86aef`.
- Renderer: LibreOffice `7.4.7.2 40(Build:2)` and Poppler `pdftoppm 22.12.0`.
- Fonts: fixture documents request Arial; `fc-match Arial` resolved to `LiberationSans-Regular.ttf` (`Liberation Sans Regular`). Available relevant fonts included Liberation Sans, Liberation Sans Narrow, DejaVu Sans, DejaVu Sans Mono, and OpenSymbol.
- Host: Windows development machine with Docker Desktop Linux containers; conversion containers ran with `--network none --memory 1g --cpus 2`.
- Visual inspection: every rendered PNG page for the 21 synthetic fixture outputs and four actual HTTP exports was inspected from temporary contact sheets. No clipping, overlap, missing target text, broken bullets/numbering, missing headers/footers, unintended blank pages, or unusable pagination was observed. Longer page-boundary replacements reflowed to two pages as expected.
- Limitation: this is LibreOffice-container evidence, not Microsoft Word fidelity. A target Word-version comparison remains a separate deployment/release confidence check before claiming Word-identical layout.

## Scope and reproducibility

Phase 6D implements a test-scoped byte-snapshot replacement experiment. No production document is read or edited. Fixtures are generated from synthetic OOXML in `DocxSpikeFixtures`; no binary fixtures or generated exports/renders are committed.

From `backend`, run:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=DocxReplacementSpikeTests' test
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=DocxReplacementSpikeTests' '-Ddocx.spike.fixtures=true' test
```

The optional second command generates 21 files under ignored `backend/target/docx-spike`: before, short, and long variants for seven supported fixture families. Each run rebuilds synthetic inputs. Java 21 ZIP/XML APIs are the only implementation dependencies. Fixtures request Arial 11 pt through document defaults; styled runs and heading properties provide explicit formatting controls. On 2026-09-19, the renderer substituted Liberation Sans for Arial.

Representative actual HTTP exports are generated through the real resolved-review, target-bound approval, and export endpoints by:

```powershell
cd backend
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=ResumeTailoringExportIT' '-Ddocx.export.fixtures=true' test
```

This writes ignored synthetic outputs under `backend/target/docx-http-export`. The generated HTTP export checksums matched the corresponding engine fixture outputs for `plain-short`, `plain-long`, `page-boundary-short`, and `page-boundary-long`; the export test also verifies no-store, `nosniff`, content type, attachment filename, and unchanged stored source bytes.

## Structural evidence

| Fixture or check | Expected result | Observed structural evidence | Layout evidence |
| --- | --- | --- | --- |
| Plain paragraph | Unique whole-paragraph replacement | Exact replacement text; source bytes unchanged | Before/short/long each 1 page; no clipping or overlap |
| Styled single run | Keep paragraph style and run properties | Target subtree equal after restoring text; properties retained | Before/short/long each 1 page; heading styling retained |
| Text split across identical styled runs | Distribute text; retain all runs | Short, long, literal XML characters, whitespace and Unicode round-trip | Before/short/long each 1 page; bold split-run rendering retained |
| Differently styled runs | Refuse | `mixed_run_formatting`; no replacement bytes | Not applicable |
| Repeated text, including unsupported locations | Refuse ambiguous resolution | `ambiguous_match` | Not applicable |
| Zero exact whole-paragraph matches | Refuse | `no_match` | Not applicable |
| Bullets and decimal numbering | Edit body paragraph only | `numPr` and numbering part retained exactly | Bullet and numbered before/short/long each 1 page; markers and indentation rendered |
| Unrelated table | Preserve; edits inside table refuse | Main-document subtree equal; `unsupported_target` for table edit | Covered in structures fixture; table remained visible and unchanged on page 1 |
| Headers and footers | Preserve; edits there refuse | Part bytes identical; unsupported-location/ambiguity tests | Headers/footers rendered on all pages including page 2 continuations |
| Hyperlink | Preserve inert relationship and subtree; target edit refuses | Relationship bytes unchanged; external resource never requested | Covered in structures fixture; hyperlink styling remained visible |
| Section breaks | Preserve untouched; section-bearing target refuses | Section subtree equal; target refusal | Structures before/short/long each 2 pages; no unintended blank pages |
| Tracked insert/delete/property changes | Preserve unrelated; relevant edit refuses | Preserved subtree and fixed-code refusals | Covered in structures fixture; unrelated tracked text rendered without layout break |
| Simple/complex fields, text box, content control, bookmark, tab/break | Preserve unrelated; relevant edit refuses | Subtree preservation or explicit unsupported/ambiguous refusal | Covered structurally; rendered field/text-box page did not show overlap or clipping in LibreOffice |
| Short and long replacements near page boundary | Structural replacement only | Filler paragraphs and trailing sentinel retained | Before and short rendered 1 page; long rendered 2 pages with expected reflow and visible trailing sentinel |
| Source mismatch or changed location/digest | Refuse | `source_changed`/`stale_anchor` | Not applicable |
| Failed replacement | No export emitted | Caller export path absent; original file bytes unchanged | Not applicable |
| Archive and XML limits | Refuse | Input, entry count, expanded part/total, XML size, ratio and depth tested | Not applicable |
| Encryption, truncation, CRC, unsafe names, malformed XML | Refuse | Fixed errors with no document content/cause | Not applicable |
| Entities, XInclude, active content, external resources | Refuse relevant package | Hardened parser/relationship/package checks | No resources activated |

Preservation means expanded bytes for unrelated package parts and DOM equivalence for unrelated main-document subtrees. It does not mean identical ZIP bytes, identical XML serialization, or identical rendered pages. Empty trailing runs after shorter replacements are intentional.

## Renderer availability and limits

Current rendered evidence was produced by a disposable LibreOffice container, not by local Microsoft Word. Generated DOCX/PDF/PNG/contact-sheet outputs were kept under ignored `backend/target` or task-local temporary directories and are not committed or uploaded as CI artifacts. The renderer build required network only while building the image; all document conversion ran with Docker networking disabled and synthetic inputs only.

Reproduction outline:

```powershell
# Generate synthetic engine fixtures
cd backend
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=DocxReplacementSpikeTests' '-Ddocx.spike.fixtures=true' test

# Generate real HTTP export fixtures
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=ResumeTailoringExportIT' '-Ddocx.export.fixtures=true' test

# Render with an offline LibreOffice container, mounting only the input and output folders.
docker run --rm --network none --memory 1g --cpus 2 `
  --mount type=bind,source="<fixture-folder>",target=/in,readonly `
  --mount type=bind,source="<render-output-folder>",target=/out `
  job-search-assistant-docx-renderer:phase6 sh -lc "<convert each DOCX with soffice, then pdftoppm>"
```

Checksums recorded from the 2026-09-19 run:

| File | SHA-256 |
| --- | --- |
| `plain-short.docx` | `e5273ed92bc3e5ca646688e5f057db264af324487f64c613cc0554bf58db68c4` |
| `plain-long.docx` | `937542fbc492fd199a4ce6217cb645bfd1bde477d3e13c873026012b3ec10709` |
| `page-boundary-short.docx` | `e370b8755ebc8b18de5bad1835b51d37b55ee93df1603faa99b92b5c3d21b167` |
| `page-boundary-long.docx` | `cc4f3fda5835ab528ad47a9f6cc5a8a254e3d9f427126ef710b8633834f1b0f6` |

The actual HTTP export files had the same four hashes as the fixture-engine outputs, confirming that browser/API-export evidence and engine fixtures converged for these representative supported variants.

Verification resumed on 2026-09-13: the complete foundation verifier passed with 98 backend fast tests, 90 PostgreSQL integration tests, 85 frontend tests, all frontend quality/build checks, and all four existing browser journeys. A new component regression verifies that an approval conflict clears review/attestation without retrying or reporting success. The initial browser startup exposed Windows PowerShell treating expected pre-migration query stderr as a terminating error; bounded bootstrap polling now treats that query failure as not ready. The full rerun passed after this fix. Maven reported a test-JVM shutdown timeout after successful test execution; no test cases failed. The developer servers were paused for verification to avoid port and dependency-file conflicts.

Historical 2026-09-13 note: the manually downloaded synthetic plain-paragraph export was inspected read-only. The expected replacement matched, all eight package entry names were retained, all XML parsed with external resolution disabled, unrelated parts were byte-identical, and the main document was structurally equivalent after undoing only the intended text replacement. That earlier check was not rendered-layout evidence. No renderer was installed then, no real candidate content was used for that comparison, and the dedicated tailoring browser journey had not yet run.

Historical 2026-09-12 note: Phase 6E reused this same engine in the internal Documents module. Its HTTP integration test compared the complete returned DOCX bytes against the fixture engine's output and verified that the stored original was unchanged. At that time this was structural/output evidence only, the owner reconfirmed that no renderer was available, and representative HTTP exports had not been rendered.

Historical 2026-09-12 note: Phase 6E automated verification passed with 98 backend fast tests (including the 13 spike cases), 90 PostgreSQL integration tests (including export races and V12-to-V13 upgrade), 84 frontend tests, format/lint/typecheck/build, and all four existing foundation browser journeys. An initial overnight run lost a connection in an existing Applications test; a fresh complete foundation rerun passed. Packaged-JAR inspection confirmed the runtime engine and V13 while excluding fixture classes. Existing migrations and dependency manifests were unchanged. Those results did not replace the rendering evidence and Phase 6F browser export journey that were completed later.

On 2026-09-11, inspection found no `soffice`/`libreoffice` command, LibreOffice installation in standard Windows program directories, Word COM registration/standard Word executable, or local renderer Docker image. Available local images were PostgreSQL, Testcontainers cleanup, and hello-world. The document skill supplies a Python rendering wrapper, but a wrapper is not a layout renderer. No DOCX was rendered and no page image was visually inspected. No renderer or font version can therefore be reported as tested.

The 2026-09-19 LibreOffice-container run closes the local rendered-layout gate for the existing supported edit boundary. A second comparison in the target Word version is still required before claiming Word fidelity. Do not treat browser HTML, extracted text, structural XML checks, or green CI alone as DOCX layout evidence.

The historical export constraints and approval/anchor implications are recorded in [ADR-018](decisions/ADR-018-bounded-docx-replacement-and-export-design.md). ADR-019 implements ephemeral controlled export. Rendered-layout and final tailoring release verification remain open; consult the dated Phase 6F status above instead of interpreting historical milestone exclusions as current capability limits.

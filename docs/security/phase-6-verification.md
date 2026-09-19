# Phase 6 verification and release gates

## Status 2026-09-19

Phase 6 is locally COMPLETE. Phase 6A-C are implemented, Phase 6D/6E have structural and rendered-layout evidence, and Phase 6F browser security/download verification and documentation reconciliation are complete. The requested repeated browser matrix and full foundation verifier passed locally on 2026-09-15. Hosted CI for the browser-fix commit was reported green by the owner; hosted CI for this documentation/test-output-hook commit is PENDING until pushed and verified.

## Control evidence

| Control | Named tests / evidence | Status |
| --- | --- | --- |
| Real session, CSRF, manual evidence and both attestations | `tailoring-security.spec.ts`: `tailoring security`, `tailoring lifecycle`, `tailoring targets` | Focused browser run passed locally on 2026-09-14; full matrix still blocked |
| Real download bytes, CRC, unchanged unrelated parts and source, safe headers | `tailoring-security.spec.ts` security scenario; `ResumeTailoringExportIT.requiresFreshTargetAttestationThenExportsOneCompleteSeparateDocx` | Focused browser run and backend verify passed locally on 2026-09-14 |
| ADMIN/MEMBER isolation and safe failures | `tailoring-security.spec.ts` security scenario; `ResumeTailoringExportIT.isolatesOwnersAdminsAndAnonymousRequestsWithCsrfAndSafeResponses` | Focused browser run and backend verify passed locally on 2026-09-14 |
| Exact target binding, legacy approval refusal | `ResumeTailoringExportIT.legacyApprovalAndRejectionDoNotAuthorizeExport`; `tailoring-security.spec.ts` security/targets scenarios | Focused browser run and backend verify passed locally on 2026-09-14 |
| Generation races without sleeps/locks during generation | `ResumeTailoringExportIT.refusesConcurrentCommittedMutationsBeforeReleaseWithoutGenerationLocks` | Barrier-based proposal/source/fact/rejection cases retained |
| Still-confirmed fact version change | `ResumeTailoringExportIT.refusesChangedFactEvenWhileConfirmedAndRequiresNewTargetReview` | Backend coverage retained; public edits return facts to draft |
| No partial output / source unchanged | `ResumeTailoringExportIT.outputValidationFailureReleasesNoDocumentAndKeepsOriginal`; browser download/source checksum checks | Focused browser run and backend verify passed locally on 2026-09-14 |
| Archive/XML limits and unsupported structures | `DocxReplacementSpikeTests` and [fidelity matrix](../docx-fidelity-matrix.md) | Structural refusal coverage retained; no wider support added |
| Failed approval and fresh attestation | `ResolvedExportReview.test.tsx`: requires a fresh review and attestation after an approval conflict without retrying | Retained |
| V13 upgrade and legacy approval attribution | `ResumeTailoringExportMigrationIT`; runner retained-volume bootstrap through schema version 13 | Backend verify passed locally on 2026-09-14; runner observed schema version 13 |
| Browser ordering/stability | Runner `-VerificationMode Matrix`: each spec independently in normal/reverse sequence, then three full-suite passes | Passed locally on 2026-09-15 |
| Full foundation verifier | `scripts/verify-foundation.ps1` | Passed locally on 2026-09-15, including all seven browser journeys |
| Rendered synthetic DOCX fixtures | `DocxReplacementSpikeTests -Ddocx.spike.fixtures=true`, disposable LibreOffice container render, visual PNG inspection | Passed locally on 2026-09-19 for all 21 generated DOCX files |
| Rendered actual HTTP exports | `ResumeTailoringExportIT -Ddocx.export.fixtures=true`, disposable LibreOffice container render, visual PNG inspection | Passed locally on 2026-09-19 for plain short/long and page-boundary short/long exports |

## Rendering gate

The rendering gate is closed locally for the current supported DOCX edit boundary. No installed Word or LibreOffice command exists on the host. Verification used a disposable local Docker image, `job-search-assistant-docx-renderer:phase6`, with LibreOffice `7.4.7.2 40(Build:2)`, Poppler `22.12.0`, and Arial substituted by Liberation Sans. Conversions ran with `--network none`, bounded CPU/memory, a temporary LibreOffice profile, and synthetic input/output mounts only.

All 21 documented synthetic fixture outputs rendered. Plain, styled, split, bullet, numbered, and short variants rendered as one page; structures variants rendered as two pages; page-boundary-long rendered as two pages. Representative actual HTTP exports from the real source-review, resolved approval, and export workflow rendered as one page for plain short/long and page-boundary short, and two pages for page-boundary long. Every rendered page was inspected from PNG contact sheets. No clipping, overlap, missing target text, broken numbering, missing headers/footers, unintended blank pages, or unusable pagination was observed. This is LibreOffice evidence, not a universal Word fidelity claim.

## Privacy and cleanup

Synthetic actors and data only. Browser contexts and downloads close/delete in `finally`; runner PostgreSQL and private storage are disposable. Developer volumes and unrelated processes are excluded. CI uploads only sanitized text diagnostics, not raw reports, documents, downloads, screenshots, traces, video or credentials. Tokens and private text must not enter persistence, URLs, logs or external requests.

## Remaining gates

- Run link/diff/artifact checks for this documentation/test-output-hook change.
- Verify hosted CI against the resulting commit after it is pushed.
- Household deployment and target Word fidelity require separate evidence; approval remains owner attestation and never submission permission.

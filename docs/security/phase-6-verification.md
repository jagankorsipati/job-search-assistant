# Phase 6 verification and release gates

## Status 2026-09-14

Phase 6 remains OPEN. Phase 6A-C are implemented. Phase 6D structural spike and Phase 6E controlled export are implemented, but rendered-layout evidence is unavailable. Phase 6F browser coverage and documentation reconciliation are present. The requested repeated browser matrix and full foundation verifier passed locally on 2026-09-15. Hosted CI for the resulting commit is PENDING until that exact commit is pushed and verified; earlier green runs do not establish this gate.

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
| Archive/XML limits and unsupported structures | `DocxReplacementSpikeTests` and [fidelity matrix](../docx-fidelity-matrix.md) | Structural only; no wider support added |
| Failed approval and fresh attestation | `ResolvedExportReview.test.tsx`: requires a fresh review and attestation after an approval conflict without retrying | Retained |
| V13 upgrade and legacy approval attribution | `ResumeTailoringExportMigrationIT`; runner retained-volume bootstrap through schema version 13 | Backend verify passed locally on 2026-09-14; runner observed schema version 13 |
| Browser ordering/stability | Runner `-VerificationMode Matrix`: each spec independently in normal/reverse sequence, then three full-suite passes | Passed locally on 2026-09-15 |
| Full foundation verifier | `scripts/verify-foundation.ps1` | Passed locally on 2026-09-15, including all seven browser journeys |

## Rendering gate

No subsequent reproducible rendered evidence has been found. No Word or LibreOffice command or standard executable is available. No renderer/font version, page counts, reflow, clipping, or visual defects can be reported as tested. All 21 documented fixture renders plus representative actual API exports (shorter, longer and page-boundary cases) remain required. Structural comparisons cannot close this gate. Source files are read-only test inputs.

## Privacy and cleanup

Synthetic actors and data only. Browser contexts and downloads close/delete in `finally`; runner PostgreSQL and private storage are disposable. Developer volumes and unrelated processes are excluded. CI uploads only sanitized text diagnostics, not raw reports, documents, downloads, screenshots, traces, video or credentials. Tokens and private text must not enter persistence, URLs, logs or external requests.

## Remaining gates

- Run JAR/link/artifact checks and hosted CI for the resulting commit.
- Obtain reproducible renderer evidence and inspect every page.
- Verify hosted CI against the resulting commit.
- Household deployment and target Word fidelity require separate evidence; approval remains owner attestation and never submission permission.

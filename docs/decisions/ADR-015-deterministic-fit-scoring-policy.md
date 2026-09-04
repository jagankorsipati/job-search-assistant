# ADR-015: Use deterministic fit scoring policy

## Status

Accepted

## Context

Phase 5A records owner-reviewed job requirements for exact immutable job-description snapshots and explicit user-selected evidence relationships. Phase 5B needs an internal analysis result that is reproducible, explainable, and truthful without adding AI, inference, public routes, frontend screens, or persisted scores.

The score measures how strongly the candidate's currently linked confirmed evidence demonstrates the reviewed job requirements. It is not a probability of being hired, a candidate-quality judgment, an eligibility decision, a recruiter decision, proof that a qualification exists or is absent, or a guarantee of job compatibility.

## Decision

Add a pure Fit scoring policy with version `DETERMINISTIC_FIT_V1`. The policy operates only on Phase 5A `JobRequirement` and `CandidateEvidenceLink` inputs loaded by an owner-scoped service method. The policy does not inspect resume text, career-fact text, profile-field values, requirement prose, or evidence notes, and it does not infer relationships.

Only `CONFIRMED` requirements participate in score denominators. `DRAFT` and `REJECTED` requirements are excluded from scoring, and their counts remain visible. If no confirmed requirements exist, the result is typed as non-scorable instead of returning `0%` or `100%`.

Every confirmed requirement receives exactly one assessment using this precedence:

1. No links: `UNASSESSED`
2. At least one `CONTRADICTS` plus at least one `SUPPORTS` or `PARTIALLY_SUPPORTS`: `CONFLICTING_EVIDENCE`
3. At least one `CONTRADICTS` and no support: `CONTRADICTED`
4. At least one `SUPPORTS` and no contradiction: `DEMONSTRATED`
5. At least one `PARTIALLY_SUPPORTS`, no full support, and no contradiction: `PARTIALLY_DEMONSTRATED`
6. Only `NOT_DEMONSTRATED` links: `NOT_DEMONSTRATED`

Unexpected or malformed inputs fail closed with a domain scoring exception. A `NOT_DEMONSTRATED` link means only that the currently linked confirmed evidence does not demonstrate the requirement. It does not mean the candidate lacks the qualification and it does not override supporting evidence.

Requirement weights are fixed integers:

- `REQUIRED`: 2
- `PREFERRED`: 1
- `UNSPECIFIED`: 1

Assessment credits are fixed decimals:

- `DEMONSTRATED`: 1.0
- `PARTIALLY_DEMONSTRATED`: 0.5
- `NOT_DEMONSTRATED`: 0.0
- `CONTRADICTED`: 0.0
- `CONFLICTING_EVIDENCE`: 0.0
- `UNASSESSED`: 0.0

The evidence-support score uses exact decimal arithmetic:

`supportPoints = sum(requirementWeight * assessmentCredit)`

`evidenceSupportScore = 100 * supportPoints / totalEligibleWeight`

The evidence-coverage score measures whether each confirmed requirement has been explicitly reviewed through at least one link:

`assessedWeight = sum(weight of confirmed requirements whose assessment is not UNASSESSED)`

`evidenceCoverageScore = 100 * assessedWeight / totalEligibleWeight`

Scores are rounded half-up to the nearest whole integer. The result includes numerator, denominator, per-requirement contribution, group breakdowns by importance, structured gaps, structured partial gaps, structured contradiction risks, and the policy version. Groups with no confirmed requirements are marked not applicable, not `0%`.

Multiple links do not increase a requirement's strength. One `SUPPORTS` link and ten `SUPPORTS` links both give full credit for that requirement because links explain why a requirement is demonstrated rather than voting on confidence. Contradictions are reported explicitly instead of producing negative points because negative scores would obscure truthfulness risks and overstate mathematical precision.

Per-requirement assessments are ordered deterministically by importance (`REQUIRED`, `PREFERRED`, `UNSPECIFIED`), category enum order, requirement creation time, and requirement UUID.

## Privacy and persistence

Scores, assessments, gaps, contradictions, timestamps, and summaries are computed on demand and not persisted, so they cannot become stale when requirements or evidence links change.

The scoring result contains identifiers and structured scoring information only. It does not copy requirement text, source excerpts, resume content, career-fact content, profile-field values, or evidence notes. Phase 5C may retrieve owner-scoped display data separately.

No scoring inputs, UUID collections, or full scoring results are logged. Safe future operational logs may include only policy version, counts, scorable status, and generic reason codes.

## Consequences

Phase 5B remains an internal service milestone. Phase 5C can add presentation and review workflows on top of the internal result without changing the scoring math, adding persistence, or weakening owner isolation.

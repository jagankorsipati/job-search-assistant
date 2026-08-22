# ADR-011: Establish Candidate Profile and Career-fact Foundation

- Status: Accepted
- Date: 2026-08-22

## Context

Phase 3 introduces the private candidate workspace. The application needs enough structured profile data for future job matching and resume generation without collecting unnecessary sensitive data or allowing generated text to become a career claim.

The existing owner-scoped authorization decision requires every private row to carry an immutable `owner_account_id` derived from `identity::actor`. Administrators administer account access only; they do not receive access to another member's profile or career facts.

## Decision

The profile module owns one `candidate_profile` row per account and many `career_fact` rows per account. Both tables live in the `job_search_assistant` schema, use UUID primary keys, carry immutable `owner_account_id` foreign keys to `user_account`, and use owner-first indexes for future scoped reads. Foreign keys restrict account deletion until private profile data has an explicit deletion design.

Candidate profiles store only professional-display and matching-oriented fields: preferred professional display name, headline, summary, location preference, target roles, work authorization statement, and remote/hybrid/on-site preference text. The profile foundation deliberately excludes passwords, credentials, Social Security numbers, immigration document numbers, birth dates, full home addresses, government identification, salary history, and references' private contact information.

Career facts are structured records, not a single uncontrolled career-history blob. Phase 3A supports employment, skill, education, certification, project, and accomplishment categories. Each fact contains owner-authored factual content, optional organization/title/location/date fields when applicable, timestamps, and an optimistic-lock version. More category-specific fields require future evidence that they support matching or integrity rather than resume formatting.

Fact status is explicit:

- `DRAFT`: entered, imported, or AI-suggested text that the owner has not confirmed.
- `CONFIRMED`: the account owner has attested that the information is accurate and it may be used for resume generation or job matching.
- `ARCHIVED`: retained for history but unavailable for new generated content.

`CONFIRMED` does not mean an employer, school, certification authority, or the application independently verified the claim. AI-generated or imported text never becomes confirmed automatically. Editing a confirmed fact returns it to draft until the owner confirms the revised content. Archived facts cannot be modified or reconfirmed without an explicit restoration transition to draft.

Future reads, writes, updates, collections, and deletes must predicate on both resource ID and `owner_account_id`. Browser-provided owner or account IDs never determine ownership. Non-owned and nonexistent resources remain indistinguishable. `ADMIN` has no bypass into another member's candidate profile or facts.

Evidence/document linkage, base resume upload, extraction review, generated resumes, embeddings, vector search, AI prompts, job data, and application tracking are deferred.

## Consequences

- Truthfulness is modeled before generation features exist.
- PostgreSQL constraints and domain invariants reject blank content, invalid categories/statuses, invalid date ranges, invalid timestamps, and negative versions.
- Structured facts preserve future matching ability without prematurely modeling resume presentation.
- The profile module depends only on the published `identity::actor` interface when application services are later added.
- Account deletion remains restricted until a reviewed owner-data deletion flow exists.

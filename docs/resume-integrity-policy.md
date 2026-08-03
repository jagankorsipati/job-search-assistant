# Résumé Integrity Policy

## Non-negotiable rule

The system may reorganize, emphasize, and reword verified experience. It must never invent a skill, accomplishment, employer, title, certification, responsibility, education item, or metric.

## Fact states

- **Draft:** entered or extracted but not approved by the user
- **Verified:** explicitly confirmed by the user and eligible for tailoring
- **Retired:** historically retained but excluded from new suggestions

Only verified facts can appear in generated application documents.

## Allowed transformations

- Reorder verified bullets or skills for relevance.
- Shorten or clarify wording without changing meaning.
- Select a subset of verified facts.
- Combine compatible verified facts while retaining provenance.
- Suggest questions asking the user to verify a possible missing fact.

## Prohibited transformations

- Convert a job requirement into a candidate skill.
- Add tools based only on a job title or industry.
- Create numerical impact not present in verified evidence.
- Upgrade proficiency, responsibility, seniority, or duration.
- Present a draft or AI inference as fact.
- Hide a known gap by ambiguous wording.

## Enforcement

Every proposed claim carries references to one or more verified fact IDs. Export is blocked when a claim lacks evidence. The review screen shows additions, removals, wording changes, and evidence. User approval is recorded, but approval cannot override the requirement for verified evidence; the user must first update and verify the profile.

## AI behavior

AI may propose wording and identify possible matches. Deterministic validation decides whether every claim is backed by eligible evidence. Missing requirements are reported separately as gaps.

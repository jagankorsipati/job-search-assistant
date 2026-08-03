# Functional Requirements

## Identity and isolation

- **FR-001:** Users can create and authenticate to local accounts.
- **FR-002:** Passwords are stored only as modern salted hashes.
- **FR-003:** Every user-owned record and file is authorized by owner identity server-side.
- **FR-004:** Authentication, logout, expiry, and failed-login events are auditable without logging secrets.
- **FR-005:** New accounts require a single-use household invitation created by an administrator.
- **FR-006:** Administrators can invite, disable, or recover accounts but cannot read user-owned career content by default.
- **FR-007:** Sessions identify exactly one user; switching users requires logout and reauthentication.

## Candidate profile

- **FR-010:** Users can manage employment, education, projects, skills, certifications, and accomplishments.
- **FR-011:** Facts have draft, verified, or retired status and retain provenance.
- **FR-012:** Users can upload a DOCX base résumé and confirm extracted facts.
- **FR-013:** AI output cannot mark a fact verified.

## Jobs and analysis

- **FR-020:** Users can save pasted job text and manually supplied URLs.
- **FR-021:** Users can edit job title, company, location, source, and description.
- **FR-022:** The system warns about likely duplicates.
- **FR-023:** Fit analysis classifies requirements and links matches to verified evidence.
- **FR-024:** Unknown or missing requirements remain visible as gaps.

## Documents

- **FR-030:** Tailoring uses only verified facts.
- **FR-031:** Users see original and proposed content before approval.
- **FR-032:** Exports record their source résumé, job, approved changes, and creation time.
- **FR-033:** Users can generate and edit grounded cover-letter drafts.
- **FR-034:** The system preserves the original résumé.

## Applications

- **FR-040:** Users can manage application stage, notes, dates, contacts, and follow-ups.
- **FR-041:** Stage changes form an immutable history.
- **FR-042:** The system does not submit an application in V1.

## Data control and operations

- **FR-050:** Users can export their structured data and documents.
- **FR-051:** Users can delete their account data after explicit confirmation.
- **FR-052:** Health endpoints reveal no personal information.
- **FR-053:** Backups and restores preserve user isolation.

## Quality attributes

- All authorization rules require integration tests.
- Core profile, fit, and integrity rules work without AI.
- AI failures degrade to deterministic/manual workflows.
- V1 supports current Chromium-based desktop browsers.

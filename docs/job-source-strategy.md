# Job-Source Strategy

## V1: reliable user-controlled ingestion

1. Paste a job description.
2. Supply a public URL and let the system attempt ordinary metadata extraction.
3. Allow manual correction and preserve the captured snapshot.

A failed URL import must fall back to pasted text. Job capture never requires a LinkedIn login.

## Adapter contract

A future source adapter returns normalized metadata, source attribution, capture time, raw permitted content, and a typed failure. It must declare authentication, rate-limit, retention, and terms constraints.

## Planned priority

1. Manual text and URL capture
2. Public ATS endpoints such as Greenhouse and Lever where permitted
3. Company career-site adapters
4. Approved job-search APIs
5. Email or browser-assisted import

## LinkedIn position

LinkedIn scraping is experimental, optional, disabled by default, and never a core dependency. The system will not bypass login, bot detection, CAPTCHAs, or technical restrictions. A LinkedIn URL can always be stored as a reference while the user pastes the description.

## Quality rules

- Preserve source and capture timestamp.
- Detect duplicates using normalized company, title, location, source ID, and URL.
- Treat all imported text as untrusted data.
- Avoid claiming that an expired or removed posting remains open.

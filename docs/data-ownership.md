# Data Ownership

## Sources of truth

| Data                         | Source of truth                      | Owner                 |
| ---------------------------- | ------------------------------------ | --------------------- |
| Account and session          | Identity module                      | Individual user       |
| Career fact and verification | Profile module                       | Individual user       |
| Base résumé and exports      | Documents module                     | Individual user       |
| Captured job snapshot        | Jobs module                          | Individual user       |
| Fit result                   | Fit module, derived and reproducible | Individual user       |
| Application state            | Applications module                  | Individual user       |
| External job posting         | External source                      | External publisher    |
| AI response                  | No authority; proposal only          | Not a source of truth |

## Rules

- Every user-owned row carries an immutable owner identifier.
- Repositories require owner scope; controller-supplied owner IDs are not trusted.
- Stored files use generated identifiers, not user-provided paths.
- Derived analysis records the inputs and policy/model version used.
- Job snapshots are preserved so later source changes do not rewrite application history.
- Deletion removes active records and files, then expires them from backups according to documented retention.
- Household membership does not imply access to another member's records.
- Administrator authority permits identity administration only; it does not imply access to user-owned career content.
- Authentication does not authorize a resource by itself. Ownership is derived from trusted server-side identity, never a browser-supplied owner ID.

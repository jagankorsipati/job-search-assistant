# ADR-012: Secure local base resume storage

Status: Accepted

## Context

Phase 3 requires each authenticated household account to keep one current base resume source document. The document is sensitive personal data and must not be parsed, converted into career facts, exposed to administrators, or stored in PostgreSQL as binary content.

## Decision

Base resume behavior lives inside the existing `documents` module. The module depends only on `identity::actor` to derive the current owner account. APIs never accept owner IDs from multipart fields, JSON, query parameters, or headers.

Metadata is stored in PostgreSQL in `job_search_assistant.base_resume_document`. The file bytes are stored through a narrow storage abstraction. Phase 3E implements local filesystem storage with an externalized `BASE_RESUME_STORAGE_ROOT`, server-generated opaque storage keys, staged writes, SHA-256 calculation while streaming, validation before publish, and atomic move where the platform supports it.

Only PDF and DOCX are accepted, up to 5 MiB. Validation checks PDF signatures/trailers and DOCX ZIP structure, required entries, traversal names, entry count, uncompressed-size bounds, and suspicious compression ratios. This validation is not malware scanning.

Downloads are authenticated, owner-scoped, `attachment` only, `no-store`, and `nosniff`. Responses expose display metadata only: document ID, normalized original filename, validated media type, byte size, timestamps, and version. Storage keys, checksums, filesystem paths, and owner IDs are never returned.

## Consequences

Replacement uses optimistic locking. The previous resume remains current if validation, staging, publish, or the metadata update fails. After a successful replacement commit, old-file cleanup is best effort. A crash between filesystem publish and database rollback/cleanup can leave an orphan file, and a post-commit cleanup failure can leave an unused old file; metadata still points at the current document. Operators must treat the storage root and backups as sensitive.

Hard deletion, retention automation, malware scanning, document parsing, AI extraction, previews, public sharing, and administrator downloads remain deferred.

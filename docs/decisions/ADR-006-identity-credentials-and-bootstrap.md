# ADR-006: Secure Identity Credentials, Bootstrap, and Invitations

- Status: Accepted
- Date: 2026-08-15

## Context

Phase 2A established account and invitation persistence without providing a way to create the first administrator, issue invitations, accept them, or verify credentials. These operations handle high-value secrets and must be safe before any HTTP surface exists.

Current [OWASP password-storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) recommends Argon2id with at least 19 MiB memory, two iterations, and parallelism one. [NIST SP 800-63B](https://pages.nist.gov/800-63-4/sp800-63b.html) recommends a minimum of 15 characters for single-factor passwords, permitting Unicode and long passphrases without arbitrary composition rules. Spring Security provides a self-describing Argon2id encoder backed by Bouncy Castle.

## Decision

Passwords are hashed with Argon2id using a 16-byte random salt, 32-byte output, 19 MiB memory, two iterations, and parallelism one. The encoded Argon2 string is self-describing and is the only password representation persisted. Parameters are fixed in code so deployments cannot silently weaken them; a future reviewed migration may raise them after measuring target hardware.

Passwords contain 15 through 128 Unicode code points. Spaces and Unicode are allowed. Passwords are never trimmed, lowercased, normalized, truncated, logged, serialized, returned, or placed on persistent domain objects. No uppercase, number, symbol, or periodic-change rule is imposed. A future HTTP boundary must add a compromised-password blocklist and mandatory rate limiting before exposure.

First-administrator bootstrap is disabled by default and activated only by the process-scoped `IDENTITY_BOOTSTRAP_ENABLED=true` operator setting plus explicit login, display-name, and password environment variables. Inputs are fully validated and hashed before the transaction writes. A PostgreSQL transaction-scoped advisory lock serializes bootstrap attempts; after the lock, the service refuses to proceed if any account exists. Exactly one active `ADMIN` can therefore be created. There is no seed, default password, public registration route, or reusable bootstrap endpoint. The operator must stop the bootstrap process, remove all bootstrap environment variables, and restart normally immediately afterward.

Invitation creation accepts an actor identity only from a trusted application boundary. The service verifies that the actor exists and is an active `ADMIN`. Phase 2B issues only `MEMBER` invitations; administrator invitations are deliberately unsupported because they delegate household authority. Tokens contain 256 bits from `SecureRandom`, are URL-safe Base64 without padding, and are returned through a one-time redacted carrier. Persistence stores only `sha256:<hex>`. The default lifetime is 24 hours and configuration is bounded from 15 minutes through seven days.

Invitation acceptance validates account inputs first, hashes the supplied token before lookup, and locks the invitation row. It generically rejects malformed, unknown, expired, revoked, or consumed invitations. Account insertion and optimistic invitation consumption occur in one transaction. A duplicate login or any failure rolls back both operations; concurrent acceptance permits at most one commit.

Credential verification normalizes login names but never passwords. Only active accounts authenticate. Unknown login, incorrect password, pending account, and disabled account all return the same failure type and message. Unknown and syntactically invalid logins perform a dummy Argon2 verification to reduce obvious timing differences. Success returns only account ID and role, not the encoded hash or account record.

Credentials, plaintext invitation tokens, token digests, and password hashes must never be logged. HTTP endpoints, Spring Security filter-chain authorization, sessions, cookies, CSRF delivery, rate limiting, authentication audit persistence, and recovery remain Phase 2C or later work.

## Consequences

- Spring Security Crypto and Bouncy Castle are identity implementation dependencies; no web-security configuration is enabled.
- PostgreSQL advisory and row locks define concurrency boundaries, while existing V2 unique constraints and optimistic versions provide final enforcement.
- No V3 migration is required for Phase 2B because existing keys and unique indexes cover every implemented lookup.
- Operators must treat bootstrap environment variables as short-lived secrets and clear them before normal startup.
- Phase 2C must benchmark Argon2 on deployment hardware and implement generic HTTP responses, secure server-managed sessions, CSRF protection, rate limiting, session rotation, and security-event auditing.

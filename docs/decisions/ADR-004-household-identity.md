# ADR-004: Use Invite-Only Accounts With Private Workspaces

- Status: Accepted
- Date: 2026-08-03

## Context

Several household members will use one installation. Résumés, application notes, and career history remain personal even when the infrastructure is shared. Public registration is unnecessary and increases risk.

## Decision

Each person has an individual account, password, session, candidate profile, document namespace, job workspace, and application history. Registration requires an expiring, single-use invitation created by a household administrator. Server-side authorization derives the owner from the authenticated session rather than accepting an owner ID from the browser.

The administrator may invite, disable, and assist recovery of accounts but cannot read another user's career records or files by default. Any future delegated access requires an explicit, revocable user grant and a separate decision.

## Consequences

- Household data remains isolated within one deployment.
- Authorization and cross-user denial require integration tests for every resource type.
- Account recovery must avoid giving administrators silent content access.
- Shared family profiles or shared job lists are deferred until explicit sharing semantics exist.

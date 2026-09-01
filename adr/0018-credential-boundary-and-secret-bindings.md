# ADR 0018: Credential boundary and user-authored secret bindings

- Status: Accepted
- Date: 2026-08-09
- Supersedes: Core-owned credential caching and the earlier deferral of user-authored bindings
- Superseded by: None
- Public references: [Credentials, connectors, and egress](../docs/operator-guide/credentials-egress.md), [graph input, credentials, and egress](../docs/security/input-secrets-egress.md)

## Context

Graphs need stable references to credentials without carrying credential material. Credential
caching and rotation had been framed as core concerns, reference-to-environment-key encodings could
collide, and a caller-chosen binding name could cross tenant boundaries. A later accepted increment
replaced the initial decision to defer user-authored bindings.

## Decision

Core retains read-side indirection but does not own credential caching, TTL, rotation, or revocation;
those are obligations of the selected credential backend. Reference encodings are injective. A
credential is resolved once for an attempt sequence, and authentication rejection is terminal rather
than retried as a transient transport failure.

User-authored bindings are handled by a server-owned surface, not by the application API or node
property inspector. The server mints an opaque, tenant-scoped reference and refuses caller-selected
references. UI and CLI use the same HTTP boundary. Secret material is intentionally excluded from
graphs, logs, errors, events, and general payloads; the product promises controlled handling, not
impossible erasure from every runtime buffer.

Owner and tenant checks occur at authenticated admission when the binding is created or selected.
The downstream `CredentialResolver.resolve(String)` contract is identity-free: it receives only the
opaque reference, so resolution behaves as bearer-reference resolution and cannot independently
recheck principal, owner, or tenant. Every entry point must therefore preserve authenticated
admission and ownership checks until an identity-aware resolution contract exists; no new path may
accept an arbitrary reference after that authorization context has been lost.

## Consequences

- Exported GraphML contains references and must be rebound in another environment.
- Node packages compile against read-side credential services, not a general secret-write API.
- The shipped SQLite credential store persists secret values without application-level at-rest
  encryption: values are in clear in the SQLite database. A principal or process that can read the
  deployment volume or database file can read those secret values. Filesystem permissions, volume
  encryption, and protected backups are operator controls, not properties supplied by the store.
- This decision does not claim secure deletion, and bearer-reference resolution means the admission
  boundary remains load-bearing until resolution itself becomes identity-aware.

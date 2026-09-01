# Embedded projection security model

Embedding is a separate read-only delivery plane governed by a registered deployment, seven attestations, and short-lived session exchange.

## Invariants

- The operator supplies the policy facts; Ravenroot records and enforces the resulting registration.
- A graph document contains no self-asserted embed permission and cannot open its own gates.
- The projection is minimized for viewing and excludes mutation, execution control, secrets, and operator APIs.

## Runtime relationships

- A host launch is exchanged once for a scoped, short-lived viewer session.
- Origin, host, deployment, expiry, and revocation are checked at the protocol boundary.
- Takedown is effective through registration or session revocation and leaves audit evidence.

## Architectural consequence

The embed plane derives only a scoped viewing session from an operator registration and never inherits author, runner, or administrative capability.

## Related reading

- [Exact contract](../reference/embed-extension-contracts.md)
- [Procedure or recovery](../operator-guide/embed-operations.md)

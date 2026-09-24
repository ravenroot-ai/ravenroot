# Embedded projection security model

Embedding is a separate read-only delivery plane governed by either a legacy registration or an
explicit dynamic origin policy, plus short-lived session exchange.

## Invariants

- The operator supplies the policy facts; Ravenroot records and enforces the resulting registration.
- A graph document contains no self-asserted embed permission and cannot open its own gates.
- The projection is minimized for viewing and excludes mutation, execution control, secrets, and operator APIs.
- A versioned source union distinguishes an immutable snapshot from a live deployment; neither source
  silently falls back to the other.

## Runtime relationships

- A host launch is exchanged once for a scoped, short-lived viewer session.
- Origin, host, deployment, expiry, and revocation are checked at the protocol boundary.
- Live observation is additionally bound to registration revision, proof key, graph version, and
  deployment incarnation. Every frame repeats the immutable source binding.
- Bounded reconnect may resume inside the process-local replay window. Gap, replacement, undeploy,
  or authority invalidation is terminal and clears runtime-derived state.
- Takedown is effective through registration or session revocation and leaves audit evidence.
- Dynamic selection discovers only the authenticated tenant's READY deployments on the current
  server. Session creation revalidates and pins the exact incarnation/version; a later replacement
  cannot inherit the grant. Dynamic grants never carry deployment execution capability.

## Architectural consequence

The embed plane derives only a scoped viewing session from a legacy registration or bounded dynamic
grant and never inherits author, runner, or administrative capability. Live observation changes
presentation state, not the graph, deployment lifecycle, or authority boundary.

## Related reading

- [Exact contract](../reference/embed-extension-contracts.md)
- [Procedure or recovery](../operator-guide/embed-operations.md)

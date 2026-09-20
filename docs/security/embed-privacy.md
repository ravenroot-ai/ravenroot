# Embed, privacy, and audit

The embedded viewer exposes only a registered projection whose privacy and takedown facts an accountable operator has attested.

## Controls

- Record deployment, provenance, classification, retention, DSR suppression, takedown, and EEA residence gates before registration.
- Bind launch and exchange to exact origin, host, source, registration revision, scope, expiry, and one-time use.
- Exclude mutation, execution control, credentials, raw operator APIs, and non-projected graph data from the viewer.
- For live sources, bind observation to deployment id, graph version, incarnation, session proof key,
  and an opaque process-local cursor. Emit only allowlisted lifecycle and execution fields.
- Audit registration, acknowledgement, session issue, projection access, observation access, expiry,
  and revocation without logging token, proof, cursor, or secret values.

The parent page receives protocol health messages but never the bearer, proof key, projection, cursor,
or observation events. The frame uses an explicit credentialed request with an authorization header;
it does not use ambient cookies. A gap, authority change, undeploy, or source replacement clears
observed state and terminates the attachment so stale runtime decoration cannot be mistaken for live
state.

## Residual responsibility

The seven gates are operator attestations, not facts computed from the graph. Ravenroot can enforce their recorded result and revocation, while the organization owns their truth and timely reevaluation.

## Application

- [Definitions and limits](../reference/embed-extension-contracts.md)
- [Operator procedure or recovery](../operator-guide/embed-operations.md)

# Embed, privacy, and audit

The embedded viewer exposes only a registered projection whose privacy and takedown facts an accountable operator has attested.

## Controls

- Record deployment, provenance, classification, retention, DSR suppression, takedown, and EEA residence gates before registration.
- Bind launch and exchange to exact origin, host, deployment, scope, expiry, and one-time use.
- Exclude mutation, execution control, credentials, raw operator APIs, and non-projected graph data from the viewer.
- Audit registration, acknowledgement, session issue, projection access, expiry, and revocation without logging token or secret values.

## Residual responsibility

The seven gates are operator attestations, not facts computed from the graph. Ravenroot can enforce their recorded result and revocation, while the organization owns their truth and timely reevaluation.

## Application

- [Definitions and limits](../reference/embed-extension-contracts.md)
- [Operator procedure or recovery](../operator-guide/embed-operations.md)

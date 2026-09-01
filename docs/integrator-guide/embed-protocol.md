# Embedded-viewer protocol

Integrate a host page with the viewer without exposing an operator token or a mutable graph surface.

## Integration sequence

1. Obtain the registered deployment identifier and exact allowed origin from the operator.
2. Request a one-time launch server-side and deliver only that launch value to the browser.
3. Let the viewer exchange it for a scoped short-lived session, then fetch the authorized projection.
4. Treat expiry or revocation as terminal: discard local projection state and request a new host-mediated launch only if policy still permits.

## Authority boundary

The integrator owns host placement and token handling. Only the operator owns registration and seven gate attestations; the viewer has read-only projection authority.

## Linked contracts

- [Primary interface](../reference/embed-extension-contracts.md)
- [Operational or security model](../security/embed-privacy.md)

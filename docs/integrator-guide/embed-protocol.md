# Embedded-viewer protocol

Integrate a host page with the viewer without exposing an operator token or a mutable graph surface.

## Integration sequence

1. Obtain the registered deployment identifier and exact allowed origin from the operator.
2. Request a one-time launch server-side and deliver only that launch value to the browser.
3. Let the viewer exchange it for a scoped short-lived session, then fetch the authorized projection.
4. For a deployment-backed registration, let the viewer open the signed, bearer-authenticated
   observation stream. The stream is bound to the resolved deployment id, graph version, incarnation,
   registration revision, and session key.
5. Treat expiry, revocation, a replay gap, undeploy, or source replacement as terminal: discard local
   runtime state and request a new host-mediated launch only if policy still permits.

## Authority boundary

The integrator owns host placement and workload-token handling. Only the operator owns registration
and the snapshot form's seven gate attestations; the viewer has read-only projection and observation
authority. The browser bearer and proof key stay inside the viewer frame. Projection and observation
payloads are never sent to the parent through `postMessage`.

## Source and continuity contract

`viewerSourceVersion: "1"` selects one explicit source union: `snapshot` or `deployment`. A deployment
projection includes an immutable `(deploymentId, graphVersion, incarnationId)` binding. Every
observation frame repeats that binding and is ignored if it does not match.

Observation reconnect is bounded and resumes with an opaque cursor. `source-gap` and
`source-invalidated` are terminal frames. Ravenroot never silently switches to GraphML, another
version, or a replacement incarnation.

## Linked contracts

- [Step-by-step host page](embed-viewer-quickstart.md)
- [Primary interface](../reference/embed-extension-contracts.md)
- [Operational or security model](../security/embed-privacy.md)

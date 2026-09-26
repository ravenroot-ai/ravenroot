# Embedded-viewer protocol

Integrate a host page with the viewer without exposing an operator token or a mutable graph surface.

## Integration sequence

1. Either obtain a legacy registration from the operator, or discover a READY deployment under the
   authenticated dynamic policy and retain its exact deployment/incarnation/version tuple.
2. Request a one-time launch server-side and deliver only that launch value to the browser.
3. Let the viewer exchange it for a scoped short-lived session, then fetch the authorized projection.
4. For a deployment-backed registration, let the viewer open the signed, bearer-authenticated
   observation stream. The stream is bound to the resolved deployment id, graph version, incarnation,
   registration revision, and session key.
5. Treat expiry, revocation, undeploy, or source replacement as terminal. V1 also treats a replay gap
   as terminal; a selected v2 run first clears runtime state and rebuilds it from that exact process's
   durable journal before resuming the live stream.

## Authority boundary

The integrator owns host placement and workload-token handling. Legacy registration and snapshot
attestations remain operator-owned. Dynamic selection is a distinct, default-off server policy: the
authenticated workload may discover only its tenant's READY `LOCAL_PROCESS` deployments, then request
a short-lived grant for one exact incarnation and graph version. Dynamic grants always contain only
graph read, deployment observation, and run read; they never contain execution authority. The browser
bearer and proof key stay inside the viewer frame. Projection and observation payloads are never sent
to the parent through `postMessage`.

## Source and continuity contract

`viewerSourceVersion: "1"` remains the unchanged snapshot/deployment contract. An integrator may
explicitly provision additive `viewerSourceVersion: "2"` for a deployment to enable the
authoritative run selector. V2 never widens the deployment grant: the server joins durable process
inventory to the current deployment and graph version before serializing a bounded list. Each row
contains only process-instance id, public lifecycle/outcome, and update time.

A deployment
projection includes an immutable `(deploymentId, graphVersion, incarnationId)` binding. Every
observation frame repeats that binding and is ignored if it does not match.

V2 selection adds `processInstanceId` to that immutable binding. Zero authorized rows produce an
accessible empty state, one is selected deterministically, and multiple rows require an explicit
choice. Changing the choice increments the viewer generation, clears all runtime decoration, and
attaches a new signed stream. Refresh keeps the exact selection only while it remains authorized.

`showStartExecution` is a presentation option and defaults false. When true, the server still
requires the separate `DEPLOYMENT_EXECUTE` capability for every request and rechecks the exact tenant,
deployment, graph version, and incarnation. The browser supplies only its minimized session proof
and a bounded idempotency request id; executable graph content and operator credentials stay server-side.
The standard `deploymentV2(...)` provisioning factory never grants execution. Operators grant it
explicitly with `deploymentV2WithExecutionCapability(...)`; either factory can independently show or
hide the control. A hidden control keeps the action endpoint unavailable even if authority was granted.

Observation reconnect is bounded and resumes with an opaque cursor. For a v2 selected run, a
process-local gap triggers `runtime-reset` followed by server-filtered durable replay for that exact
process; an unavailable or truncated durable stream still fails closed as terminal `source-gap`.
`source-invalidated` is terminal. Ravenroot never silently switches to GraphML, another version, or
a replacement incarnation.

The v2-only browser endpoints are `POST /v1/embed/runs` and `POST /v1/embed/executions`. Both use the
same origin, cookie exclusion, short-lived bearer, proof-of-possession, revision, and replay checks
as projection and observation. Dynamic grants can use run reads but cannot use execution. The separate
server-only `GET /v1/embed/deployments` discovery is tenant-bound, READY-only, bounded to 100 rows,
and process-local; it is not global inventory or lifecycle control. `DELETE /v1/embed/grants/{id}`
revokes a dynamic grant without disclosing whether a missing or foreign id exists.

## Linked contracts

- [Step-by-step host page](embed-viewer-quickstart.md)
- [Primary interface](../reference/embed-extension-contracts.md)
- [Operational or security model](../security/embed-privacy.md)

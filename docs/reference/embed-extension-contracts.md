# Embed and extension contracts

## Embedded deployment gates

An operator may register an embedded projection only after explicitly attesting all seven gates for that deployment:

| Gate | Operator attestation |
|---|---|
| Deployment | The exact deployed viewer and graph revision are identified |
| Provenance | Content origin and authority are known |
| Classification | Data and content classification permits the projection |
| Retention | Retention behavior matches the deployment obligation |
| DSR suppression | Data-subject request suppression is applied where required |
| Takedown | An accountable operator can revoke the projection |
| EEA residence | Residence requirements are satisfied for the deployment |

Ravenroot records the attestations; it does not infer them from the graph, call an external policy evaluator, or continuously reinterpret them. Registration, show, audit, and revoke are operator actions.

## Versioned viewer sources

V1 registrations continue to record `viewerSourceVersion: "1"` and exactly one source:

| Source | Registration input | Runtime behavior |
|---|---|---|
| `snapshot` | GraphML, graph coordinates, deployment snapshot coordinates, lifecycle, policy revision, and seven explicit attestations | Serves the immutable minimized projection captured during provisioning |
| `deployment` | A process-local deployment id | Resolves a safe projection and immutable graph-version/incarnation binding when the session is used, then permits read-only observation |

The additive v2 deployment source opts into selectable live runs. It adds run-read authority and may
request a visible Start execution affordance. Visibility is never authority: Start is absent by
default, and showing it does not grant the separately provisioned embed-execute capability. Run
identity is the exact `(tenant, deployment, graph version, incarnation, process instance)` tuple.
Only active and bounded recent terminal rows are projected; workload, lease, worker, correlation,
credential, payload, and tenant-wide metadata are excluded.

For the CLI, the presence of `--graphml` selects snapshot provisioning. Its snapshot deployment
coordinate may be supplied with the compatible `--deployment-id` spelling or the explicit
`--snapshot-deployment-id` alias. Without `--graphml`, `--deployment-id` selects a live deployment
source and all snapshot-only inputs are refused.

## Session sequence

1. The operator registers the deployment and records every gate.
2. The host requests a launch for that registered deployment.
3. The browser receives a one-time launch value.
4. The embedded viewer exchanges it for a short-lived session.
5. The viewer retrieves only the authorized read-only projection.
6. If the source is a deployment, the viewer uses its proof key and bearer to request
   `/v1/embed/observation`; the stream carries only allowlisted lifecycle and execution fields.
7. Expiry, registration revocation, access revocation, undeploy, or source replacement ends the
   attachment. A selected v2 replay gap is rebuilt from its exact durable process stream when that
   stream is complete; otherwise it ends fail closed.

The projection cannot mutate a graph, read credentials, install adapters, or expand its own scope.
V1 cannot start execution. V2 can request one server-side traversal only when both the presentation
option and independent capability are present; the presentation option alone still renders the
control but its action is refused. V2 cannot stop/restart/undeploy a deployment or
control the global engine. Exact origin and host checks apply at launch and exchange.

The deployment observation cursor is opaque, bound to the registration revision, session, deployment,
graph version, incarnation, and selected process, and valid only within the process-local replay
window. A reconnect can resume within that window. V2 clears observed state and replays the selected
process's durable stream on a gap; a truncated stream or mismatched binding requires a fresh
projection/session and never changes the registered source.

## Dynamic read-only policy

Legacy registrations remain supported unchanged. As an additive alternative, an operator can enable
dynamic grants with `RAVENROOT_EMBED_DYNAMIC_ORIGIN_POLICY=restricted` or `authenticated`. The default
is `disabled`. Restricted mode accepts only exact canonical origins from
`RAVENROOT_EMBED_DYNAMIC_ALLOWED_ORIGINS`. Authenticated mode accepts canonical HTTPS origins and
loopback HTTP (`localhost`, `127.0.0.1`, or `::1`) for local development. Parent and viewer origins
must differ.

Discovery requires an authenticated workload with `ravenroot.embed.deployment.discover`; creation,
acknowledgement, and revocation require `ravenroot.embed.session.create`. Discovery returns only
tenant-owned READY deployments hosted in the current server process, with exact deployment id,
incarnation id, graph version, and canonical digest. Creation re-resolves all of them. The resulting
opaque grant is stored only by digest, expires after a bounded TTL, is explicitly revocable, and pins
the exact source before the browser session begins. Tenant, workload identity, readiness, incarnation,
graph version, policy revision, origin, capabilities, expiry, and revocation are rechecked at the
relevant session boundary. Replacement, stop, expiry, revocation, or policy change fails closed and
never follows another deployment.

## Extension discovery

Node packages, model adapters, agent runtimes, engine adapters, persistence adapters, and connector plugins declare identity, version, compatibility, and capabilities. Discovery is descriptive, not authoritative: installing code does not grant credential, tool, egress, or deployment rights.

A missing adapter makes its dependent capability unavailable. An incompatible package is refused before it can contribute catalog entries.

## Model-provider profile

| Field | Meaning |
|---|---|
| `id` | Server identity of the owned profile |
| `adapter` | Installed adapter identifier |
| `endpoint` | Provider endpoint subject to egress policy |
| `model` | Provider model identifier |
| `credentialMode` | Credential resolution strategy |
| `credentialRef` | Server-minted credential reference |
| `usable` | Whether verification permits use |
| `reason` | Classified verification outcome |
| `detail` | Sanitized diagnostic detail |

Verification records checked steps and uses classified outcomes including `reached`, `provider-unreachable`, `adapter-not-installed`, `egress-refused`, and `credential-not-resolved`. A profile is not usable merely because it was created.

## Program sandbox contract

Program artifacts move through create, validate, test, approve, activate, and retire. A validation request returns a validated or rejected result with HTTP 200; it returns HTTP 501 when the runtime or sandbox supervisor is absent, before source execution. Active execution is bounded by the configured timeout and heap. Dual control is disabled by default and becomes mandatory only when the operator explicitly sets `RAVENROOT_ARTIFACT_DUAL_CONTROL=true`.

For operational procedure see [Embedded-viewer operations](../operator-guide/embed-operations.md). For security rationale see [Embed, privacy, and audit](../security/embed-privacy.md).

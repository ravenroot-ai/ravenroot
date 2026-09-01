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

## Session sequence

1. The operator registers the deployment and records every gate.
2. The host requests a launch for that registered deployment.
3. The browser receives a one-time launch value.
4. The embedded viewer exchanges it for a short-lived session.
5. The viewer retrieves only the authorized read-only projection.
6. Expiry, deployment revocation, or access revocation ends the session.

The projection cannot mutate a graph, start execution, read credentials, install adapters, or expand its own scope. Exact origin and host checks apply at launch and exchange.

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

Program artifacts move through create, validate, test, approve, activate, and retire. A validation request returns a validated or rejected result with HTTP 200; it returns HTTP 501 when the runtime or sandbox supervisor is absent, before source execution. Active execution is bounded by the configured timeout and heap and observes dual control by default.

For operational procedure see [Embedded-viewer operations](../operator-guide/embed-operations.md). For security rationale see [Embed, privacy, and audit](../security/embed-privacy.md).

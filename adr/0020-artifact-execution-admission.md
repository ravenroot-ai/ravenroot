# ADR 0020: Artifact execution admission and the TOCTOU boundary

- Status: Accepted
- Date: 2026-08-10
- Supersedes: Checking an artifact snapshot before executing that same mutable registry entry later
- Superseded by: None
- Public references: [AI, tools, and programmable code](../docs/security/ai-code.md), [credentials, providers, and artifacts](../docs/user-guide/integrations-artifacts.md)

## Context

Program execution previously checked an artifact's state on one registry snapshot and later handed
that snapshot to a worker. Retirement or replacement between check and use created a time-of-check
to time-of-use window, and the path did not structurally bind tenant identity.

## Decision

Execution is admitted with a single-use reservation issued by the artifact registry rather than by
passing an artifact snapshot. Redemption occurs immediately before source reaches the worker and
checks lifecycle state, tenant ownership, and reservation validity atomically at the registry
boundary. Revocation prevents later redemption and cancels work that the runtime can still identify.

Provenance verification is part of redemption through an injected verifier; core holds no signing
key. The registry contract and conformance tests cover both admission and the refusal paths.

The shipped registry and revocation state are in-memory and process-local. A restart forgets
artifacts, reservations, and revocations; subsequent execution fails closed with an unknown-artifact
result until the artifact is admitted again. `ArtifactEvidence` defines evidence shape and binding
but provides no at-rest encryption mechanism. Deployments that persist evidence must supply storage
protection themselves.

Tenant scope is enforced at artifact admission and reservation redemption. After admitted source is
handed to the program sandbox, the sandbox execution request is tenant-blind: it carries no tenant
identity with which to repeat that check. The accepted boundary therefore ends at admission and must
not be described as end-to-end tenant isolation inside the worker.

## Consequences

- Authorization follows the artifact actually executed, not an earlier snapshot.
- Tenant and provenance checks cannot be bypassed by swapping a registry entry after approval.
- Restart loses process-local registry and revocation state and fails closed rather than reconstructing
  authority from artifact bytes.
- Evidence confidentiality and worker-internal tenant isolation are not provided by this contract.
- Cancellation of already-running arbitrary code remains cooperative; admission closes the registry
  race but does not claim process-level preemption.

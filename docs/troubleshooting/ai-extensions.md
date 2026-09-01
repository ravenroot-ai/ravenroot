# AI, artifacts, plugins, and connectors

Use classified provider, sandbox, and discovery reasons to repair exactly one capability boundary without exposing credentials or bypassing supervision.

## Provider verification is not usable

**Diagnosis:** The classified reason is adapter-not-installed, credential-not-resolved, egress-refused, provider-unreachable, or a failed checked step.

**Action:** Install a compatible adapter, repair the owned credential reference, allow the exact destination, or restore provider reachability according to the reported reason. Never copy a raw key into the graph.

**Verify:** Run provider verification again and require a usable profile before Run.

## Program validation returns HTTP 501

**Diagnosis:** The program runtime or sandbox supervisor is absent; source was not executed.

**Action:** Install and configure the supervisor, then confirm timeout, heap, and dual-control settings. Do not bypass supervision by invoking source in-process.

**Verify:** Validate again, Test the artifact, obtain separate approval, activate it, and execute a bounded graph.

## A plugin contributes no nodes

**Diagnosis:** Discovery refused incompatibility, duplicate identity, invalid manifest, or startup failure.

**Action:** Read package identity, version, core range, and capability diagnostics. Replace it with a compatible package from the operator-approved source and restart through the controlled deployment path.

**Verify:** Inspect `/v1/runtime` and `/v1/node-types`; the catalog must contain only the expected new entries.

## Related contracts

- [Primary contract](../reference/embed-extension-contracts.md)
- [Control procedure](../integrator-guide/extensions-adapters.md)

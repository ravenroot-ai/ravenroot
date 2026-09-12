# AI, artifacts, plugins, and connectors

Use classified provider, sandbox, and discovery reasons to repair exactly one capability boundary without exposing credentials or bypassing supervision.

## Provider verification is not usable

**Diagnosis:** The classified reason is adapter-not-installed, credential-not-resolved, egress-refused, provider-unreachable, or a failed checked step.

**Action:** Install a compatible adapter, repair the owned credential reference, allow the exact destination, or restore provider reachability according to the reported reason. Never copy a raw key into the graph.

**Verify:** Run provider verification again and require a usable profile before Run.

This is model-backed graph-node configuration. For the separate workspace authoring assistant,
follow the status-field runbook in [Configure the authoring assistant](../operator-guide/authoring-assistant.md#availability-diagnosis).

## Program validation returns HTTP 501

**Diagnosis:** The program runtime or sandbox supervisor is absent; source was not executed.

**Action:** Install and configure the supervisor, then confirm timeout, heap, and dual-control settings. Do not bypass supervision by invoking source in-process.

**Verify:** Validate again, Test the artifact, obtain separate approval, activate it, and execute a bounded graph.

## A plugin contributes no nodes

**Diagnosis:** Compare `./plugin.sh list`, `RAVENROOT_ENABLED_PLUGINS`, startup diagnostics, and
`ravenroot node-types`. A valid installed bundle can still be absent because its manifest ID was not
enabled or because the image predates its installation. Discovery can also refuse unknown or
duplicate IDs, invalid/tampered bytes, SDK incompatibility, missing classes/dependencies, duplicate
node behaviors, or a missing package service grant.

**Action:** Use the exact manifest ID, add only the required service grant, and rebuild the image if
bundle bytes changed. Restart or recreate the service; do not use `service.sh --skipimage` when the
current image lacks the bundle. Replace bytes only through the reviewed update procedure.

**Verify:** Require a healthy startup and the exact expected IDs in `/v1/node-types`; then Test the
bundle's minimal example. Files in a convention directory or a successful build are not activation
evidence.

## A removed bundle prevents startup

**Diagnosis:** The manifest ID remains in `RAVENROOT_ENABLED_PLUGINS` after `plugin.sh remove`, so
startup refuses an unknown enabled ID.

**Action:** Remove only that exact ID from the comma-separated allowlist and recreate the service.

**Verify:** `plugin.sh list` and the running catalog both omit the bundle, while all intentionally
enabled sibling bundles remain present.

## Related contracts

- [Primary contract](../reference/embed-extension-contracts.md)
- [Control procedure](../integrator-guide/extensions-adapters.md)
- [Bundle lifecycle](../operator-guide/plugin-bundles.md)

# Embedded viewing and accessibility

Consume a pre-authorized read-only projection and navigate Ravenroot without depending on pointer input alone.

## Procedure

1. An embedded viewer starts only from a host launch bound to a registered source and exact browser boundary.
2. The projection supports viewing and navigation; it exposes no Modify, execution, credential, adapter, or operator control.
3. In the full workspace, open **Deployments**, choose **Open read-only view**, then use the Cyto,
   N8N, or Elastic selector. Opening the view attaches observation only; closing it detaches and never
   stops, restarts, or undeploys the deployment.
4. Use focus order, keyboard canvas controls, alternative graph contents, Inspector labels, and status
   announcements to understand selection, lifecycle, continuity, and execution changes.
5. Treat `GAP`, `VERSION_MISMATCH`, and `DETACHED` as terminal read-only states. They clear runtime
   decoration so stale color or emphasis is not announced as current.
6. When reporting an accessibility defect, include the control, input method, announced state, and expected state without attaching sensitive graph data.

## Authority boundary

The host and operator determine whether a projection may be shown. The viewer cannot grant itself broader content or actions.

## Verification

Prove keyboard navigation in the full workspace and separately prove that the embedded projection
exposes no mutation or execution action. Compare Cyto, N8N, and Elastic in dark and light themes:
identity, label, bypass, edge meaning, lifecycle, and runtime state must remain equivalent even when
their visual geometry differs.

- [Reference contract](../reference/embed-extension-contracts.md)
- [Concept or recovery](../troubleshooting/embed-backup.md)

# Embedded viewing and accessibility

Consume a pre-authorized read-only projection and navigate Ravenroot without depending on pointer input alone.

## Procedure

1. An embedded viewer starts only from a host launch bound to a registered source and exact browser boundary.
2. The projection supports viewing and navigation; it exposes no Modify, execution, credential, adapter, or operator control.
3. In the full workspace, open **Deployments**, choose **Open read-only view**, then use **Design** or
   **Monitoring**. **Render** recomputes only the selected view; changing views restores that view's
   prior viewport and positions without fitting, laying out, or starting a simulation. Opening the
   view attaches observation only; closing it detaches and never
   stops, restarts, or undeploys the deployment. The attachment is session-only and is not restored
   as a live document after reloading the workspace.
4. For a v2 embed, use the labeled **Run** choice. With no authorized runs it is disabled and a live
   status announces the empty state. One run is selected deterministically; multiple runs remain a
   normal keyboard-operable select. A switch clears old runtime cues before the new stream attaches.
5. Use focus order, keyboard canvas controls, alternative graph contents, Inspector labels, and status
   announcements to understand selection, lifecycle, continuity, and execution changes.
6. Treat `GAP`, `VERSION_MISMATCH`, and `DETACHED` as terminal read-only states. They clear runtime
   decoration so stale color or emphasis is not announced as current.
7. **Start execution** is absent from focus and accessibility trees unless the registration's
   presentation option requests it. Its server action still requires independent execute authority.
   It starts one traversal only; it does not
   expose stop, restart, undeploy, or global engine controls.
8. When reporting an accessibility defect, include the control, input method, announced state, and expected state without attaching sensitive graph data.

## Authority boundary

The host and operator determine whether a projection may be shown. The viewer cannot grant itself broader content or actions.

## Verification

Prove keyboard navigation in the full workspace and separately prove that the embedded projection
exposes no mutation or global lifecycle action. Compare Design and Monitoring in dark and light themes:
identity, label, bypass, edge meaning, lifecycle, and runtime state must remain equivalent even when
their visual geometry differs.

- [Reference contract](../reference/embed-extension-contracts.md)
- [Concept or recovery](../troubleshooting/embed-backup.md)

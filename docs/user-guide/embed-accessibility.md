# Embedded viewing and accessibility

Consume a pre-authorized read-only projection and navigate Ravenroot without depending on pointer input alone.

## Procedure

1. An embedded viewer starts only from a host launch bound to a registered deployment and exact browser boundary.
2. The projection supports viewing and navigation; it exposes no Modify, execution, credential, adapter, or operator control.
3. In the full workspace, use focus order, keyboard canvas controls, Inspector labels, and status announcements to understand selection and execution changes.
4. When reporting an accessibility defect, include the control, input method, announced state, and expected state without attaching sensitive graph data.

## Authority boundary

The host and operator determine whether a projection may be shown. The viewer cannot grant itself broader content or actions.

## Verification

Prove keyboard navigation in the full workspace and separately prove that the embedded projection exposes no mutation or execution action.

- [Reference contract](../reference/embed-extension-contracts.md)
- [Concept or recovery](../troubleshooting/embed-backup.md)

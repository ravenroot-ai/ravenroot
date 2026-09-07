# Workspace and graph authoring

Use the canvas to create a valid document without losing the GraphML contract.

## Procedure

1. Open the workspace and enable **Modify**; viewing alone does not unlock editing controls.
2. Add nodes from the server-provided palette. Select a node or existing edge to edit its declared properties in the Inspector. With **Autosave** enabled, valid changes are saved to the document automatically; one text-editing focus session is one undo step. Turn Autosave off to keep changes pending until **Save node** or **Save edge**.
3. Connect with pointer drag or press **E**, choose a direction with the arrow keys, and confirm with **Enter**. **Escape** cancels; **R** and **Shift+R** reconnect edge endpoints. Creating a new edge remains explicit: complete its Inspector form with **Add edge**.
4. Use multi-selection, undo, and redo before export; then validate the saved document with `ravenroot validate`.

Each authenticated, workspace-aware Ravenroot service keeps a separate browser workspace for the
exact tenant returned by that service. Open document order, the selected document, lifecycle mode,
and canonical graph content return after reload. A legacy service, an offline connection, or a
service without a workspace scope keeps documents in the current browser session only; export them
before closing the page. Session-only documents are never silently moved into a tenant workspace.

Test and deployed documents are read-only snapshots. Use **File → Fork as Draft** to create a new,
independently editable document whose provenance still points to the snapshot and source graph
version. The snapshot and any deployed version remain unchanged.

Inspector saves change graph content without choosing a presentation for you. **Save node**, **Save edge**,
valid autosaves, undo, and redo retain the active document's Design arrangement, positions, viewport, and
compatible edge routes. Use an explicit **Arrange** command when you want to reposition the graph.

## Visual groups

In editable **Design**, select two or more real nodes and choose **Group selection** in the node
minibar, its **More** menu, or **Edit → Group selection** (`Ctrl/Cmd+G`). Enter a name and choose
**Create group**. Cancel leaves the document unchanged. Each node can belong to one group; groups
are flat and disjoint. A group can include parallel branches without changing their execution.

The compact summary displays the name and member count. Choose **Expand** to inspect its members.
The expanded header remains selectable and offers **Collapse**. Each group keeps its own state.
The group Inspector lists the real members; selecting a member reveals it. Groups expose no behavior
configuration or execution controls. Keyboard users can select the header or summary on the canvas
and use the **Edit** menu; touch users can use the selected node's minibar or the same menu.

Use **Rename group**, **Replace members with selection**, or **Ungroup** in the Inspector or Edit
menu. For replacement, inspect the group, select the intended real nodes, then invoke the explicit
replacement command and confirm. Replacement retains the group identity and name. Ungroup and
Delete on a summary remove only the grouping. A mixed selection of groups and real elements refuses
deletion. Deleting real members updates membership in the same undo step and dissolves a group
when fewer than two members remain. Creating, renaming, replacing, ungrouping and dragging groups
are undoable authoring edits. Duplicating a real node does not add its copy to a group.

Collapse and expansion keep zoom and external positions. They do not run an arrangement or fit the
graph; expanded detail may extend outside the viewport. Use the explicit navigation or Fit commands
when needed. A summary drag translates every member by the same amount. Arrange continues to
operate on the complete graph. Reduced-motion preferences retain the same geometry and focus.

Workspace restoration retains each document's grouping, presentation, viewport and focus under its
document and tenant identity. Same-name documents remain independent. Export GraphML to carry the
latest names, members and collapsed state to another workspace; the unsaved indicator includes
unexported presentation changes. In Test and deployed snapshots, collapse/expand stays in a local
presentation overlay and leaves the pinned graph version unchanged. Fork as Draft retains an
independent grouping state.

If grouping metadata is malformed or from a newer version, the entire real graph is shown and the
original metadata is preserved. In editable Design, **Edit → Remove unsupported visual group
metadata** provides an explicit, undoable repair before creating new groups.

Visual groups never become runtime nodes or executable subgraphs. Real nodes, edges, outcomes,
parallel flags and joins remain the execution model, including while their presentation is collapsed.
Monitoring keeps observations attached to those real members. Counts of running, failed or waiting
members describe the members' current observations; they are not a successful or failed execution
of the visual group itself.

## Authority boundary

An author controls document structure, labels, and behavior properties. Catalog installation, credentials, and deployment remain outside the workspace.

## Verification

Reopen the exported GraphML, confirm node and edge counts plus Inspector values, then require CLI validation exit 0.

- [Reference contract](../reference/graphml.md)
- [Concept or recovery](../troubleshooting/graph-execution.md)

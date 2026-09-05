# Workspace and graph authoring

Use the canvas to create a valid document without losing the GraphML contract.

## Procedure

1. Open the workspace and enable **Modify**; viewing alone does not unlock editing controls.
2. Add nodes from the server-provided palette. Select a node or existing edge to edit its declared properties in the Inspector. With **Autosave** enabled, valid changes are saved to the document automatically; one text-editing focus session is one undo step. Turn Autosave off to keep changes pending until **Save node** or **Save edge**.
3. Connect with pointer drag or press **E**, choose a direction with the arrow keys, and confirm with **Enter**. **Escape** cancels; **R** and **Shift+R** reconnect edge endpoints. Creating a new edge remains explicit: complete its Inspector form with **Add edge**.
4. Use multi-selection, undo, and redo before export; then validate the saved document with `ravenroot validate`.

## Authority boundary

An author controls document structure, labels, and behavior properties. Catalog installation, credentials, and deployment remain outside the workspace.

## Verification

Reopen the exported GraphML, confirm node and edge counts plus Inspector values, then require CLI validation exit 0.

- [Reference contract](../reference/graphml.md)
- [Concept or recovery](../troubleshooting/graph-execution.md)

# Repository-backed graph authoring

When an operator enables Git authoring, the main **Open** and **Save** actions become **Open from repository** and **Save draft to repository**. **Open local file** and **Download GraphML** remain available in the **File** menu.

Until the runtime configuration proves whether this workspace is local or repository-backed, the main Open and Save actions stay unavailable. An authorization failure, malformed response, or unreachable configured runtime keeps them unavailable instead of silently downloading a local file. Use **Retry connection** to recover. **Open local file** and **Download GraphML** remain explicit offline actions in the File menu during that failure state.

- **Open from repository** lists graphs in your assigned workspace and opens the exact stored GraphML.
- **Save draft to repository** creates or updates a draft. Ravenroot shows an authored version and keeps edits made while a save is running unsaved.
- **Repository history** lists source commits and can restore one as a new commit.
- **Discard draft changes** replaces the draft with the reviewed release.
- **Delete repository draft** removes the draft file while Git retains its history.
- **Propose release review** opens or reuses a review pull request in the graph repository.
- **Published versions** lists CI-verified immutable versions. Choose a current version to import and register, or an older version to roll back. Registration does not run the graph.

The document heading shows the authored `vN`, short Git revision, draft or released-source state, publication state, and an in-progress or failed save. When a graph has both a newer draft and a reviewed release, **Open from repository** lists both sources. The released source is read-only; **Open repository draft** opens its corresponding editable draft without changing either source.

If another save, release merge, or publication changes the graph before your mutation completes, Ravenroot refuses the stale request. Reopen the graph, review the new version or diff, and apply your change again. Repeating an uncertain request is safe when the client uses the same request identity.

“Released” means the exact version exists on the configured reviewed branch. “Published” additionally means release CI validated and placed the immutable GraphML and digest manifest in the operator's artifact store. Only a published version can be deployed from this workflow.

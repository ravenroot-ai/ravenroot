# Repository-backed graph authoring

When an operator enables Git authoring, the main **Open** and **Save** actions become **Open from repository** and **Save draft to repository**. **Open local file** and **Download GraphML** remain available in the **File** menu.

Until the runtime configuration proves whether this workspace is local or repository-backed, the main Open and Save actions stay unavailable. An authorization failure, malformed response, or unreachable configured runtime keeps them unavailable instead of silently downloading a local file. Use **Retry connection** to recover. **Open local file** and **Download GraphML** remain explicit offline actions in the File menu during that failure state.

- **Open from repository** lists graphs in your assigned workspace and opens the exact stored GraphML.
- **Save draft to repository** creates or updates a draft. Ravenroot shows an authored version and keeps edits made while a save is running unsaved.
- **Repository history** lists source commits and can restore one as a new commit.
- **Discard draft changes** replaces the draft with the reviewed release.
- **Delete repository draft** removes the draft file while Git retains its history.
- **Propose release review** opens or reuses a review pull request (GitHub) or merge request (GitLab) in the graph repository.
- **Published versions** lists CI-verified immutable versions. Choose a current version to import and register, or an older version to roll back. Registration does not run the graph.

The document heading shows the authored `vN`, short Git revision, draft or released-source state, publication state, and an in-progress or failed save. When a graph has both a newer draft and a reviewed release, **Open from repository** lists both sources. The released source is read-only; **Open repository draft** opens its corresponding editable draft without changing either source.

If another save, release merge, or publication changes the graph before your mutation completes, Ravenroot refuses the stale request. Reopen the graph, review the new version or diff, and apply your change again. Repeating an uncertain request is safe when the client uses the same request identity.

A startup `?file=` URL remains a browser import when Git authoring is enabled. It does not inherit a
repository filename, revision, graph identity, or released-source state. **Save draft to repository**
therefore asks for a repository filename before its first save and applies the same revision conflict
checks as any other repository draft. **Open from repository** continues to open managed draft or
released sources separately; a released source stays read-only and offers **Open existing draft** when a valid successor exists, or confirmed **Create next version** when eligible.
The source URL is not used as repository identity or changed by these actions.

“Released” means the exact version exists on the configured reviewed branch. “Published” additionally means release CI validated and placed the immutable GraphML and digest manifest in the operator's artifact store. Only a published version can be deployed from this workflow.


Use `?repositoryDocument=orders.graphml&source=draft` to open a managed editable draft, or `source=release` for the read-only release. Omit `source` to select draft. Authenticate to the configured runtime first; opening waits for configuration and authentication. The link cannot select a repository, tenant or credential. Invalid or unavailable managed links show an error. Do not combine them with `?file=`, which remains a content import.

**Open from repository** offers selectable draft/release sources, names and paths, versions and publication state. Search filters loaded documents; **Load more** continues the inventory and the picker explicitly identifies incomplete or failed pages. Use the source filter to focus on drafts or releases. These sources do not indicate another user's live editing presence or locks.

From a released view, **Create next version** appears only with server support and document write permission. Confirm the displayed vN → vN+1 change. This copies the release into the same managed document's editable successor, even while publication is pending or failed. If a successor already exists, **Open existing draft** opens it. Drafts have no increment action. Saving a released vN with changed content is refused; opening a link never creates a version. Repeated saves, reverting content and restoring history keep the successor's version. Explicit discard restores release content while retaining a successor already assigned.

Reopening a source that already has local edits, a running save or a refused save activates that open document and preserves its graph, undo history and observed revision. Reopening a clean source fetches its current version into the same document; edits made while that fetch is pending are preserved instead. Workspace restoration keeps unsaved managed graph changes protected; undo stacks remain local to the current session. Repository identity includes the configured service, workspace and repository. Older documents without that binding, or documents from another repository connection, remain available for explicit download but cannot save into the current repository; protect the work and reapply it to a freshly opened managed source.

A refused save shows a visible accessible message above the editor and retains your unsaved edits. On a conflict, reopen the managed draft and review changes before reapplying edits. An imported document cannot overwrite an existing filename without its revision: choose a new filename or open the managed document and reapply the changes. For authorization failures, authenticate with permission before retrying.

A legacy divergent draft still numbered like its release needs deliberate reconciliation: download/back up all work, review the diff, explicitly discard to the released baseline, create the successor, then reapply the protected edits. Never discard before protecting work.

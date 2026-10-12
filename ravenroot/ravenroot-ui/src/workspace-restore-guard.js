export function captureWorkspaceRestoreBaseline(workspace) {
  return {
    activeId: workspace.activeId,
    documents: workspace.documents.map(document_ => ({
      document: document_,
      id: document_.id,
      incarnation: document_.incarnation,
      graph: document_.graph,
      history: document_.history,
      revision: document_.history?.revision(),
      dirty: Boolean(document_.history?.isDirty()),
      authoring: JSON.stringify(document_.authoring ?? null),
    })),
  };
}

export function workspaceMatchesRestoreBaseline(workspace, baseline) {
  return baseline.activeId === workspace.activeId
    && baseline.documents.length === workspace.documents.length
    && baseline.documents.every((entry, index) => {
    const current = workspace.documents[index];
    return current === entry.document && current?.id === entry.id
      && current.incarnation === entry.incarnation && current.graph === entry.graph
      && current.history === entry.history && current.history?.revision() === entry.revision
      && Boolean(current.history?.isDirty()) === entry.dirty
      && JSON.stringify(current.authoring ?? null) === entry.authoring;
  });
}

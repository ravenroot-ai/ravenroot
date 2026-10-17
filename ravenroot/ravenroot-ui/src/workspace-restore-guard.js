function canonicalValue(value) {
  if (Array.isArray(value)) return value.map(canonicalValue);
  if (!value || typeof value !== 'object') return value ?? null;
  return Object.fromEntries(Object.keys(value).sort()
    .map(key => [key, canonicalValue(value[key])]));
}

function modePresentationIntent(state) {
  if (!state || typeof state !== 'object') return null;
  return {
    visualGroupState: state.visualGroupState ?? null,
    layoutMode: state.layoutMode ?? null,
    forces: state.forces ?? null,
  };
}

/**
 * Captures stable user-owned presentation choices without renderer output such as positions,
 * viewport, selection, focus, or simulation progress.
 */
export function stableUserPresentationFingerprint(document_) {
  const fallback = document_?.presentation || {};
  const viewStates = document_?.viewStates ?? fallback.viewStates;
  return JSON.stringify(canonicalValue({
    fontSize: document_?.fontSize ?? fallback.fontSize ?? null,
    renderMode: document_?.renderMode ?? fallback.renderMode ?? null,
    layoutMode: document_?.layoutMode ?? fallback.layoutMode ?? null,
    designArrangement: document_?.designArrangement ?? fallback.designArrangement ?? null,
    visualStyle: document_?.visualStyle ?? fallback.visualStyle ?? null,
    filterActive: Boolean(document_?.filterActive),
    traceActive: Boolean(document_?.traceActive),
    n8nActive: Boolean(document_?.n8nActive),
    monitoringForces: document_?.monitoringForces ?? fallback.monitoringForces ?? null,
    visualGroupState: document_?.visualGroupState ?? fallback.visualGroupState ?? null,
    visualGroupPresentationDirty: Boolean(document_?.visualGroupPresentationDirty
      ?? fallback.visualGroupPresentationDirty),
    viewStates: {
      design: modePresentationIntent(viewStates?.design),
      monitoring: modePresentationIntent(viewStates?.monitoring),
    },
  }));
}

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
      presentation: stableUserPresentationFingerprint(document_),
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
      && JSON.stringify(current.authoring ?? null) === entry.authoring
      && stableUserPresentationFingerprint(current) === entry.presentation;
  });
}

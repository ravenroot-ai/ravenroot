import { describe, expect, it } from 'vitest';

import {
  captureWorkspaceRestoreBaseline,
  stableUserPresentationFingerprint,
  workspaceMatchesRestoreBaseline,
} from '../src/workspace-restore-guard.js';

function record(id = 'placeholder') {
  let revision = 0;
  let dirty = false;
  return {
    id,
    incarnation: `${id}-incarnation`,
    graph: { id },
    authoring: { providerDocumentId: null, revision: null },
    fontSize: 20,
    renderMode: 'design',
    layoutMode: 'cyto',
    designArrangement: null,
    visualStyle: 'cyto',
    filterActive: false,
    traceActive: false,
    n8nActive: false,
    monitoringForces: { repulsion: 115, attraction: 0.2 },
    visualGroupState: { group: { collapsed: false } },
    visualGroupPresentationDirty: false,
    viewStates: { design: { visualGroupState: { group: { collapsed: false } },
      layoutMode: 'cyto', canvasState: { positions: { volatile: { x: 1, y: 2 } } } } },
    history: {
      revision: () => revision,
      isDirty: () => dirty,
      edit: () => { revision += 1; dirty = true; },
      undo: () => { revision += 1; dirty = false; },
    },
  };
}

describe('workspace restoration guard', () => {
  it('accepts an unchanged live workspace after a slow snapshot read', () => {
    const document_ = record();
    const workspace = { documents: [document_] };
    const baseline = captureWorkspaceRestoreBaseline(workspace);
    expect(workspaceMatchesRestoreBaseline(workspace, baseline)).toBe(true);
  });

  it('preserves a user edit made while restoration is pending', () => {
    const document_ = record();
    const workspace = { documents: [document_] };
    const baseline = captureWorkspaceRestoreBaseline(workspace);
    document_.history.edit();
    expect(workspaceMatchesRestoreBaseline(workspace, baseline)).toBe(false);
  });

  it('treats edit then undo as live work and does not replace it', () => {
    const document_ = record();
    const workspace = { documents: [document_] };
    const baseline = captureWorkspaceRestoreBaseline(workspace);
    document_.history.edit();
    document_.history.undo();
    expect(document_.history.isDirty()).toBe(false);
    expect(workspaceMatchesRestoreBaseline(workspace, baseline)).toBe(false);
  });

  it('rejects document, incarnation, graph, history, order and repository-binding changes', () => {
    const factories = [
      (workspace, document_) => workspace.documents.push(record('second')),
      (_workspace, document_) => { document_.incarnation = 'replacement'; },
      (_workspace, document_) => { document_.graph = { id: 'replacement' }; },
      (_workspace, document_) => { document_.history = record('history').history; },
      (_workspace, document_) => { document_.authoring.providerDocumentId = 'repo.graphml'; },
      (_workspace, document_) => { document_.authoring.revision = { draft: 'new-revision' }; },
    ];
    for (const mutate of factories) {
      const document_ = record();
      const workspace = { documents: [document_] };
      const baseline = captureWorkspaceRestoreBaseline(workspace);
      mutate(workspace, document_);
      expect(workspaceMatchesRestoreBaseline(workspace, baseline)).toBe(false);
    }
  });

  it('rejects an active-document change while restoration is pending', () => {
    const first = record('first');
    const second = record('second');
    const workspace = { activeId: first.id, documents: [first, second] };
    const baseline = captureWorkspaceRestoreBaseline(workspace);
    workspace.activeId = second.id;
    expect(workspaceMatchesRestoreBaseline(workspace, baseline)).toBe(false);
  });

  it('rejects stable user presentation changes while restoration is pending', () => {
    const factories = [
      document_ => { document_.fontSize = 27; },
      document_ => { document_.renderMode = 'monitoring'; },
      document_ => { document_.layoutMode = 'elastic'; },
      document_ => { document_.designArrangement = 'layered'; },
      document_ => { document_.visualStyle = 'n8n4'; },
      document_ => { document_.filterActive = true; },
      document_ => { document_.traceActive = true; },
      document_ => { document_.n8nActive = true; },
      document_ => { document_.monitoringForces.repulsion = 240; },
      document_ => { document_.visualGroupState.group.collapsed = true; },
      document_ => { document_.visualGroupPresentationDirty = true; },
    ];
    for (const mutate of factories) {
      const document_ = record();
      const workspace = { documents: [document_] };
      const baseline = captureWorkspaceRestoreBaseline(workspace);
      mutate(document_);
      expect(workspaceMatchesRestoreBaseline(workspace, baseline)).toBe(false);
    }
  });

  it('ignores volatile renderer and automatic layout output', () => {
    const document_ = record();
    const baseline = stableUserPresentationFingerprint(document_);
    document_.viewStates.design.canvasState.positions.volatile = { x: 900, y: 700 };
    document_.canvasState = { zoom: 3, pan: { x: 10, y: 20 } };
    document_.cursorId = 'automatic-focus';
    document_.layoutBusy = true;
    expect(stableUserPresentationFingerprint(document_)).toBe(baseline);
  });
});

import { describe, expect, it } from 'vitest';

import {
  captureDocumentCloseSnapshot,
  classifyDocumentCloseTargets,
  resolveDocumentCloseSnapshot,
} from '../src/document-close-plan.js';
import { createDocumentRecord, createWorkspace } from '../src/workspace.js';

function record(id, { dirty = false } = {}) {
  return createDocumentRecord({
    id,
    history: { isDirty: () => dirty },
  });
}

describe('close-all document planning', () => {
  it('captures exact record incarnations and excludes documents opened later', () => {
    const workspace = createWorkspace();
    const first = workspace.add(record('first'));
    const snapshot = captureDocumentCloseSnapshot(workspace.documents);
    workspace.add(record('later'));

    expect(resolveDocumentCloseSnapshot(workspace, snapshot)).toEqual([first]);
  });

  it('does not target a replacement record or replacement incarnation', () => {
    const workspace = createWorkspace();
    const original = workspace.add(record('same'));
    const snapshot = captureDocumentCloseSnapshot(workspace.documents);

    original.incarnation = 'replacement-incarnation';
    expect(resolveDocumentCloseSnapshot(workspace, snapshot)).toEqual([]);

    workspace.close('same');
    workspace.add(record('same'));
    expect(resolveDocumentCloseSnapshot(workspace, snapshot)).toEqual([]);
  });

  it('classifies dirty documents and active local sessions independently', () => {
    const clean = record('clean');
    const dirty = record('dirty', { dirty: true });
    const listening = record('listening');
    listening.sourceSession.sessionId = 'session-1';
    listening.sourceSession.state = 'LISTENING';

    expect(classifyDocumentCloseTargets([clean, dirty, listening])).toEqual({
      dirty: [dirty],
      activeSessions: [listening],
    });
  });
});

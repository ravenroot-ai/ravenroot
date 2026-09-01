import { describe, expect, it } from 'vitest';

import {
  PENDING_EXECUTION,
  bindExecution,
  createDocumentRecord,
  createWorkspace,
  detachExecution,
  documentForRuntimeEvent,
  hasUnsavedWork,
} from '../src/workspace.js';

function workspaceOf(...names) {
  const workspace = createWorkspace();
  names.forEach((name, index) => workspace.add(createDocumentRecord({ id: `doc-${index + 1}`, name })));
  return workspace;
}

// ── The definition of "the active document" ──────────────────────────────────────────────────────
//
// Pinned by tests rather than left to emerge. The inspector, the activity log, the minimap and the
// statistics all follow it, so an ambiguous answer shows up as a panel describing the wrong graph.

describe('exactly one document is active, and which one is not left to chance', () => {
  it('gives each open document an opaque incarnation independent of its reusable tab id', () => {
    const first = createDocumentRecord({ id: 'doc-1' });
    const second = createDocumentRecord({ id: 'doc-1' });
    expect(first.incarnation).toBeTruthy();
    expect(second.incarnation).toBeTruthy();
    expect(first.incarnation).not.toBe(second.incarnation);
  });

  it('keeps display identity distinct from the export filename', () => {
    const document_ = createDocumentRecord({
      id: 'doc-1', name: 'orders.graphml', displayName: 'orders.graphml (2)',
    });

    expect(document_.name).toBe('orders.graphml');
    expect(document_.displayName).toBe('orders.graphml (2)');
  });

  it('gives each document an isolated transient renderer handle', () => {
    const first = createDocumentRecord({ id: 'doc-1' });
    const second = createDocumentRecord({ id: 'doc-2' });
    const renderer = {};

    first.renderer = renderer;

    expect(first.renderer).toBe(renderer);
    expect(second.renderer).toBeNull();
  });

  it('gives every fresh document an independent Design visual default', () => {
    const first = createDocumentRecord({ id: 'doc-1' });
    const second = createDocumentRecord({ id: 'doc-2' });

    expect(first.renderMode).toBe('design');
    expect(second.renderMode).toBe('design');
    expect(first.visualStyle).toBe('cyto');
    expect(second.visualStyle).toBe('cyto');

    first.visualStyle = 'n8n4';
    expect(second.visualStyle).toBe('cyto');
  });

  it('has no active document while empty, and reports null rather than a stale record', () => {
    const workspace = createWorkspace();

    expect(workspace.active).toBeNull();
    expect(workspace.size).toBe(0);
  });

  it('activates the document it has just opened', () => {
    const workspace = workspaceOf('a.graphml');
    expect(workspace.active.name).toBe('a.graphml');

    workspace.add(createDocumentRecord({ id: 'doc-2', name: 'b.graphml' }));

    expect(workspace.active.name).toBe('b.graphml');
  });

  it('keeps exactly one active document at every point in a sequence of opens', () => {
    const workspace = workspaceOf('a', 'b', 'c');

    expect(workspace.size).toBe(3);
    expect(workspace.documents.filter(document_ => document_ === workspace.active)).toHaveLength(1);
  });

  it('refuses to activate an id it does not hold, rather than leaving nothing active', () => {
    const workspace = workspaceOf('a', 'b');

    expect(workspace.activate('doc-99')).toBeNull();
    expect(workspace.active.id).toBe('doc-2');
  });

  it('activates by id and nothing else changes', () => {
    const workspace = workspaceOf('a', 'b', 'c');

    workspace.activate('doc-1');

    expect(workspace.active.id).toBe('doc-1');
    expect(workspace.documents.map(document_ => document_.id)).toEqual(['doc-1', 'doc-2', 'doc-3']);
  });
});

describe('closing a document moves the activation deterministically', () => {
  it('gives the activation to the previous sibling when the active document is closed', () => {
    const workspace = workspaceOf('a', 'b', 'c');
    workspace.activate('doc-2');

    workspace.close('doc-2');

    expect(workspace.active.id).toBe('doc-1');
    expect(workspace.documents.map(document_ => document_.id)).toEqual(['doc-1', 'doc-3']);
  });

  it('falls back to the next sibling when the closed document was the first', () => {
    const workspace = workspaceOf('a', 'b', 'c');
    workspace.activate('doc-1');

    workspace.close('doc-1');

    expect(workspace.active.id).toBe('doc-2');
  });

  it('leaves the activation alone when a document that was not active is closed', () => {
    const workspace = workspaceOf('a', 'b', 'c');
    workspace.activate('doc-3');

    workspace.close('doc-1');

    expect(workspace.active.id).toBe('doc-3');
  });

  it('has no active document once the last one is closed', () => {
    const workspace = workspaceOf('only');

    workspace.close('doc-1');

    expect(workspace.active).toBeNull();
    expect(workspace.size).toBe(0);
  });

  it('reports closing an unknown id rather than silently emptying the workspace', () => {
    const workspace = workspaceOf('a');

    expect(workspace.close('doc-99')).toBeNull();
    expect(workspace.size).toBe(1);
  });
});

// ── Isolation between documents ──────────────────────────────────────────────────────────────────

describe('three simultaneous documents keep their own state', () => {
  it('gives each document its own view state, and changing one changes only that one', () => {
    const workspace = workspaceOf('a', 'b', 'c');
    const [first, second, third] = workspace.documents;

    second.layoutMode = 'elastic';
    second.renderMode = 'monitoring';
    second.visualStyle = 'cyto';
    second.filterActive = { elType: 'node', type: 'agent' };
    second.traceActive = true;
    second.cursorId = 'node-7';
    second.fontSize = 26;

    expect(first.layoutMode).toBe('cyto');
    expect(third.layoutMode).toBe('cyto');
    expect([first.renderMode, third.renderMode]).toEqual(['design', 'design']);
    expect([first.visualStyle, third.visualStyle]).toEqual(['cyto', 'cyto']);
    expect(first.filterActive).toBeNull();
    expect(third.filterActive).toBeNull();
    expect([first.traceActive, third.traceActive]).toEqual([false, false]);
    expect([first.cursorId, third.cursorId]).toEqual([null, null]);
    expect([first.fontSize, third.fontSize]).toEqual([20, 20]);
  });

  // (UI-12): the contract specifies font size is a property of the document, taking the place
  // `layoutMode`/`filterActive`/`traceActive`/`n8nActive`/`cursorId` already occupy above, rather than
  // living only in the `#font-slider` DOM element the way it did before this field existed. This is
  // the cheap guard, not the proof: it only shows the record has somewhere to keep the value. Whether
  // `app.js` actually reads and writes it when a document is opened, switched to or closed is asserted
  // behaviourally by `e2e/font-size-per-document.spec.js`, because a passing record-shape test here
  // proves nothing about whether `initCy` still resets every document to a constant on open.
  it('gives a fresh document the same font size initCy has always applied, per-document rather than shared', () => {
    const workspace = workspaceOf('a', 'b');
    const [first, second] = workspace.documents;

    expect(first.fontSize).toBe(20);
    expect(second.fontSize).toBe(20);

    first.fontSize = 26;

    expect(first.fontSize).toBe(26);
    expect(second.fontSize).toBe(20);
  });

  it('gives each document its own finished-execution set, so one run cannot answer for another', () => {
    const workspace = workspaceOf('a', 'b', 'c');
    const [first, second, third] = workspace.documents;

    second.execution.finished.add('exec-2');

    expect(second.execution.finished.has('exec-2')).toBe(true);
    expect(first.execution.finished.has('exec-2')).toBe(false);
    expect(third.execution.finished.has('exec-2')).toBe(false);
  });

  it('never shares a record between two documents', () => {
    const workspace = workspaceOf('a', 'b', 'c');
    const [first, second, third] = workspace.documents;

    expect(new Set([first.execution, second.execution, third.execution]).size).toBe(3);
    expect(new Set([first.execution.finished, second.execution.finished, third.execution.finished]).size).toBe(3);
  });
});

// ── Runtime event routing ────────────────────────────────────────────────────────────────────────

describe('a runtime event reaches the document that owns the run, and no other', () => {
  function boundWorkspace() {
    const workspace = workspaceOf('a', 'b', 'c');
    const [first, second, third] = workspace.documents;
    first.execution = { executionId: 'exec-1', graphVersion: 'v1', finished: new Set() };
    second.execution = { executionId: 'exec-2', graphVersion: 'v2', finished: new Set() };
    third.execution = { executionId: 'exec-3', graphVersion: 'v3', finished: new Set() };
    return workspace;
  }

  it('routes each of three concurrent runs to its own document', () => {
    const workspace = boundWorkspace();

    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-1', graphVersion: 'v1' }).id).toBe('doc-1');
    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-2', graphVersion: 'v2' }).id).toBe('doc-2');
    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-3', graphVersion: 'v3' }).id).toBe('doc-3');
  });

  it('routes the canonical form produced from a durable SSE event', () => {
    const workspace = boundWorkspace();
    const durableCanonical = {
      eventType: 'EXECUTION_FAILED', type: 'EXECUTION_FAILED',
      traversalId: 'exec-2', executionId: 'exec-2', graphVersion: 'v2',
    };

    expect(documentForRuntimeEvent(workspace, durableCanonical).id).toBe('doc-2');
  });

  it('drops an event whose execution matches no open document', () => {
    const workspace = boundWorkspace();

    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-9', graphVersion: 'v1' })).toBeNull();
  });

  it('drops an event for the right execution but a stale graph version', () => {
    const workspace = boundWorkspace();

    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-2', graphVersion: 'v-old' })).toBeNull();
  });

  it('uses process incarnation as positive stale evidence when id and graph version are reused', () => {
    const workspace = boundWorkspace();
    workspace.documents[0].execution.processInstanceId = 'process-current';

    expect(documentForRuntimeEvent(workspace, {
      executionId: 'exec-1', graphVersion: 'v1', processInstanceId: 'process-old',
    })).toBeNull();
    expect(documentForRuntimeEvent(workspace, {
      executionId: 'exec-1', graphVersion: 'v1', processInstanceId: 'process-current',
    })?.id).toBe('doc-1');
  });

  it('does not reject a truthful current event merely because a legacy adapter omits process identity', () => {
    const workspace = boundWorkspace();
    workspace.documents[0].execution.processInstanceId = 'process-current';

    expect(documentForRuntimeEvent(workspace, {
      executionId: 'exec-1', graphVersion: 'v1',
    })?.id).toBe('doc-1');
  });

  it('drops an event carrying no execution id at all', () => {
    const workspace = boundWorkspace();

    expect(documentForRuntimeEvent(workspace, { nodeId: 'n1' })).toBeNull();
    expect(documentForRuntimeEvent(workspace, null)).toBeNull();
  });

  it('lets a document that has submitted but has no id yet claim the events it is waiting for', () => {
    const workspace = workspaceOf('a', 'b');
    workspace.documents[1].execution.executionId = PENDING_EXECUTION;

    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-new' }).id).toBe('doc-2');
  });

  it('prefers the document that owns the execution over one that is merely waiting', () => {
    const workspace = boundWorkspace();
    workspace.documents[2].execution.executionId = PENDING_EXECUTION;

    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-1', graphVersion: 'v1' }).id).toBe('doc-1');
  });

  it('stops projecting a closed document\'s run without pretending the run ended', () => {
    const workspace = boundWorkspace();
    const [, second] = workspace.documents;

    detachExecution(second);

    // The execution is still running on the server. What changed is only that nothing on the client
    // claims its events any more.
    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-2', graphVersion: 'v2' })).toBeNull();
    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-1', graphVersion: 'v1' }).id).toBe('doc-1');
  });

  it('replaces and clears the origin-stable reconciliation client with the execution binding', () => {
    const document_ = createDocumentRecord({ id: 'doc-1' });
    const firstClient = { execution: () => {} };
    const secondClient = { execution: () => {} };

    bindExecution(document_, 'exec-1', 'v1', firstClient, 'process-1');
    expect(document_.execution.reconciliationClient).toBe(firstClient);
    expect(document_.execution.processInstanceId).toBe('process-1');
    const firstGeneration = document_.execution.generation;
    document_.execution.commandFlight = { generation: firstGeneration };
    const oldClaim = {
      token: { executionId: 'exec-1', client: firstClient, controller: new AbortController() },
      fetchPending: true, reportClaimed: false, completed: false,
    };
    document_.execution.outcomeClaim = oldClaim;
    bindExecution(document_, 'exec-2', 'v2', secondClient, 'process-2');
    expect(document_.execution.reconciliationClient).toBe(secondClient);
    expect(document_.execution.processInstanceId).toBe('process-2');
    expect(document_.execution.generation).toBe(firstGeneration + 1);
    expect(document_.execution.commandFlight).toBeNull();
    expect(document_.execution.outcomeClaim).toBeNull();
    expect(document_.execution.retiredOutcomeClaim).toBe(oldClaim);
    detachExecution(document_);
    expect(document_.execution.reconciliationClient).toBeNull();
    expect(document_.execution.processInstanceId).toBeNull();
    expect(document_.execution.generation).toBe(firstGeneration + 2);
    expect(document_.execution.outcomeClaim).toBeNull();
    expect(document_.execution.retiredOutcomeClaim).toBeNull();
  });

  // ── The pending fallback is a guess and must not outrank a fact ──────
  //
  // The combination that matters is the one the suite never built: a stale event AND a document
  // waiting for an id. Each alone was already covered, and each alone passed, which is exactly why
  // the combined defect was not exposed.

  it('drops a stale-version event instead of handing it to a document that is merely pending', () => {
    const workspace = boundWorkspace();
    workspace.documents[2].execution.executionId = PENDING_EXECUTION;

    // exec-2 belongs to doc-2, whose graph has since been republished. The event is stale, so it is
    // dropped — it is emphatically NOT evidence about doc-3's unrelated submission.
    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-2', graphVersion: 'v-old' })).toBeNull();
  });

  it('still lets the pending document claim an execution id no open document holds', () => {
    const workspace = boundWorkspace();
    workspace.documents[2].execution.executionId = PENDING_EXECUTION;

    // The narrowing above must not cost the pending document the events it is genuinely waiting for.
    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-brand-new' }).id).toBe('doc-3');
  });

  // ── THE GRAPH-VERSION GUARD RETAINS THE SINGLE-DOCUMENT RULE ──

  it('drops an event carrying no graph version at all when the document has one', () => {
    const workspace = boundWorkspace();

    // The single-document editor discarded this: `activeGraphVersion && event.graphVersion !==
    // activeGraphVersion` is true when the event's version is undefined. Changing the guard to
    // "both present and different" silently began accepting it. Belonging to a run
    // has to be shown, not merely left unrefuted.
    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-2' })).toBeNull();
  });

  it('accepts a version-less event for a document that has no version of its own', () => {
    const workspace = workspaceOf('a');
    workspace.documents[0].execution.executionId = 'exec-1';

    // The guard is about contradicting a known version, not about demanding one exist.
    expect(documentForRuntimeEvent(workspace, { executionId: 'exec-1' }).id).toBe('doc-1');
  });
});

// ── Leaving the page is a question about the workspace ────────────────

describe('unsaved work is asked of every open document, not just the visible one', () => {
  function documentWithHistory(id, dirty) {
    return createDocumentRecord({ id, name: id, history: { isDirty: () => dirty } });
  }

  it('reports unsaved work when a background document is the dirty one', () => {
    const workspace = createWorkspace();
    workspace.add(documentWithHistory('doc-1', false));
    workspace.add(documentWithHistory('doc-2', true));
    workspace.activate('doc-1');

    // doc-2 is not the document in front of the user, and it is precisely the one whose unsaved
    // edits would otherwise be thrown away without a word.
    expect(hasUnsavedWork(workspace)).toBe(true);
  });

  it('reports no unsaved work when every document is saved', () => {
    const workspace = createWorkspace();
    workspace.add(documentWithHistory('doc-1', false));
    workspace.add(documentWithHistory('doc-2', false));

    expect(hasUnsavedWork(workspace)).toBe(false);
  });

  it('reports no unsaved work for an empty workspace', () => {
    expect(hasUnsavedWork(createWorkspace())).toBe(false);
  });

  it('treats a document that has no history yet as clean rather than throwing', () => {
    const workspace = createWorkspace();
    workspace.add(createDocumentRecord({ id: 'doc-1', name: 'fresh' }));

    expect(hasUnsavedWork(workspace)).toBe(false);
  });
});

// ── THE ASSISTANT'S EVENT TAIL IS PER DOCUMENT ─────────────────────────────────────────────────
//
// A module-scoped buffer filtered only at write time would let File → New show the assistant's
// `events` chip as "attached" while carrying the previous graph's executionIds. Record-owned storage
// makes that state unconstructible rather than relying on resets, so this test asserts isolation at
// the record where the guarantee lives.
describe('the per-document runtime event tail', () => {
  it('gives every document its own array, never a shared one', () => {
    const first = createDocumentRecord({ id: 'doc-1' });
    const second = createDocumentRecord({ id: 'doc-2' });

    expect(first.execution.events).toEqual([]);
    expect(second.execution.events).toEqual([]);
    // Not merely equal — a SHARED array would also be equal, and shared is the defect.
    expect(first.execution.events).not.toBe(second.execution.events);

    first.execution.events.push({ executionId: 'exec-a', nodeId: 'alpha' });
    expect(second.execution.events).toEqual([]);
  });

  it('sits alongside the other per-document run state, not beside it in workspace scope', () => {
    const record = createDocumentRecord({ id: 'doc-1' });
    // Every field describing one document's run lives under the same key. A future reader adding
    // run state has one obvious place to put it, which is how this defect is prevented next time.
    expect(Object.keys(record.execution).sort())
      .toEqual([
        'commandFlight', 'events', 'executionId', 'finished', 'generation', 'graphVersion', 'monitoringFlow',
        'outcomeClaim', 'processInstanceId', 'reconciliationClient', 'reconciliationController',
        'reconciliationState', 'retiredOutcomeClaim',
      ]);
  });
});

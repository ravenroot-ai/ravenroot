import {
  DEFAULT_RENDER_MODE,
  DEFAULT_VISUAL_STYLE,
} from './graph-view-state.js';
import { retireExecutionOutcomeClaim } from './execution-reconciliation.js';

// The workspace: an ordered list of open documents and exactly one active document.
//
// The editor was written around a single open graph. Everything that describes "the document the
// user is looking at" lived in module-level variables in app.js — the Cytoscape instance, the
// canonical graph, the undo stack, the layout, the filters, the runtime binding. That is not a
// naming problem: it is where the state lives. This module moves the home of that state into a
// record per document, so that more than one can exist at the same time.
//
// One document remains visible at a time. Additional open documents retain their own selection,
// viewport, layout, filters and runtime projection, and runtime events from the service remain
// attributable to the correct document.
//
// The module is deliberately free of DOM and Cytoscape: everything here is data and rules, so the
// rules can be tested without a browser. app.js owns the rendering, this owns the bookkeeping.

// A submission that has been sent but whose execution id has not come back yet.
export const PENDING_EXECUTION = 'pending';

// ── The definition of "the active document" ──────────────────────────────────────────────────────
//
// Decided here rather than left to emerge, because the auxiliary panels follow it: the inspector,
// the activity log, the minimap and the statistics all show the active document, so an ambiguous
// answer is not an abstract problem — it is an inspector showing the wrong graph.
//
// 1. Exactly one document is active whenever the workspace is not empty.
// 2. The active document is the one whose canvas holds keyboard focus. `activate` is the only way
// to change it; pane focus handlers call it and nothing else changes this contract.
// 3. Opening a document activates it.
// 4. Closing the active document activates its nearest surviving neighbour: the previous sibling
// if there is one, otherwise the next. Closing any other document never changes which document
// is active.
// 5. An empty workspace has no active document, and `active` is null rather than a stale record.

export function createDocumentRecord({
  id,
  incarnation = createDocumentIncarnation(),
  name = 'untitled.graphml',
  displayName = name,
  graph = null,
  history = null,
}) {
  return {
    id: String(id),
    // Opaque identity of this exact open document incarnation. Filename, tab id and graph version
    // are all reusable; this value rotates whenever content replaces the record.
    incarnation: String(incarnation),
    name,
    // UI identity is intentionally distinct from the export filename. Two files with the same
    // basename remain distinguishable without leaking a local path or changing either download.
    displayName,
    graph,
    history,
    // Owned by app.js, one per document. Held here so the record is the single home of the state.
    cy: null,
    // The pane is built BEFORE the canvas and the canvas is created inside it, because moving a
    // `.doc-canvas` after the fact stops its Cytoscape instance painting for good (UI-03). Both are
    // held here so a document carries its own DOM rather than the layout
    // having to look it up.
    pane: null,
    container: null,
    // The renderer is a browser-only handle, isolated per document alongside
    // its pane/container. It is intentionally transient: GraphML and Graphify
    // describe the graph, never the view implementation currently painting it.
    renderer: null,
    // The semantic product choice is canonical per document. The split fields remain internal so
    // activation can repaint without rerunning a layout and old records can be normalized safely.
    renderMode: DEFAULT_RENDER_MODE,
    layoutMode: 'cyto',
    visualStyle: DEFAULT_VISUAL_STYLE,
    filterActive: null,
    traceActive: false,
    n8nActive: false,
    layoutBusy: false,
    // Transient renderer ownership. These fields are deliberately absent from every serializer:
    // they describe in-flight browser work, never GraphML or Graphify document state.
    layoutSessionToken: null,
    layoutPendingRefit: false,
    layoutDeferredRaf: null,
    // Cyto's automatic anchors are transient renderer state, owned per document so a background
    // canvas cannot consume another document's drag frame.
    cytoEdgeGeometryRaf: null,
    cytoEdgeRouteCache: new Map(),
    cytoEdgeRouteDirtyNodes: new Set(),
    cursorId: null,
    // UI-12 label font size is a property of the document, not the workspace.
    // Before this field existed the value lived only in the `#font-slider` DOM element, so opening
    // ANY document overwrote it for whichever document happened to be active — there was nowhere
    // per-document to keep it, unlike `layoutMode`/`filterActive`/`traceActive`/`n8nActive`/`cursorId`
    // just above, which already had a home. 20 matches the slider's own HTML default and is the value
    // `initCy` has always applied to a freshly opened document (it also raises edge labels from the
    // stylesheet's 10px to 15px) — a document that has never had its own size set gets exactly what
    // every document got before this field existed, not a new default.
    fontSize: 20,
    // Runtime monitoring. `finished` is per document because "has this execution finished" is a
    // question about one document's run, not about the workspace.
    // `events` is the bounded tail of that document's runtime events, so the assistant's `events`
    // context class has something real to attach. It lives HERE, on the
    // record, and nowhere else — there is no module-level copy in app.js to fall out of step.
    // A workspace-scoped buffer would have carried one document's `executionId`s into the context
    // sent about another, and that is a false-context defect the attachment claim cannot catch:
    // `attachmentClaimViolations` constrains PRESENCE, not PROVENANCE. Storing it per document
    // makes the bleed unconstructible instead of merely reset in the right places.
    execution: {
      executionId: null,
      // Stable server-authored identity of the process incarnation. Unlike browser arrival order,
      // this value is present in both the submission response and every live/durable event.
      processInstanceId: null,
      graphVersion: null,
      finished: new Set(),
      events: [],
      // Transport could not prove whether the bound execution is still active. This remains
      // per-document and transient: it changes command safety, never GraphML/Graphify content.
      reconciliationState: 'known',
      // The exact client which accepted the execution. A later service-origin change must not look
      // up this execution id against another runtime. Like the other monitoring fields, it is never
      // included in GraphML or Graphify serialization.
      reconciliationClient: null,
      // Monotonic binding identity and the synchronous command gate. Neither crosses a serializer.
      generation: 0,
      commandFlight: null,
      reconciliationController: null,
      // Exactly-once terminal reporting for only this generation; replaced bindings retire it.
      outcomeClaim: null,
      // At most one previous generation survives, and only while its outcome GET is in flight.
      retiredOutcomeClaim: null,
      // Monitoring-only observations. Never serialized into GraphML/Graphify and isolated by the
      // document execution binding so background runs cannot paint the visible document.
      monitoringFlow: null,
    },
    // One browser-owned idempotency key and its process-local listener status. It is distinct
    // from `execution`: starting it creates no traversal, and later inbound events have their own ids.
    sourceSession: {
      sessionId: null,
      state: '',
      sourceCount: 0,
      diagnostic: '',
      client: null,
      generation: 0,
      pollController: null,
      startPromise: null,
      stopPromise: null,
      stopRequested: false,
      observationUnavailable: false,
    },
  };
}

export function createDocumentIncarnation() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  const random = Math.random().toString(36).slice(2);
  return `document-${Date.now().toString(36)}-${random}`;
}

export function createWorkspace() {
  const documents = [];
  let activeId = null;

  const indexOf = id => documents.findIndex(doc => doc.id === String(id));

  const workspace = {
    documents,

    get active() {
      return documents.find(doc => doc.id === activeId) || null;
    },

    get activeId() {
      return activeId;
    },

    get size() {
      return documents.length;
    },

    find(id) {
      return documents.find(doc => doc.id === String(id)) || null;
    },

    // Rule 3: opening activates.
    add(document) {
      documents.push(document);
      activeId = document.id;
      return document;
    },

    // Rule 2: the single entry point. Activating an unknown id is refused rather than silently
    // leaving the workspace without an active document.
    activate(id) {
      const target = workspace.find(id);
      if (!target) return null;
      activeId = target.id;
      return target;
    },

    // Rule 4. Returns the document that was removed, or null when the id was not open.
    close(id) {
      const index = indexOf(id);
      if (index < 0) return null;
      const [removed] = documents.splice(index, 1);
      if (removed.id !== activeId) return removed;
      const neighbour = documents[index - 1] || documents[index] || null;
      activeId = neighbour ? neighbour.id : null;
      return removed;
    },
  };

  return workspace;
}

// ── Runtime event routing ────────────────────────────────────────────────────────────────────────
//
// One event stream serves the whole workspace, because it is a property of the service and not of
// any one document. Which document an event belongs to is therefore decided here, and an event that
// belongs to no open document is dropped rather than painted on whichever graph happens to be in
// front of the user.
//
// Binding is keyed on `executionId` (== traversalId) alone. A traversal that resumes an existing
// process after a wait gets a NEW traversalId while keeping the same processInstanceId, introducing
// a second value for the identifier used here. Such an
// event will not match any `binding.executionId` here and will be silently dropped, even though the
// document that submitted the original traversal is still open. The additional identifiers are
// display-only by design (the
// three identifiers are already on every event and are rendered per-row; see `appendActivityEvent`
// in app.js) and deliberately do not alter this binding. Reattaching a resumed traversal to its
// process's open document requires a separate routing rule; this display-only lookup does not decide it.
export function documentForRuntimeEvent(workspace, event) {
  const executionId = event?.executionId;
  if (!executionId) return null;

  const bound = workspace.documents.find(doc => {
    const binding = doc.execution;
    if (binding.executionId !== executionId) return false;
    // A traversal id and graph version can be repeated by an adapter or deterministic fixture. A
    // process-instance mismatch is positive evidence that the frame predates this binding. Missing
    // identity is not such evidence: legacy adapters must not make a truthful current terminal look
    // stale merely because the client cannot prove which incarnation produced it.
    if (binding.processInstanceId && event.processInstanceId
        && binding.processInstanceId !== event.processInstanceId) return false;
    // A version mismatch is a stale event for a graph that has since been republished. The
    // single-document editor dropped it; so does this.
    //
    // The event must carry the SAME version, not merely a non-conflicting one: an event with no
    // version at all is a mismatch, because nothing shows it belongs to the run this document is
    // watching. A "both present and different" check quietly ACCEPTS version-less events into a
    // versioned document. With one document that is an invisible widening; with several open it is the
    // difference between an ignored event and an event painted on the wrong graph.
    if (binding.graphVersion && binding.graphVersion !== event.graphVersion) return false;
    return true;
  });
  if (bound) return bound;

  // The pending fallback is a guess, and a guess must not outrank a fact. When an open document
  // holds this execution id, the event belongs to that run — it reached here only because its
  // version is stale — and a document that has merely submitted must not adopt another document's
  // superseded event. Only when no open document claims the id at all is the waiting document the
  // best available answer.
  const claimedByOpenDocument = workspace.documents.some(
    doc => doc.execution.executionId === executionId,
  );
  if (claimedByOpenDocument) return null;

  // A document that has submitted but has not yet been told its execution id still claims the
  // events it is plainly waiting for, exactly as the single-document editor did.
  return workspace.documents.find(doc => doc.execution.executionId === PENDING_EXECUTION) || null;
}

// Leaving the page is a decision about the whole workspace, not about the document in front of the
// user: a modified document in a background pane is exactly as unsaved as the visible one, and it is
// the one the user is most likely to have forgotten. The caller writes the working view back into
// the active record first, so that "dirty" here means every document's own history.
export function hasUnsavedWork(workspace) {
  return workspace.documents.some(document_ => Boolean(document_.history?.isDirty()));
}

export function bindExecution(document, executionId, graphVersion = null, reconciliationClient = null,
  processInstanceId = null) {
  retireExecutionOutcomeClaim(document.execution, executionId, reconciliationClient);
  document.execution.commandFlight?.controller?.abort();
  document.execution.reconciliationController?.abort();
  document.execution.generation += 1;
  document.execution.commandFlight = null;
  document.execution.reconciliationController = null;
  document.execution.executionId = executionId;
  document.execution.processInstanceId = processInstanceId;
  document.execution.graphVersion = graphVersion;
  document.execution.reconciliationState = 'known';
  document.execution.reconciliationClient = reconciliationClient;
  if (executionId && executionId !== PENDING_EXECUTION) {
    document.execution.finished.delete(executionId);
  }
  return document.execution;
}

// Detaching keeps the run alive on the server: the client never owned it. Closing a document with a
// live run therefore stops projecting it, and does not try to cancel it.
export function detachExecution(document) {
  retireExecutionOutcomeClaim(document.execution, null, null, { cancelInFlight: true });
  document.execution.commandFlight?.controller?.abort();
  document.execution.reconciliationController?.abort();
  document.execution.generation += 1;
  document.execution.commandFlight = null;
  document.execution.reconciliationController = null;
  document.execution.executionId = null;
  document.execution.processInstanceId = null;
  document.execution.graphVersion = null;
  document.execution.finished.clear();
  document.execution.reconciliationState = 'known';
  document.execution.reconciliationClient = null;
  return document.execution;
}

export function isExecutionFinished(document, executionId) {
  return document.execution.finished.has(executionId);
}

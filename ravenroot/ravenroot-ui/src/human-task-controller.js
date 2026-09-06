import { humanTaskContext, nextHumanTaskBackoff } from './human-task-attention.js';

const EMPTY_COUNTS = Object.freeze({ pending: 0, escalated: 0 });

function initialState() {
  return { kind: 'unavailable', message: 'Run or select an authoritative deployment context to see Human Tasks.',
    nodeCounts: new Map(), selectedNodeId: null, page: { kind: 'unavailable', message:
      'Run or select an authoritative deployment context to see Human Tasks.', items: [], counts: EMPTY_COUNTS,
    nextCursor: null, hasPrevious: false, pageNumber: 1 } };
}

export function createHumanTaskController({ onChange = () => {}, setTimer = setTimeout,
  clearTimer = clearTimeout } = {}) {
  let client = null;
  let capability = null;
  let documentRecord = null;
  let state = initialState();
  let pageCursors = [null];
  let pageIndex = 0;
  let timer = null;
  let controller = null;
  let generation = 0;
  let backoff = 0;

  const publish = () => onChange(state);
  const context = () => humanTaskContext(documentRecord);
  function stopFlight() { controller?.abort(); controller = null; }
  function stopTimer() { if (timer != null) clearTimer(timer); timer = null; }
  function schedule(delay) {
    stopTimer();
    if (client && capability && context()) timer = setTimer(() => { void refresh({ automatic: true }); }, delay);
  }

  async function refresh({ automatic = false } = {}) {
    stopTimer();
    stopFlight();
    const ownGeneration = ++generation;
    const filters = context();
    if (!client || !capability) {
      const selectedNodeId = state.selectedNodeId;
      state = initialState();
      state.selectedNodeId = selectedNodeId;
      state.message = 'This runtime does not expose embedded Human Task presentation.';
      state.page.message = state.message;
      publish();
      return state;
    }
    if (!filters) {
      const selectedNodeId = state.selectedNodeId;
      state = initialState();
      state.selectedNodeId = selectedNodeId;
      publish(); return state;
    }
    controller = new AbortController();
    const signal = controller.signal;
    if (!automatic || state.kind === 'unavailable') {
      state = { ...state, kind: 'loading', page: { ...state.page, kind: 'loading' } }; publish();
    }
    try {
      const graphRequest = client.humanTaskAttention(filters, { signal, capability });
      const nodeRequest = state.selectedNodeId ? client.humanTaskAttention({ ...filters,
        nodeId: state.selectedNodeId, cursor: pageCursors[pageIndex] || undefined }, { signal, capability }) : null;
      const [graphPage, nodePage] = await Promise.all([graphRequest, nodeRequest]);
      if (generation !== ownGeneration || signal.aborted) return state;
      const nodeCounts = new Map(graphPage.nodeCounts.map(entry => [entry.nodeId,
        { pending: entry.pending, escalated: entry.escalated }]));
      const page = nodePage ? { kind: 'ready', items: nodePage.items, counts: nodePage.counts,
        nextCursor: nodePage.nextCursor, hasPrevious: pageIndex > 0, pageNumber: pageIndex + 1 } : {
        kind: 'ready', items: [], counts: EMPTY_COUNTS, nextCursor: null,
        hasPrevious: false, pageNumber: 1 };
      state = { ...state, kind: 'ready', message: '', nodeCounts, page };
      backoff = 0;
      publish();
      schedule(capability.attentionPollMillis);
      return state;
    } catch (error) {
      if (generation !== ownGeneration || signal.aborted) return state;
      // Cursors are bound to the authenticated authority and exact query. A role/scope change makes
      // an older page cursor invalid; return to the first page once instead of retrying that token
      // forever. A first-page refusal still follows the ordinary visible backoff path.
      if (error?.status === 400 && pageIndex > 0) {
        pageCursors = [null]; pageIndex = 0; backoff = 0;
        return refresh();
      }
      backoff = nextHumanTaskBackoff(backoff, capability);
      state = { ...state, kind: 'error', message: `Human Tasks could not be refreshed: ${error.message}`,
        page: { ...state.page, kind: 'error', message:
          `Human Tasks could not be refreshed: ${error.message}` } };
      publish();
      schedule(backoff);
      return state;
    } finally {
      if (generation === ownGeneration) controller = null;
    }
  }

  function selectNode(nodeId) {
    const normalized = nodeId == null ? null : String(nodeId);
    if (normalized === state.selectedNodeId) return refresh();
    pageCursors = [null]; pageIndex = 0;
    state = { ...state, selectedNodeId: normalized, page: { kind: client && capability && context()
      ? 'loading' : 'unavailable', message: '', items: [], counts: EMPTY_COUNTS,
    nextCursor: null, hasPrevious: false, pageNumber: 1 } };
    publish();
    return refresh();
  }

  function nextPage() {
    if (!state.page.nextCursor) return Promise.resolve(state);
    pageCursors[pageIndex + 1] = state.page.nextCursor;
    pageIndex += 1;
    return refresh();
  }
  function previousPage() {
    if (pageIndex < 1) return Promise.resolve(state);
    pageIndex -= 1;
    return refresh();
  }

  return {
    configure(nextClient, nextCapability, nextDocument) {
      const changed = client !== nextClient || capability !== nextCapability || documentRecord !== nextDocument;
      client = nextClient; capability = nextCapability; documentRecord = nextDocument;
      if (changed) { pageCursors = [null]; pageIndex = 0; backoff = 0; }
      return refresh();
    },
    selectNode, nextPage, previousPage, refresh,
    state: () => state,
    destroy() { generation += 1; stopFlight(); stopTimer(); client = null; documentRecord = null; },
  };
}

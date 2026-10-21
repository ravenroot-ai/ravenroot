import { createBoundHistoryReader, createHistoryPager } from './deployment-history-model.js';
import { uiText } from './ui-text.js';

/** Shared read-only native/embed history surface. Every list is one replaceable server page. */
export function createDeploymentHistoryMonitor({ container, projection, read, viewerFactory,
  isCurrent = () => true, theme = 'dark', t = uiText, localization = null }) {
  const document = container.ownerDocument;
  const reader = createBoundHistoryReader({ source: projection.source, read, isCurrent });
  const pagers = new Set(), previews = new Map();
  let initialized = false, initialization = null;
  let destroyed = false, limit = 1, selectionEpoch = 0, selectionKind = null, selectedSections = [];
  const localizedArguments = new WeakMap(), rowArguments = new WeakMap();
  const localize = (node, key, args = {}) => { node.textContent = t(key, args); node.dataset.historyText = key; localizedArguments.set(node, args); return node; };
  const element = (tag, text, className) => {
    const node = document.createElement(tag); if (text != null) node.textContent = text;
    if (className) node.className = className; return node;
  };
  const button = (key, action) => {
    const node = localize(element('button'), key); node.type = 'button';
    node.addEventListener('click', () => Promise.resolve(action()).catch(error => {
      if (!destroyed && error?.name !== 'AbortError') status.textContent = t('history.failed');
    })); return node;
  };
  container.classList.add('deployment-history');
  const header = element('header'), status = element('p', '', 'history-status');
  status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
  header.append(localize(element('h2'), 'history.title'), localize(element('p'), 'history.pinned', {
    deploymentId: projection.source.deploymentId, version: projection.source.graphVersion }), status);
  const rootDisclosure = element('details'), rootSummary = localize(element('summary'), 'history.choose'); rootDisclosure.open = true;
  const selectionDisclosure = element('details'), selectionSummary = localize(element('summary'), 'history.traversals'); selectionDisclosure.hidden = true;
  const roots = element('section'), selections = element('section'), previewList = element('section');
  rootDisclosure.append(rootSummary, roots); selectionDisclosure.append(selectionSummary, selections);
  container.replaceChildren(header, rootDisclosure, selectionDisclosure, previewList);
  const label = (view, row, compact = false) => {
    const identity = value => compact && typeof value === 'string' && value.length > 24 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
    const unavailable = t('history.unknown');
    if (view === 'histories') return t('history.row.business', { reference: row.publicReference || unavailable,
      id: identity(row.applicationCorrelationId), state: row.state || unavailable, lifecycle: row.lifecycle || unavailable });
    if (['traversals', 'uncorrelated-traversals', 'process-traversals'].includes(view)) return t('history.row.traversal', {
      id: identity(row.traversalId), process: identity(row.processInstanceId), entry: row.entryNodeId || unavailable, time: row.startedAt || unavailable, status: row.status || unavailable });
    if (view === 'visits') return t('history.row.visit', { id: identity(row.invocationId), node: row.nodeId,
      position: row.position ?? unavailable, time: row.startedAt || unavailable, status: row.status || unavailable });
    if (view === 'attempts') return t('history.row.attempt', { id: identity(row.attemptId), ordinal: row.ordinal,
      time: row.startedAt || unavailable, status: row.status || unavailable });
    if (view === 'joins') return t('history.row.join', { node: row.nodeId, round: row.roundId, phase: row.phase });
    return row.contributionId || row.invocationId || row.attemptId || row.nodeId || unavailable;
  };
  const setRowLabel = (node, view, row) => { node.textContent = label(view, row, true); node.setAttribute('aria-label', label(view, row)); node.title = label(view, row); node.dataset.historyRowView = view; rowArguments.set(node, row); return node; };
  const labelElement = (tag, view, row) => setRowLabel(element(tag), view, row);
  const facts = row => {
    const list = element('dl');
    for (const [name, value] of Object.entries(row)) {
      if (value == null || typeof value === 'object' || name === 'content') continue;
      const term = element('dt', name), detail = element('dd', String(value)); list.append(term, detail);
    }
    const groups = {
      applicationContext: ['applicationCorrelationId', 'contributionId', 'contributionKind', 'roundId', 'publicReference'],
      closureSignal: ['requested', 'auditReason', 'processInstanceId', 'traversalId', 'invocationId', 'attemptId', 'requestedAt'],
      derivation: ['processInstanceId', 'traversalId', 'invocationId', 'attemptId', 'auditReason'],
    };
    const headings = { applicationContext: 'history.facts.applicationContext', closureSignal: 'history.facts.closureSignal', derivation: 'history.facts.derivation' };
    // Only the public metadata schema is inspectable here. Payloads, identity objects and
    // arbitrary nested properties must never become an alternative activity-capture path.
    for (const [group, fields] of Object.entries(groups)) {
      if (!row[group] || typeof row[group] !== 'object' || Array.isArray(row[group])) continue;
      const term = localize(element('dt'), headings[group]), detail = element('dd'), nested = element('dl');
      nested.dataset.historyFacts = group;
      for (const name of fields) {
        const value = row[group][name];
        nested.append(element('dt', name), ['string', 'number', 'boolean'].includes(typeof value) ? element('dd', String(value)) : localize(element('dd'), 'history.unknown'));
      }
      detail.append(nested); list.append(term, detail);
    }
    return list;
  };
  function section(parent, key, query, onSelect) {
    const root = element('section', null, 'history-page'), title = localize(element('h3'), key);
    const rows = element('ul'), message = element('p'), controls = element('nav');
    controls.setAttribute('aria-label', t(key)); controls.dataset.historyLabel = key;
    const pager = createHistoryPager({ reader, query, limit: query.view === 'activity' ? 1 : limit, onChange(state) {
      if (destroyed || !root.isConnected) return;
      rows.replaceChildren();
      message.textContent = state.busy ? t('history.loading') : state.failure ? t('history.failed')
        : state.page && state.page.availability !== 'AVAILABLE' ? t('history.unavailable', { availability: state.page.availability })
          : state.page?.contentPolicy ? t('history.contentPolicy', { policy: state.page.contentPolicy, gap: state.page.gap ? t('common.yes') : t('common.no') })
          : !state.page?.items.length ? t('history.empty') : '';
      for (const row of state.page?.items || []) {
        const item = element('li');
        if (onSelect) { const select = labelElement('button', query.view, row); select.type = 'button'; select.addEventListener('click', () => onSelect(row)); item.append(select);
          const details = element('details'); details.append(localize(element('summary'), 'history.details'), facts(row)); item.append(details); }
        else { item.append(facts(row)); if (query.view === 'activity' && row.content != null) item.append(element('pre', JSON.stringify(row.content, null, 2))); }
        rows.append(item);
      }
      previous.disabled = state.busy || !state.hasPrevious; next.disabled = state.busy || !state.hasNext;
      refresh.disabled = state.busy;
    }});
    const previous = button('history.previous', () => pager.previous()), next = button('history.next', () => pager.next()), refresh = button('history.refresh', () => pager.refresh());
    controls.append(previous, next, refresh); root.append(title, message, rows, controls); parent.append(root); pagers.add(pager);
    const result = { root, pager, destroy() { pager.destroy(); pagers.delete(pager); root.remove(); } };
    pager.load().catch(() => {}); return result;
  }
  function chooseBusiness(row) {
    selectionKind = 'business'; localize(selectionSummary, 'history.traversals'); delete selectionSummary.dataset.historyRowView; rowArguments.delete(selectionSummary);
    selectionEpoch++; setRowLabel(rootSummary, 'histories', row);
    delete rootSummary.dataset.historyText; rootDisclosure.open = false; selectionDisclosure.hidden = false; selectionDisclosure.open = true;
    for (const state of [...previews.values()]) state.retire();
    for (const old of selectedSections) old.destroy(); selectedSections = [];
    selectedSections.push(section(selections, 'history.traversals', { view: 'traversals', applicationCorrelationId: row.applicationCorrelationId }, openTraversal));
    selectedSections.push(section(selections, 'history.joins', { view: 'joins', applicationCorrelationId: row.applicationCorrelationId }, round => {
      const existing = selectedSections.find(item => item.members); existing?.destroy();
      const members = section(selections, 'history.members', { view: 'join-members', applicationCorrelationId: row.applicationCorrelationId,
        nodeId: round.nodeId, roundId: round.roundId }); members.members = true; selectedSections = selectedSections.filter(item => item !== existing); selectedSections.push(members);
    }));
  }
  function viewerShell(parent) {
    const scope = element('div', null, 'history-preview'); scope.dataset.viewerFocusScope = '';
    const before = element('span', '', 'embed-focus-sentinel'), after = before.cloneNode(); before.tabIndex = 0; after.tabIndex = 0;
    const root = element('section', null, 'embed-viewer'); root.dataset.viewerRoot = '';
    const toolbar = element('nav', null, 'embed-viewer-toolbar');
    const mode = element('select'); mode.dataset.viewerMode = ''; mode.setAttribute('aria-label', t('embed.controls.view')); mode.dataset.historyLabel = 'embed.controls.view';
    for (const [value, key] of [['design', 'embed.mode.design'], ['monitoring', 'embed.mode.monitoring']]) { const option = localize(element('option'), key); option.value = value; mode.append(option); }
    toolbar.append(mode);
    for (const command of ['render', 'fit', 'zoom-in', 'zoom-out']) { const control = button(command === 'render' ? 'embed.controls.render' : command === 'fit' ? 'embed.controls.fit' : command === 'zoom-in' ? 'embed.controls.zoomIn' : 'embed.controls.zoomOut', () => {}); control.dataset.viewerCommand = command; toolbar.append(control); }
    const maximize = button('embed.controls.maximize', () => {}); maximize.dataset.viewerMaximize = ''; toolbar.append(maximize);
    const canvas = element('div', null, 'embed-viewer-canvas'); canvas.dataset.viewerCanvas = ''; canvas.tabIndex = 0; canvas.setAttribute('role', 'application'); canvas.setAttribute('aria-label', t('embed.canvas.label')); canvas.dataset.historyLabel = 'embed.canvas.label';
    const minimap = element('canvas', null, 'embed-viewer-minimap'); minimap.dataset.viewerMinimap = ''; minimap.tabIndex = 0; minimap.setAttribute('role', 'application'); minimap.setAttribute('aria-label', t('embed.minimap.label')); minimap.dataset.historyLabel = 'embed.minimap.label';
    const state = element('p'); state.dataset.viewerStatus = ''; const metadata = element('p'); metadata.dataset.viewerMetadata = '';
    const alternative = element('ol'); alternative.dataset.viewerAlternative = '';
    const contents = element('details'); contents.append(localize(element('summary'), 'embed.contents.label'), alternative);
    const footer = element('footer'); footer.append(minimap, state, metadata);
    root.append(toolbar, canvas, footer, contents); scope.append(before, root, after); parent.append(scope); return { scope, root };
  }
  async function openTraversal(row) {
    const key = `${row.processInstanceId}:${row.traversalId}`;
    setRowLabel(selectionSummary, 'traversals', row);
    delete selectionSummary.dataset.historyText; selectionDisclosure.hidden = false; selectionDisclosure.open = false; rootDisclosure.open = false;
    if (previews.has(key)) { previews.get(key).root.scrollIntoView({ block: 'nearest' }); return; }
    if (previews.size >= 2) { status.textContent = t('history.previewLimit'); return; }
    const root = element('article', null, 'history-traversal'); root.dataset.traversalId = row.traversalId;
    const title = labelElement('h3', 'traversals', row), coverage = element('p'), inspector = element('aside', null, 'history-inspector');
    inspector.setAttribute('aria-label', t('history.inspect')); inspector.dataset.historyLabel = 'history.inspect'; inspector.append(element('p', t('history.selectNode')));
    const coverageControls = element('nav');
    const state = { root, viewer: null, retired: false, mounted: false, epoch: selectionEpoch, coverageGeneration: 0, controller: new AbortController(), nodeRows: [], edgeRows: [], nodes: null, edges: null, inspectorPages: [], coverageFlights: new Set() };
    const scoped = { processInstanceId: row.processInstanceId, traversalId: row.traversalId };
    const retire = () => { state.retired = true; state.coverageGeneration++; state.controller.abort(); state.viewer?.destroy(); for (const item of state.inspectorPages) item.destroy(); previews.delete(key); root.remove(); };
    state.retire = retire;
    root.append(title, button('history.close', retire), coverage, coverageControls); previewList.append(root); previews.set(key, state);
    const shell = viewerShell(root); root.append(inspector);
    function updateNodeTotals() {
      if (!state.selectedNodeId || !state.nodeTotals) return;
      const row = state.nodeRows.find(item => item.nodeId === state.selectedNodeId);
      if (!row && (!state.nodes || state.nodes.nextCursor || state.nodes.availability !== 'AVAILABLE')) state.nodeTotals.textContent = t('history.coverage.nodeNotLoaded');
      else state.nodeTotals.textContent = t('history.nodeTotals', { visits: row?.visitCount || 0, attempts: row?.attemptCount || 0, active: row?.activeVisits || 0, failed: row?.failedVisits || 0 });
    }
    function inspect(nodeId) {
      if (state.retired) return;
      state.selectedNodeId = nodeId;
      for (const old of state.inspectorPages) old.destroy(); state.inspectorPages = []; inspector.replaceChildren(element('h4', nodeId));
      state.nodeTotals = element('p'); state.nodeTotals.className = 'history-node-totals'; inspector.append(state.nodeTotals); updateNodeTotals();
      const visits = section(inspector, 'history.visits', { view: 'visits', ...scoped, nodeId }, visit => {
        for (const old of state.inspectorPages.slice(1)) old.destroy(); state.inspectorPages = state.inspectorPages.slice(0, 1);
        const selection = { ...scoped, invocationId: visit.invocationId };
        const captureControl = selected => {
          for (const old of state.inspectorPages.slice(3)) old.destroy(); state.inspectorPages = state.inspectorPages.slice(0, 3);
          inspector.querySelector('[data-history-content-control]')?.remove();
          const content = button('history.activity', () => { content.remove(); state.inspectorPages.push(section(inspector, 'history.activity', { view: 'activity', ...selected })); });
          content.dataset.historyContentControl = ''; inspector.append(content);
        };
        state.inspectorPages.push(section(inspector, 'history.attempts', { view: 'attempts', ...selection }, attempt => captureControl({ ...selection, attemptId: attempt.attemptId })));
        state.inspectorPages.push(section(inspector, 'history.parents', { view: 'parents', ...selection }));
        captureControl(selection);
      }); state.inspectorPages.push(visits);
    }
    const paint = () => {
      if (state.retired || destroyed) return;
      updateNodeTotals();
      const complete = state.nodes?.availability === 'AVAILABLE' && !state.nodes.nextCursor && state.edges?.availability === 'AVAILABLE' && !state.edges.nextCursor;
      const unavailable = [state.nodes, state.edges].some(page => page && page.availability !== 'AVAILABLE');
      coverage.textContent = t(unavailable ? 'history.coverage.unavailable' : complete ? 'history.coverage.complete' : 'history.coverage.partial', {
        nodes: unavailable ? state.nodes?.availability : state.nodeRows.length, edges: unavailable ? state.edges?.availability : state.edgeRows.length });
      moreNodes.disabled = !state.nodes?.nextCursor || state.coverageFlights.has('nodes'); moreEdges.disabled = !state.edges?.nextCursor || state.coverageFlights.has('edges');
      state.viewer?.applyHistorySummaries({ nodes: state.nodeRows, edges: state.edgeRows,
        nodeAvailability: state.nodes?.availability || 'NOT_RECORDED', edgeAvailability: state.edges?.availability || 'NOT_RECORDED',
        nodeCoverageComplete: Boolean(state.nodes && !state.nodes.nextCursor), edgeCoverageComplete: Boolean(state.edges && !state.edges.nextCursor) });
    };
    state.paint = paint;
    async function loadCoverage(view) {
      if (state.retired || state.epoch !== selectionEpoch || state.coverageFlights.has(view)) return;
      const token = state.coverageGeneration;
      state.coverageFlights.add(view); paint();
      try {
        const page = await reader.read({ view, ...scoped, limit, ...(state[view]?.nextCursor ? { cursor: state[view].nextCursor } : {}) }, { signal: state.controller.signal });
        if (state.retired || destroyed || token !== state.coverageGeneration || state.epoch !== selectionEpoch) return;
        const rows = view === 'nodes' ? state.nodeRows : state.edgeRows, budget = view === 'nodes' ? 5000 : 10000;
        if (rows.length + page.items.length > budget) throw new RangeError('Traversal coverage budget exceeded');
        rows.push(...page.items); state[view] = page;
      } finally { if (token === state.coverageGeneration) { state.coverageFlights.delete(view); paint(); } }
    }
    const moreNodes = button('history.moreNodes', () => loadCoverage('nodes')), moreEdges = button('history.moreEdges', () => loadCoverage('edges'));
    state.refreshCoverage = async () => {
      if (state.retired || !state.mounted) return;
      state.coverageGeneration++; state.controller.abort(); state.controller = new AbortController(); state.coverageFlights.clear();
      state.nodeRows = []; state.edgeRows = []; state.nodes = null; state.edges = null; paint();
      return Promise.all([loadCoverage('nodes'), loadCoverage('edges')]);
    };
    coverageControls.append(moreNodes, moreEdges, button('history.refreshCoverage', () => state.refreshCoverage()));
    try {
      state.viewer = viewerFactory(shell.root, { theme, onInspectNode: inspect, localization, fitOnResize: true }); await state.viewer.mount(projection);
      if (state.retired || destroyed) { state.viewer.destroy(); return; }
      state.mounted = true; state.viewer.selectTraversal(row.processInstanceId, row.traversalId);
      await Promise.all([loadCoverage('nodes'), loadCoverage('edges')]);
    } catch (error) { if (!state.retired && error?.name !== 'AbortError') coverage.textContent = t('history.failed'); }
  }
  const retry = button('history.retry', () => initialize()); retry.hidden = true; header.append(retry);
  function initialize() {
    if (initialization) return initialization;
    retry.disabled = true;
    initialization = (async () => {
    const page = await reader.read({ view: 'policy', limit: 1 });
    if (destroyed) return;
    const max = page.items[0]?.maxPageSize;
    if (!Number.isSafeInteger(max) || max < 1 || max > 1000) throw new TypeError('Invalid history policy');
    limit = Math.min(10, max); initialized = true; retry.hidden = true; status.textContent = t('history.limit', { limit });
    section(roots, 'history.business', { view: 'histories' }, chooseBusiness);
    section(roots, 'history.uncorrelated', { view: 'uncorrelated-traversals' }, row => {
      if (selectionKind !== 'uncorrelated') { selectionEpoch++; for (const state of [...previews.values()]) state.retire();
        for (const old of selectedSections) old.destroy(); selectedSections = []; }
      selectionKind = 'uncorrelated'; localize(rootSummary, 'history.uncorrelated'); delete rootSummary.dataset.historyRowView; rowArguments.delete(rootSummary);
      return openTraversal(row);
    });
    })().catch(error => { if (!destroyed && error?.name !== 'AbortError') { status.textContent = t('history.failed'); retry.hidden = false; } })
      .finally(() => { initialization = null; retry.disabled = false; });
    return initialization;
  }
  const ready = initialize();
  return Object.freeze({ ready,
    refresh() { if (!initialized) return initialize(); return Promise.all([...pagers].map(pager => pager.refresh().catch(() => {})).concat([...previews.values()].map(state => state.refreshCoverage().catch(() => {})))); },
    notify() { /* Reads remain explicit; a stream cannot silently drain history pages. */ },
    refreshLocale() { for (const node of container.querySelectorAll('[data-history-text]')) node.textContent = t(node.dataset.historyText, localizedArguments.get(node) || {});
      for (const node of container.querySelectorAll('[data-history-row-view]')) setRowLabel(node, node.dataset.historyRowView, rowArguments.get(node));
      for (const node of container.querySelectorAll('[data-history-label]')) node.setAttribute('aria-label', t(node.dataset.historyLabel));
      for (const state of previews.values()) { state.viewer?.refreshLocale?.(); state.paint(); } },
    destroy() { destroyed = true; reader.destroy(); for (const pager of pagers) pager.destroy(); pagers.clear();
      for (const state of [...previews.values()]) state.retire(); previews.clear(); container.replaceChildren(); },
  });
}

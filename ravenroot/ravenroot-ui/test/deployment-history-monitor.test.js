import { expect, it, vi } from 'vitest';
import { createDeploymentHistoryMonitor } from '../src/deployment-history-monitor.js';
const source = { kind: 'deployment', deploymentId: 'orders', graphVersion: 'v1', incarnationId: 'i1' };
const response = (view, items, nextCursor = null) => ({ source, view, items, nextCursor, availability: 'AVAILABLE' });
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };
function fixture(read) {
  document.body.replaceChildren(); const container = document.createElement('main'); document.body.append(container);
  const viewers = [];
  const viewerFactory = vi.fn((_root, options) => { const viewer = { mount: vi.fn(async () => {}), selectTraversal: vi.fn(), applyHistorySummaries: vi.fn(), destroy: vi.fn(), options }; viewers.push(viewer); return viewer; });
  const monitor = createDeploymentHistoryMonitor({ container, projection: { source }, read, viewerFactory });
  return { monitor, container, viewers, viewerFactory };
}
it('honors page cap one without draining coverage and preserves inspector during graph paging', async () => {
  const queries = [];
  const { monitor, container, viewers } = fixture(async query => {
    queries.push(query);
    if (query.view === 'policy') return response('policy', [{ maxPageSize: 1 }]);
    if (query.view === 'uncorrelated-traversals') return response(query.view, [{ processInstanceId: 'p1', traversalId: 't1', status: 'RUNNING' }]);
    if (query.view === 'nodes') return response('nodes', [{ nodeId: query.cursor ? 'worker' : 'start', visitCount: 1, activeVisits: 0, completedVisits: 1, failedVisits: 0, attemptCount: 1 }], query.cursor ? null : 'nodes-more');
    if (query.view === 'edges') return response('edges', [{ edgeId: 'edge', traversalCount: 4 }]);
    return response(query.view, []);
  });
  await monitor.ready; await flush();
  container.querySelector('.history-page:nth-child(2) li button').click(); await flush();
  expect(queries.filter(query => query.view === 'nodes')).toHaveLength(1);
  expect(viewers[0].applyHistorySummaries.mock.lastCall[0]).toMatchObject({ nodeCoverageComplete: false, edgeCoverageComplete: true });
  expect(container.textContent).toContain('Omitted activity is unknown');
  viewers[0].options.onInspectNode('worker'); await flush();
  const inspector = container.querySelector('.history-inspector'); expect(inspector.textContent).toContain('not yet loaded');
  [...container.querySelectorAll('button')].find(button => button.textContent === 'Load more node coverage').click(); await flush();
  expect(viewers[0].applyHistorySummaries.mock.lastCall[0]).toMatchObject({ nodeCoverageComplete: true, nodes: expect.any(Array) });
  expect(container.querySelector('.history-inspector')).toBe(inspector);
  await monitor.refresh(); await flush();
  expect(queries.filter(query => query.view === 'nodes')).toHaveLength(3);
  expect(viewers[0].applyHistorySummaries.mock.lastCall[0]).toMatchObject({ nodeCoverageComplete: false });
  expect(container.querySelector('.history-inspector')).toBe(inspector);
  expect(queries.every(query => Number(query.limit) === 1)).toBe(true);
  monitor.destroy(); expect(viewers[0].destroy).toHaveBeenCalledOnce();
});
it('never mounts a third graph and retires ignored-abort reads without stale publication', async () => {
  let resolveCoverage;
  const { monitor, container, viewers } = fixture(async query => {
    if (query.view === 'policy') return response('policy', [{ maxPageSize: 10 }]);
    if (query.view === 'uncorrelated-traversals') return response(query.view, [1, 2, 3].map(id => ({ processInstanceId: 'p1', traversalId: `t${id}` })));
    if (query.view === 'nodes') return new Promise(resolve => { resolveCoverage = resolve; });
    return response(query.view, []);
  });
  await monitor.ready; await flush();
  const rows = [...container.querySelectorAll('.history-page:nth-child(2) li button')]; rows.forEach(row => row.click()); await flush();
  expect(viewers).toHaveLength(2); expect(container.querySelectorAll('[data-viewer-root]')).toHaveLength(2);
  monitor.destroy(); const published = viewers[1].applyHistorySummaries.mock.calls.length;
  resolveCoverage(response('nodes', [{ nodeId: 'late', visitCount: 1000 }])); await flush();
  expect(viewers[1].applyHistorySummaries.mock.calls).toHaveLength(published); expect(container.children).toHaveLength(0);
});

it('business selection retires old graph previews and fences their late coverage', async () => {
  let finishOld;
  const { monitor, container, viewers } = fixture(async query => {
    if (query.view === 'policy') return response('policy', [{ maxPageSize: 10 }]);
    if (query.view === 'histories') return response(query.view, [{ applicationCorrelationId: 'a' }, { applicationCorrelationId: 'b' }]);
    if (query.view === 'traversals') return response(query.view, [{ processInstanceId: 'process', traversalId: query.applicationCorrelationId }]);
    if (query.view === 'nodes') return new Promise(resolve => { finishOld = resolve; });
    return response(query.view, []);
  });
  await monitor.ready; await flush();
  const businesses = [...container.querySelectorAll('.history-page:first-child li button')];
  businesses[0].click(); await flush();
  container.querySelectorAll('.history-page')[2].querySelector('li button').click(); await flush();
  expect(container.querySelectorAll('.history-traversal')).toHaveLength(1);
  businesses[1].click(); await flush();
  expect(viewers[0].destroy).toHaveBeenCalledOnce(); expect(container.querySelectorAll('.history-traversal')).toHaveLength(0);
  const count = viewers[0].applyHistorySummaries.mock.calls.length;
  finishOld(response('nodes', [{ nodeId: 'stale' }])); await flush();
  expect(viewers[0].applyHistorySummaries.mock.calls).toHaveLength(count);
  monitor.destroy();
});


it('retries a failed policy initialization with the same bounded source and no hidden activation', async () => {
  let policies = 0; const queries = [];
  const { monitor, container } = fixture(async query => {
    queries.push(query.view); if (query.view === 'policy') { if (++policies === 1) throw new Error('503'); return response('policy', [{ maxPageSize: 1 }]); }
    return response(query.view, []);
  });
  await monitor.ready; expect(container.textContent).toContain('Retry history'); expect(container.querySelectorAll('.history-page')).toHaveLength(0);
  await monitor.refresh(); await flush();
  expect(container.querySelectorAll('.history-page')).toHaveLength(2); expect(queries).toEqual(['policy', 'policy', 'histories', 'uncorrelated-traversals']);
  monitor.destroy();
});

it('exposes only public closure and derivation facts without reading activity capture', async () => {
  const queries = [], cause = { processInstanceId: 'original-process', traversalId: 'original-traversal', invocationId: 'original-visit', attemptId: 'original-attempt', auditReason: 'declared production complete', secret: 'never expose', payload: { token: 'never expose' } };
  const { monitor, container } = fixture(async query => {
    queries.push(query.view);
    if (query.view === 'policy') return response('policy', [{ maxPageSize: 10 }]);
    if (query.view === 'histories') return response(query.view, [{ applicationCorrelationId: 'business', lifecycle: 'OPEN', state: 'RUNNING', runningTraversals: 1,
      closureSignal: { ...cause, requested: 'COMPLETED', requestedAt: '2026-10-21T00:00:00Z' } }]);
    if (query.view === 'traversals') return response(query.view, [{ processInstanceId: 'derived-process', traversalId: 'derived-traversal', derivation: cause,
      applicationContext: { applicationCorrelationId: 'derived-business', publicReference: 'public.csv', identity: { token: 'never expose' } } }]);
    return response(query.view, []);
  });
  await monitor.ready; await flush();
  expect(container.querySelector('[data-history-facts="closureSignal"]').textContent).toContain('COMPLETED');
  expect(container.textContent).toContain('RUNNING'); expect(container.textContent).toContain('runningTraversals1');
  container.querySelector('.history-page li button').click(); await flush();
  for (const name of ['processInstanceId', 'traversalId', 'invocationId', 'attemptId', 'auditReason']) expect(container.querySelector('[data-history-facts="derivation"]').textContent).toContain(cause[name]);
  expect(container.querySelector('[data-history-facts="applicationContext"]').textContent).toContain('derived-business');
  expect(container.textContent).not.toContain('never expose'); expect(queries).not.toContain('activity');
  monitor.destroy();
});
it('shows known contribution progress and keeps an unannounced expectation explicit', async () => {
  const { monitor, container } = fixture(async query => {
    if (query.view === 'policy') return response(query.view, [{ maxPageSize: 10 }]);
    if (query.view === 'histories') return response(query.view, [{ applicationCorrelationId: 'ingestion', publicReference: 'sales.csv', state: 'AWAITING_EXTERNAL_EVENT', lifecycle: 'OPEN' }]);
    if (query.view === 'joins') return response(query.view, [
      { nodeId: 'six', roundId: 'one', phase: 'OPEN', expectedContributions: 6, receivedContributions: 4, expectation: 'MANIFEST' },
      { nodeId: 'dynamic', roundId: 'two', phase: 'OPEN', expectedContributions: 0, receivedContributions: 2, expectation: 'UNANNOUNCED' },
    ]);
    return response(query.view, []);
  });
  await monitor.ready; await flush(); container.querySelector('.history-page li button').click(); await flush();
  expect(container.textContent).toContain('4/6 contributions'); expect(container.textContent).toContain('2/unavailable contributions');
  expect(container.textContent).not.toContain('2/0 contributions'); monitor.destroy();
});

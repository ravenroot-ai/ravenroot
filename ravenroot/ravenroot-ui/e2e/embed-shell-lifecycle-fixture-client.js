import {
  createEmbedShellLifecycle,
  EmbedShellFailure,
  renderEmbedTextAlternative,
} from '/embed-shell-lifecycle.js';
import {
  resolveViewerRoutesWithinBudget,
  viewerSupportsElastic,
} from '/viewer-route-budget.js';

const root = document.querySelector('#shell');
const lifecycle = createEmbedShellLifecycle({
  root,
  status: document.querySelector('#status'),
  alternative: document.querySelector('#alternative'),
  before: document.querySelector('#focus-before'),
  after: document.querySelector('#focus-after'),
  timeoutMilliseconds: 250,
});
const graph = Object.freeze({
  nodes: Object.freeze([
    Object.freeze({ id: 'start', kind: 'START' }),
    Object.freeze({ id: 'finish', kind: 'END' }),
  ]),
  edges: Object.freeze([Object.freeze({ source: 'start', target: 'finish' })]),
});
let activations = 0;
document.querySelector('#local-action').addEventListener('click', () => {
  activations += 1;
  root.dataset.localActivations = String(activations);
});

function maximumProjection() {
  const nodes = Array.from({ length: 2_000 }, (_, index) => ({
    id: `node-${index}`,
    kind: index === 0 ? 'START' : 'PASSTHROUGH',
    x: (index % 50) * 100,
    y: Math.floor(index / 50) * 80,
    width: 64,
    height: 40,
  }));
  const edges = Array.from({ length: 5_000 }, (_, index) => ({
    id: `edge-${index}`,
    source: nodes[index % nodes.length].id,
    target: nodes[(index * 17 + 1) % nodes.length].id,
    label: '',
  }));
  return { nodes, edges };
}

function parallelProjection(edgeCount = 5_000) {
  const nodes = [
    { id: 'source', kind: 'START', x: 0, y: 0, width: 64, height: 40 },
    { id: 'target', kind: 'END', x: 300, y: 0, width: 64, height: 40 },
  ];
  const edges = Array.from({ length: edgeCount }, (_, index) => ({
    id: `parallel-${index}`, source: 'source', target: 'target', label: '',
  }));
  return { nodes, edges };
}

async function stressAndCancel() {
  const projection = maximumProjection();
  const detachedAlternative = document.createElement('ol');
  const startedAt = performance.now();
  const routePlan = resolveViewerRoutesWithinBudget(projection);
  renderEmbedTextAlternative(detachedAlternative, projection);
  const readinessElapsed = performance.now() - startedAt;
  let pulseObserved = false;
  const pulse = new Promise(resolve => setTimeout(() => {
    pulseObserved = true;
    resolve();
  }, 0));
  let taskReachedAbortBoundary;
  const reachedAbortBoundary = new Promise(resolve => { taskReachedAbortBoundary = resolve; });
  const pending = lifecycle.run(async signal => {
    taskReachedAbortBoundary();
    await new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => reject(signal.reason), { once: true });
    });
    return projection;
  });
  await reachedAbortBoundary;
  lifecycle.destroy();
  await pending.catch(() => {});
  await pulse;
  return Object.freeze({
    routeStrategy: routePlan.strategy,
    elasticAvailable: viewerSupportsElastic(projection.nodes.length, projection.edges.length),
    readinessElapsed,
    pulseObserved,
    state: lifecycle.state,
    alternativeChildren: document.querySelector('#alternative').children.length,
    detachedNodes: detachedAlternative.children.length,
    detachedRelationships: detachedAlternative.querySelectorAll('ul > li').length,
    focusBoundariesRemoved: [...document.querySelectorAll('#focus-before, #focus-after')]
      .every(element => !element.hasAttribute('data-embed-focus-boundary')),
  });
}

async function parallelEdgeDeadline() {
  const projection = parallelProjection();
  const detachedAlternative = document.createElement('ol');
  let routePlan;
  let readinessElapsed;
  let pulseObserved = false;
  const pulse = new Promise(resolve => setTimeout(() => {
    pulseObserved = true;
    resolve();
  }, 0));
  const pending = lifecycle.run(async () => {
    const startedAt = performance.now();
    routePlan = resolveViewerRoutesWithinBudget(projection);
    renderEmbedTextAlternative(detachedAlternative, projection);
    readinessElapsed = performance.now() - startedAt;
    // Exercise the elapsed-deadline guard for synchronous renderer work: a
    // timer cannot interrupt the main thread, but READY must still be denied.
    const overrunUntil = performance.now() + 300;
    while (performance.now() < overrunUntil) { /* test-only deadline overrun */ }
    return projection;
  });
  await pending.catch(() => {});
  await new Promise(resolve => setTimeout(resolve, 50));
  await pulse;
  const stateAfterDeadline = lifecycle.state;
  const alternativeAfterDeadline = document.querySelector('#alternative').children.length;
  lifecycle.destroy();
  return Object.freeze({
    routeStrategy: routePlan.strategy,
    readinessElapsed,
    pulseObserved,
    stateAfterDeadline,
    stateAfterDestroy: lifecycle.state,
    alternativeAfterDeadline,
    detachedNodes: detachedAlternative.children.length,
    detachedRelationships: detachedAlternative.querySelectorAll('ul > li').length,
    focusBoundariesRemoved: [...document.querySelectorAll('#focus-before, #focus-after')]
      .every(element => !element.hasAttribute('data-embed-focus-boundary')),
  });
}

window.embedShellFixture = Object.freeze({
  get state() { return lifecycle.state; },
  get activations() { return activations; },
  show(kind, delay = 0) {
    return lifecycle.run(async signal => {
      if (delay > 0) await new Promise((resolve, reject) => {
        const timer = setTimeout(resolve, delay);
        signal.addEventListener('abort', () => {
          clearTimeout(timer);
          reject(signal.reason);
        }, { once: true });
      });
      if (kind === 'empty') return { nodes: [], edges: [] };
      if (kind === 'ready') return graph;
      throw new EmbedShellFailure(kind);
    });
  },
  stressAndCancel,
  parallelEdgeDeadline,
  destroy() { lifecycle.destroy(); },
});

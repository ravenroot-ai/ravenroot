const STATE_COPY = Object.freeze({
  idle: '',
  loading: 'Loading graph…',
  ready: 'Graph ready.',
  empty: 'This graph is empty.',
  error: 'The graph could not be displayed.',
  expired: 'This viewing session has expired.',
  offline: 'The graph service is unavailable.',
  incompatible: 'This graph requires a newer viewer.',
  destroyed: 'Viewer closed.',
});

const FAILURE_STATES = new Set(['error', 'expired', 'offline', 'incompatible']);
const NODE_KINDS = new Set(['START', 'PASSTHROUGH', 'BEHAVIOR', 'END', 'ERROR']);
const KIND_LABELS = Object.freeze({
  START: 'Start',
  PASSTHROUGH: 'Step',
  BEHAVIOR: 'Behavior',
  END: 'End',
  ERROR: 'Error',
});

function requiredElement(value, name) {
  if (!(value instanceof Element)) throw new TypeError(`${name} must be an element.`);
  return value;
}

function requiredText(value, name) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new TypeError(`Invalid text alternative: ${name}.`);
  }
  return value;
}

/**
 * A typed, fixed-copy failure for the embed shell. Failure details remain in
 * application diagnostics; only the closed kind crosses into the DOM.
 */
export class EmbedShellFailure extends Error {
  constructor(kind) {
    if (!FAILURE_STATES.has(kind)) throw new TypeError('Invalid embed shell failure kind.');
    super(`Embed shell ${kind}.`);
    this.name = 'EmbedShellFailure';
    this.kind = kind;
  }
}

function failureState(failure) {
  return failure instanceof EmbedShellFailure ? failure.kind : 'error';
}

/**
 * Copies only the viewer allowlist needed by a text alternative. Unknown
 * projection fields cannot enter the returned structure or later reach the DOM.
 */
export function createEmbedTextAlternative(snapshot) {
  if (!Array.isArray(snapshot?.nodes) || !Array.isArray(snapshot?.edges)) {
    throw new TypeError('Invalid text alternative projection.');
  }

  const nodes = snapshot.nodes.map(node => {
    const id = requiredText(node?.id, 'node.id');
    const kind = requiredText(node?.kind, 'node.kind');
    if (!NODE_KINDS.has(kind)) throw new TypeError('Invalid text alternative: node.kind.');
    return { id, kind };
  });
  const ids = new Set(nodes.map(node => node.id));
  if (ids.size !== nodes.length) throw new TypeError('Invalid text alternative topology.');

  const outgoing = new Map(nodes.map(node => [node.id, []]));
  const incoming = new Map(nodes.map(node => [node.id, []]));
  snapshot.edges.forEach(edge => {
    const source = requiredText(edge?.source, 'edge.source');
    const target = requiredText(edge?.target, 'edge.target');
    if (!ids.has(source) || !ids.has(target)) {
      throw new TypeError('Invalid text alternative topology.');
    }
    outgoing.get(source).push(Object.freeze({ target }));
    incoming.get(target).push(Object.freeze({ source }));
  });

  return Object.freeze(nodes.map(node => Object.freeze({
    id: node.id,
    kind: node.kind,
    label: `${KIND_LABELS[node.kind]} node ${node.id}`,
    outgoing: Object.freeze(outgoing.get(node.id)),
    incoming: Object.freeze(incoming.get(node.id)),
  })));
}

/** Render an allowlisted relationship list without interpreting markup. */
export function renderEmbedTextAlternative(list, snapshot) {
  requiredElement(list, 'alternative');
  const alternative = createEmbedTextAlternative(snapshot);
  const fragment = list.ownerDocument.createDocumentFragment();

  if (alternative.length === 0) {
    const empty = list.ownerDocument.createElement('li');
    empty.textContent = 'No nodes in this graph.';
    fragment.append(empty);
  }

  for (const node of alternative) {
    const item = list.ownerDocument.createElement('li');
    const label = list.ownerDocument.createElement('span');
    label.textContent = node.label;
    item.append(label);

    const relationships = [
      ...node.outgoing.map(edge => `Connects to ${edge.target}`),
      ...node.incoming.map(edge => `Receives from ${edge.source}`),
    ];
    if (relationships.length > 0) {
      const relationshipList = list.ownerDocument.createElement('ul');
      relationshipList.setAttribute('aria-label', `Relationships for ${node.id}`);
      for (const relationship of relationships) {
        const relationshipItem = list.ownerDocument.createElement('li');
        relationshipItem.textContent = relationship;
        relationshipList.append(relationshipItem);
      }
      item.append(relationshipList);
    }
    fragment.append(item);
  }

  list.replaceChildren(fragment);
  return alternative;
}

function rememberAttribute(element, name) {
  return element.hasAttribute(name) ? element.getAttribute(name) : null;
}

/**
 * Marks the two in-document boundary stops. Native sequential focus navigation
 * then leaves the iframe after either edge; no parent protocol is involved.
 */
export function installLocalFocusBoundary({ before, after }) {
  requiredElement(before, 'before sentinel');
  requiredElement(after, 'after sentinel');
  if (before.ownerDocument !== after.ownerDocument) {
    throw new TypeError('Focus sentinels must share a document.');
  }

  const previous = [before, after].map(element => ({
    tabindex: rememberAttribute(element, 'tabindex'),
    boundary: rememberAttribute(element, 'data-embed-focus-boundary'),
    label: rememberAttribute(element, 'aria-label'),
    role: rememberAttribute(element, 'role'),
  }));
  for (const [element, boundary, label] of [
    [before, 'before', 'Start of embedded graph'],
    [after, 'after', 'End of embedded graph'],
  ]) {
    element.setAttribute('tabindex', '0');
    element.setAttribute('role', 'navigation');
    element.setAttribute('data-embed-focus-boundary', boundary);
    element.setAttribute('aria-label', label);
  }

  let removed = false;
  return () => {
    if (removed) return;
    removed = true;
    [before, after].forEach((element, index) => {
      for (const [name, value] of [
        ['tabindex', previous[index].tabindex],
        ['data-embed-focus-boundary', previous[index].boundary],
        ['aria-label', previous[index].label],
        ['role', previous[index].role],
      ]) {
        if (value === null) element.removeAttribute(name);
        else element.setAttribute(name, value);
      }
    });
  };
}

function abortPromise(signal) {
  let listener;
  const promise = new Promise((resolve, reject) => {
    listener = () => reject(signal.reason ?? new EmbedShellFailure('error'));
    if (signal.aborted) listener();
    else signal.addEventListener('abort', listener, { once: true });
  });
  return { promise, remove: () => signal.removeEventListener('abort', listener) };
}

/**
 * Owns shell-only state and cleanup. `task` receives an AbortSignal and returns
 * an allowlisted viewer snapshot; rendering remains in the shared viewer core.
 */
export function createEmbedShellLifecycle({
  root,
  status,
  alternative,
  before,
  after,
  timeoutMilliseconds = 5_000,
}) {
  requiredElement(root, 'root');
  requiredElement(status, 'status');
  requiredElement(alternative, 'alternative');
  if (!Number.isSafeInteger(timeoutMilliseconds) || timeoutMilliseconds < 1
      || timeoutMilliseconds > 30_000) {
    throw new RangeError('Embed shell timeout is out of bounds.');
  }

  const removeFocusBoundary = installLocalFocusBoundary({ before, after });
  let state = 'idle';
  let destroyed = false;
  let generation = 0;
  let active = null;

  const transition = next => {
    state = next;
    root.dataset.viewerState = next;
    status.textContent = STATE_COPY[next];
  };

  const cancelActive = reason => {
    if (active !== null && !active.controller.signal.aborted) active.controller.abort(reason);
  };

  transition('idle');
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');

  return Object.freeze({
    get state() { return state; },
    async run(task, { signal } = {}) {
      if (destroyed) throw new Error('Embed shell is destroyed.');
      if (typeof task !== 'function') throw new TypeError('Embed shell task must be a function.');

      cancelActive(new DOMException('Viewer operation superseded.', 'AbortError'));
      const operation = ++generation;
      const controller = new AbortController();
      const relayAbort = () => controller.abort(signal.reason
        ?? new DOMException('Viewer operation aborted.', 'AbortError'));
      if (signal?.aborted) relayAbort();
      else signal?.addEventListener('abort', relayAbort, { once: true });

      const aborted = abortPromise(controller.signal);
      const deadline = performance.now() + timeoutMilliseconds;
      const timer = setTimeout(() => controller.abort(new Error('Viewer operation timed out.')),
        timeoutMilliseconds);
      active = { operation, controller, timer };
      transition('loading');
      alternative.replaceChildren();

      try {
        const snapshot = await Promise.race([
          Promise.resolve().then(() => task(controller.signal)),
          aborted.promise,
        ]);
        if (controller.signal.aborted) throw controller.signal.reason;
        if (destroyed || operation !== generation) {
          throw new DOMException('Viewer operation superseded.', 'AbortError');
        }
        renderEmbedTextAlternative(alternative, snapshot);
        if (controller.signal.aborted || performance.now() >= deadline) {
          throw controller.signal.reason ?? new Error('Viewer operation timed out.');
        }
        if (destroyed || operation !== generation) {
          throw new DOMException('Viewer operation superseded.', 'AbortError');
        }
        transition(snapshot.nodes.length === 0 ? 'empty' : 'ready');
        return snapshot;
      } catch (failure) {
        if (!destroyed && operation === generation) {
          alternative.replaceChildren();
          transition(failureState(failure));
        }
        throw failure;
      } finally {
        clearTimeout(timer);
        aborted.remove();
        signal?.removeEventListener('abort', relayAbort);
        if (active?.operation === operation) active = null;
      }
    },
    destroy({ preserveState = false } = {}) {
      if (destroyed) return;
      destroyed = true;
      generation += 1;
      cancelActive(new DOMException('Viewer destroyed.', 'AbortError'));
      active = null;
      alternative.replaceChildren();
      removeFocusBoundary();
      if (!preserveState) transition('destroyed');
    },
  });
}

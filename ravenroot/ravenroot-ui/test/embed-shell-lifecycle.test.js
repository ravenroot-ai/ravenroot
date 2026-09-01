import { readFileSync } from 'node:fs';
import { resolve as resolvePath } from 'node:path';

import { describe, expect, it, vi } from 'vitest';

import {
  createEmbedShellLifecycle,
  createEmbedTextAlternative,
  EmbedShellFailure,
  installLocalFocusBoundary,
  renderEmbedTextAlternative,
} from '../src/embed-shell-lifecycle.js';

function snapshot(overrides = {}) {
  return {
    nodes: [
      { id: 'start', kind: 'START', secret: 'node-secret' },
      { id: 'finish', kind: 'END' },
    ],
    edges: [{ source: 'start', target: 'finish', secret: 'edge-secret' }],
    bearer: 'must-not-cross',
    ...overrides,
  };
}

function fixture(timeoutMilliseconds = 100) {
  document.body.innerHTML = `
    <span id="before"></span>
    <main id="root"><ol id="alternative"></ol><p id="status"></p></main>
    <span id="after"></span>`;
  const elements = {
    root: document.querySelector('#root'),
    status: document.querySelector('#status'),
    alternative: document.querySelector('#alternative'),
    before: document.querySelector('#before'),
    after: document.querySelector('#after'),
  };
  return {
    elements,
    lifecycle: createEmbedShellLifecycle({ ...elements, timeoutMilliseconds }),
  };
}

describe('embed shell text alternative', () => {
  it('copies only node identity, kind, and directed relationships', () => {
    const alternative = createEmbedTextAlternative(snapshot());

    expect(alternative).toEqual([
      {
        id: 'start', kind: 'START', label: 'Start node start',
        outgoing: [{ target: 'finish' }], incoming: [],
      },
      {
        id: 'finish', kind: 'END', label: 'End node finish',
        outgoing: [], incoming: [{ source: 'start' }],
      },
    ]);
    expect(JSON.stringify(alternative)).not.toContain('secret');
    expect(Object.isFrozen(alternative)).toBe(true);
    expect(Object.isFrozen(alternative[0].outgoing[0])).toBe(true);
  });

  it('renders relationship text with textContent and no unallowlisted projection data', () => {
    const list = document.createElement('ol');
    renderEmbedTextAlternative(list, snapshot({
      nodes: [
        { id: '<start>', kind: 'START', raw: '<img src=x onerror=alert(1)>' },
        { id: 'finish', kind: 'END' },
      ],
      edges: [{ source: '<start>', target: 'finish' }],
      tenant: 'tenant-secret',
    }));

    expect(list.textContent).toContain('Start node <start>');
    expect(list.textContent).toContain('Connects to finish');
    expect(list.textContent).toContain('Receives from <start>');
    expect(list.querySelector('img')).toBeNull();
    expect(list.innerHTML).not.toContain('tenant-secret');
  });

  it('rejects duplicate nodes, unknown kinds, and relationships outside the node set', () => {
    expect(() => createEmbedTextAlternative(snapshot({
      nodes: [{ id: 'same', kind: 'START' }, { id: 'same', kind: 'END' }],
      edges: [],
    }))).toThrow('Invalid text alternative topology.');
    expect(() => createEmbedTextAlternative(snapshot({
      nodes: [{ id: 'node', kind: 'CUSTOM' }], edges: [],
    }))).toThrow('node.kind');
    expect(() => createEmbedTextAlternative(snapshot({
      edges: [{ source: 'start', target: 'absent' }],
    }))).toThrow('Invalid text alternative topology.');
  });

  it('builds the maximum relationship index in linear time', () => {
    const nodes = Array.from({ length: 2_000 }, (_, index) => ({
      id: `node-${index}`, kind: index === 0 ? 'START' : 'PASSTHROUGH',
    }));
    const edges = Array.from({ length: 5_000 }, (_, index) => ({
      source: nodes[index % nodes.length].id,
      target: nodes[(index * 17 + 1) % nodes.length].id,
    }));
    const started = performance.now();
    const alternative = createEmbedTextAlternative({ nodes, edges });
    const elapsed = performance.now() - started;

    expect(alternative).toHaveLength(2_000);
    expect(alternative.reduce((count, node) => count + node.outgoing.length, 0)).toBe(5_000);
    expect(alternative.reduce((count, node) => count + node.incoming.length, 0)).toBe(5_000);
    expect(elapsed).toBeLessThan(500);
  });
});

describe('embed shell lifecycle', () => {
  it('transitions through loading, ready, and empty with fixed live-region copy', async () => {
    const { lifecycle, elements } = fixture();
    let resolve;
    const pending = lifecycle.run(() => new Promise(settle => { resolve = settle; }));
    expect(lifecycle.state).toBe('loading');
    expect(elements.root.dataset.viewerState).toBe('loading');
    expect(elements.status.textContent).toBe('Loading graph…');

    await Promise.resolve();
    resolve(snapshot());
    await pending;
    expect(lifecycle.state).toBe('ready');
    expect(elements.alternative.textContent).toContain('Connects to finish');

    await lifecycle.run(async () => snapshot({ nodes: [], edges: [] }));
    expect(lifecycle.state).toBe('empty');
    expect(elements.status.textContent).toBe('This graph is empty.');
    expect(elements.alternative.textContent).toBe('No nodes in this graph.');
  });

  it.each([
    ['error', 'The graph could not be displayed.'],
    ['expired', 'This viewing session has expired.'],
    ['offline', 'The graph service is unavailable.'],
    ['incompatible', 'This graph requires a newer viewer.'],
  ])('maps %s failures to fixed copy without rendering exception details', async (kind, copy) => {
    const { lifecycle, elements } = fixture();
    const failure = kind === 'error'
      ? new Error('bearer and upstream details')
      : new EmbedShellFailure(kind);

    await expect(lifecycle.run(async () => { throw failure; })).rejects.toBe(failure);
    expect(lifecycle.state).toBe(kind);
    expect(elements.status.textContent).toBe(copy);
    expect(document.body.textContent).not.toContain('bearer and upstream details');
    expect(elements.alternative.children).toHaveLength(0);
  });

  it('bounds ignored aborts with a timeout and clears the timer', async () => {
    vi.useFakeTimers();
    try {
      const { lifecycle, elements } = fixture(50);
      let receivedSignal;
      const pending = lifecycle.run(signal => {
        receivedSignal = signal;
        return new Promise(() => {});
      });
      const rejection = expect(pending).rejects.toThrow('timed out');

      await vi.advanceTimersByTimeAsync(50);
      await rejection;
      expect(receivedSignal.aborted).toBe(true);
      expect(lifecycle.state).toBe('error');
      expect(elements.status.textContent).toBe('The graph could not be displayed.');
      expect(vi.getTimerCount()).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not transition READY when synchronous readiness work exhausts the deadline', async () => {
    const { lifecycle, elements } = fixture(5);
    const now = vi.spyOn(performance, 'now');
    now.mockReturnValueOnce(10).mockReturnValue(20);
    try {
      await expect(lifecycle.run(async () => snapshot())).rejects.toThrow('timed out');
      expect(lifecycle.state).toBe('error');
      expect(elements.root.dataset.viewerState).not.toBe('ready');
      expect(elements.alternative.children).toHaveLength(0);
    } finally {
      now.mockRestore();
    }
  });

  it('aborts a superseded operation without allowing stale settlement to replace current state', async () => {
    const { lifecycle, elements } = fixture();
    let oldSignal;
    let resolveOld;
    const old = lifecycle.run(signal => {
      oldSignal = signal;
      return new Promise(resolve => { resolveOld = resolve; });
    });
    const oldRejection = expect(old).rejects.toMatchObject({ name: 'AbortError' });

    await lifecycle.run(async () => snapshot({ nodes: [], edges: [] }));
    resolveOld(snapshot());
    await oldRejection;
    expect(oldSignal.aborted).toBe(true);
    expect(lifecycle.state).toBe('empty');
    expect(elements.status.textContent).toBe('This graph is empty.');
  });

  it('relays caller abort, destroys idempotently, and prevents late DOM work', async () => {
    const { lifecycle, elements } = fixture();
    const caller = new AbortController();
    let taskSignal;
    const pending = lifecycle.run(signal => {
      taskSignal = signal;
      return new Promise(() => {});
    }, { signal: caller.signal });
    const rejection = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    caller.abort(new DOMException('Navigation stopped.', 'AbortError'));
    await rejection;
    expect(taskSignal.aborted).toBe(true);
    expect(lifecycle.state).toBe('error');

    const destroying = lifecycle.run(() => new Promise(() => {}));
    const destroyRejection = expect(destroying).rejects.toMatchObject({ name: 'AbortError' });
    lifecycle.destroy();
    lifecycle.destroy();
    await destroyRejection;
    expect(lifecycle.state).toBe('destroyed');
    expect(elements.root.dataset.viewerState).toBe('destroyed');
    expect(elements.status.textContent).toBe('Viewer closed.');
    expect(elements.alternative.children).toHaveLength(0);
    await expect(lifecycle.run(async () => snapshot())).rejects.toThrow('destroyed');
  });

  it('can release all lifecycle resources while preserving a fixed failure state', async () => {
    const { lifecycle, elements } = fixture();
    await expect(lifecycle.run(async () => {
      throw new EmbedShellFailure('offline');
    })).rejects.toMatchObject({ kind: 'offline' });

    lifecycle.destroy({ preserveState: true });
    expect(lifecycle.state).toBe('offline');
    expect(elements.root.dataset.viewerState).toBe('offline');
    expect(elements.status.textContent).toBe('The graph service is unavailable.');
    expect(elements.before.hasAttribute('data-embed-focus-boundary')).toBe(false);
    expect(elements.after.hasAttribute('data-embed-focus-boundary')).toBe(false);
    await expect(lifecycle.run(async () => snapshot())).rejects.toThrow('destroyed');
  });
});

describe('local focus boundary', () => {
  it('uses native document-edge stops and restores existing attributes on cleanup', () => {
    document.body.innerHTML = '<span id="before" tabindex="-1"></span><span id="after"></span>';
    const before = document.querySelector('#before');
    const after = document.querySelector('#after');
    const cleanup = installLocalFocusBoundary({ before, after });

    expect(before.tabIndex).toBe(0);
    expect(after.tabIndex).toBe(0);
    expect(before.getAttribute('role')).toBe('navigation');
    expect(after.getAttribute('role')).toBe('navigation');
    expect(before.dataset.embedFocusBoundary).toBe('before');
    expect(after.dataset.embedFocusBoundary).toBe('after');

    cleanup();
    cleanup();
    expect(before.getAttribute('tabindex')).toBe('-1');
    expect(before.hasAttribute('data-embed-focus-boundary')).toBe(false);
    expect(after.hasAttribute('tabindex')).toBe(false);
  });

  it('contains no parent messaging surface or protocol extension', () => {
    const source = readFileSync(resolvePath(process.cwd(), 'src/embed-shell-lifecycle.js'), 'utf8');

    expect(source).not.toContain('postMessage');
    expect(source).not.toContain('MessageChannel');
    expect(source).not.toContain('focusExit');
    expect(source).not.toContain('addEventListener(\'message\'');
  });
});

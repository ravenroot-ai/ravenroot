import { describe, expect, it, vi } from 'vitest';

import { createReadOnlyViewerCore, createViewerSnapshot } from '../src/viewer-core.js';

function projection(overrides = {}) {
  return {
    viewerContractVersion: '1.0',
    graphId: 'graph-1',
    graphVersionId: 'version-1',
    canonicalDigest: 'digest-1',
    nodes: [{
      id: 'start',
      kind: 'START',
      layout: { x: 10, y: 20, width: 84, height: 84 },
      secret: 'must-not-cross',
    }],
    edges: [],
    runtimeState: 'must-not-cross',
    ...overrides,
  };
}

function renderer() {
  return {
    render: vi.fn(),
    fit: vi.fn(),
    zoomBy: vi.fn(),
    panBy: vi.fn(),
    destroy: vi.fn(),
  };
}

describe('shared read-only viewer core', () => {
  it('copies only the projection allowlist into an immutable renderer snapshot', () => {
    const snapshot = createViewerSnapshot(projection());

    expect(snapshot.nodes[0]).toEqual({
      id: 'start', kind: 'START', visualType: 'start', label: 'start', bypassed: false,
      layout: { x: 10, y: 20, width: 84, height: 84 },
    });
    expect(snapshot.elements[0].data).toEqual({
      id: 'start', label: '▶ start', cardLabel: 'start', name: 'start', nodeType: 'start', bypassed: false,
      runtimeState: 'idle', runtimeObserved: false, nw: 84, nh: 84,
    });
    expect(JSON.stringify(snapshot)).not.toContain('must-not-cross');
    expect(Object.isFrozen(snapshot)).toBe(true);
  });

  it('accepts only the allowlisted visual semantics needed for editor parity', () => {
    const snapshot = createViewerSnapshot(projection({
      nodes: [{ id: 'worker', kind: 'BEHAVIOR', visualType: 'agent', label: 'Summarize',
        bypassed: true, layout: { x: 1, y: 2, width: 140, height: 72 }, secret: 'no' }],
      edges: [],
    }));
    expect(snapshot.elements[0].data).toMatchObject({
      id: 'worker', name: 'Summarize', nodeType: 'agent', bypassed: true,
    });
    expect(JSON.stringify(snapshot)).not.toContain('secret');
  });

  it('settles READY only after rendering and delegates read-only viewport operations', async () => {
    let settle;
    const target = renderer();
    target.render.mockReturnValue(new Promise(resolve => { settle = resolve; }));
    const viewer = createReadOnlyViewerCore(target);

    const mounting = viewer.mount(projection());
    expect(viewer.state).toBe('loading');
    settle();
    await mounting;
    expect(viewer.state).toBe('ready');

    viewer.fit(48);
    viewer.zoomBy(1.2);
    viewer.panBy({ x: 10, y: -5 });
    expect(target.fit).toHaveBeenCalledWith(48);
    expect(target.zoomBy).toHaveBeenCalledWith(1.2);
    expect(target.panBy).toHaveBeenCalledWith({ x: 10, y: -5 });
  });

  it('rejects incompatible, invalid, and non-closed topology before rendering', async () => {
    const target = renderer();
    const viewer = createReadOnlyViewerCore(target);

    await expect(viewer.mount(projection({ viewerContractVersion: '2.0' })))
      .rejects.toThrow('Incompatible viewer projection.');
    await expect(viewer.mount(projection({
      edges: [{ source: 'start', target: 'missing' }],
    }))).rejects.toThrow('Invalid viewer projection topology.');
    expect(target.render).not.toHaveBeenCalled();
  });

  it('rejects an over-budget projection before handing any elements to the renderer', async () => {
    const target = renderer();
    const viewer = createReadOnlyViewerCore(target, {
      nodes: 0, edges: 0, renderMilliseconds: 100,
    });

    await expect(viewer.mount(projection())).rejects.toThrow('rendering budget');
    expect(viewer.state).toBe('error');
    expect(target.render).not.toHaveBeenCalled();
  });

  it('keeps destroyed terminal while an in-flight render settles late', async () => {
    let settle;
    const target = renderer();
    target.render.mockReturnValue(new Promise(resolve => { settle = resolve; }));
    const viewer = createReadOnlyViewerCore(target);

    const mounting = viewer.mount(projection());
    viewer.destroy();
    expect(viewer.state).toBe('destroyed');

    settle();
    await expect(mounting).rejects.toThrow('Viewer render superseded.');
    expect(viewer.state).toBe('destroyed');
  });

  it('does not let a superseded mount overwrite the newer operation state', async () => {
    let settleFirst;
    const target = renderer();
    target.render
      .mockReturnValueOnce(new Promise(resolve => { settleFirst = resolve; }))
      .mockResolvedValueOnce();
    const viewer = createReadOnlyViewerCore(target);

    const first = viewer.mount(projection());
    await viewer.mount(projection({ graphVersionId: 'version-2' }));
    expect(viewer.state).toBe('ready');

    settleFirst();
    await expect(first).rejects.toThrow('Viewer render superseded.');
    expect(viewer.state).toBe('ready');
  });
});

import { afterEach, describe, expect, it, vi } from 'vitest';

import { createCytoscapeReadOnlyRendererAdapter } from '../src/viewer-renderer-adapter.js';

function instance() {
  const remove = vi.fn();
  return {
    target: {
      elements: vi.fn(() => ({ remove })),
      add: vi.fn(),
      layout: vi.fn(() => ({ run: vi.fn() })),
      fit: vi.fn(),
      zoom: vi.fn(() => 1),
      width: vi.fn(() => 800),
      height: vi.fn(() => 600),
      panBy: vi.fn(),
      destroy: vi.fn(),
    },
    remove,
  };
}

function snapshot() {
  return {
    nodes: [{ id: 'start', layout: null }],
    elements: [{ data: { id: 'start', label: 'start' } }],
  };
}

function controlledFrames() {
  let nextHandle = 1;
  const callbacks = new Map();
  const cancelledCallbacks = new Map();
  vi.stubGlobal('requestAnimationFrame', vi.fn(callback => {
    const handle = nextHandle++;
    callbacks.set(handle, callback);
    return handle;
  }));
  vi.stubGlobal('cancelAnimationFrame', vi.fn(handle => {
    const callback = callbacks.get(handle);
    if (callback) cancelledCallbacks.set(handle, callback);
    callbacks.delete(handle);
  }));
  return { callbacks, cancelledCallbacks };
}

afterEach(() => vi.unstubAllGlobals());

describe('Cytoscape read-only renderer lifecycle', () => {
  it('cancels a pending render frame and rejects promptly when destroyed', async () => {
    const frames = controlledFrames();
    const { target } = instance();
    const adapter = createCytoscapeReadOnlyRendererAdapter(target);

    const rendering = adapter.render(snapshot());
    const [handle] = frames.callbacks.keys();
    const cancelledCallback = frames.callbacks.get(handle);
    adapter.destroy();

    await expect(rendering).rejects.toMatchObject({ name: 'AbortError' });
    expect(cancelAnimationFrame).toHaveBeenCalledWith(handle);
    expect(frames.callbacks.size).toBe(0);
    expect(target.destroy).toHaveBeenCalledOnce();

    const mutations = {
      add: target.add.mock.calls.length,
      layout: target.layout.mock.calls.length,
      raf: requestAnimationFrame.mock.calls.length,
    };
    cancelledCallback(1);
    expect(target.add).toHaveBeenCalledTimes(mutations.add);
    expect(target.layout).toHaveBeenCalledTimes(mutations.layout);
    expect(requestAnimationFrame).toHaveBeenCalledTimes(mutations.raf);
  });

  it('owns and cancels the nested readiness frame', async () => {
    const frames = controlledFrames();
    const { target } = instance();
    const adapter = createCytoscapeReadOnlyRendererAdapter(target);

    const rendering = adapter.render(snapshot());
    const [firstHandle, firstCallback] = frames.callbacks.entries().next().value;
    frames.callbacks.delete(firstHandle);
    firstCallback(1);
    const [secondHandle] = frames.callbacks.keys();

    adapter.destroy();
    await expect(rendering).rejects.toMatchObject({ name: 'AbortError' });
    expect(cancelAnimationFrame).toHaveBeenCalledWith(secondHandle);
    expect(frames.callbacks.size).toBe(0);
  });
});

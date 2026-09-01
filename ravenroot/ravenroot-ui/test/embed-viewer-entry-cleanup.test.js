import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  core: null,
  instance: null,
  rendererContract: null,
  cytoscapeAdapter: null,
}));

vi.mock('cytoscape', () => ({ default: vi.fn(() => mocks.instance) }));
vi.mock('../src/viewer-renderer-adapter.js', () => ({
  createCytoscapeReadOnlyRendererAdapter: vi.fn(() => mocks.cytoscapeAdapter),
  createReadOnlyRendererAdapter: vi.fn(contract => contract),
}));
vi.mock('../src/viewer-core.js', () => ({
  createReadOnlyViewerCore: vi.fn(contract => {
    mocks.rendererContract = contract;
    return mocks.core;
  }),
}));

import { createEmbedViewer } from '../src/embed-viewer-entry.js';

function shell() {
  document.body.innerHTML = `
    <span class="embed-focus-sentinel"></span>
    <main id="viewer">
      <p data-viewer-status></p><p data-viewer-metadata></p>
      <select data-viewer-mode><option value="cyto">Cyto</option>
        <option value="n8n">N8N</option><option value="elastic">Elastic</option></select>
      <button data-viewer-command="fit"></button>
      <div data-viewer-canvas></div><canvas data-viewer-minimap tabindex="0"></canvas>
      <ol data-viewer-alternative></ol>
    </main>
    <span class="embed-focus-sentinel"></span>`;
  return document.querySelector('#viewer');
}

describe('embed viewer failed-mount cleanup', () => {
  beforeEach(() => {
    mocks.cytoscapeAdapter = {
      render: vi.fn(), fit: vi.fn(), zoomBy: vi.fn(), panBy: vi.fn(), destroy: vi.fn(),
    };
    mocks.instance = {
      nodes: vi.fn(() => []), edges: vi.fn(() => []), style: vi.fn(), on: vi.fn(),
      elements: vi.fn(() => ({ boundingBox: vi.fn() })), extent: vi.fn(),
      width: vi.fn(() => 800), height: vi.fn(() => 500), zoom: vi.fn(() => 1),
      pan: vi.fn(), resize: vi.fn(),
    };
    mocks.core = {
      state: 'ready',
      mount: vi.fn(() => new Promise(() => {})),
      fit: vi.fn(), zoomBy: vi.fn(), panBy: vi.fn(),
      destroy: vi.fn(() => mocks.rendererContract.destroy()),
    };
  });

  it('fully tears down a timed-out production mount without replacing its fixed error state', async () => {
    vi.useFakeTimers();
    const disconnect = vi.fn();
    const observe = vi.fn();
    let resizeCallback;
    const oldResizeObserver = globalThis.ResizeObserver;
    globalThis.ResizeObserver = class {
      constructor(callback) { resizeCallback = callback; }
      observe = observe;
      disconnect = disconnect;
    };
    const requestFrame = vi.spyOn(globalThis, 'requestAnimationFrame').mockReturnValue(17);
    const cancelFrame = vi.spyOn(globalThis, 'cancelAnimationFrame').mockImplementation(() => {});
    try {
      const root = shell();
      const mode = root.querySelector('[data-viewer-mode]');
      const canvas = root.querySelector('[data-viewer-canvas]');
      const minimap = root.querySelector('[data-viewer-minimap]');
      const control = root.querySelector('[data-viewer-command]');
      const removals = [mode, canvas, minimap, control]
        .map(element => vi.spyOn(element, 'removeEventListener'));
      const viewer = createEmbedViewer(root);
      resizeCallback();
      const pending = viewer.mount({ nodes: [], edges: [] });
      const rejection = expect(pending).rejects.toThrow('timed out');

      await vi.advanceTimersByTimeAsync(5_000);
      await rejection;
      await vi.runAllTimersAsync();

      expect(viewer.state).toBe('error');
      expect(root.dataset.viewerState).toBe('error');
      expect(root.querySelector('[data-viewer-status]').textContent)
        .toBe('The graph could not be displayed.');
      expect(disconnect).toHaveBeenCalledOnce();
      expect(mocks.core.destroy).toHaveBeenCalledOnce();
      expect(mocks.cytoscapeAdapter.destroy).toHaveBeenCalledOnce();
      expect(cancelFrame).toHaveBeenCalled();
      expect(root.querySelector('.embed-viewer-elastic')).toBeNull();
      expect(root.querySelector('[data-viewer-alternative]').children).toHaveLength(0);
      expect(document.querySelectorAll('[data-embed-focus-boundary]')).toHaveLength(0);
      removals.forEach(removal => expect(removal).toHaveBeenCalled());

      viewer.destroy();
      expect(viewer.state).toBe('error');
      expect(mocks.core.destroy).toHaveBeenCalledOnce();
    } finally {
      cancelFrame.mockRestore();
      requestFrame.mockRestore();
      globalThis.ResizeObserver = oldResizeObserver;
      vi.useRealTimers();
    }
  });
});

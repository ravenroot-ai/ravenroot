import { describe, expect, it, vi } from 'vitest';

import { createEmbedMaximizeController } from '../src/embed-maximize.js';

describe('embedded graph maximize controller', () => {
  it('falls back to a fixed viewport when fullscreen is denied and restores focus with Escape', async () => {
    document.body.innerHTML = '<button id="before">Before</button><main id="viewer"><button id="maximize"></button></main>';
    const before = document.querySelector('#before');
    const root = document.querySelector('#viewer');
    const button = document.querySelector('#maximize');
    before.focus();
    root.requestFullscreen = vi.fn(() => Promise.reject(new Error('denied')));
    const resize = vi.fn();
    const requestAnimationFrame = vi.fn(callback => { callback(); return 1; });
    const controller = createEmbedMaximizeController(root, button, {
      window: { requestAnimationFrame }, onResize: resize,
    });

    button.click();
    await Promise.resolve();
    expect(controller.maximized).toBe(true);
    expect(root.classList.contains('embed-viewer--maximized')).toBe(true);
    expect(button.getAttribute('aria-label')).toBe('Restore embedded graph');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    expect(controller.maximized).toBe(false);
    expect(document.activeElement).toBe(before);
    expect(resize).toHaveBeenCalledTimes(3); // initial, maximize, restore
    controller.destroy();
  });

  it('synchronizes the restore control when native fullscreen exits externally', async () => {
    document.body.innerHTML = '<main id="viewer"><button id="maximize"></button></main>';
    const root = document.querySelector('#viewer');
    const button = document.querySelector('#maximize');
    let fullscreenElement = null;
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => fullscreenElement });
    root.requestFullscreen = vi.fn(() => { fullscreenElement = root; return Promise.resolve(); });
    document.exitFullscreen = vi.fn(() => { fullscreenElement = null; return Promise.resolve(); });
    const controller = createEmbedMaximizeController(root, button, {
      window: { requestAnimationFrame: callback => { callback(); return 1; } },
    });
    controller.maximize();
    await Promise.resolve();
    fullscreenElement = null;
    document.dispatchEvent(new Event('fullscreenchange'));
    expect(controller.maximized).toBe(false);
    expect(button.getAttribute('aria-pressed')).toBe('false');
    controller.destroy();
  });
});

for (const action of ['restore', 'destroy']) {
  it(`exits its own late native fullscreen after pending ${action}`, async () => {
    document.body.innerHTML = '<main><button></button></main>';
    const root = document.querySelector('main');
    let fullscreenElement = null;
    let resolve;
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => fullscreenElement });
    root.requestFullscreen = () => new Promise(done => { resolve = done; });
    document.exitFullscreen = vi.fn(() => { fullscreenElement = null; return Promise.resolve(); });
    const controller = createEmbedMaximizeController(root, root.querySelector('button'));
    controller.maximize(); controller[action]();
    fullscreenElement = root; resolve(); await Promise.resolve();
    expect(controller.maximized).toBe(false);
    expect(fullscreenElement).toBe(null);
    expect(document.exitFullscreen).toHaveBeenCalledOnce();
    controller.destroy();
  });
}

it('retired fullscreen requests never exit a different viewer or clear its document state', async () => {
  document.body.innerHTML = '<main id="a"><button></button></main><main id="b"><button></button></main>';
  const [a, b] = document.querySelectorAll('main');
  let fullscreenElement = null;
  let resolve;
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => fullscreenElement });
  a.requestFullscreen = () => new Promise(done => { resolve = done; });
  b.requestFullscreen = undefined;
  document.exitFullscreen = vi.fn();
  const ca = createEmbedMaximizeController(a, a.querySelector('button'));
  const cb = createEmbedMaximizeController(b, b.querySelector('button'));
  ca.maximize(); cb.maximize(); ca.destroy();
  fullscreenElement = b; resolve(); await Promise.resolve();
  expect(document.exitFullscreen).not.toHaveBeenCalled();
  expect(cb.maximized).toBe(true);
  expect(document.documentElement.classList.contains('embed-viewer-document--maximized')).toBe(true);
  fullscreenElement = null; cb.destroy();
});

it('synchronizes an external exit before the fullscreen request promise settles', async () => {
  document.body.innerHTML = '<main><button></button></main>';
  const root = document.querySelector('main');
  let fullscreenElement = null;
  let resolve;
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => fullscreenElement });
  root.requestFullscreen = () => new Promise(done => { resolve = done; });
  const controller = createEmbedMaximizeController(root, root.querySelector('button'));
  controller.maximize(); fullscreenElement = root; document.dispatchEvent(new Event('fullscreenchange'));
  fullscreenElement = null; document.dispatchEvent(new Event('fullscreenchange'));
  resolve(); await Promise.resolve();
  expect(controller.maximized).toBe(false);
  controller.destroy();
});

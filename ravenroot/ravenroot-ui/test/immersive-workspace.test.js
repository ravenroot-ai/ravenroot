import { describe, expect, it } from 'vitest';
import { defaultLayout } from '../src/panel-layout.js';
import {
  defaultImmersiveLayout, recoverImmersiveLayout, surfaceRect, usableGraphRect, graphFitViewport,
} from '../src/immersive-workspace.js';

describe('immersive workspace recovery', () => {
  it('starts a new Catalog minimized while preserving an explicitly visible legacy Catalog', () => {
    expect(recoverImmersiveLayout(null).surfaces.catalog.minimized).toBe(true);
    expect(recoverImmersiveLayout(null, defaultLayout()).surfaces.catalog.minimized).toBe(false);
  });

  it('migrates legacy zone placement, collapsed state and normalized dimensions', () => {
    const legacy = defaultLayout();
    legacy.zones.right.collapsed = true;
    legacy.zones.right.dimension = .32;
    legacy.zones.bottom.dimension = .28;
    legacy.panels.filter(panel => panel.zone === 'left').forEach(panel => { panel.closed = true; });
    const state = recoverImmersiveLayout(null, legacy);
    expect(state.v).toBe(1);
    expect(state.surfaces.inspector).toMatchObject({ dock: 'right', minimized: true, width: .32 });
    expect(state.surfaces.activity).toMatchObject({ dock: 'bottom', height: .28 });
    expect(state.surfaces.catalog.minimized).toBe(true);
    expect(legacy.v).toBe(3);
  });

  it('repairs off-screen values and recovers damaged surfaces without losing valid siblings', () => {
    const raw = defaultImmersiveLayout();
    raw.surfaces.inspector = { dock: 'floating', x: 15, y: -4, width: 20, height: .001, minimized: true };
    raw.surfaces.catalog.dock = 'removed-edge';
    raw.surfaces.activity.x = NaN;
    const recovered = recoverImmersiveLayout(raw);
    expect(recovered.surfaces.inspector).toEqual({ dock: 'floating', x: 1, y: 0, width: 1, height: .05, minimized: true });
    expect(recovered.surfaces.catalog).toEqual(defaultImmersiveLayout().surfaces.catalog);
    expect(recovered.surfaces.activity).toEqual(defaultImmersiveLayout().surfaces.activity);
    expect(recovered.surfaces.tools).toEqual(raw.surfaces.tools);
  });

  it('keeps floating headers inside the viewport after a monitor or window change', () => {
    const panel = { dock: 'floating', x: 1, y: 1, width: .8, height: .8 };
    for (const bounds of [{ width: 390, height: 520 }, { width: 1280, height: 560 }]) {
      const rect = surfaceRect(panel, bounds, 'inspector');
      expect(rect.x).toBeGreaterThanOrEqual(0);
      expect(rect.y).toBeGreaterThanOrEqual(0);
      expect(rect.x + rect.width).toBeLessThanOrEqual(bounds.width);
      expect(rect.y + rect.height).toBeLessThanOrEqual(bounds.height);
    }
  });
});

describe('usable graph viewport', () => {
  it('finds a clear region between docked overlays in translated screen coordinates', () => {
    const bounds = { x: 100, y: 60, width: 1200, height: 800 };
    expect(usableGraphRect(bounds, [
      { x: 100, y: 60, width: 250, height: 800 },
      { x: 1000, y: 60, width: 300, height: 800 },
      { x: 350, y: 660, width: 650, height: 200 },
    ])).toEqual({ x: 350, y: 60, width: 650, height: 600 });
  });

  it('accounts for a floating middle overlay and ignores obstacles beyond the pane', () => {
    const bounds = { x: 0, y: 0, width: 1000, height: 600 };
    const floating = { x: 600, y: 200, width: 200, height: 200 };
    const result = usableGraphRect(bounds, [floating, { x: 1400, y: 0, width: 200, height: 600 }]);
    expect(result).toEqual({ x: 0, y: 0, width: 600, height: 600 });
    expect(result.x + result.width).toBeLessThanOrEqual(floating.x);
    expect(usableGraphRect(bounds)).toEqual(bounds);
  });
});


describe('compact graph framing', () => {
  it('leaves graph space between the top palette and a narrow Inspector drawer', () => {
    const bounds = { width: 390, height: 580 };
    const state = defaultImmersiveLayout();
    const tools = surfaceRect(state.surfaces.tools, bounds, 'tools');
    const inspector = surfaceRect(state.surfaces.inspector, bounds, 'inspector');
    expect(inspector.height).toBeLessThanOrEqual(bounds.height * .4);
    expect(inspector.y - tools.y - tools.height).toBeGreaterThan(100);
  });

  it('fits a very large graph into a shallow clear region without consuming it with padding', () => {
    const area = { x: 12, y: 174, width: 366, height: 120 };
    const box = { x1: -1000, y1: -500, x2: 7000, y2: 4500, w: 8000, h: 5000 };
    const viewport = graphFitViewport(area, box);
    expect(viewport.zoom).toBeLessThan(.05);
    expect(box.x1 * viewport.zoom + viewport.pan.x).toBeGreaterThan(area.x);
    expect(box.x2 * viewport.zoom + viewport.pan.x).toBeLessThan(area.x + area.width);
    expect(box.y1 * viewport.zoom + viewport.pan.y).toBeGreaterThan(area.y);
    expect(box.y2 * viewport.zoom + viewport.pan.y).toBeLessThan(area.y + area.height);
  });
});

import { describe, expect, it } from 'vitest';
import { clampViewportCenter, minimapToWorld, projectMinimap } from '../src/minimap-geometry.js';

describe('minimap geometry', () => {
  it('projects huge negative coordinates into a finite proportional map', () => {
    const result = projectMinimap({
      contentBounds: { x1: -1_000_000, y1: -500_000, x2: 3_000_000, y2: 500_000 },
      visibleBounds: { x1: -100_000, y1: -100_000, x2: 100_000, y2: 100_000 },
      width: 160, height: 100,
    });
    expect(Object.values(result.viewport).every(Number.isFinite)).toBe(true);
    expect(result.viewport.width).toBeGreaterThanOrEqual(12);
    expect(result.viewport.x).toBeGreaterThanOrEqual(result.map.x);
    expect(result.viewport.x + result.viewport.width).toBeLessThanOrEqual(result.map.x + result.map.width);
  });

  it('round-trips minimap points to renderer coordinates', () => {
    const result = projectMinimap({
      contentBounds: { x1: -100, y1: -50, x2: 300, y2: 150 },
      visibleBounds: { x1: 0, y1: 0, x2: 100, y2: 100 }, width: 160, height: 100,
    });
    const point = minimapToWorld(result, {
      x: 20 * result.scale + result.offsetX, y: 40 * result.scale + result.offsetY,
    });
    expect(point.x).toBeCloseTo(20, 12);
    expect(point.y).toBeCloseTo(40, 12);
  });

  it('clamps a viewport inside content while centering oversized viewports', () => {
    expect(clampViewportCenter(
      { x1: 0, y1: 0, x2: 100, y2: 100 },
      { x1: 0, y1: 0, x2: 20, y2: 20 }, { x: -500, y: 500 },
    )).toEqual({ x: 10, y: 90 });
    expect(clampViewportCenter(
      { x1: 0, y1: 0, x2: 100, y2: 100 },
      { x1: -100, y1: -100, x2: 200, y2: 200 }, { x: 0, y: 0 },
    )).toEqual({ x: 50, y: 50 });
  });
});

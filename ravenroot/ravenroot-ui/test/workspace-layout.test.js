import { describe, expect, it } from 'vitest';

import {
  clampSharesToMinimums,
  normalizeShares,
  planWorkspaceLayout,
  readableZoomForFonts,
  resizeAxisShares,
  serializeWorkspaceLayout,
  validateWorkspaceLayout,
} from '../src/workspace-layout.js';

const docs = count => Array.from({ length: count }, (_, index) => ({ active: index === 0 }));

describe('workspace grid planning', () => {
  it.each([
    ['horizontal', 2, 1214, 615, 2, 1, 'all'],
    ['vertical', 2, 1214, 615, 1, 2, 'all'],
    ['grid', 2, 1214, 615, 2, 1, 'all'],
    ['horizontal', 3, 1214, 615, 3, 1, 'all'],
    ['vertical', 3, 1214, 615, 1, 1, 'active-only'],
    ['grid', 3, 1214, 615, 2, 2, 'all'],
    ['grid', 5, 1214, 615, 3, 2, 'all'],
    ['horizontal', 2, 694, 515, 1, 1, 'active-only'],
    ['vertical', 2, 694, 515, 1, 2, 'all'],
    ['grid', 3, 694, 515, 1, 1, 'active-only'],
  ])('%s / %i documents at %ix%i', (mode, count, width, height, columns, rows, visibility) => {
    const plan = planWorkspaceLayout({ mode, documents: docs(count), availableWidth: width, availableHeight: height });
    expect([plan.columns, plan.rows, plan.visibility]).toEqual([columns, rows, visibility]);
  });

  it('uses the exact 228px pane block floor', () => {
    expect(planWorkspaceLayout({ mode: 'vertical', documents: docs(2), availableWidth: 720, availableHeight: 457 }).visibility)
      .toBe('all');
    expect(planWorkspaceLayout({ mode: 'vertical', documents: docs(2), availableWidth: 720, availableHeight: 456 }).visibility)
      .toBe('active-only');
  });

  it('lets measured content requirements force the responsive state', () => {
    expect(planWorkspaceLayout({
      mode: 'horizontal', availableWidth: 800, availableHeight: 500,
      documents: [{ active: true, minInline: 450 }, { minInline: 450 }],
    }).visibility).toBe('active-only');
  });

  it('reserves a measured overlay only in the occupied cell it intersects', () => {
    const plan = planWorkspaceLayout({
      mode: 'horizontal', availableWidth: 821, availableHeight: 500,
      documents: docs(2).map(document_ => ({ ...document_, minInline: 360 })),
      overlays: [{ left: 0, top: 400, right: 100, bottom: 500 }],
    });
    expect(plan.visibility).toBe('all');
    expect(plan.columnMins).toEqual([460, 360]);
    expect(planWorkspaceLayout({
      mode: 'horizontal', availableWidth: 820, availableHeight: 500,
      documents: docs(2).map(document_ => ({ ...document_, minInline: 360 })),
      overlays: [{ left: 0, top: 400, right: 100, bottom: 500 }],
    }).visibility).toBe('active-only');
  });

  it('rejects an asymmetric two-document grid instead of inventing unequal initial tracks', () => {
    const plan = planWorkspaceLayout({
      mode: 'grid', availableWidth: 1200, availableHeight: 400,
      documents: [{ active: true, minInline: 800 }, { minInline: 360 }],
      columnShares: [2 / 3, 1 / 3],
    });
    expect(plan.visibility).toBe('active-only');
    expect(plan.reason).toBe('insufficient-space');
  });

  it.each([
    [3, 1001, 457, 500, 2, 2, 'all'],
    [3, 1000, 457, 500, 1, 1, 'active-only'],
    [5, 1172, 457, 390, 3, 2, 'all'],
    [5, 1171, 457, 390, 1, 1, 'active-only'],
  ])('applies global equal-track minima for %i asymmetric documents at %ix%i',
    (count, width, height, wideMinimum, columns, rows, visibility) => {
      const documents = docs(count).map((document_, index) => ({
        ...document_, minInline: index === 0 ? wideMinimum : 360,
      }));
      const plan = planWorkspaceLayout({ mode: 'grid', documents, availableWidth: width, availableHeight: height });
      expect([plan.columns, plan.rows, plan.visibility]).toEqual([columns, rows, visibility]);
    });

  it('preserves explicitly resized shares only for the same feasible grid topology', () => {
    const input = {
      mode: 'grid', documents: docs(2), availableWidth: 1201, availableHeight: 400,
      gridTopology: '2x1', preserveGridColumnShares: true,
    };
    expect(planWorkspaceLayout({ ...input, columnShares: [0.7, 0.3] }).columnShares)
      .toEqual([0.7, 0.30000000000000004]);
    expect(planWorkspaceLayout({ ...input, columnShares: [0.8, 0.2] }).visibility)
      .toBe('active-only');
    expect(planWorkspaceLayout({ ...input, gridTopology: '1x2', columnShares: [0.7, 0.3] }).columnShares)
      .toEqual([0.5, 0.5]);
  });

  it('breaks an aspect-score tie toward the larger column count', () => {
    const height = 1001;
    const requiredRatioTwice = 2 * (360 / 228);
    const width = (requiredRatioTwice + 1 / (2 * height))
      / (1 / (2 * height) + 2 / (height - 1));
    const plan = planWorkspaceLayout({ mode: 'grid', documents: docs(2), availableWidth: width, availableHeight: height });
    expect([plan.columns, plan.rows]).toEqual([2, 1]);
  });
});

// "single" is a DELIBERATE user choice, always showing exactly the active document, and it
// must not be confused with `active-only`/`insufficient-space` — the automatic fallback the other
// three modes already fall into when the viewport is too narrow for their floor (documented in
// `aff9614`, and unchanged by this feature: see the `reason` distinction asserted below and the
// unmodified `it.each` cases above it in this file).
describe('single pane mode', () => {
  it('always shows exactly the active document, regardless of viewport size or document count', () => {
    const plan = planWorkspaceLayout({
      mode: 'single', documents: docs(5), availableWidth: 4000, availableHeight: 2000,
    });
    expect(plan).toMatchObject({
      mode: 'single', visibility: 'active-only', reason: 'single-mode', columns: 1, rows: 1,
    });
    expect(plan.cells).toEqual([{ index: 0, column: 0, row: 0 }]);
  });

  it('follows the active document, not always the first one', () => {
    const documents = docs(3).map((document_, index) => ({ ...document_, active: index === 2 }));
    const plan = planWorkspaceLayout({
      mode: 'single', documents, availableWidth: 4000, availableHeight: 2000,
    });
    expect(plan.cells).toEqual([{ index: 2, column: 0, row: 0 }]);
  });

  it('is distinguishable from the automatic narrow-viewport fallback by reason, not just shape', () => {
    const wide = planWorkspaceLayout({ mode: 'single', documents: docs(2), availableWidth: 4000, availableHeight: 2000 });
    const narrow = planWorkspaceLayout({ mode: 'horizontal', documents: docs(2), availableWidth: 694, availableHeight: 515 });
    expect([wide.visibility, narrow.visibility]).toEqual(['active-only', 'active-only']);
    expect(wide.reason).toBe('single-mode');
    expect(narrow.reason).toBe('insufficient-space');
  });

  it('is accepted by persistence and validation alongside the existing three modes', () => {
    expect(serializeWorkspaceLayout({ mode: 'single', columnShares: [1] })).toBe('{"version":1,"mode":"single"}');
    expect(validateWorkspaceLayout({ version: 1, mode: 'single' }).mode).toBe('single');
  });
});

describe('workspace shares', () => {
  it('normalizes valid shares and repairs malformed inputs', () => {
    expect(normalizeShares([2, 1], 2)[0]).toBeCloseTo(2 / 3, 12);
    expect(normalizeShares([2, 1], 2)[1]).toBeCloseTo(1 / 3, 12);
    expect(normalizeShares([1, Number.NaN], 2)).toEqual([0.5, 0.5]);
    expect(normalizeShares([1], 2)).toEqual([0.5, 0.5]);
  });

  it('moves a cumulative boundary and clamps whole-axis floors', () => {
    const shares = resizeAxisShares({ shares: [1, 1, 1], sizes: [400, 400, 400], boundary: 1,
      deltaPx: 300, minimums: [360, 360, 360] });
    expect(shares[0]).toBeCloseTo(1 / 3, 12);
    expect(shares[1]).toBeCloseTo(440 / 1200, 12);
    expect(shares[2]).toBeCloseTo(0.3, 12);
  });

  it('repairs skewed shares against every current track floor', () => {
    const shares = clampSharesToMinimums([0.8, 0.1, 0.1], [360, 360, 360], 1200);
    expect(shares[0]).toBeCloseTo(0.4, 12);
    expect(shares[1]).toBeCloseTo(0.3, 12);
    expect(shares[2]).toBeCloseTo(0.3, 12);
  });
});

describe('workspace persistence', () => {
  it('persists only the versioned mode and rejects malformed modes', () => {
    expect(serializeWorkspaceLayout({ mode: 'grid', columnShares: [0.2, 0.8] }))
      .toBe('{"version":1,"mode":"grid"}');
    expect(validateWorkspaceLayout(JSON.parse('{"version":1,"mode":"wat"}')).mode).toBe('horizontal');
    expect(validateWorkspaceLayout(JSON.parse('{"version":2,"mode":"grid"}')).mode).toBe('horizontal');
  });
});

describe('readable graph zoom', () => {
  it('uses the smallest visible node or edge label font', () => {
    expect(readableZoomForFonts(20, 15)).toBeCloseTo(11 / 15, 12);
    expect(readableZoomForFonts(8, 12)).toBeCloseTo(11 / 8, 12);
  });
});

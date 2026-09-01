import { describe, expect, it } from 'vitest';

import {
  PANE_COMFORT_WIDTH,
  PANE_MIN_WIDTH,
  STAGE_MIN_HEIGHT,
  paneCapacity,
  planPaneLayout,
  resizePaneGrow,
  resizeSplit,
  separatorPosition,
  separatorRange,
} from '../src/panes.js';

// ── THE PANE-COUNT/WIDTH RULE (UI-03) ───────────────────────────────────────────────────────
//
// These numbers are MEASURED, not chosen. They are pinned here so that the measurement outlives the
// person who took it, and so that changing them is a deliberate act with a failing test attached
// rather than a quiet edit to a constant.
//
// The constraint on this editor is HORIZONTAL, not vertical. 574px of fixed chrome (508 at ≤1180px)
// leaves roughly 353px per pane in a two-way split at 1280px — below the 360px floor, which is why
// tiling is unavailable there with the inspector open. A pane header spends HEIGHT, which the
// product has; anything that spends WIDTH is taken from the graph exactly where the graph is
// already narrowest.
//
// The module is deliberately free of DOM: the rule is arithmetic, so it is tested as arithmetic.

describe('the measured constants', () => {
  it('keeps the floor and the comfort width the measurement produced', () => {
    expect(PANE_MIN_WIDTH).toBe(360);
    expect(PANE_COMFORT_WIDTH).toBe(480);
  });
});

describe('how many panes the available width can carry', () => {
  it('divides the available width by the floor', () => {
    expect(paneCapacity(1216)).toBe(3);
    expect(paneCapacity(720)).toBe(2);
    expect(paneCapacity(719)).toBe(1);
  });

  it('never reports less than one, because the active document is always shown', () => {
    expect(paneCapacity(0)).toBe(1);
    expect(paneCapacity(-40)).toBe(1);
    expect(paneCapacity(Number.NaN)).toBe(1);
  });
});

describe('the plan for a given viewport', () => {
  it('refuses to tile two documents at 1280px with the inspector open', () => {
    // 1280 viewport − 574px of fixed chrome. The measured case, stated in the measured terms.
    const plan = planPaneLayout({ availableWidth: 706, documentCount: 2 });

    expect(plan.mode).toBe('single');
    expect(plan.reason).toBe('too-narrow');
    expect(plan.visible).toBe(1);
  });

  it('tiles two documents once each of them clears the floor', () => {
    const plan = planPaneLayout({ availableWidth: 720, documentCount: 2 });

    expect(plan.mode).toBe('tiled');
    expect(plan.visible).toBe(2);
    // 360 each: above the floor, below comfort. Permitted, and reported as tight.
    expect(plan.comfortable).toBe(false);
  });

  it('reports comfort separately from permission', () => {
    expect(planPaneLayout({ availableWidth: 960, documentCount: 2 }).comfortable).toBe(true);
    expect(planPaneLayout({ availableWidth: 959, documentCount: 2 }).comfortable).toBe(false);
  });

  it('shows a single document as a single pane whatever the width', () => {
    const plan = planPaneLayout({ availableWidth: 2400, documentCount: 1 });

    expect(plan.mode).toBe('single');
    expect(plan.reason).toBe('single-document');
    expect(plan.visible).toBe(1);
  });

  it('has no pane at all for an empty workspace', () => {
    const plan = planPaneLayout({ availableWidth: 1600, documentCount: 0 });

    expect(plan.mode).toBe('single');
    expect(plan.visible).toBe(0);
    expect(plan.reason).toBe('empty');
  });

  // Hiding `#cy`, the shared host of every pane, collapses the row while the elastic renderer runs.
  // The overlay is confined to the active pane, so the plan does not bend to the
  // renderer: the layout mode is not the geometry's business. The stopgap's input is still passed
  // here deliberately, to assert it is IGNORED rather than merely absent.
  it('tiles regardless of the elastic renderer, which is confined to its own pane', () => {
    const plan = planPaneLayout({ availableWidth: 2400, documentCount: 3, activeLayoutMode: 'elastic' });

    expect(plan.mode).toBe('tiled');
    expect(plan.reason).toBe('tiled');
    expect(plan.visible).toBe(3);
  });

  it('tiles three documents when three clear the floor', () => {
    expect(planPaneLayout({ availableWidth: 1080, documentCount: 3 }).mode).toBe('tiled');
    expect(planPaneLayout({ availableWidth: 1079, documentCount: 3 }).mode).toBe('single');
  });
});

// ── Resize ───────────────────────────────────────────────────────────────────────────────────────
//
// Panes are laid out with flex grow factors rather than pixel widths, so that resizing the window
// preserves the proportions the user chose without any pixel bookkeeping. Dragging a separator is
// therefore a conversion: pixels the user asked for, back into grow factors.

describe('moving a separator', () => {
  it('moves the boundary by the requested pixels and keeps the pair total', () => {
    const next = resizePaneGrow({ growA: 1, growB: 1, widthA: 600, widthB: 600, deltaPx: 120 });

    expect(next.growA + next.growB).toBeCloseTo(2, 10);
    expect(next.growA / next.growB).toBeCloseTo(720 / 480, 10);
  });

  it('refuses to push either pane below the floor', () => {
    // 600/600 with a 400px pull would leave 200px, which is under the 360px floor.
    const next = resizePaneGrow({ growA: 1, growB: 1, widthA: 600, widthB: 600, deltaPx: -400 });

    expect(next.growA / (next.growA + next.growB)).toBeCloseTo(360 / 1200, 10);
  });

  it('is a no-op when the pair cannot honour the floor at all', () => {
    const next = resizePaneGrow({ growA: 1, growB: 1, widthA: 300, widthB: 300, deltaPx: 60 });

    expect(next).toEqual({ growA: 1, growB: 1 });
  });

  it('preserves an uneven split it is not asked to change', () => {
    const next = resizePaneGrow({ growA: 2, growB: 1, widthA: 800, widthB: 400, deltaPx: 0 });

    expect(next.growA).toBeCloseTo(2, 10);
    expect(next.growB).toBeCloseTo(1, 10);
  });
});

describe('the shared splitter geometry', () => {
  it('moves either axis using the same pair arithmetic', () => {
    expect(resizeSplit({ sizeA: 500, sizeB: 300, deltaPx: 40, minA: 360, minB: 120 }))
      .toEqual({ sizeA: 540, sizeB: 260, shareA: 0.675 });
  });

  it('pins the horizontal stage to the measured 360px floor', () => {
    const next = resizeSplit({ sizeA: 500, sizeB: 300, deltaPx: -400, minA: PANE_MIN_WIDTH, minB: 120 });
    expect(next.sizeA).toBe(360);
    // MUTATION CONTROL: a 359px floor would permit the forbidden measurement.
    expect(resizeSplit({ sizeA: 500, sizeB: 300, deltaPx: -400, minA: 359, minB: 120 }).sizeA)
      .toBe(359);
  });

  it('pins the vertical stage to the exact measured 204px floor', () => {
    const next = resizeSplit({ sizeA: 500, sizeB: 300, deltaPx: -400, minA: STAGE_MIN_HEIGHT, minB: 72 });
    expect(next.sizeA).toBe(204);
    // MUTATION CONTROL: this demonstrates the assertion observes the actual clamp, not a constant
    // that happens to equal itself.
    expect(resizeSplit({ sizeA: 500, sizeB: 300, deltaPx: -400, minA: 203, minB: 72 }).sizeA)
      .toBe(203);
  });

  it('refuses a pair that cannot honour both floors', () => {
    expect(resizeSplit({ sizeA: 200, sizeB: 60, deltaPx: 20, minA: 204, minB: 72 }))
      .toEqual({ sizeA: 200, sizeB: 60, shareA: 200 / 260 });
  });
});

describe('what the separator reports to assistive technology', () => {
  it('states the first pane share as a percentage', () => {
    expect(separatorPosition({ widthA: 600, widthB: 600 })).toBe(50);
    expect(separatorPosition({ widthA: 900, widthB: 300 })).toBe(75);
  });

  it('answers 50 rather than dividing by zero before the first layout', () => {
    expect(separatorPosition({ widthA: 0, widthB: 0 })).toBe(50);
  });

  it('reports the reachable percentage range from the real pair floors', () => {
    expect(separatorRange({ sizeA: 500, sizeB: 300, minA: 360, minB: 72 }))
      .toEqual({ min: 45, max: 91 });
    expect(separatorRange({ sizeA: 0, sizeB: 0, minA: 72, minB: 72 }))
      .toEqual({ min: 0, max: 100 });
  });
});

// ── The stage floor exists as a literal in TWO files, and only one of them is authoritative ──────
//
// `panes.js` owns STAGE_MIN_HEIGHT and app.js publishes it over the CSS custom property at boot, so
// the RUNTIME value is genuinely single-sourced. But `styles.css` still declares a default, because
// removing it would leave `calc(100% - var(--stage-min-h))` invalid for the frames before app.js
// runs. That default is therefore a value nothing reads once the app is alive — which is exactly
// the shape of a number that goes stale silently, and the dock is about to become resizable against
// it.
//
// This is the pin. It is cheap, it fails loudly, and it is the reason the duplication is safe.
describe('the stage floor declared in CSS', () => {
  const declaredFloor = (css) => {
    const match = css.match(/--stage-min-h:\s*(\d+)px/);
    return match ? Number(match[1]) : null;
  };

  it('matches the constant app.js publishes over it', async () => {
    const { readFile } = await import('node:fs/promises');
    const css = await readFile('src/styles.css', 'utf8');
    expect(declaredFloor(css)).toBe(STAGE_MIN_HEIGHT);
    expect(Number(css.match(/--stage-min-w:\s*(\d+)px/)?.[1])).toBe(PANE_MIN_WIDTH);
  });

  it('would notice if the two drifted apart', () => {
    // THE CONTROL. The assertion above is an equality between two numbers that are equal today; on
    // its own it cannot show it is capable of failing. Run the same extraction over a stylesheet
    // that has drifted and require it to disagree.
    const drifted = ':root { --stage-min-h: 320px; }';
    expect(declaredFloor(drifted)).not.toBe(STAGE_MIN_HEIGHT);
    // And over one with the declaration removed entirely, which is the other way it can rot.
    expect(declaredFloor(':root { --panel-hd-h: 32px; }')).toBeNull();
  });
});

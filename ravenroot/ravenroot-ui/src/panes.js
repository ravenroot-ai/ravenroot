// Pane geometry: how many documents may be shown at once, and where the boundary between two of
// them sits (UI-03).
//
// Like `workspace.js`, this module is deliberately free of DOM and Cytoscape. Everything here is
// arithmetic, so the rule can be tested without a browser and read without tracing a render.
//
// ── Why the numbers are what they are ────────────────────────────────────────────────────────────
//
// THE CONSTRAINT ON THIS EDITOR IS HORIZONTAL, NOT VERTICAL. That is a measurement, not a
// preference, and it decides more than it looks like it decides:
//
// * 574px of the viewport is fixed chrome — the palette and the inspector — falling to 508px at
// ≤1180px where the palette collapses.
// * At a 1280px viewport with the inspector open that leaves ~706px of canvas, which is ~353px per
// pane in a two-way split: BELOW the 360px floor. So tiling is unavailable there, and that is a
// consequence of the arithmetic rather than a special case written into it.
//
// The same measurement is why each pane's identity is carried by a 24px HEADER STRIP rather than by
// a chip floating over the canvas. A strip spends HEIGHT, which this product has. An overlay chip
// spends CANVAS, and it spends it exactly where the graph is already narrowest — the two-way split
// at the bottom of the supported range, which is the case that is already hardest to read.
//
// ANYONE LATER TEMPTED TO "SAVE" THE STRIP BY FLOATING A CHIP OVER THE CANVAS MUST MEET THAT
// ARGUMENT RATHER THAN RE-DERIVE IT. The strip is not decoration and it is not window chrome: it has
// no buttons, no drag, no stacking order and no geometry to restore. It is the smallest thing that
// answers "which document am I looking at" in text.

// A pane narrower than this cannot show a graph usefully, so the workspace refuses to tile rather
// than tile badly.
export const PANE_MIN_WIDTH = 360;
// A pane at least this wide is comfortable. Between the floor and comfort a split is permitted and
// reported as tight, which is what lets the header degrade its own rendering instead of overflowing.
export const PANE_COMFORT_WIDTH = 480;
// The strip. Height, deliberately — see above.
export const PANE_HEADER_HEIGHT = 24;

// ── The stage's height floor ──────────────────────────────────────────────────────────────
//
// New, and it needs a reason the existing set could not serve: the constants above are all widths,
// because until the bottom dock existed NOTHING COULD TAKE HEIGHT FROM THE STAGE. The dock can, so
// the stage needs a floor on the other axis too.
//
// MEASURED, NOT ASSERTED, and the distinction matters — the design study proposed 320px derived
// from "four rows of n8n-style node cards", which is a fact about n8n rather than about this
// product. Measured against THIS stage, at 1440x900, shrinking it 2px at a time and reading live
// getBoundingClientRect rather than computing from the stylesheet:
//
// * The stage CARRIES ITS OWN CONTROLS. `#minimap` (100px tall) and `#zoom-ctrl` (3 x 32px
// `.zbtn` + 2 x 4px gaps = 104px) are `position:absolute; bottom:16px` INSIDE `#cy-wrap`, so
// they ride the stage down as the dock pushes. Measured, the taller cluster plus its inset
// occupies the bottom 120px: the band above the controls came out at exactly `stage - 120` at
// every one of the 25 heights sampled.
// * Below a 120px stage the controls stop being fully inside the stage at all — measured, they
// escape the top edge at 116px.
// * A stage that is nothing but its own controls is not a stage. The band above them has to hold
// at least ONE FULL-SIZE NODE, and 84px is the product's own number: `nodeSize()` in app.js
// returns [84, 84] for start and end nodes, the largest thing the renderer draws.
//
// So the floor is 104 + 16 + 84 = 204, and the measurement agrees exactly: at a 204px stage the
// band above the controls is 84px and a full node fits; at 202px it is 82px and one does not.
//
// It is deliberately NOT rounded to a friendlier number. 204 is legible as its own derivation, and
// a rounded constant whose arithmetic no longer adds up is how a measured floor decays back into an
// asserted one.
export const STAGE_MIN_HEIGHT = 204;

// A splitter never stores the pixels it measured. It uses them only to derive the next relative
// share, which is the dimension that survives a different viewport. This is the common grammar for
// graph panes, side columns and the bottom dock.
export const SPLITTER_KEY_STEP = 32;

export function resizeSplit({ sizeA, sizeB, deltaPx, minA = 0, minB = 0 }) {
  const total = sizeA + sizeB;
  if (!Number.isFinite(total) || !(total > 0)) return { sizeA, sizeB, shareA: 0.5 };
  if (total < minA + minB) return { sizeA, sizeB, shareA: sizeA / total };

  const nextA = Math.min(total - minB, Math.max(minA, sizeA + deltaPx));
  return { sizeA: nextA, sizeB: total - nextA, shareA: nextA / total };
}

// How many panes the available width can carry at the floor.
export function paneCapacity(availableWidth) {
  if (!Number.isFinite(availableWidth) || availableWidth <= 0) return 1;
  return Math.max(1, Math.floor(availableWidth / PANE_MIN_WIDTH));
}

// The whole decision, in one place and with its reason attached. The reason is returned because
// "why is my second document not on screen" is a question the UI has to be able to answer.
// `activeLayoutMode` is accepted and IGNORED, deliberately — see the note on the elastic renderer
// below for why the parameter once mattered and no longer does.
export function planPaneLayout({ availableWidth, documentCount } = {}) {
  const capacity = paneCapacity(availableWidth);
  const count = Math.max(0, Math.trunc(documentCount) || 0);

  if (count === 0) {
    return { mode: 'single', visible: 0, reason: 'empty', comfortable: false, capacity };
  }

  // ── THE ELASTIC RENDERER IS NOT THE GEOMETRY'S BUSINESS ────────────────────
  //
  // The geometry plan must not force single-pane mode for the elastic renderer. Doing so would make
  // neighboring documents impossible to click or focus while elastic runs.
//
  // `#d3-elastic` is confined to the active pane (app.js, `startD3Elastic`) and hides that pane's own
  // `.doc-canvas`, never the shared host. Renderer assertions therefore inspect the pane-local canvas
  // rather than treating a hidden shared `#cy` as the definition of elastic rendering.
  //
  // The branch is gone rather than inverted: a renderer that lives inside one pane gives the plan
  // no reason to know which layout the active document runs.

  if (count === 1) {
    return {
      mode: 'single',
      visible: 1,
      reason: 'single-document',
      comfortable: availableWidth >= PANE_COMFORT_WIDTH,
      capacity,
    };
  }

  // More documents than the width can carry at the floor. Showing the active document alone is
  // the deliberate narrow state, rather than four unreadable
  // slivers.
  if (count > capacity) {
    return { mode: 'single', visible: 1, reason: 'too-narrow', comfortable: false, capacity };
  }

  return {
    mode: 'tiled',
    visible: count,
    reason: 'tiled',
    comfortable: availableWidth / count >= PANE_COMFORT_WIDTH,
    capacity,
  };
}

// ── Resize ───────────────────────────────────────────────────────────────────────────────────────
//
// Panes are laid out with flex GROW FACTORS rather than pixel widths, so that resizing the window
// keeps the proportions the user chose without any pixel bookkeeping to go stale. Dragging or
// keying a separator is therefore a conversion: the pixels the user asked for, expressed back as
// grow factors over the pair. Only the two panes either side of the separator are touched, so the
// rest of the row does not move.
export function resizePaneGrow({ growA, growB, widthA, widthB, deltaPx, minWidth = PANE_MIN_WIDTH }) {
  const total = widthA + widthB;
  const growTotal = growA + growB;
  // The pair cannot honour the floor at all — the row is mid-transition, or the window is smaller
  // than two panes. Refuse rather than produce a geometry that has to be undone.
  if (!(total > 0) && growTotal > 0) return { growA, growB };
  if (total < minWidth * 2) return { growA, growB };
  if (!(growTotal > 0)) return { growA, growB };

  const { shareA } = resizeSplit({
    sizeA: widthA,
    sizeB: widthB,
    deltaPx,
    minA: minWidth,
    minB: minWidth,
  });
  return { growA: shareA * growTotal, growB: (1 - shareA) * growTotal };
}

// What the separator tells assistive technology: the first pane's share of the pair, as a percent.
// `aria-valuenow` on a window splitter is a position, and a position needs a scale.
export function separatorPosition({ widthA, widthB }) {
  const total = widthA + widthB;
  if (!(total > 0)) return 50;
  return Math.round((widthA / total) * 100);
}

export function separatorRange({ sizeA, sizeB, minA = 0, minB = 0 }) {
  const total = sizeA + sizeB;
  if (!Number.isFinite(total) || !(total > 0)) return { min: 0, max: 100 };
  return {
    min: Math.round((Math.min(total, Math.max(0, minA)) / total) * 100),
    max: Math.round((Math.max(0, total - Math.max(0, minB)) / total) * 100),
  };
}

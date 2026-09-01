import { PANE_HEADER_HEIGHT, PANE_MIN_WIDTH, STAGE_MIN_HEIGHT } from './panes.js';

export const WORKSPACE_LAYOUT_STORAGE_KEY = 'ravenroot.workspace-layout.v1';
// "single" sits ALONGSIDE horizontal/vertical/grid, not beneath them. It is the user's own
// choice to see one pane no matter how many documents are open, which is a different fact from the
// `active-only`/`insufficient-space` state the other three already fall into when the viewport is
// too narrow for their floor (see `planWorkspaceLayout` — that fallback is untouched by this mode).
export const WORKSPACE_LAYOUT_MODES = Object.freeze(['horizontal', 'vertical', 'grid', 'single']);
export const WORKSPACE_GRID_GAP = 1;
export const LABEL_SCREEN_MINIMUM = 11;
export const DEFAULT_WORKSPACE_LAYOUT = Object.freeze({
  mode: 'horizontal',
  columnShares: Object.freeze([1]),
  rowShares: Object.freeze([1]),
});

export function normalizeShares(shares, count) {
  const length = Math.max(1, Math.trunc(count) || 1);
  if (!Array.isArray(shares) || shares.length !== length
      || shares.some(value => !Number.isFinite(value) || value <= 0)) {
    return Array.from({ length }, () => 1 / length);
  }
  const total = shares.reduce((sum, value) => sum + value, 0);
  const normalized = shares.map(value => value / total);
  normalized[length - 1] = 1 - normalized.slice(0, -1).reduce((sum, value) => sum + value, 0);
  return normalized;
}

export function defaultWorkspaceLayout() {
  return { mode: 'horizontal', columnShares: [1], rowShares: [1] };
}

export function validateWorkspaceLayout(value) {
  const supportedVersion = value && (!Object.hasOwn(value, 'version') || value.version === 1);
  const mode = supportedVersion && WORKSPACE_LAYOUT_MODES.includes(value?.mode) ? value.mode : 'horizontal';
  return { mode, columnShares: [1], rowShares: [1] };
}

export function serializeWorkspaceLayout(layout) {
  return JSON.stringify({ version: 1, mode: validateWorkspaceLayout(layout).mode });
}

function requirementsFor(document_, reservation = {}) {
  return {
    inline: Math.max(PANE_MIN_WIDTH, (Number(document_?.minInline) || 0) + (reservation.inline || 0)),
    block: Math.max(PANE_HEADER_HEIGHT + STAGE_MIN_HEIGHT,
      (Number(document_?.minBlock) || 0) + (reservation.block || 0)),
  };
}

function cellsFor(count, columns) {
  return Array.from({ length: count }, (_, index) => ({
    index,
    column: index % columns,
    row: Math.floor(index / columns),
  }));
}

function overlayReservation(cell, columns, rows, availableWidth, availableHeight, gap, overlays) {
  const cellWidth = (availableWidth - gap * Math.max(0, columns - 1)) / columns;
  const cellHeight = (availableHeight - gap * Math.max(0, rows - 1)) / rows;
  const left = cell.column * (cellWidth + gap);
  const top = cell.row * (cellHeight + gap);
  const right = left + cellWidth;
  const bottom = top + cellHeight;
  const intersections = overlays.map(overlay => {
    const overlapWidth = Math.max(0, Math.min(right, overlay.right) - Math.max(left, overlay.left));
    const overlapHeight = Math.max(0, Math.min(bottom, overlay.bottom) - Math.max(top, overlay.top));
    if (!overlapWidth || !overlapHeight) return null;
    return {
      inline: [Math.max(left, overlay.left), Math.min(right, overlay.right)],
      block: [Math.max(top, overlay.top), Math.min(bottom, overlay.bottom)],
    };
  }).filter(Boolean);
  const unionLength = intervals => intervals.sort((a, b) => a[0] - b[0]).reduce((result, interval) => {
    const last = result[result.length - 1];
    if (!last || interval[0] > last[1]) result.push([...interval]);
    else last[1] = Math.max(last[1], interval[1]);
    return result;
  }, []).reduce((sum, interval) => sum + interval[1] - interval[0], 0);
  return {
    inline: unionLength(intersections.map(item => item.inline)),
    block: unionLength(intersections.map(item => item.block)),
  };
}

function trackMinimums(documents, cells, columns, rows, geometry) {
  const columnMins = Array(columns).fill(0);
  const rowMins = Array(rows).fill(0);
  cells.forEach(cell => {
    const reservation = overlayReservation(cell, columns, rows, geometry.availableWidth,
      geometry.availableHeight, geometry.gap, geometry.overlays);
    const requirement = requirementsFor(documents[cell.index], reservation);
    columnMins[cell.column] = Math.max(columnMins[cell.column], requirement.inline);
    rowMins[cell.row] = Math.max(rowMins[cell.row], requirement.block);
  });
  return { columnMins, rowMins };
}

function candidate({ documents, columns, rows, availableWidth, availableHeight, gap, overlays = [] }) {
  const cells = cellsFor(documents.length, columns);
  const { columnMins, rowMins } = trackMinimums(documents, cells, columns, rows,
    { availableWidth, availableHeight, gap, overlays });
  const width = Math.max(0, availableWidth - gap * Math.max(0, columns - 1));
  const height = Math.max(0, availableHeight - gap * Math.max(0, rows - 1));
  const feasible = columnMins.reduce((a, b) => a + b, 0) <= width
    && rowMins.reduce((a, b) => a + b, 0) <= height;
  return { columns, rows, cells, columnMins, rowMins, feasible, width, height };
}

function gridCandidate(input) {
  const { documents, availableWidth, availableHeight, gap } = input;
  return Array.from({ length: documents.length }, (_, index) => {
    const columns = index + 1;
    const rows = Math.ceil(documents.length / columns);
    const item = candidate({ documents, columns, rows, availableWidth, availableHeight, gap, overlays: input.overlays });
    const cellWidth = item.width / columns;
    const cellHeight = item.height / rows;
    const maxInline = Math.max(...item.columnMins);
    const maxBlock = Math.max(...item.rowMins);
    // Grid tracks are globally equal when a topology is selected. A wide document cannot make its
    // own column wider and call an otherwise infeasible candidate valid: that would turn the
    // deterministic grid search into an irregular nested layout and create unreadable siblings.
    item.feasible = cellWidth >= maxInline && cellHeight >= maxBlock;
    item.columnMins = Array(columns).fill(maxInline);
    item.rowMins = Array(rows).fill(maxBlock);
    item.score = [
      Math.abs(cellWidth / Math.max(1, cellHeight) - maxInline / maxBlock),
      columns * rows - documents.length,
      Math.abs(columns - rows),
      -columns,
    ];
    return item;
  }).filter(item => item.feasible).sort((a, b) => {
    for (let i = 0; i < a.score.length; i += 1) {
      if (Math.abs(a.score[i] - b.score[i]) > 1e-9) return a.score[i] - b.score[i];
    }
    return 0;
  })[0];
}

export function planWorkspaceLayout({
  mode = 'horizontal', documents = [], availableWidth = 0, availableHeight = 0,
  columnShares, rowShares, overlays = [], gap = WORKSPACE_GRID_GAP,
  gridTopology = null, preserveGridColumnShares = false, preserveGridRowShares = false,
} = {}) {
  const count = documents.length;
  const activeIndex = Math.max(0, documents.findIndex(item => item.active));
  if (!count) return {
    mode, visibility: 'empty', reason: 'empty', columns: 0, rows: 0, cells: [],
    columnShares: [1], rowShares: [1], columnMins: [], rowMins: [],
  };

  // Deliberately short-circuits before the horizontal/vertical/grid feasibility search below: this
  // is a chosen presentation, not something that can ever fail to fit, so it never touches
  // `candidate`/`gridCandidate` and can never produce their `insufficient-space` reason. Shares the
  // `active-only` SHAPE those fallbacks use (`syncPaneLayout` in app.js already knows how to show
  // exactly one pane for it) but carries its own `reason` so the two are never mistaken for another
  // in a stored plan or a test.
  if (mode === 'single') return {
    mode, visibility: 'active-only', reason: 'single-mode', columns: 1, rows: 1,
    cells: [{ index: activeIndex, column: 0, row: 0 }],
    columnShares: [1], rowShares: [1],
    columnMins: [requirementsFor(documents[activeIndex]).inline],
    rowMins: [requirementsFor(documents[activeIndex]).block],
  };

  let result;
  if (mode === 'vertical') {
    result = candidate({ documents, columns: 1, rows: count, availableWidth, availableHeight, gap, overlays });
  } else if (mode === 'grid') {
    result = gridCandidate({ documents, availableWidth, availableHeight, gap, overlays });
  } else {
    result = candidate({ documents, columns: count, rows: 1, availableWidth, availableHeight, gap, overlays });
  }

  if (!result) return {
    mode, visibility: 'active-only', reason: 'insufficient-space', columns: 1, rows: 1,
    cells: [{ index: activeIndex, column: 0, row: 0 }],
    columnShares: [1], rowShares: [1], columnMins: [requirementsFor(documents[0]).inline],
    rowMins: [requirementsFor(documents[0]).block],
  };
  if (!result.feasible) return {
    mode, visibility: 'active-only', reason: 'insufficient-space', columns: 1, rows: 1,
    cells: [{ index: activeIndex, column: 0, row: 0 }],
    columnShares: [1], rowShares: [1], columnMins: result.columnMins, rowMins: result.rowMins,
  };
  let plannedColumnShares = normalizeShares(columnShares, result.columns);
  let plannedRowShares = normalizeShares(rowShares, result.rows);
  if (mode === 'grid') {
    const topology = `${result.columns}x${result.rows}`;
    const topologyMatches = topology === gridTopology;
    plannedColumnShares = topologyMatches && preserveGridColumnShares
      ? normalizeShares(columnShares, result.columns) : equalAxisShares(result.columns);
    plannedRowShares = topologyMatches && preserveGridRowShares
      ? normalizeShares(rowShares, result.rows) : equalAxisShares(result.rows);
    const columnsValid = plannedColumnShares.every((share, index) =>
      share * result.width + 1e-9 >= result.columnMins[index]);
    const rowsValid = plannedRowShares.every((share, index) =>
      share * result.height + 1e-9 >= result.rowMins[index]);
    if (!columnsValid || !rowsValid) return {
      mode, visibility: 'active-only', reason: 'resized-track-below-minimum', columns: 1, rows: 1,
      cells: [{ index: activeIndex, column: 0, row: 0 }], columnShares: [1], rowShares: [1],
      columnMins: result.columnMins, rowMins: result.rowMins,
    };
  }
  return {
    ...result,
    mode,
    visibility: 'all',
    reason: count === 1 ? 'single-document' : 'fits',
    columnShares: mode === 'grid' ? plannedColumnShares
      : clampSharesToMinimums(columnShares, result.columnMins, result.width),
    rowShares: mode === 'grid' ? plannedRowShares
      : clampSharesToMinimums(rowShares, result.rowMins, result.height),
  };
}

export function clampSharesToMinimums(shares, minimums, available) {
  const normalized = normalizeShares(shares, minimums.length);
  if (!(available > 0)) return normalized;
  const floors = minimums.map(value => Math.max(0, value) / available);
  if (floors.reduce((sum, value) => sum + value, 0) > 1) return normalized;
  const next = normalized.map((value, index) => Math.max(value, floors[index]));
  const excess = next.reduce((sum, value) => sum + value, 0) - 1;
  if (excess <= 1e-12) return normalizeShares(next, next.length);
  const donorTotal = next.reduce((sum, value, index) => sum + Math.max(0, value - floors[index]), 0);
  return normalizeShares(next.map((value, index) =>
    value - excess * Math.max(0, value - floors[index]) / donorTotal), next.length);
}

export function resizeAxisShares({ shares, sizes, boundary, deltaPx, minimums }) {
  const normalized = normalizeShares(shares, sizes.length);
  if (boundary < 0 || boundary >= sizes.length - 1) return normalized;
  const next = [...sizes];
  const pairTotal = next[boundary] + next[boundary + 1];
  if (!(pairTotal > 0) || pairTotal < minimums[boundary] + minimums[boundary + 1]) return normalized;
  next[boundary] = Math.min(pairTotal - minimums[boundary + 1],
    Math.max(minimums[boundary], next[boundary] + deltaPx));
  next[boundary + 1] = pairTotal - next[boundary];
  return normalizeShares(next, next.length);
}

export function equalAxisShares(count) {
  return normalizeShares(null, count);
}

export function readableZoomForFonts(nodeFont, edgeFont, labelMinimum = LABEL_SCREEN_MINIMUM) {
  const smallestVisibleFont = Math.min(Number(nodeFont) || 1, Number(edgeFont) || 1);
  return labelMinimum / smallestVisibleFont;
}

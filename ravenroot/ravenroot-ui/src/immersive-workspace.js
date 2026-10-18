// Shell geometry is independent of graph documents, renderer instances and command history.
export const IMMERSIVE_LAYOUT_KEY = 'ravenroot.ui.immersive-layout';
const IDS = ['catalog', 'inspector', 'activity', 'tools'];
const EDGES = ['floating', 'top', 'right', 'bottom', 'left'];
const clone = value => JSON.parse(JSON.stringify(value));
const clamp = (value, min, max) => Math.min(Math.max(value, min), Math.max(min, max));

export function defaultImmersiveLayout() {
  return { v: 1, surfaces: {
    catalog: { dock: 'left', x: .02, y: .12, width: .18, height: .72, minimized: true },
    inspector: { dock: 'right', x: .74, y: .12, width: .215, height: .72, minimized: false },
    activity: { dock: 'bottom', x: .25, y: .72, width: .46, height: .25, minimized: false },
    tools: { dock: 'top', x: .35, y: .02, width: .3, height: .09, minimized: false },
  } };
}

export function recoverImmersiveLayout(raw, legacy) {
  const state = defaultImmersiveLayout();
  if (raw?.v === 1 && raw.surfaces && typeof raw.surfaces === 'object') {
    for (const id of IDS) {
      const value = raw.surfaces[id];
      if (!value || !EDGES.includes(value.dock)) continue;
      if (!['x', 'y', 'width', 'height'].every(key => Number.isFinite(value[key]))) continue;
      state.surfaces[id] = { dock: value.dock, x: clamp(value.x, 0, 1), y: clamp(value.y, 0, 1),
        width: clamp(value.width, .05, 1), height: clamp(value.height, .05, 1), minimized: value.minimized === true };
    }
  } else if (legacy?.panels) {
    const groups = { catalog: ['search', 'node-types', 'node-catalog', 'node-palettes', 'edge-types', 'graph-stats'],
      inspector: ['inspector', 'assistant'], activity: ['activity'] };
    for (const [id, panels] of Object.entries(groups)) {
      const entries = legacy.panels.filter(panel => panels.includes(panel.id));
      const first = entries.find(panel => !panel.closed) || entries[0];
      if (!first) continue;
      state.surfaces[id].dock = EDGES.includes(first.zone) ? first.zone : state.surfaces[id].dock;
      state.surfaces[id].minimized = entries.every(panel => panel.closed) || legacy.zones?.[first.zone]?.collapsed === true;
      const dimension = legacy.zones?.[first.zone]?.dimension;
      if (Number.isFinite(dimension)) state.surfaces[id][first.zone === 'bottom' ? 'height' : 'width'] = clamp(dimension, .1, .8);
    }
  }
  return state;
}

export function surfaceRect(surface, bounds, id = '') {
  const W = Math.max(1, bounds.width), H = Math.max(1, bounds.height), gap = Math.min(12, W / 12, H / 12);
  const tools = id === 'tools';
  let width = clamp(surface.width * W, tools ? 170 : Math.min(260, W - gap * 2), W - gap * 2);
  let height = clamp(surface.height * H, tools ? 48 : Math.min(130, H - gap * 2), H - gap * 2);
  let x = surface.x * W, y = surface.y * H;
  if (surface.dock === 'floating' && W < 900) y = Math.max(92, y);
  if (tools) {
    const vertical = ['left', 'right'].includes(surface.dock);
    width = vertical ? clamp(surface.width * W, Math.min(174, W - gap * 2), Math.min(240, W - gap * 2)) : clamp(surface.width * W, Math.min(300, W - gap * 2), W - gap * 2);
    height = vertical ? clamp(surface.height * H, Math.min(190, H - gap * 2), H - gap * 2) : clamp(surface.height * H, Math.min(82, H - gap * 2), H - gap * 2);
  }
  if (surface.dock === 'left') { x = gap; y = tools ? (H - height) / 2 : 104; }
  if (surface.dock === 'right') { x = W - width - gap; y = tools ? (H - height) / 2 : 104; }
  if (surface.dock === 'top') { x = (W - width) / 2; y = tools ? (W < 900 ? 92 : 12) : 104; }
  if (surface.dock === 'bottom') { x = (W - width) / 2; y = H - height - gap - 42; }
  if (W < 900 && !tools) {
    width = W - gap * 2; height = Math.min(height, H * .52);
    x = gap; y = H - height - 54;
  }
  return { x: clamp(x, gap, W - width - gap), y: clamp(y, gap, H - height - gap), width, height };
}

// The largest unobscured rectangle also handles floating surfaces in the middle of a pane.
export function usableGraphRect(bounds, obstacles = []) {
  const right = bounds.x + bounds.width, bottom = bounds.y + bounds.height;
  const blocks = obstacles.map(box => ({ x: Math.max(bounds.x, box.x), y: Math.max(bounds.y, box.y),
    right: Math.min(right, box.x + box.width), bottom: Math.min(bottom, box.y + box.height) }))
    .filter(box => box.right > box.x && box.bottom > box.y);
  const xs = [...new Set([bounds.x, right, ...blocks.flatMap(box => [box.x, box.right])])].sort((a, b) => a - b);
  const ys = [...new Set([bounds.y, bottom, ...blocks.flatMap(box => [box.y, box.bottom])])].sort((a, b) => a - b);
  let best = { ...bounds, width: 0, height: 0 };
  for (let l = 0; l < xs.length - 1; l++) for (let r = l + 1; r < xs.length; r++) {
    for (let t = 0; t < ys.length - 1; t++) for (let b = t + 1; b < ys.length; b++) {
      const width = xs[r] - xs[l], height = ys[b] - ys[t];
      if (width * height <= best.width * best.height) continue;
      if (blocks.some(box => xs[l] < box.right && xs[r] > box.x && ys[t] < box.bottom && ys[b] > box.y)) continue;
      best = { x: xs[l], y: ys[t], width, height };
    }
  }
  return best.width > 0 && best.height > 0 ? best : { ...bounds, width: 1, height: 1 };
}

export function createImmersiveWorkspace({ root, surfaces, legacyLayout, onChange = () => {}, onShow = () => {}, onReset = () => {}, text: t }) {
  const doc = root.ownerDocument;
  let stored;
  try { stored = JSON.parse(globalThis.localStorage?.getItem(IMMERSIVE_LAYOUT_KEY)); } catch { /* optional storage */ }
  let state = recoverImmersiveLayout(stored, legacyLayout), focused = false, transaction = null, narrowActive = null;
  const previousFocus = new Map();
  const messages = {
    tools: values => t('workspace.overlay.tools', values),
    catalog: values => t('workspace.overlay.catalog', values),
    inspector: values => t('workspace.overlay.inspector', values),
    activity: values => t('workspace.overlay.activity', values),
    move: values => t('workspace.overlay.move', { name: values.name }),
    minimize: values => t('workspace.overlay.minimize', { name: values.name }),
    restore: values => t('workspace.overlay.restore', { name: values.name }),
    resize: values => t('workspace.overlay.resize', { name: values.name }),
    dock: values => t('workspace.overlay.dock', { name: values.name }),
    floating: values => t('workspace.overlay.floating', values),
    top: values => t('workspace.overlay.top', values),
    right: values => t('workspace.overlay.right', values),
    bottom: values => t('workspace.overlay.bottom', values),
    left: values => t('workspace.overlay.left', values),
    panels: values => t('workspace.overlay.panels', values),
    focus: values => t('workspace.overlay.focus', values),
    exitFocus: values => t('workspace.overlay.exitFocus', values),
    reset: values => t('workspace.overlay.reset', values),
    instructions: values => t('workspace.overlay.instructions', values),
    moved: values => t('workspace.overlay.moved', { name: values.name, position: values.position }),
    cancelled: values => t('workspace.overlay.cancelled', values),
  };
  const key = (name, values) => messages[name](values);
  const make = (tag, className, parent) => { const el = doc.createElement(tag); el.className = className; parent?.append(el); return el; };
  const button = (label, parent, attr, value) => { const el = make('button', 'surface-button', parent); el.type = 'button'; el.textContent = label; if (attr) el.dataset[attr] = value; return el; };
  root.classList.add('immersive-workspace');
  const controls = make('nav', 'workspace-controls', root);
  controls.setAttribute('aria-label', key('panels'));
  const indexButton = button(key('panels'), controls, 'workspaceAction', 'index');
  indexButton.setAttribute('aria-haspopup', 'dialog'); indexButton.setAttribute('aria-expanded', 'false');
  const focusButton = button(key('focus'), controls, 'workspaceAction', 'focus');
  focusButton.setAttribute('aria-pressed', 'false');
  button(key('reset'), controls, 'workspaceAction', 'reset');
  const shelf = make('nav', 'workspace-shelf', root); shelf.setAttribute('aria-label', key('restore', { name: key('panels') }));
  const index = make('div', 'workspace-panel-index', root); index.hidden = true; index.setAttribute('role', 'dialog'); index.setAttribute('aria-label', key('panels'));
  const preview = make('div', 'workspace-dock-preview', root); preview.hidden = true; preview.setAttribute('aria-hidden', 'true');
  const live = make('div', 'visually-hidden', root); live.setAttribute('role', 'status'); live.setAttribute('aria-live', 'polite');
  const instructions = make('div', 'visually-hidden', root); instructions.id = 'workspace-move-instructions'; instructions.textContent = key('instructions');
  const announce = message => { live.textContent = message; };
  const persist = () => { try { globalThis.localStorage?.setItem(IMMERSIVE_LAYOUT_KEY, JSON.stringify(state)); } catch { /* layout remains usable */ } };
  const nameOf = id => key(id);
  const gripOf = id => surfaces[id].querySelector('[data-surface-move]');
  function closeIndex(restore = false) { index.hidden = true; indexButton.setAttribute('aria-expanded', 'false'); if (restore) indexButton.focus({ preventScroll: true }); }
  function placeGraphControls() {
    const canvas = doc.getElementById('cy-wrap'), zoom = doc.getElementById('zoom-ctrl'), map = doc.getElementById('minimap');
    if (!canvas || !zoom) return;
    const bounds = canvas.getBoundingClientRect();
    const obstacles = [...IDS.map(id => surfaces[id]), controls, shelf]
      .filter(el => !el.hidden && !el.inert && getComputedStyle(el).display !== 'none')
      .map(el => { const box = el.getBoundingClientRect(); return { x: box.left, y: box.top, width: box.width, height: box.height }; });
    const area = usableGraphRect({ x: bounds.left, y: bounds.top, width: bounds.width, height: bounds.height }, obstacles);
    // A horizontal strip remains usable in the shallow clear area above a narrow-screen drawer.
    zoom.style.flexDirection = area.height < 180 ? 'row' : '';
    const zoomBox = zoom.getBoundingClientRect();
    const zoomX = clamp(area.x + area.width - zoomBox.width - 8, bounds.left, bounds.right - zoomBox.width);
    const zoomY = clamp(area.y + area.height - zoomBox.height - 8, bounds.top, bounds.bottom - zoomBox.height);
    Object.assign(zoom.style, { left: `${zoomX - bounds.left}px`, top: `${zoomY - bounds.top}px`, right: 'auto', bottom: 'auto' });
    if (map) {
      const mapBox = map.getBoundingClientRect();
      const fits = area.width >= mapBox.width + zoomBox.width + 24 && area.height >= mapBox.height + 16;
      map.style.visibility = fits ? '' : 'hidden';
      Object.assign(map.style, { left: `${Math.max(0, area.x - bounds.left + 8)}px`, top: `${Math.max(0, area.y - bounds.top + area.height - mapBox.height - 8)}px`, right: 'auto', bottom: 'auto' });
    }
  }
  function refresh() {
    const bounds = root.getBoundingClientRect();
    root.classList.toggle('workspace-focus', focused);
    for (const id of IDS) {
      const el = surfaces[id], item = state.surfaces[id], rect = surfaceRect(item, bounds, id);
      el.dataset.dock = item.dock;
      el.style.left = `${rect.x}px`; el.style.top = `${rect.y}px`; el.style.width = `${rect.width}px`; el.style.height = `${rect.height}px`;
      const responsiveHidden = bounds.width < 900 && id !== 'tools' && narrowActive !== id;
      el.dataset.responsive = String(bounds.width < 900);
      el.classList.toggle('surface--minimized', item.minimized || responsiveHidden);
      el.classList.toggle('surface--focus-hidden', focused);
      el.inert = item.minimized || responsiveHidden || focused;
      el.querySelector('[data-surface-dock]').value = item.dock;
      const restore = shelf.querySelector(`[data-surface-restore="${id}"]`);
      restore.hidden = !item.minimized && !responsiveHidden;
    }
    indexButton.textContent = key('panels'); controls.setAttribute('aria-label', key('panels'));
    index.setAttribute('aria-label', key('panels')); instructions.textContent = key('instructions');
    controls.querySelector('[data-workspace-action=reset]').textContent = key('reset');
    for (const id of IDS) {
      const el = surfaces[id]; el.querySelector('.surface-title').textContent = nameOf(id);
      for (const action of ['move', 'minimize', 'resize', 'dock']) el.querySelector(`[data-surface-${action}]`).setAttribute('aria-label', key(action, { name: nameOf(id) }));
      el.querySelectorAll('option').forEach(option => { option.textContent = key(option.value); });
      shelf.querySelector(`[data-surface-restore="${id}"] .surface-restore-label`).textContent = nameOf(id);
      shelf.querySelector(`[data-surface-restore="${id}"]`).setAttribute('aria-label', key('restore', { name: nameOf(id) }));
      index.querySelector(`[data-surface-show="${id}"]`).textContent = nameOf(id);
    }
    focusButton.textContent = key(focused ? 'exitFocus' : 'focus'); focusButton.setAttribute('aria-pressed', String(focused));
    placeGraphControls();
    onChange(state);
  }
  function change(fn) { fn(); persist(); refresh(); }
  function raise(id) { for (const other of IDS) surfaces[other].style.zIndex = other === id ? '24' : '20'; }
  function show(id) {
    if (!state.surfaces[id]) return;
    change(() => { focused = false; narrowActive = id; state.surfaces[id].minimized = false; });
    onShow(id);
    raise(id);
    closeIndex();
    const prior = previousFocus.get(id);
    (prior?.isConnected && surfaces[id].contains(prior) ? prior : gripOf(id)).focus({ preventScroll: true });
  }
  function minimize(id) {
    previousFocus.set(id, doc.activeElement?.matches('[data-surface-minimize]') ? gripOf(id) : surfaces[id].contains(doc.activeElement) ? doc.activeElement : gripOf(id));
    change(() => { state.surfaces[id].minimized = true; if (narrowActive === id) narrowActive = null; });
    shelf.querySelector(`[data-surface-restore="${id}"]`).focus({ preventScroll: true });
  }
  function dock(id, edge) { if (!EDGES.includes(edge)) return; change(() => { state.surfaces[id].dock = edge; }); announce(key('moved', { name: nameOf(id), position: key(edge) })); }
  function cancel() {
    if (!transaction) return false;
    const { snapshot, id, target, pointerId } = transaction; transaction = null; state = snapshot;
    if (pointerId != null && target.hasPointerCapture?.(pointerId)) target.releasePointerCapture(pointerId);
    preview.hidden = true; root.classList.remove('workspace-moving'); persist(); refresh(); gripOf(id).focus({ preventScroll: true }); announce(key('cancelled')); return true;
  }
  function finish() {
    if (!transaction) return;
    const { id, edge, moved, target, pointerId } = transaction; transaction = null;
    if (edge && moved) state.surfaces[id].dock = edge;
    if (pointerId != null && target.hasPointerCapture?.(pointerId)) target.releasePointerCapture(pointerId);
    preview.hidden = true; root.classList.remove('workspace-moving'); persist(); refresh();
  }
  function begin(event, id, resizing = false) {
    if (event.button !== 0) return;
    event.preventDefault(); event.stopPropagation(); finish();
    const bounds = root.getBoundingClientRect(), rect = surfaceRect(state.surfaces[id], bounds, id);
    transaction = { id, snapshot: clone(state), startX: event.clientX, startY: event.clientY, rect, bounds, resizing, target: event.currentTarget, pointerId: event.pointerId, moved: false };
    event.currentTarget.setPointerCapture(event.pointerId); event.currentTarget.focus({ preventScroll: true });
    // Reordering stays below the always-reachable recovery controls.
    raise(id);
  }
  function move(event) {
    if (!transaction || transaction.pointerId !== event.pointerId) return;
    event.preventDefault(); event.stopPropagation();
    const t = transaction, dx = event.clientX - t.startX, dy = event.clientY - t.startY;
    if (!t.moved && Math.hypot(dx, dy) < 4) return;
    t.moved = true; root.classList.add('workspace-moving');
    const item = state.surfaces[t.id];
    if (t.resizing) { item.width = clamp((t.rect.width + dx) / t.bounds.width, .05, 1); item.height = clamp((t.rect.height + dy) / t.bounds.height, .05, 1); }
    else {
      item.dock = 'floating'; item.x = (t.rect.x + dx) / t.bounds.width; item.y = (t.rect.y + dy) / t.bounds.height;
      item.width = t.rect.width / t.bounds.width; item.height = t.rect.height / t.bounds.height;
      const x = event.clientX - t.bounds.left, y = event.clientY - t.bounds.top;
      t.edge = x < 40 ? 'left' : x > t.bounds.width - 40 ? 'right' : y < 40 ? 'top' : y > t.bounds.height - 40 ? 'bottom' : null;
      preview.hidden = !t.edge;
      if (t.edge) { const rect = surfaceRect({ ...item, dock: t.edge }, t.bounds, t.id); Object.assign(preview.style, { left: `${rect.x}px`, top: `${rect.y}px`, width: `${rect.width}px`, height: `${rect.height}px` }); preview.dataset.dock = t.edge; }
    }
    refresh();
  }
  function keyboard(event, id, resizing = false) {
    const vectors = { ArrowLeft: [-1, 0, 'left'], ArrowRight: [1, 0, 'right'], ArrowUp: [0, -1, 'top'], ArrowDown: [0, 1, 'bottom'] };
    if (event.key === 'Escape' && cancel()) { event.preventDefault(); event.stopPropagation(); return; }
    if (event.key === 'Enter') { finish(); return; }
    const vector = vectors[event.key]; if (!vector) return;
    event.preventDefault(); event.stopPropagation();
    if (!transaction) transaction = { id, snapshot: clone(state), target: event.currentTarget };
    if (event.altKey) { dock(id, vector[2]); return; }
    const bounds = root.getBoundingClientRect(), item = state.surfaces[id], rect = surfaceRect(item, bounds, id), step = 16;
    if (event.shiftKey || resizing) { item.width = clamp((rect.width + vector[0] * step) / bounds.width, .05, 1); item.height = clamp((rect.height + vector[1] * step) / bounds.height, .05, 1); }
    else { item.dock = 'floating'; item.x = (rect.x + vector[0] * step) / bounds.width; item.y = (rect.y + vector[1] * step) / bounds.height; }
    persist(); refresh();
  }
  for (const id of IDS) {
    const el = surfaces[id]; el.classList.add('workspace-surface'); el.dataset.surfaceId = id;
    // Only tool chrome and the Activity host move. The renderer subtree never moves.
    if (el.parentElement !== root) root.append(el);
    const header = make('div', 'surface-hd'); el.prepend(header);
    const grip = button('⠿', header, 'surfaceMove', id); grip.classList.add('surface-grip'); grip.setAttribute('aria-label', key('move', { name: nameOf(id) })); grip.setAttribute('aria-describedby', instructions.id);
    const title = make('span', 'surface-title', header); title.textContent = nameOf(id);
    const actions = make('div', 'surface-actions', header);
    const select = make('select', 'surface-dock', actions); select.dataset.surfaceDock = id; select.setAttribute('aria-label', key('dock', { name: nameOf(id) }));
    for (const edge of EDGES) { const option = make('option', '', select); option.value = edge; option.textContent = key(edge); }
    select.addEventListener('change', () => dock(id, select.value));
    const hide = button('−', actions, 'surfaceMinimize', id); hide.setAttribute('aria-label', key('minimize', { name: nameOf(id) })); hide.addEventListener('click', () => minimize(id));
    const resize = button('◢', el, 'surfaceResize', id); resize.classList.add('surface-resize'); resize.setAttribute('aria-label', key('resize', { name: nameOf(id) })); resize.setAttribute('aria-describedby', instructions.id);
    for (const [control, resizing] of [[grip, false], [resize, true]]) {
      control.addEventListener('pointerdown', event => begin(event, id, resizing)); control.addEventListener('pointermove', move); control.addEventListener('pointerup', finish); control.addEventListener('pointercancel', cancel); control.addEventListener('lostpointercapture', () => { if (transaction?.pointerId != null) cancel(); });
      control.addEventListener('keydown', event => keyboard(event, id, resizing)); control.addEventListener('blur', () => { if (transaction?.pointerId == null) finish(); });
    }
    el.addEventListener('pointerdown', () => raise(id)); el.addEventListener('focusin', () => raise(id));
    for (const type of ['pointerdown', 'pointermove', 'pointerup', 'wheel', 'dblclick']) el.addEventListener(type, event => event.stopPropagation());
    const restore = button('', shelf, 'surfaceRestore', id);
    const icon = doc.createElementNS('http://www.w3.org/2000/svg', 'svg'); icon.classList.add('ctl-glyph'); icon.setAttribute('aria-hidden', 'true');
    const use = doc.createElementNS('http://www.w3.org/2000/svg', 'use'); use.setAttribute('href', `#${{ catalog: 'i-p-node-catalog', inspector: 'i-p-inspector', activity: 'i-p-activity', tools: 'i-index' }[id]}`); icon.append(use); restore.append(icon);
    const restoreLabel = make('span', 'surface-restore-label', restore); restoreLabel.textContent = nameOf(id); restore.setAttribute('aria-label', key('restore', { name: nameOf(id) })); restore.addEventListener('click', () => show(id));
    const entry = button(nameOf(id), index, 'surfaceShow', id); entry.addEventListener('click', () => show(id));
  }
  indexButton.addEventListener('click', () => { const opening = index.hidden; closeIndex(); if (opening) { index.hidden = false; indexButton.setAttribute('aria-expanded', 'true'); index.querySelector('button').focus(); } });
  index.addEventListener('keydown', event => { if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); closeIndex(true); } });
  controls.addEventListener('click', event => { const action = event.target.dataset.workspaceAction; if (action === 'focus') setFocus(!focused); if (action === 'reset') reset(); });
  function setFocus(value) { if (value && IDS.some(id => surfaces[id].contains(doc.activeElement))) focusButton.focus({ preventScroll: true }); focused = Boolean(value); refresh(); }
  function reset() { finish(); state = defaultImmersiveLayout(); focused = false; narrowActive = null; onReset(); persist(); refresh(); }
  doc.addEventListener('keydown', event => { if (event.key === 'Escape' && cancel()) { event.preventDefault(); event.stopImmediatePropagation(); } }, true);
  doc.addEventListener('pointerdown', event => { if (!index.hidden && !index.contains(event.target) && event.target !== indexButton) closeIndex(); });
  new ResizeObserver(() => { if (transaction) cancel(); refresh(); }).observe(root);
  persist(); refresh();
  return { getState: () => clone(state), isVisible: id => !surfaces[id].inert, show, minimize, dock, reveal(id) { if (state.surfaces[id]?.minimized) change(() => { state.surfaces[id].minimized = false; }); }, setFocus, reset, refresh,
    usableRect(element) { const bounds = element.getBoundingClientRect(); const obstacles = [...IDS.map(id => surfaces[id]), controls, shelf, doc.getElementById('zoom-ctrl'), doc.getElementById('minimap')].filter(el => el && !el.hidden && !el.inert && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden').map(el => { const box = el.getBoundingClientRect(); return { x: box.left, y: box.top, width: box.width, height: box.height }; }); const rect = usableGraphRect({ x: bounds.left, y: bounds.top, width: bounds.width, height: bounds.height }, obstacles); return { x: rect.x - bounds.left, y: rect.y - bounds.top, width: rect.width, height: rect.height }; },
  };
}

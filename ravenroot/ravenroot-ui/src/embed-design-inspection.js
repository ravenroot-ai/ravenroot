import { runtimeNodeTooltipText } from './viewer-elastic-renderer.js';
import { uiText } from './ui-text.js';

/** Native keyboard targets over the read-only Design glyphs; runtime belongs to real nodes only. */
export function createEmbedDesignInspection({ canvas, cy, tooltip, snapshot, runtime, projection, toggle,
  onInspect = () => {}, t = uiText }) {
  const layer = canvas.ownerDocument.createElement('div');
  layer.className = 'embed-design-inspection';
  canvas.append(layer);
  const buttons = new Map();
  let hovered = null;
  let enabled = true;
  function hide() { hovered = null; tooltip.style.display = 'none'; }
  function refreshTooltip() {
    if (!hovered || !enabled) return;
    const node = snapshot()?.nodes.find(item => item.id === hovered);
    const element = cy.getElementById(hovered);
    if (!node || element.empty() || !element.visible()) { hide(); return; }
    const state = runtime()?.nodeStates.get(hovered);
    tooltip.textContent = runtimeNodeTooltipText({ ...node, ...state,
      runtimeObserved: Boolean(state), instances: state?.activeInstances,
      historyNotLoaded: Boolean(runtime()?.nodeHistoryAvailability && !state && !runtime()?.nodeCoverageComplete) }, t);
    tooltip.style.display = 'block';
    const point = element.renderedPosition();
    const bounds = canvas.getBoundingClientRect();
    const tip = tooltip.getBoundingClientRect();
    tooltip.style.left = `${Math.max(8, Math.min(point.x + 14, bounds.width - tip.width - 8))}px`;
    tooltip.style.top = `${Math.max(8, Math.min(point.y - 10, bounds.height - tip.height - 8))}px`;
  }
  function refresh() {
    if (!enabled || cy.destroyed()) return;
    const entries = [];
    for (const group of projection()?.groups || []) entries.push({
      id: group.collapsed ? group.summaryId : group.headerId, group,
      label: t('embed.inspect.group', {
        action: t(group.collapsed ? 'embed.inspect.expand' : 'embed.inspect.collapse'),
        name: group.name,
        count: group.memberNodeIds.length,
      }),
    });
    for (const node of snapshot()?.nodes || []) {
      const element = cy.getElementById(node.id);
      if (element.nonempty() && element.visible()) entries.push({ id: node.id,
        label: t('embed.inspect.node', { name: node.label || node.id }) });
    }
    const keep = new Set(entries.map(entry => entry.id));
    for (const [id, button] of buttons) if (!keep.has(id)) { button.remove(); buttons.delete(id); }
    for (const entry of entries) {
      let button = buttons.get(entry.id);
      if (!button) {
        button = canvas.ownerDocument.createElement('button');
        button.type = 'button';
        button.dataset.designElement = entry.id;
        button.addEventListener('click', () => {
          if (entry.group) {
            const wasFocused = canvas.ownerDocument.activeElement === button;
            hide(); toggle(entry.group.id, !entry.group.collapsed);
            if (wasFocused) {
              const next = projection()?.groups.find(group => group.id === entry.group.id);
              buttons.get(next?.collapsed ? next.summaryId : next?.headerId)?.focus();
            }
          } else { cy.$(':selected').unselect(); cy.getElementById(entry.id).select(); onInspect(entry.id); }
        });
        if (!entry.group) {
          const show = () => { hovered = entry.id; refreshTooltip(); };
          button.addEventListener('pointerenter', show); button.addEventListener('focus', show);
          button.addEventListener('pointerleave', hide); button.addEventListener('blur', hide);
        }
        buttons.set(entry.id, button); layer.append(button);
      }
      button.setAttribute('aria-label', entry.label);
      const bounds = cy.getElementById(entry.id).renderedBoundingBox({ includeLabels: false });
      const width = Math.max(44, bounds.w), height = Math.max(44, bounds.h);
      Object.assign(button.style, { left: `${bounds.x1 - (width - bounds.w) / 2}px`, top: `${bounds.y1 - (height - bounds.h) / 2}px`,
        width: `${width}px`, height: `${height}px` });
    }
    refreshTooltip();
  }
  cy.on('pan zoom position render', refresh);
  return {
    refresh,
    setEnabled(value) { enabled = value; layer.hidden = !value; hide(); if (value) refresh(); },
    destroy() { cy.off('pan zoom position render', refresh); hide(); layer.remove(); buttons.clear(); },
  };
}

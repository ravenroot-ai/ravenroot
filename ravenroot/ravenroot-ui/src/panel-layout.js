// Which panel is in which zone, in what order, which zones are collapsed, and the relative
// dimensions chosen through their splitters (UI-04, and).
//
// Like `panes.js` and `workspace.js`, this module is deliberately free of DOM and of storage:
// everything here is data and rules, so the rules can be tested without a browser and read without
// tracing a render. app.js owns the rendering and owns the one call to `localStorage`; this owns
// what a layout IS and what makes one invalid.
//
// ── WHY A DESCRIPTOR AND NOT PIXELS ──────────────────────────────────────────────────────────────
//
// A stored layout has to survive things the user did not do: a different window size, a different
// monitor, a build that adds a panel, a build that removes one. PIXEL POSITIONS SURVIVE NONE OF
// THAT — a column pinned at x=1420 is off-screen on a laptop, and there is no honest way to repair
// it after the fact. What is stored here is therefore STRUCTURE: which zone, what order, open or
// closed, collapsed or not. Every one of those survives a resize by construction, because none of
// them is measured in pixels.
//
// ── WHY INVALID DEGRADES TO THE DEFAULT RATHER THAN BEING REPAIRED ───────────────────────────────
//
// A half-repaired layout is a UI the user cannot reason about and cannot describe to anyone else.
// Recovery from the default costs one command; recovery from a subtly wrong layout costs a support
// conversation. Discarding is only safe because THE DEFAULT LAYOUT IS GOOD, which is what increment
// 1 established, so this rule and that one hold each other up.

export const LAYOUT_VERSION = 3;
export const LAYOUT_STORAGE_KEY = 'ravenroot.ui.panel-layout';

export const ZONES = Object.freeze(['left', 'right', 'bottom']);

// The descriptor set. A panel that is not in here cannot appear in a layout, and a panel that IS in
// here always appears in one — those two rules together are what let a build add a panel without
// resetting anybody's stored layout.
// `kind` governs CAPABILITY rather than naming a list of seven panels. `palette` panels can reflow
// repeated items into a grid; `control` (search, one input) and `bounded` (graph-stats, a list of
// label:value lines) can each reflow their OWN content shape into something shorter too. The two
// `unbounded` panels (Inspector, Runtime activity) still cannot: they fill their zone rather than
// stacking inside it, so there is no column height for a short form to give back.
export const PANELS = Object.freeze([
  { id: 'search', title: 'Search', zone: 'left', kind: 'control' },
  { id: 'node-types', title: 'Node Types', zone: 'left', kind: 'palette' },
  { id: 'node-catalog', title: 'Node Catalog', zone: 'left', kind: 'palette' },
  { id: 'edge-types', title: 'Edge Types', zone: 'left', kind: 'palette' },
  { id: 'graph-stats', title: 'Graph Stats', zone: 'left', kind: 'bounded' },
  { id: 'inspector', title: 'Inspector', zone: 'right', kind: 'unbounded' },
  // `unbounded` for the same reason the other two are: a transcript and a composer fill the
  // column rather than stacking inside it, so there is no column height a short form could give
  // back — offering one would put a "Shorten" command in its menu that does nothing visible, which
  // is the inert-menu-entry defect `panelMenuCommands` exists to avoid.
  // It sits AFTER the Inspector so the right column reads top-to-bottom as "what is selected, then
  // what you can ask about it", and so an existing user's stored layout gains it by the
  // `panels-added` path below rather than by a reset.
  //
  // `defaultClosed` — THE ONE PANEL THAT DOES NOT SHIP OPEN, AND THE MEASUREMENT THAT DECIDED IT.
  //
  // MEASURED, one-off, 2026-08-19, Chromium via Playwright against this tree: opening this panel
  // next to the ordinary Modify-mode node editor overflows the right column — 242px at 1280x800,
  // 181px at 1440x900, 53px at 1920x1080. None of those three numbers is pinned by a test, so none
  // is quoted here as if it were. The viewport's HEIGHT is the dominant variable, not its width: at
  // a fixed 1280 width, 800→1080 tall alone takes the overflow from 242px to 72px, while at a fixed
  // 800 height, 1280→1920 wide alone only takes it to 223px. (Also sensitive to Chromium's own font
  // metrics, independently of viewport size — inherited from the test's own comment below, not
  // remeasured here — which is why the assertion it makes has slack.)
  //
  // WHAT IS ACTUALLY PINNED, by e2e/panel-system.spec.js ("keeps Save and Delete reachable even in
  // the one configuration opted the Assistant out of by default"): at 1280x800 the overflow is
  // greater than 100px, and Save/Delete stay reachable without scrolling anyway, because a CSS rule
  // (`src/styles.css`, `.editor-actions { position: sticky; bottom: 0; }` — not this file, which is
  // DOM-free by construction) pins the row to the bottom of the visible column regardless of how
  // much sits above it. Without the sticky row, Save/Cancel are entirely below the fold when overflow
  // measures 205px at 1280x800 and 112px at
  // 1440x900. Inferring that a second open panel makes Save/Cancel unreachable ignores the sticky
  // positioning that closes off exactly that failure mode.
  //
  // SO THE REASON THIS PANEL SHIPS CLOSED IS THE OVERFLOW ITSELF, NOT UNREACHABLE COMMANDS. A form
  // that scrolls before its own primary actions is a bad first impression at every size measured
  // above, even with those actions pinned in view, and the overflow cannot be tuned away with a
  // smaller default share: at 1280x800 the node editor alone needs 553px against a ~644px column,
  // and this panel's own composer and chips add 259px before its transcript shows a single line.
  // There is no split at which both fit without the column scrolling.
  //
  // So the panel ships closed and is one rail click away, in the Panels index like everything
  // else. A user who opens it has chosen to accept the scroll and can size the splitter; nobody
  // silently loses a guarantee that was measured and written down.
  { id: 'assistant', title: 'Assistant', zone: 'right', kind: 'unbounded', defaultClosed: true },
  { id: 'activity', title: 'Runtime activity', zone: 'bottom', kind: 'unbounded' },
]);

// Every kind except `unbounded` has a short form. Each reflows its own content shape rather
// than sharing one CSS rule: see styles.css for the grid, the wrapped chips and the tightened
// control, and the comment on each for why that shape is the one that actually saves height.
const SHORTENABLE_KINDS = new Set(['palette', 'control', 'bounded']);

export function canShorten(id) {
  return SHORTENABLE_KINDS.has(panelDescriptor(id)?.kind);
}

const PANEL_IDS = new Set(PANELS.map(panel => panel.id));

export function panelDescriptor(id) {
  return PANELS.find(panel => panel.id === id) || null;
}

export function defaultLayout() {
  return {
    v: LAYOUT_VERSION,
    zones: Object.fromEntries(ZONES.map(zone => [zone, {
      collapsed: false,
      maximised: false,
      // Null means the responsive product default. Once resized this becomes a normalized share,
      // never a pixel width/height or an absolute position.
      dimension: null,
    }])),
    panels: PANELS.map(panel => ({
      id: panel.id, zone: panel.zone, closed: panel.defaultClosed === true, short: false, size: null,
    })),
  };
}

// The order of a zone is the order of `panels` filtered to that zone. Keeping ONE ordered list
// rather than an index per panel means two panels can never claim the same index, so there is no
// conflict to resolve and no repair to get wrong.
export function panelsInZone(layout, zone) {
  return layout.panels.filter(panel => panel.zone === zone);
}

export function openPanelsInZone(layout, zone) {
  return panelsInZone(layout, zone).filter(panel => !panel.closed);
}

// Compact stack geometry is an outcome of the descriptor, not a measurement of the DOM. It is
// deliberately narrower than `canShorten()`: the dock does not stack compact panels, an empty
// column has no compact content, and one visible full/unbounded panel must keep the normal stack.
// `collapsed` is intentionally irrelevant here. It hides an otherwise valid compact intent; it
// does not erase it, so reopening the column returns to the same mode without rewriting storage.
export function isPanelStackCompact(layout, zone) {
  if (zone !== 'left' && zone !== 'right') return false;
  const open = openPanelsInZone(layout, zone);
  return open.length > 0 && open.every(panel => canShorten(panel.id) && panel.short);
}

// A zone with nothing open in it has nothing to show. The columns answer this by collapsing to
// their rail, which still carries the way back; the dock answers it by being absent entirely, which
// is why the caller has to ask rather than assume one behaviour for all three.
export function isZoneEmpty(layout, zone) {
  return openPanelsInZone(layout, zone).length === 0;
}

// ── Validation ───────────────────────────────────────────────────────────────────────────────────
//
// Returns the layout to USE, never null: a caller that has to decide what to do about invalid
// storage is a caller that will get it wrong somewhere. `degraded` and `reason` are returned so the
// decision is visible in a test and in a log rather than silent.
export function validateLayout(raw) {
  const fallback = (reason) => ({ layout: defaultLayout(), degraded: true, reason });

  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return fallback('not-an-object');
  // A VERSION BUMP DISCARDS RATHER THAN MIGRATES. A migration that is only exercised once, against
  // a shape nobody has written down, is a bug with a long fuse; the cost of discarding is one
  // command.
  if (raw.v !== LAYOUT_VERSION) return fallback('version');
  if (!Array.isArray(raw.panels)) return fallback('panels-not-an-array');

  if (!raw.zones || typeof raw.zones !== 'object' || Array.isArray(raw.zones)) {
    return fallback('zones-not-an-object');
  }

  for (const zone of ZONES) {
    const storedZone = raw.zones[zone];
    if (!storedZone || typeof storedZone !== 'object' || Array.isArray(storedZone)) {
      return fallback('zone-not-an-object');
    }
    if (typeof storedZone.collapsed !== 'boolean' || typeof storedZone.maximised !== 'boolean') {
      return fallback('zone-state');
    }
    if (zone !== 'bottom' && storedZone.maximised) return fallback('column-maximised');
    const dimension = storedZone.dimension;
    if (dimension !== null && (!Number.isFinite(dimension) || dimension <= 0 || dimension >= 1)) {
      return fallback('dimension');
    }
  }

  const seen = new Set();
  const panels = [];
  for (const entry of raw.panels) {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return fallback('panel-entry');
    const { id, zone } = entry;
    if (typeof id !== 'string') return fallback('panel-id');
    // THE ONLY MALFORMED-ENTRY EXCEPTION: an unknown id belonged to a panel another build had and
    // this one does not. Discarding that whole record is forward/backward compatibility, not a
    // repair of current-schema state. Every KNOWN panel below must be structurally exact.
    if (!PANEL_IDS.has(id)) continue;
    if (seen.has(id)) return fallback('panel-duplicate');
    if (typeof zone !== 'string' || !ZONES.includes(zone)) return fallback('panel-zone');
    if (typeof entry.closed !== 'boolean' || typeof entry.short !== 'boolean') {
      return fallback('panel-state');
    }
    if (entry.size !== null
      && (!Number.isFinite(entry.size) || entry.size <= 0 || entry.size > 1)) {
      return fallback('panel-size');
    }
    seen.add(id);
    // Only a panel that CAN shorten may restore short, so a stale semantic capability cannot
    // resurrect a form that no longer exists. Its type is still required above.
    panels.push({
      id, zone, closed: entry.closed === true, short: entry.short === true && canShorten(id),
      size: entry.size,
    });
  }

  // A panel in the descriptor set but absent from storage takes its default zone and is appended.
  // THIS IS WHAT LETS A NEW PANEL APPEAR FOR AN EXISTING USER WITHOUT A STORAGE RESET.
  const missing = PANELS.filter(panel => !seen.has(panel.id));
  for (const panel of missing) {
    // `defaultClosed` is honoured here too, and that is the whole point of it being a descriptor
    // property rather than a boot-time nudge in app.js: an EXISTING user gaining a new panel must
    // get exactly what a new user gets. Appending this one open would rearrange a column they had
    // already arranged, to make room for something they never asked for.
    panels.push({
      id: panel.id, zone: panel.zone, closed: panel.defaultClosed === true, short: false, size: null,
    });
  }

  const zones = Object.fromEntries(ZONES.map(zone => [
    zone,
    // Types were already checked above: projection never coerces storage into a different state.
    {
      collapsed: raw.zones[zone].collapsed,
      // A column already occupies its full vertical axis at rest, so maximising it would do
      // nothing. Only the dock has another axis to claim, and therefore only it may retain this.
      maximised: raw.zones[zone].maximised,
      dimension: raw.zones[zone].dimension,
    },
  ]));

  return {
    layout: { v: LAYOUT_VERSION, zones, panels },
    degraded: missing.length > 0,
    reason: missing.length > 0 ? 'panels-added' : null,
  };
}

// ── Operations ───────────────────────────────────────────────────────────────────────────────────
//
// Every one returns a NEW layout. The caller re-renders from the result rather than mutating what
// it is looking at, which is what keeps "what is on screen" and "what will be stored" the same
// object at all times.

export function movePanelToZone(layout, id, zone) {
  if (!PANEL_IDS.has(id) || !ZONES.includes(zone)) return layout;
  const panels = layout.panels.filter(panel => panel.id !== id);
  const moving = layout.panels.find(panel => panel.id === id);
  if (!moving) return layout;
  // Appended, so a moved panel lands at the end of its new zone where the user can see it, rather
  // than at an index inherited from a zone it is no longer in.
  panels.push({ ...moving, zone, size: moving.zone === zone ? moving.size : null });
  return { ...layout, panels };
}

// Drag needs what the menu did not: a move that lands at a CHOSEN position rather than at the end.
// The menu appends because "Move to bottom dock" says nothing about where; a drag says exactly
// where, and dropping a panel somewhere other than where it was released would make the gesture a
// lie. Same underlying list, so drag stays a PROGRESSIVE ENHANCEMENT over the menu rather than a
// second implementation of it.
export function movePanelToZoneAt(layout, id, zone, index) {
  if (!PANEL_IDS.has(id) || !ZONES.includes(zone)) return layout;
  const moving = layout.panels.find(panel => panel.id === id);
  if (!moving) return layout;

  const others = layout.panels.filter(panel => panel.id !== id);
  const zoneMembers = others.filter(panel => panel.zone === zone);
  const clamped = Math.max(0, Math.min(Number.isFinite(index) ? Math.trunc(index) : zoneMembers.length,
    zoneMembers.length));

  // Rebuild the single ordered list, inserting before the member currently at `clamped`. Splicing
  // against the zone's own members rather than against absolute positions is what keeps every other
  // zone's order untouched.
  const anchor = zoneMembers[clamped];
  const moved = { ...moving, zone, size: moving.zone === zone ? moving.size : null };
  if (!anchor) return { ...layout, panels: [...others, moved] };

  const at = others.indexOf(anchor);
  return { ...layout, panels: [...others.slice(0, at), moved, ...others.slice(at)] };
}

export function movePanelWithinZone(layout, id, direction) {
  const step = direction === 'up' ? -1 : 1;
  const moving = layout.panels.find(panel => panel.id === id);
  if (!moving) return layout;
  const siblings = panelsInZone(layout, moving.zone);
  const at = siblings.findIndex(panel => panel.id === id);
  const to = at + step;
  if (at < 0 || to < 0 || to >= siblings.length) return layout;

  const reordered = siblings.slice();
  [reordered[at], reordered[to]] = [reordered[to], reordered[at]];

  // Rebuild the single list, replacing this zone's members in their new order and leaving every
  // other zone's entries exactly where they were.
  let next = 0;
  const panels = layout.panels.map(panel =>
    panel.zone === moving.zone ? reordered[next++] : panel);
  return { ...layout, panels };
}

export function setPanelClosed(layout, id, closed) {
  if (!PANEL_IDS.has(id)) return layout;
  return {
    ...layout,
    panels: layout.panels.map(panel => (panel.id === id ? { ...panel, closed: Boolean(closed) } : panel)),
  };
}

export function setPanelShort(layout, id, short) {
  if (!canShorten(id)) return layout;
  return {
    ...layout,
    panels: layout.panels.map(panel => (panel.id === id ? { ...panel, short: Boolean(short) } : panel)),
  };
}

// A panel size is a normalized weight, never a pixel measurement. `null` for a zone restores the
// responsive/content-sized default; an object updates the named panels and preserves closed
// siblings so reopening a panel restores the size it had before it was hidden.
export function setPanelSizes(layout, zone, sizes) {
  if (!ZONES.includes(zone)) return layout;
  if (sizes !== null) {
    if (!sizes || typeof sizes !== 'object' || Array.isArray(sizes)) return layout;
    for (const [id, size] of Object.entries(sizes)) {
      const panel = layout.panels.find(entry => entry.id === id);
      if (!panel || panel.zone !== zone || !Number.isFinite(size) || size <= 0 || size > 1) return layout;
    }
  }
  return {
    ...layout,
    panels: layout.panels.map(panel => {
      if (panel.zone !== zone) return panel;
      if (sizes === null) return panel.size === null ? panel : { ...panel, size: null };
      return Object.hasOwn(sizes, panel.id) ? { ...panel, size: sizes[panel.id] } : panel;
    }),
  };
}

export function setZoneCollapsed(layout, zone, collapsed) {
  if (!ZONES.includes(zone)) return layout;
  return {
    ...layout,
    zones: { ...layout.zones, [zone]: { ...layout.zones[zone], collapsed: Boolean(collapsed) } },
  };
}

export function setZoneDimension(layout, zone, dimension) {
  if (!ZONES.includes(zone)) return layout;
  if (dimension !== null && (!Number.isFinite(dimension) || dimension <= 0 || dimension >= 1)) {
    return layout;
  }
  return {
    ...layout,
    zones: {
      ...layout.zones,
      [zone]: { ...layout.zones[zone], dimension, maximised: false },
    },
  };
}

export function setDockMaximised(layout, maximised) {
  return {
    ...layout,
    zones: {
      ...layout.zones,
      bottom: { ...layout.zones.bottom, maximised: Boolean(maximised) },
    },
  };
}

// What the `⋮` menu offers for one panel. IT LISTS ONLY COMMANDS THAT DO SOMETHING: a menu entry
// that is present but inert is the same defect as a drag grip that does not drag, one level up.
// So the panel's current zone is not offered as a move target, and Move up / Move down appear only
// when there is somewhere to move to.
export function panelMenuCommands(layout, id) {
  const panel = layout.panels.find(entry => entry.id === id);
  if (!panel) return [];

  const siblings = panelsInZone(layout, panel.zone);
  const at = siblings.findIndex(entry => entry.id === id);
  const commands = [];

  for (const zone of ZONES) {
    if (zone === panel.zone) continue;
    commands.push({ command: 'move-zone', zone, label: `Move to ${zoneLabel(zone)}` });
  }
  if (at > 0) commands.push({ command: 'move-up', label: 'Move up' });
  if (at >= 0 && at < siblings.length - 1) commands.push({ command: 'move-down', label: 'Move down' });
  // The word says which direction it goes — never "collapse", which is what a ZONE does. Two
  // different outcomes must never share a verb.
  // The label used to read "Shorten (icons only)" / "Lengthen (show labels)". That parenthetical
  // was already inaccurate for Node Catalog, which keeps its name rather than going icon-only
  // (see styles.css), and made it wrong for two more panels: Search has no icon to fall back
  // to, and Graph Stats reflows lines into wrapped chips rather than dropping anything. The comment
  // The CSS rule is that "the user does not need to know the rule differs, only
  // that the panel got shorter and still reads" — the label now matches that by not promising a
  // specific mechanism at all.
  if (canShorten(id)) {
    commands.push(panel.short
      ? { command: 'lengthen', label: 'Lengthen' }
      : { command: 'shorten', label: 'Shorten' });
  }
  if (panel.zone === 'bottom') {
    commands.push(layout.zones.bottom.maximised
      ? { command: 'restore', label: 'Restore dock size' }
      : { command: 'maximise', label: 'Maximise dock' });
  }
  // Not offered when the panel is ALREADY closed. `defaultClosed` makes that state ordinary, and
  // this function's contract is to list no inert commands. The "every entry changes the layout"
  // test in `panel-layout.test.js` covers this case.
  if (!panel.closed) commands.push({ command: 'close', label: 'Close' });
  return commands;
}

export function zoneLabel(zone) {
  if (zone === 'left') return 'left column';
  if (zone === 'right') return 'right column';
  return 'bottom dock';
}

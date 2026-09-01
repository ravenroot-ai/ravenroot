import { describe, expect, it } from 'vitest';

import {
  LAYOUT_VERSION,
  PANELS,
  ZONES,
  defaultLayout,
  isZoneEmpty,
  isPanelStackCompact,
  movePanelToZone,
  movePanelToZoneAt,
  movePanelWithinZone,
  openPanelsInZone,
  panelMenuCommands,
  canShorten,
  panelsInZone,
  setPanelClosed,
  setPanelSizes,
  setPanelShort,
  setDockMaximised,
  setZoneCollapsed,
  setZoneDimension,
  validateLayout,
} from '../src/panel-layout.js';

const idsIn = (layout, zone) => panelsInZone(layout, zone).map(panel => panel.id);

describe('the default layout', () => {
  it('places every panel exactly once in the default arrangement', () => {
    const layout = defaultLayout();
    expect(layout.panels).toHaveLength(PANELS.length);
    expect(new Set(layout.panels.map(panel => panel.id)).size).toBe(PANELS.length);
    expect(idsIn(layout, 'left')).toEqual(['search', 'node-types', 'node-catalog', 'edge-types', 'graph-stats']);
    expect(idsIn(layout, 'right')).toEqual(['inspector', 'assistant']);
    expect(idsIn(layout, 'bottom')).toEqual(['activity']);
    // A `defaultClosed` panel means "everything ships open" is no longer the
    // rule — "everything ships as its descriptor says" is. Asserted as the exact set rather than
    // relaxed to a count, so a second panel quietly acquiring `defaultClosed` fails here.
    expect(layout.panels.filter(panel => panel.closed).map(panel => panel.id)).toEqual(['assistant']);
    expect(layout.panels.every(panel => panel.size === null)).toBe(true);
    expect(ZONES.every(zone => layout.zones[zone].collapsed === false)).toBe(true);
    expect(ZONES.every(zone => layout.zones[zone].dimension === null)).toBe(true);
    expect(ZONES.every(zone => layout.zones[zone].maximised === false)).toBe(true);
  });
});

describe('validating stored layout', () => {
  const nonDefaultStoredLayout = () => setZoneDimension(
    setZoneCollapsed(
      setPanelClosed(movePanelToZone(defaultLayout(), 'graph-stats', 'right'), 'search', true),
      'left', true,
    ),
    'left', 0.24,
  );

  // Each of these is a shape a real browser can hand back: a key written by a different build, a
  // truncated write, a hand-edited value, or a null from a cleared store.
  it.each([
    ['null', null],
    ['a string', 'left'],
    ['an array', []],
    ['a wrong version', { v: 99, panels: [] }],
    ['a missing version', { panels: [] }],
    ['panels that are not an array', { v: LAYOUT_VERSION, panels: { id: 'search' } }],
  ])('degrades %s to the default rather than repairing it', (_label, raw) => {
    const { layout, degraded } = validateLayout(raw);
    expect(degraded).toBe(true);
    expect(layout).toEqual(defaultLayout());
  });

  it('discards a panel id this build does not have, instead of putting a ghost in the rail', () => {
    const { layout } = validateLayout({
      v: LAYOUT_VERSION,
      zones: defaultLayout().zones,
      panels: [
        { id: 'a-panel-from-the-future', zone: 'left', closed: false, short: false },
        { id: 'search', zone: 'right', closed: false, short: false },
      ],
    });
    expect(layout.panels.map(panel => panel.id)).not.toContain('a-panel-from-the-future');
    // CONTROL: the same assertion must be capable of FINDING an id, or it passes vacuously for
    // any string at all. A known id in the same result proves the selector is real.
    expect(layout.panels.map(panel => panel.id)).toContain('search');
    expect(layout.panels).toHaveLength(PANELS.length);
  });

  it.each([
    ['a non-object entry', layout => { layout.panels[1] = null; }],
    ['a duplicate known id', layout => { layout.panels.push({ ...layout.panels[0] }); }],
    ['a non-string id', layout => { layout.panels[1].id = 12; }],
    ['an invalid zone', layout => { layout.panels[1].zone = 'floating'; }],
    ['a non-boolean closed state', layout => { layout.panels[1].closed = 'yes'; }],
    ['a non-boolean short state', layout => { layout.panels[1].short = 1; }],
  ])('degrades the whole non-default descriptor for %s', (_label, mutate) => {
    const stored = structuredClone(nonDefaultStoredLayout());
    mutate(stored);
    const result = validateLayout(stored);

    expect(result.degraded).toBe(true);
    expect(result.layout).toEqual(defaultLayout());
    // NON-VACUITY: the valid siblings deliberately carried placement, close, collapse and geometry
    // state that a partial repair would preserve and therefore fail the exact-default assertion.
    expect(stored).not.toEqual(defaultLayout());
  });

  it.each([
    ['collapsed', 'yes'],
    ['maximised', 1],
  ])('degrades the whole descriptor for a non-boolean zone %s', (field, value) => {
    const stored = structuredClone(nonDefaultStoredLayout());
    stored.zones.right[field] = value;
    expect(validateLayout(stored).layout).toEqual(defaultLayout());
  });

  it('delimits forward compatibility: unknown ids are discarded and missing known panels append', () => {
    const stored = nonDefaultStoredLayout();
    stored.panels = stored.panels
      .filter(panel => panel.id !== 'activity')
      .concat({ id: 'removed-in-this-build', zone: 'bottom', closed: false, short: false });
    const { layout, degraded, reason } = validateLayout(stored);
    expect(layout.panels.map(panel => panel.id)).not.toContain('removed-in-this-build');
    expect(layout.panels.at(-1)).toEqual({
      id: 'activity', zone: 'bottom', closed: false, short: false, size: null,
    });
    expect(layout.zones.left).toEqual(stored.zones.left);
    expect(layout.panels.find(panel => panel.id === 'search').closed).toBe(true);
    expect(degraded).toBe(true);
    expect(reason).toBe('panels-added');
  });

  it('gives a panel this build added but storage has never seen its default zone', () => {
    // Storage written before `activity` existed. It must appear, in the dock, without the user
    // having to reset anything.
    const stored = {
      v: LAYOUT_VERSION,
      zones: defaultLayout().zones,
      panels: PANELS.filter(panel => panel.id !== 'activity')
        .map(panel => ({ id: panel.id, zone: panel.zone, closed: false, short: false, size: null })),
    };
    const { layout, degraded, reason } = validateLayout(stored);
    expect(idsIn(layout, 'bottom')).toEqual(['activity']);
    expect(degraded).toBe(true);
    expect(reason).toBe('panels-added');
  });

  it('degrades an invalid known-panel zone instead of repairing that entry alone', () => {
    const stored = nonDefaultStoredLayout();
    stored.panels.find(panel => panel.id === 'inspector').zone = 'floating';
    expect(validateLayout(stored).layout).toEqual(defaultLayout());
  });

  it('preserves boolean collapse state exactly', () => {
    const zones = defaultLayout().zones;
    const { layout } = validateLayout({
      v: LAYOUT_VERSION,
      zones: {
        left: { ...zones.left, collapsed: true },
        right: { ...zones.right, collapsed: false },
        bottom: { ...zones.bottom, collapsed: true },
      },
      panels: defaultLayout().panels,
    });
    expect(layout.zones.left.collapsed).toBe(true);
    expect(layout.zones.right.collapsed).toBe(false);
    expect(layout.zones.bottom.collapsed).toBe(true);
  });

  it('keeps a stored arrangement that is entirely valid', () => {
    const stored = setZoneCollapsed(
      setPanelClosed(movePanelToZone(defaultLayout(), 'graph-stats', 'right'), 'search', true),
      'left', true,
    );
    const { layout, degraded } = validateLayout(JSON.parse(JSON.stringify(stored)));
    expect(degraded).toBe(false);
    expect(idsIn(layout, 'right')).toEqual(['inspector', 'assistant', 'graph-stats']);
    expect(layout.panels.find(panel => panel.id === 'search').closed).toBe(true);
    expect(layout.zones.left.collapsed).toBe(true);
  });

  it('stores normalized dimensions and never pixel positions', () => {
    const stored = JSON.parse(JSON.stringify(setZoneDimension(defaultLayout(), 'left', 0.2)));
    const numbers = [];
    const walk = (node, path) => {
      if (typeof node === 'number' && path !== 'v') numbers.push(path);
      if (node && typeof node === 'object') {
        for (const [key, value] of Object.entries(node)) walk(value, path ? `${path}.${key}` : key);
      }
    };
    walk(stored, '');
    expect(numbers).toEqual(['zones.left.dimension']);
    expect(stored.zones.left.dimension).toBe(0.2);
    expect(JSON.stringify(stored)).not.toMatch(/(?:left|top|right|bottom)(?:Px|Position)/i);
  });

  it.each([Number.NaN, Number.POSITIVE_INFINITY, -0.1, 0, 1, 1.1, '20%'])
  ('degrades an invalid dimension (%s) to the whole default', dimension => {
    const stored = defaultLayout();
    stored.zones.left.dimension = dimension;
    expect(validateLayout(stored)).toMatchObject({ layout: defaultLayout(), degraded: true, reason: 'dimension' });
  });
});

describe('zone dimensions and maximise', () => {
  it('updates one relative dimension without touching placement or collapse', () => {
    const before = setZoneCollapsed(defaultLayout(), 'right', true);
    const next = setZoneDimension(before, 'left', 0.24);
    expect(next.zones.left.dimension).toBe(0.24);
    expect(next.zones.right.collapsed).toBe(true);
    expect(next.panels).toEqual(before.panels);
  });

  it('keeps maximise dock-only and restores the chosen dock dimension', () => {
    const sized = setZoneDimension(defaultLayout(), 'bottom', 0.3);
    const maximised = setDockMaximised(sized, true);
    expect(maximised.zones.bottom).toMatchObject({ dimension: 0.3, maximised: true });
    expect(validateLayout({
      ...maximised,
      zones: { ...maximised.zones, left: { ...maximised.zones.left, maximised: true } },
    }).layout.zones.left.maximised).toBe(false);
    expect(setDockMaximised(maximised, false).zones.bottom.dimension).toBe(0.3);
  });
});

describe('normalized panel sizes', () => {
  it('sizes named panels without touching other zones or persisting pixels', () => {
    const next = setPanelSizes(defaultLayout(), 'left', { search: 0.2, 'node-types': 0.8 });
    expect(next.panels.find(panel => panel.id === 'search').size).toBe(0.2);
    expect(next.panels.find(panel => panel.id === 'node-types').size).toBe(0.8);
    expect(next.panels.find(panel => panel.id === 'inspector').size).toBeNull();
    expect(JSON.stringify(next)).not.toMatch(/(?:height|top|bottom)Px/i);
  });

  it('clears every internal size in one zone for Home/reset semantics', () => {
    const sized = setPanelSizes(defaultLayout(), 'left', { search: 0.2, 'node-types': 0.8 });
    const reset = setPanelSizes(sized, 'left', null);
    expect(reset.panels.filter(panel => panel.zone === 'left').every(panel => panel.size === null)).toBe(true);
  });

  it.each([0, -0.1, 1.1, Number.NaN, Number.POSITIVE_INFINITY, '0.5'])
  ('refuses an invalid normalized panel size (%s)', size => {
    const layout = defaultLayout();
    expect(setPanelSizes(layout, 'left', { search: size })).toBe(layout);
    const hostile = structuredClone(layout);
    hostile.panels.find(panel => panel.id === 'search').size = size;
    expect(validateLayout(hostile)).toMatchObject({
      layout: defaultLayout(), degraded: true, reason: 'panel-size',
    });
  });
});

describe('moving panels', () => {
  it('appends a moved panel to its new zone so it lands where the user can see it', () => {
    const layout = movePanelToZone(defaultLayout(), 'search', 'right');
    expect(idsIn(layout, 'right')).toEqual(['inspector', 'assistant', 'search']);
    expect(idsIn(layout, 'left')).toEqual(['node-types', 'node-catalog', 'edge-types', 'graph-stats']);
  });

  it('preserves size on same-zone reorder and resets it on a cross-zone move', () => {
    const sized = setPanelSizes(defaultLayout(), 'left', { 'node-catalog': 0.4 });
    const reordered = movePanelWithinZone(sized, 'node-catalog', 'up');
    expect(reordered.panels.find(panel => panel.id === 'node-catalog').size).toBe(0.4);
    const moved = movePanelToZone(reordered, 'node-catalog', 'right');
    expect(moved.panels.find(panel => panel.id === 'node-catalog').size).toBeNull();
  });

  it('allows any panel into any zone — the descriptor validates structure, not taste', () => {
    for (const zone of ZONES) {
      const layout = movePanelToZone(defaultLayout(), 'inspector', zone);
      expect(idsIn(layout, zone)).toContain('inspector');
    }
  });

  it('reorders within a zone and leaves every other zone untouched', () => {
    const before = defaultLayout();
    const layout = movePanelWithinZone(before, 'node-catalog', 'up');
    expect(idsIn(layout, 'left')).toEqual(['search', 'node-catalog', 'node-types', 'edge-types', 'graph-stats']);
    expect(idsIn(layout, 'right')).toEqual(idsIn(before, 'right'));
    expect(idsIn(layout, 'bottom')).toEqual(idsIn(before, 'bottom'));
  });

  it('refuses to move past either end rather than wrapping around', () => {
    const layout = defaultLayout();
    expect(idsIn(movePanelWithinZone(layout, 'search', 'up'), 'left')).toEqual(idsIn(layout, 'left'));
    expect(idsIn(movePanelWithinZone(layout, 'graph-stats', 'down'), 'left')).toEqual(idsIn(layout, 'left'));
  });
});

describe('the overflow menu', () => {
  it('never offers the zone the panel is already in', () => {
    const commands = panelMenuCommands(defaultLayout(), 'inspector');
    const zones = commands.filter(entry => entry.command === 'move-zone').map(entry => entry.zone);
    expect(zones).not.toContain('right');
    // CONTROL: it must be offering SOME zones, or "does not contain right" is vacuous.
    expect(zones.sort()).toEqual(['bottom', 'left']);
  });

  it('omits Move up on the first panel and Move down on the last', () => {
    const layout = defaultLayout();
    const first = panelMenuCommands(layout, 'search').map(entry => entry.command);
    const last = panelMenuCommands(layout, 'graph-stats').map(entry => entry.command);
    const middle = panelMenuCommands(layout, 'node-catalog').map(entry => entry.command);
    expect(first).not.toContain('move-up');
    expect(first).toContain('move-down');
    expect(last).not.toContain('move-down');
    expect(last).toContain('move-up');
    // CONTROL: a panel with somewhere to go in both directions gets both.
    expect(middle).toContain('move-up');
    expect(middle).toContain('move-down');
  });

  it('offers no reordering at all to the only panel in its zone', () => {
    const commands = panelMenuCommands(defaultLayout(), 'activity').map(entry => entry.command);
    expect(commands).not.toContain('move-up');
    expect(commands).not.toContain('move-down');
    // CONTROL: the menu is not simply empty — close and the two moves are there.
    expect(commands).toContain('close');
    expect(commands.filter(command => command === 'move-zone')).toHaveLength(2);
  });

  it('lists no command that does nothing: every entry changes the layout', () => {
    // The generalisation of the grip rule, one level up. Each offered command must produce a
    // different layout than the one it was offered for.
    const layout = defaultLayout();
    for (const panel of PANELS) {
      for (const entry of panelMenuCommands(layout, panel.id)) {
        const next = entry.command === 'move-zone' ? movePanelToZone(layout, panel.id, entry.zone)
          : entry.command === 'move-up' ? movePanelWithinZone(layout, panel.id, 'up')
            : entry.command === 'move-down' ? movePanelWithinZone(layout, panel.id, 'down')
              : entry.command === 'maximise' ? setDockMaximised(layout, true)
                : entry.command === 'restore' ? setDockMaximised(layout, false)
              : setPanelClosed(layout, panel.id, true);
        expect(next, `${panel.id} / ${entry.command} was inert`).not.toEqual(layout);
      }
    }
  });

  it('offers maximise only in the dock and restore only while the dock is maximised', () => {
    expect(panelMenuCommands(defaultLayout(), 'activity').map(entry => entry.command)).toContain('maximise');
    expect(panelMenuCommands(defaultLayout(), 'inspector').map(entry => entry.command)).not.toContain('maximise');
    expect(panelMenuCommands(setDockMaximised(defaultLayout(), true), 'activity')
      .map(entry => entry.command)).toContain('restore');
  });
});

describe('closing', () => {
  it('empties a zone only when every panel in it is closed', () => {
    let layout = defaultLayout();
    expect(isZoneEmpty(layout, 'bottom')).toBe(false);
    layout = setPanelClosed(layout, 'activity', true);
    expect(isZoneEmpty(layout, 'bottom')).toBe(true);
    // A closed panel still EXISTS in the layout — that is what the Panels index enumerates and
    // what makes the route back possible.
    expect(layout.panels.map(panel => panel.id)).toContain('activity');
    expect(openPanelsInZone(layout, 'bottom')).toHaveLength(0);
  });

  it('reopens a panel into the zone it was closed in', () => {
    const moved = movePanelToZone(defaultLayout(), 'activity', 'left');
    const closed = setPanelClosed(moved, 'activity', true);
    const reopened = setPanelClosed(closed, 'activity', false);
    expect(idsIn(reopened, 'left')).toContain('activity');
    expect(idsIn(reopened, 'bottom')).toEqual([]);
  });
});

describe('dropping a panel at a chosen position', () => {
  it('lands where it was released rather than at the end', () => {
    const layout = movePanelToZoneAt(defaultLayout(), 'inspector', 'left', 2);
    expect(idsIn(layout, 'left')).toEqual(
      ['search', 'node-types', 'inspector', 'node-catalog', 'edge-types', 'graph-stats']);
    expect(idsIn(layout, 'right')).toEqual(['assistant']);
  });

  it('reorders within a zone by dropping, without disturbing any other zone', () => {
    const before = defaultLayout();
    const layout = movePanelToZoneAt(before, 'graph-stats', 'left', 0);
    expect(idsIn(layout, 'left')).toEqual(
      ['graph-stats', 'search', 'node-types', 'node-catalog', 'edge-types']);
    expect(idsIn(layout, 'right')).toEqual(idsIn(before, 'right'));
    expect(idsIn(layout, 'bottom')).toEqual(idsIn(before, 'bottom'));
  });

  it('clamps an index past either end instead of losing the panel', () => {
    const high = movePanelToZoneAt(defaultLayout(), 'inspector', 'left', 99);
    expect(idsIn(high, 'left').at(-1)).toBe('inspector');
    const low = movePanelToZoneAt(defaultLayout(), 'inspector', 'left', -5);
    expect(idsIn(low, 'left')[0]).toBe('inspector');
    // CONTROL: the panel is still present exactly once in both, so "clamped" is not "dropped".
    for (const layout of [high, low]) {
      expect(layout.panels.filter(panel => panel.id === 'inspector')).toHaveLength(1);
      expect(layout.panels).toHaveLength(PANELS.length);
    }
  });

  it('is refused for an unknown panel or an unknown zone, leaving the layout untouched', () => {
    const layout = defaultLayout();
    expect(movePanelToZoneAt(layout, 'no-such-panel', 'left', 0)).toBe(layout);
    expect(movePanelToZoneAt(layout, 'inspector', 'floating', 0)).toBe(layout);
    // CONTROL: a legitimate call must NOT be refused, or the two above pass for the wrong reason.
    expect(movePanelToZoneAt(layout, 'inspector', 'left', 0)).not.toBe(layout);
  });
});

describe('the short form', () => {
  it('is offered to every kind except unbounded', () => {
    expect(canShorten('node-types')).toBe(true);
    expect(canShorten('edge-types')).toBe(true);
    expect(canShorten('node-catalog')).toBe(true);
    // Search (`control`) and Graph Stats (`bounded`) used to be refused here — that refusal
    // is exactly what left 1180x800 with a 77px floor no matter what else was shortened, because
    // neither offered anywhere further to give. Each reflows its own content shape (see
    // styles.css), so both are offered now.
    expect(canShorten('search')).toBe(true);
    expect(canShorten('graph-stats')).toBe(true);
    // CONTROL: it must still be REFUSED somewhere, or "not offered to everything" is vacuous.
    // Inspector and Runtime activity are `unbounded` — they fill their zone rather than stacking
    // inside it, so there is no column height for a short form to give back.
    expect(canShorten('inspector')).toBe(false);
    expect(canShorten('activity')).toBe(false);
  });

  it('refuses to shorten a panel that has no short form', () => {
    const layout = defaultLayout();
    expect(setPanelShort(layout, 'inspector', true)).toBe(layout);
    // CONTROL: a shortenable panel must actually change, or the refusal above proves nothing.
    expect(setPanelShort(layout, 'node-types', true)).not.toBe(layout);
  });

  it('offers Shorten and Lengthen as opposite commands, never both at once', () => {
    const full = panelMenuCommands(defaultLayout(), 'node-types').map(entry => entry.command);
    expect(full).toContain('shorten');
    expect(full).not.toContain('lengthen');

    const short = panelMenuCommands(setPanelShort(defaultLayout(), 'node-types', true), 'node-types')
      .map(entry => entry.command);
    expect(short).toContain('lengthen');
    expect(short).not.toContain('shorten');

    // And an unbounded panel — the one kind left refused — is offered neither.
    const inspector = panelMenuCommands(defaultLayout(), 'inspector').map(entry => entry.command);
    expect(inspector).not.toContain('shorten');
    expect(inspector).not.toContain('lengthen');
  });

  it('offers Shorten to search and graph-stats now that both have a short form', () => {
    for (const id of ['search', 'graph-stats']) {
      const full = panelMenuCommands(defaultLayout(), id).map(entry => entry.command);
      expect(full, `${id} does not offer shorten`).toContain('shorten');
      expect(full).not.toContain('lengthen');

      const short = panelMenuCommands(setPanelShort(defaultLayout(), id, true), id).map(entry => entry.command);
      expect(short, `${id} does not offer lengthen once short`).toContain('lengthen');
      expect(short).not.toContain('shorten');
    }
  });

  it('degrades the previous descriptor version after dimensions change the schema', () => {
    const legacy = {
      v: 1,
      zones: { left: { collapsed: true }, right: { collapsed: false }, bottom: { collapsed: false } },
      panels: PANELS.map(panel => ({ id: panel.id, zone: panel.zone, closed: false })),
    };
    expect(validateLayout(legacy)).toMatchObject({
      layout: defaultLayout(), degraded: true, reason: 'version',
    });
  });

  it('never restores a short state onto a panel that cannot have one', () => {
    const hostile = {
      v: LAYOUT_VERSION,
      zones: defaultLayout().zones,
      panels: PANELS.map(panel => ({
        id: panel.id, zone: panel.zone, closed: false, short: true, size: null,
      })),
    };
    const { layout } = validateLayout(hostile);
    // Inspector and Runtime activity are the two kinds left refused (`unbounded`): the only
    // panels a hostile `short: true` must still be stripped from.
    expect(layout.panels.find(panel => panel.id === 'inspector').short).toBe(false);
    expect(layout.panels.find(panel => panel.id === 'activity').short).toBe(false);
    // CONTROL: the palettes DID keep it, so the rule is selective rather than blanket-clearing.
    // Search and Graph Stats belong on this side of the line too, not the refused one.
    expect(layout.panels.find(panel => panel.id === 'node-types').short).toBe(true);
    expect(layout.panels.find(panel => panel.id === 'search').short).toBe(true);
    expect(layout.panels.find(panel => panel.id === 'graph-stats').short).toBe(true);
  });

  it('round-trips a short palette through validation', () => {
    const stored = setPanelShort(setPanelShort(defaultLayout(), 'node-types', true), 'edge-types', true);
    const { layout } = validateLayout(JSON.parse(JSON.stringify(stored)));
    expect(layout.panels.find(panel => panel.id === 'node-types').short).toBe(true);
    expect(layout.panels.find(panel => panel.id === 'edge-types').short).toBe(true);
  });

  it('pins the exact set of shortenable panel ids, so a future control/bounded panel forces a decision', () => {
    // `canShorten()` is a POSITIVE allowlist over `kind` (palette, control, bounded), not a list of
    // panel ids — see the definition above. That is the right shape for the capability, but it
    // means a future panel declared with one of those three kinds inherits a short form the moment
    // it is added to `PANELS`, silently, via generic padding and whatever grid/wrap rule its kind's
    // CSS block already matches. Graceful, but undesigned: nobody looked at THAT panel's content
    // shape and decided a short form was right for it, the way `styles.css` argues one deliberate
    // case at a time for palettes, Search and Graph Stats.
    //
    // This test is the tripwire. It is a LITERAL array, not `PANELS.filter(p => canShorten(p.id))`
    // — a derived assertion would update itself the instant a sixth panel joined one of the three
    // kinds, silently proving nothing. Written as a literal, adding that panel makes this test fail
    // until a person edits it, which is the decision this test exists to force.
    const shortenableIds = PANELS.filter(panel => canShorten(panel.id)).map(panel => panel.id);
    expect(shortenableIds).toEqual(['search', 'node-types', 'node-catalog', 'edge-types', 'graph-stats']);
  });

  describe('derived compact panel-stack mode', () => {
    const shortenAll = (layout, zone) => openPanelsInZone(layout, zone)
      .reduce((next, panel) => setPanelShort(next, panel.id, true), layout);

    it('is true only when every open panel in a side column is shortenable and short', () => {
      const left = shortenAll(defaultLayout(), 'left');
      expect(isPanelStackCompact(left, 'left')).toBe(true);
      expect(isPanelStackCompact(left, 'right')).toBe(false);

      const mixed = setPanelShort(left, 'search', false);
      expect(isPanelStackCompact(mixed, 'left')).toBe(false);
    });

    it('never selects compact mode for the bottom dock or an empty side column', () => {
      const allShort = shortenAll(defaultLayout(), 'left');
      expect(isPanelStackCompact(allShort, 'bottom')).toBe(false);

      const emptyLeft = allShort.panels.reduce((next, panel) => (
        panel.zone === 'left' ? setPanelClosed(next, panel.id, true) : next
      ), allShort);
      expect(isPanelStackCompact(emptyLeft, 'left')).toBe(false);
    });

    it('ignores closed panels but a visible unbounded panel blocks compact mode', () => {
      let layout = movePanelToZone(shortenAll(defaultLayout(), 'left'), 'inspector', 'left');
      expect(isPanelStackCompact(layout, 'left')).toBe(false);

      layout = setPanelClosed(layout, 'inspector', true);
      expect(isPanelStackCompact(layout, 'left')).toBe(true);

      layout = movePanelToZone(layout, 'activity', 'left');
      expect(isPanelStackCompact(layout, 'left')).toBe(false);
      expect(isPanelStackCompact(setPanelClosed(layout, 'activity', true), 'left')).toBe(true);
    });

    it('follows cross-zone moves and does not treat collapsed state as a compact-mode input', () => {
      let layout = movePanelToZone(shortenAll(defaultLayout(), 'left'), 'node-types', 'right');
      expect(isPanelStackCompact(layout, 'left')).toBe(true);
      expect(isPanelStackCompact(layout, 'right')).toBe(false);

      // A second `unbounded` panel exists in this column. It ships closed, so this line is a
      // no-op today — written out anyway because the property under test is "no unbounded panel is
      // OPEN here", and leaving that resting on another panel's default would make this test pass
      // for a reason it is not about.
      layout = setPanelClosed(layout, 'inspector', true);
      layout = setPanelClosed(layout, 'assistant', true);
      layout = setPanelShort(layout, 'node-types', true);
      expect(isPanelStackCompact(layout, 'right')).toBe(true);

      const collapsed = setZoneCollapsed(layout, 'right', true);
      expect(isPanelStackCompact(collapsed, 'right')).toBe(true);
    });

    it('round-trips compact intent through validation and clears it with the default reset descriptor', () => {
      const compact = shortenAll(defaultLayout(), 'left');
      const stored = JSON.parse(JSON.stringify(compact));
      expect(isPanelStackCompact(validateLayout(stored).layout, 'left')).toBe(true);
      expect(isPanelStackCompact(defaultLayout(), 'left')).toBe(false);
    });

    it('does not accept hostile stored short state on unbounded panels as compact intent', () => {
      const hostile = shortenAll(defaultLayout(), 'left');
      hostile.panels.find(panel => panel.id === 'inspector').short = true;
      hostile.panels.find(panel => panel.id === 'inspector').zone = 'left';

      const { layout } = validateLayout(hostile);
      expect(layout.panels.find(panel => panel.id === 'inspector').short).toBe(false);
      expect(isPanelStackCompact(layout, 'left')).toBe(false);
    });
  });
});

// ── THE FIRST PANEL THAT DOES NOT SHIP OPEN ──────────────────────────────────────────────────
//
// The reason is measured, not stylistic: a second open `unbounded` panel in the right column
// overflows it. This file is DOM-free and measures nothing; see the `defaultClosed` comment in
// `panel-layout.js` for the measurement itself and the e2e test that pins the guarantee that
// actually matters — reachability, not the overflow number.
describe('a panel that ships closed', () => {
  it('is closed in the default layout while still occupying its zone and order', () => {
    const layout = defaultLayout();
    const assistant = layout.panels.find(panel => panel.id === 'assistant');
    expect(assistant.closed).toBe(true);
    expect(assistant.zone).toBe('right');
    // Present, ordered and reachable — closed is not absent.
    expect(idsIn(layout, 'right')).toEqual(['inspector', 'assistant']);
    expect(openPanelsInZone(layout, 'right').map(panel => panel.id)).toEqual(['inspector']);
  });

  it('leaves the right column to the Inspector alone, matching the measured configuration', () => {
    expect(openPanelsInZone(defaultLayout(), 'right').map(panel => panel.id)).toEqual(['inspector']);
  });

  it('arrives closed for an EXISTING user, rather than rearranging a column they had arranged', () => {
    // A stored v3 layout written by a build that predates the panel.
    const stored = defaultLayout();
    stored.panels = stored.panels.filter(panel => panel.id !== 'assistant');
    const { layout, degraded, reason } = validateLayout(JSON.parse(JSON.stringify(stored)));

    expect(degraded).toBe(true);
    expect(reason).toBe('panels-added');
    expect(layout.panels.find(panel => panel.id === 'assistant').closed).toBe(true);
    expect(openPanelsInZone(layout, 'right').map(panel => panel.id)).toEqual(['inspector']);
  });

  it('is still fully operable once opened, like any other panel', () => {
    const opened = setPanelClosed(defaultLayout(), 'assistant', false);
    expect(openPanelsInZone(opened, 'right').map(panel => panel.id)).toEqual(['inspector', 'assistant']);
    // And a stored OPEN state survives validation: shipping closed is a default, never a ceiling.
    const { layout } = validateLayout(JSON.parse(JSON.stringify(opened)));
    expect(layout.panels.find(panel => panel.id === 'assistant').closed).toBe(false);
  });

  it('offers no short form, because an unbounded panel has no column height to give back', () => {
    expect(canShorten('assistant')).toBe(false);
    expect(panelMenuCommands(defaultLayout(), 'assistant').map(entry => entry.command))
      .not.toContain('shorten');
  });
});

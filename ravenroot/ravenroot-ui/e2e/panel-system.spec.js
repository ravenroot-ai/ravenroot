import { expect, test } from '@playwright/test';

// The panel system has seven panels in three zones, with Runtime activity in a bottom dock instead
// of the right column into a bottom dock.
//
// This tier had NO coverage at all before this file — no spec matched `#info`, `#sidebar`,
// `#info-close` or `#activity-log` by name, which is a large part of why a dead `#info.on` toggle
// survived in the markup for so long. These are the guarantees the panel system makes, so
// they are asserted rather than left to the next reader to rediscover.

const PANELS = ['search', 'node-types', 'node-catalog', 'edge-types', 'graph-stats', 'inspector', 'assistant', 'activity'];

// Seeds a selected node in Modify mode, which is the state that puts the node editor in the
// Inspector. `#btn-new` is used rather than a fixture file because the editor's height is what is
// under test, not the graph's contents.
async function selectedNodeInModifyMode(page) {
  await page.goto('/');
  await page.locator('#btn-new').click();
  await page.locator('#btn-modify').click();
  await page.waitForTimeout(300);
  await page.evaluate(() => {
    const host = document.querySelector('.doc-canvas') || document.getElementById('cy');
    const instance = host && host._cyreg && host._cyreg.cy;
    const node = instance.nodes()[0];
    node.emit('tap');
    instance.$(node).select();
  });
  await page.waitForSelector('#node-editor', { timeout: 10_000 });
  await page.waitForTimeout(300);
}

const inspectorOverflow = page => page.evaluate(() => {
  const body = document.getElementById('info-body');
  return Math.max(0, body.scrollHeight - body.clientHeight);
});

// ── Shared with the describe block below ────────────────────────────────────────────────────
// Two helpers, not reused from 'the short form' describe further down: that block's `shorten` and
// `column` are declared inside its own callback and are not in scope here. Given the same shape of
// helper, this block gets its own module-scoped copy rather than reaching into another describe's
// closure.
const shortenPanel = async (page, id) => {
  await page.locator(`.panel[data-panel-id="${id}"] [data-action="panel-menu"]`).click();
  await page.waitForTimeout(130);
  await page.locator('#panel-menu [data-menu-command="shorten"]').click();
  await page.waitForTimeout(200);
};
const sidebarOverflow = page => page.evaluate(() => {
  const scroll = document.getElementById('sidebar-scroll');
  const statsPanel = document.querySelector('.panel[data-panel-id="graph-stats"]');
  const stats = statsPanel.getBoundingClientRect();
  const originalScrollTop = scroll.scrollTop;
  scroll.scrollTop = scroll.scrollHeight;
  const scrollBounds = scroll.getBoundingClientRect();
  const statsAtEnd = statsPanel.getBoundingClientRect();
  const endReachable = statsAtEnd.bottom <= scrollBounds.bottom + 1;
  scroll.scrollTop = originalScrollTop;
  return {
    overflow: Math.max(0, scroll.scrollHeight - scroll.clientHeight),
    statsAboveFold: stats.bottom <= window.innerHeight,
    endReachable,
  };
});
// Makes the "no persisted layout" precondition explicit rather than assumed. Playwright already
// gives each test a fresh, storage-isolated context, so this is a no-op today. The property these
// tests pin is the DEFAULT, unresized column width. A splitter can persist a dragged width to this
// same storage key, and a
// dragged-and-persisted width is a DIFFERENT property that a fixed-viewport measurement cannot
// speak to at all; that separate property is intentionally untested here.
const resetToDefaultLayout = page => page.evaluate(() => localStorage.removeItem('ravenroot.ui.panel-layout'));

test('every panel is a panel: one header, one title, in a declared zone', async ({ page }) => {
  await page.goto('/');

  for (const id of PANELS) {
    const panel = page.locator(`.panel[data-panel-id="${id}"]`);
    await expect(panel).toHaveCount(1);
    await expect(panel.locator('.panel-hd .panel-title')).toHaveCount(1);
    await expect(panel.locator('.panel-hd .panel-title')).not.toBeEmpty();
  }

  // The zones each panel belongs to on first run. Activity's is the one deliberate relocation.
  await expect(page.locator('.panel[data-panel-id="inspector"]')).toHaveAttribute('data-panel-zone', 'right');
  await expect(page.locator('.panel[data-panel-id="activity"]')).toHaveAttribute('data-panel-zone', 'bottom');
  await expect(page.locator('#sidebar .panel')).toHaveCount(5);

  // Every panel carries close and menu controls only because the rail and Panels index provide a
  // discoverable route back. A closable panel without that route is a trap, so the controls and
  // restoration routes are asserted together.
  await expect(page.locator('.panel-hd [data-action="panel-close"]')).toHaveCount(PANELS.length);
  await expect(page.locator('.panel-hd [data-action="panel-menu"]')).toHaveCount(PANELS.length);
  // The route back is the condition that permits close.
  await expect(page.locator('.rail .rail-index')).toHaveCount(2);
  await expect(page.locator('.rail .rail-toggle')).toHaveCount(2);

  // A drag handle may exist only with the dragging it advertises; otherwise it is an affordance
  // with nothing to afford. Every panel therefore has a grip, and the drag operation coupled to it
  // is asserted in its own describe block below. Removing either behavior makes the pair fail.
  await expect(page.locator('.panel-hd .panel-grip')).toHaveCount(PANELS.length);
  await expect(page.locator('#info-close')).toHaveAttribute('data-action', 'close-info');

  // The dead toggle is retired, not carried forward: `#info` was `display:none` plus a hardcoded
  // `on` class that nothing in app.js ever read or wrote.
  await expect(page.locator('#info')).not.toHaveClass(/\bon\b/);
});

test('Runtime activity is in the dock and the Inspector owns the right column', async ({ page }) => {
  await page.goto('/');

  await expect(page.locator('#dock #activity-log')).toHaveCount(1);
  await expect(page.locator('#info #activity-log')).toHaveCount(0);
  await expect(page.locator('#info #info-body')).toHaveCount(1);

  // The dock spans the STAGE COLUMN only. If it spanned the full viewport it would take height from
  // the two side columns, and a collapsed column would later leave a dead notch beside it.
  const geometry = await page.evaluate(() => {
    const rect = id => document.getElementById(id).getBoundingClientRect();
    return {
      dockLeft: Math.round(rect('dock').left),
      dockRight: Math.round(rect('dock').right),
      sidebarRight: Math.round(rect('sidebar').right),
      infoLeft: Math.round(rect('info').left),
      leftSplitterRight: Math.round(document.querySelector('[data-layout-splitter="left"]')
        .getBoundingClientRect().right),
      rightSplitterLeft: Math.round(document.querySelector('[data-layout-splitter="right"]')
        .getBoundingClientRect().left),
    };
  });
  expect(geometry.dockLeft).toBe(geometry.leftSplitterRight);
  expect(geometry.dockRight).toBe(geometry.rightSplitterLeft);
  expect(geometry.leftSplitterRight).toBeGreaterThan(geometry.sidebarRight);
  expect(geometry.rightSplitterLeft).toBeLessThan(geometry.infoLeft);
});

test('the dock pushes the stage instead of overlaying it, so the zoom controls survive', async ({ page }) => {
  await page.goto('/');

  // `#minimap` and `#zoom-ctrl` are `position:absolute; bottom:16px` INSIDE `#cy-wrap`. An
  // overlaying dock would bury both; a pushing dock shrinks the stage and they ride up with it.
  const geometry = await page.evaluate(() => {
    const rect = id => document.getElementById(id).getBoundingClientRect();
    const wrap = rect('cy-wrap');
    const dock = rect('dock');
    const splitter = document.querySelector('[data-layout-splitter="bottom"]').getBoundingClientRect();
    const minimap = rect('minimap');
    const zoom = rect('zoom-ctrl');
    return {
      splitterFollowsStage: Math.round(splitter.top) === Math.round(wrap.bottom),
      dockFollowsSplitter: Math.round(dock.top) === Math.round(splitter.bottom),
      minimapInside: minimap.bottom <= wrap.bottom + 1 && minimap.top >= wrap.top - 1,
      zoomInside: zoom.bottom <= wrap.bottom + 1 && zoom.top >= wrap.top - 1,
    };
  });
  expect(geometry.splitterFollowsStage).toBe(true);
  expect(geometry.dockFollowsSplitter).toBe(true);
  expect(geometry.minimapInside).toBe(true);
  expect(geometry.zoomInside).toBe(true);
});

test.describe('the Modify-mode node editor, at the widths the defect was measured at', () => {
  for (const [width, height, wasOverflowing] of [[1280, 800, 205], [1440, 900, 112]]) {
    test(`fits the Inspector at ${width}x${height}, where it used to overflow by ${wasOverflowing}px`, async ({ page }) => {
      await page.setViewportSize({ width, height });
      await selectedNodeInModifyMode(page);

      // ── THE CONTROL, FIRST ────────────────────────────────────────────────────────────────────
      // A measurement of "no overflow" is worth nothing unless the measurement can detect overflow.
      // Constrain the body and confirm the same expression reports it, before trusting a zero.
      //
      // `max-height`, NOT `height`: `#info-body` is a flex item with `flex: 1 1 auto`, so a height
      // is simply grown back to fill the column and the body never gets smaller. That is not a
      // hypothetical — it is what this control did on its first run, and it reported no overflow
      // for a reason that had nothing to do with the product. A control that cannot fail is worth
      // as little as an assertion that cannot fail.
      await page.evaluate(() => { document.getElementById('info-body').style.maxHeight = '80px'; });
      expect(await inspectorOverflow(page)).toBeGreaterThan(0);
      await page.evaluate(() => { document.getElementById('info-body').style.maxHeight = ''; });
      await page.waitForTimeout(200);

      // ── THE CLAIM ─────────────────────────────────────────────────────────────────────────────
      expect(await inspectorOverflow(page)).toBe(0);

      // What the overflow actually cost: the primary action of the primary editing form was below
      // the fold. Assert the outcome, not only the number.
      const actionsInView = await page.evaluate(() => {
        const body = document.getElementById('info-body').getBoundingClientRect();
        const actions = document.querySelector('.editor-actions').getBoundingClientRect();
        return actions.bottom <= body.bottom + 1 && actions.top >= body.top - 1;
      });
      expect(actionsInView).toBe(true);
    });
  }
});

test.describe('the editor action row stays in view when the form overflows', () => {
  // The dock fixes the ORDINARY editor. The PROGRAMMABLE editor still overflows after it, because
  // its height is carried by full-width rows that extra column width cannot reflow away — so the
  // pinned row is what keeps Save and Delete reachable there. The two editors are measured
  // separately on purpose and must not be blurred into one number.
  const CATALOG = JSON.stringify([{
    behavior: 'program', displayName: 'Governed program', category: 'core',
    description: 'Executes a governed JavaScript artifact stored server-side.',
    visualType: 'flow', agentic: false, capabilities: [],
    properties: [{ name: 'artifactId', type: 'STRING', required: false, description: 'id', defaultValue: '' }],
  }]);

  const actionsInsideBody = page => page.evaluate(() => {
    const body = document.getElementById('info-body').getBoundingClientRect();
    const actions = document.querySelector('.editor-actions').getBoundingClientRect();
    return actions.bottom <= body.bottom + 1 && actions.top >= body.top - 1;
  });

  test('and the assertion is shown failing with the rule disabled before its pass is believed', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.route('**/v1/node-types', route => route.fulfill({
      status: 200, contentType: 'application/json; charset=utf-8', body: CATALOG,
    }));
    await page.route('**/v1/events', route => route.fulfill({ status: 204, body: '' }));

    await selectedNodeInModifyMode(page);
    await page.evaluate(() => {
      const form = document.getElementById('node-editor');
      form.elements.catalogBehavior.value = 'program';
      form.elements.catalogBehavior.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.waitForSelector('.program-workspace', { timeout: 10_000 });
    await page.waitForTimeout(300);

    // The premise: this form genuinely does overflow, so the question is not vacuous.
    expect(await inspectorOverflow(page)).toBeGreaterThan(0);

    // ── RED: with the rule disabled the row is NOT in view ────────────────────────────────────
    // A green assertion that would stay green with the feature deleted is not evidence.
    await page.addStyleTag({ content: '.editor-actions { position: static !important; }' });
    await page.waitForTimeout(150);
    expect(await actionsInsideBody(page)).toBe(false);

    // ── GREEN: with the shipped rule in force it is ───────────────────────────────────────────
    await page.evaluate(() => {
      document.querySelectorAll('style').forEach(style => {
        if (style.textContent.includes('position: static !important')) style.remove();
      });
    });
    await page.waitForTimeout(150);
    expect(await actionsInsideBody(page)).toBe(true);
  });

  test('pins the destructive action at a different weight from the primary one', async ({ page }) => {
    // Pinning the row permanently in view is only acceptable because Delete stops being
    // interchangeable with Save at a glance. `delete-node` deletes immediately, with no
    // confirmation, so this is a safety property rather than a stylistic one.
    await page.setViewportSize({ width: 1280, height: 800 });
    await selectedNodeInModifyMode(page);

    const widths = await page.evaluate(() => ({
      danger: document.querySelector('.editor-actions .btn.danger').getBoundingClientRect().width,
      primary: document.querySelector('.editor-actions .btn.primary').getBoundingClientRect().width,
    }));
    expect(widths.danger).toBeGreaterThan(0);
    expect(widths.primary).toBeGreaterThan(widths.danger);
  });

  // ── THE THIRD CONFIGURATION, NEITHER OF THE TWO ABOVE ──────────────────────────────────────
  //
  // Not the programmable editor (that one overflows regardless of the right column's width), and
  // not today's shipped default (the ORDINARY editor alone, which the widths test in the previous
  // describe block already proved fits with zero overflow). This is the ORDINARY editor with the
  // Assistant panel FORCED open beside it — the exact configuration `defaultClosed: true` in
  // `panel-layout.js` exists to keep out of the box, so this is what happens to a user who opens it
  // anyway. The `defaultClosed` comment there records a one-off measurement of the overflow this
  // produces; this test deliberately does not repeat that figure, so the prose and assertion cannot
  // drift apart. It pins only what needs a regression guard —
  // that the overflow is real, and that Save/Delete stay reachable anyway — checked directly rather
  // than trusted from the comment.
  test('keeps Save and Delete reachable when the normally closed Assistant is opened', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await selectedNodeInModifyMode(page);

    // Force the Assistant open the way a user does — one click on the rail mark, same as
    // `openAssistant` in assistant-panel.spec.js. Not imported: that helper is declared inside its
    // own file's module scope, and this file already carries its own small helpers rather than
    // reaching across spec files (see `sidebarOverflow` / `shortenPanel` above).
    await page.locator('[data-rail-panel="assistant"]').click();
    await expect(page.locator('.panel[data-panel-id="assistant"]')).toBeVisible();
    await page.waitForTimeout(200);

    // ── THE PREMISE, MEASURED ────────────────────────────────────────────────────────────────
    // If this configuration did not genuinely overflow, the reachability claim below would be
    // free — it would hold whether or not the pinned-row rule did anything. The bound is loose on
    // purpose: at this exact viewport the overflow also moves with Chromium's own font metrics,
    // not only with the property under test, so a tight assertion here would be a test of font
    // rendering rather than of reachability.
    expect(await inspectorOverflow(page)).toBeGreaterThan(100);

    // ── THE CLAIM ─────────────────────────────────────────────────────────────────────────────
    expect(await actionsInsideBody(page)).toBe(true);

    // ── CONTROL: the claim above must be capable of failing, or it is worth nothing ────────────
    // Without the pinned row, Save and Delete drop below the fold — the guarantee the pinned row
    // itself exists to hold. That is a separate claim from why the Assistant ships closed: the
    // overflow above is why (see the `defaultClosed` comment in `panel-layout.js`), not this.
    await page.addStyleTag({ content: '.editor-actions { position: static !important; }' });
    await page.waitForTimeout(150);
    expect(await actionsInsideBody(page)).toBe(false);
  });
});

test('the dock gives way before the stage falls below its measured floor', async ({ page }) => {
  await page.goto('/');

  const at = async (width, height) => {
    await page.setViewportSize({ width, height });
    await page.waitForTimeout(250);
    return page.evaluate(() => ({
      dock: Math.round(document.getElementById('dock').getBoundingClientRect().height),
      stage: Math.round(document.getElementById('cy-wrap').getBoundingClientRect().height),
      floor: parseInt(getComputedStyle(document.documentElement).getPropertyValue('--stage-min-h'), 10),
    }));
  };

  // CONTROL: with room to spare the dock is its full default height, so the assertion below is
  // about the clamp doing something rather than about the dock always being small.
  const roomy = await at(1280, 900);
  expect(roomy.dock).toBe(160);
  expect(roomy.stage).toBeGreaterThanOrEqual(roomy.floor);

  // The floor is published from `panes.js` STAGE_MIN_HEIGHT, so this also asserts the two have not
  // drifted apart.
  expect(roomy.floor).toBe(204);

  const cramped = await at(1280, 420);
  expect(cramped.dock).toBeLessThan(160);
  expect(cramped.stage).toBeGreaterThanOrEqual(cramped.floor);
});

// ══ One splitter grammar for zones, dock and graph panes ═══════════════════════════════════════

test.describe('the workspace splitters', () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
  });

  test('keeps every new splitter visible, focusable, axis-correct and live in ARIA', async ({ page }) => {
    const workspaceSplitters = page.locator('[data-splitter-kind="workspace"]');
    await expect(workspaceSplitters).toHaveCount(3);
    const panelSplitters = page.locator('[data-splitter-kind="panel"]');
    await expect(panelSplitters).toHaveCount(4);
    expect(await panelSplitters.evaluateAll(items => items.map(item =>
      `${item.dataset.panelBefore}|${item.dataset.panelAfter}`))).toEqual([
      'search|node-types',
      'node-types|node-catalog',
      'node-catalog|edge-types',
      'edge-types|graph-stats',
    ]);
    for (const zone of ['left', 'right', 'bottom']) {
      await expect(page.locator(`[data-layout-splitter="${zone}"]`)).toBeVisible();
    }
    await expect(page.locator('[data-layout-splitter="bottom"]'))
      .toHaveAttribute('aria-orientation', 'horizontal');
    for (const zone of ['left', 'right']) {
      await expect(page.locator(`[data-layout-splitter="${zone}"]`))
        .toHaveAttribute('aria-orientation', 'vertical');
    }

    const left = page.locator('[data-layout-splitter="left"]');
    const before = await left.getAttribute('aria-valuenow');
    const widthBefore = await page.locator('#sidebar').evaluate(element => element.getBoundingClientRect().width);
    await left.focus();
    await page.keyboard.press('ArrowRight');
    await expect.poll(() => page.locator('#sidebar')
      .evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(widthBefore);
    await expect(left).not.toHaveAttribute('aria-valuenow', before);

    const right = page.locator('[data-layout-splitter="right"]');
    const rightBefore = await page.locator('#info').evaluate(element => element.getBoundingClientRect().width);
    await right.focus();
    await page.keyboard.press('ArrowLeft');
    await expect.poll(() => page.locator('#info')
      .evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(rightBefore);

    const dock = page.locator('[data-layout-splitter="bottom"]');
    const dockBefore = await page.locator('#dock').evaluate(element => element.getBoundingClientRect().height);
    await dock.focus();
    await page.keyboard.press('ArrowUp');
    await expect.poll(() => page.locator('#dock')
      .evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThan(dockBefore);
  });

  test('uses the visible separator as a pointer target, not a hover-only affordance', async ({ page }) => {
    const splitter = page.locator('[data-layout-splitter="left"]');
    const box = await splitter.boundingBox();
    expect(box.width).toBe(1);
    const before = await page.locator('#sidebar').evaluate(element => element.getBoundingClientRect().width);
    // Four pixels outside the painted line is still inside its 9px hit band.
    await page.mouse.move(box.x + box.width / 2 + 4, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2 + 48, box.y + box.height / 2, { steps: 4 });
    await page.mouse.up();
    await expect.poll(() => page.locator('#sidebar')
      .evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(before + 40);
  });

  test('keeps the adjacent column hover shortcut clickable beside the enlarged hit band', async ({ page }) => {
    await page.locator('#sidebar .rail-toggle').click();
    const hoverStrip = page.locator('#sidebar .zone-hover');
    const shortcut = hoverStrip.locator('.zone-hover-btn');
    await hoverStrip.hover();
    await expect(shortcut).toHaveCSS('pointer-events', 'auto');
    await shortcut.click();
    await expect(page.locator('#sidebar')).not.toHaveClass(/zone--collapsed/);
  });

  test('resizes adjacent panels with complete horizontal separator semantics', async ({ page }) => {
    const splitter = page.locator(
      '[data-splitter-kind="panel"][data-panel-before="node-types"][data-panel-after="node-catalog"]',
    );
    await expect(splitter).toHaveAttribute('role', 'separator');
    await expect(splitter).toHaveAttribute('aria-orientation', 'horizontal');
    await expect(splitter).toHaveAttribute('aria-controls', 'panel-node-types panel-node-catalog');
    await expect(splitter).toHaveAttribute('aria-label', 'Resize Node Types and Node Catalog panels');
    await expect(splitter).toHaveAttribute('tabindex', '0');
    const values = await splitter.evaluate(element => ({
      min: Number(element.getAttribute('aria-valuemin')),
      now: Number(element.getAttribute('aria-valuenow')),
      max: Number(element.getAttribute('aria-valuemax')),
      height: element.getBoundingClientRect().height,
    }));
    expect(values.height).toBe(1);
    expect(values.min).toBeLessThanOrEqual(values.now);
    expect(values.now).toBeLessThan(values.max);

    const positionBefore = Number(await splitter.getAttribute('aria-valuenow'));
    const box = await splitter.boundingBox();
    // Exercise the transparent hit band four pixels below the one-pixel painted rule.
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + 4);
    await page.mouse.down();
    await expect(splitter).toHaveClass(/pane-separator--active/);
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + 36, { steps: 4 });
    await page.mouse.up();
    await expect(splitter).not.toHaveClass(/pane-separator--active/);
    await expect.poll(async () => Number(await splitter.getAttribute('aria-valuenow')))
      .toBeGreaterThan(positionBefore);

    const stored = await storedLayout(page);
    expect(stored.v).toBe(3);
    const sized = stored.panels.filter(panel => panel.zone === 'left' && panel.size !== null);
    expect(sized.length).toBeGreaterThan(1);
    for (const panel of sized) expect(panel.size).toBeGreaterThan(0);
    expect(JSON.stringify(stored)).not.toMatch(/(?:heightPx|widthPx|topPx|leftPx)/i);

    // The enlarged hit target must not steal adjacent panel controls.
    await page.locator('[data-panel-id="node-types"] [data-action="panel-menu"]').click();
    await expect(page.locator('#panel-menu')).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('preserves normalized sizes, rebuilds only open boundaries, and resets by zone or globally', async ({ page }) => {
    const first = page.locator(
      '[data-splitter-kind="panel"][data-panel-before="node-types"][data-panel-after="node-catalog"]',
    );
    await first.focus();
    await page.keyboard.press('ArrowDown');
    const resized = await storedLayout(page);
    const nodeTypesSize = resized.panels.find(panel => panel.id === 'node-types').size;
    expect(nodeTypesSize).toBeGreaterThan(0);

    await page.reload();
    expect((await storedLayout(page)).panels.find(panel => panel.id === 'node-types').size)
      .toBeCloseTo(nodeTypesSize, 6);

    await page.locator('[data-panel-id="node-types"] [data-action="panel-close"]').click();
    expect((await storedLayout(page)).panels.find(panel => panel.id === 'node-types').size)
      .toBeCloseTo(nodeTypesSize, 6);
    expect(await page.locator('[data-splitter-kind="panel"][data-panel-zone="left"]')
      .evaluateAll(items => items.map(item => `${item.dataset.panelBefore}|${item.dataset.panelAfter}`)))
      .toEqual(['search|node-catalog', 'node-catalog|edge-types', 'edge-types|graph-stats']);

    await page.locator('#sidebar [data-rail-panel="node-types"]').click();
    await expect(page.locator('[data-panel-id="node-types"]')).toBeVisible();
    expect((await storedLayout(page)).panels.find(panel => panel.id === 'node-types').size)
      .toBeCloseTo(nodeTypesSize, 6);

    const reopened = page.locator(
      '[data-splitter-kind="panel"][data-panel-before="node-types"][data-panel-after="node-catalog"]',
    );
    await reopened.focus();
    await page.keyboard.press('Home');
    expect((await storedLayout(page)).panels.filter(panel => panel.zone === 'left')
      .every(panel => panel.size === null)).toBe(true);

    await reopened.focus();
    await page.keyboard.press('ArrowDown');
    await page.locator('[data-panel-id="node-types"] [data-action="panel-menu"]').click();
    await page.locator('#panel-menu [data-menu-command="move-zone"][data-menu-zone="right"]').click();
    const moved = await storedLayout(page);
    expect(moved.panels.find(panel => panel.id === 'node-types').size).toBeNull();
    await expect(page.locator(
      '[data-splitter-kind="panel"][data-panel-zone="right"][data-panel-before="inspector"][data-panel-after="node-types"]',
    )).toHaveCount(1);

    await page.locator('#sidebar .rail-index').click();
    await page.locator('[data-index-reset]').click();
    expect((await storedLayout(page)).panels.every(panel => panel.size === null)).toBe(true);
  });

  test('clamps panel resize to 72px and removes panel splitters while their zone is collapsed', async ({ page }) => {
    const splitter = page.locator(
      '[data-splitter-kind="panel"][data-panel-before="node-types"][data-panel-after="node-catalog"]',
    );
    const box = await splitter.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2, box.y + 500, { steps: 6 });
    await page.mouse.up();
    const heights = await page.locator('[data-panel-id="node-types"], [data-panel-id="node-catalog"]')
      .evaluateAll(panels => panels.map(panel => panel.getBoundingClientRect().height));
    expect(Math.min(...heights)).toBeGreaterThanOrEqual(72);

    await page.locator('#sidebar .rail-toggle').click();
    await expect(page.locator('[data-splitter-kind="panel"][data-panel-zone="left"]').first()).toBeHidden();
    expect(await page.locator('[data-splitter-kind="panel"][data-panel-zone="left"]')
      .evaluateAll(items => items.every(item => item.tabIndex === -1))).toBe(true);
    await page.locator('#sidebar .rail-toggle').click();
    await expect(splitter).toBeVisible();

    const overflow = await page.evaluate(() => ({
      x: document.documentElement.scrollWidth - innerWidth,
      y: document.documentElement.scrollHeight - innerHeight,
    }));
    expect(overflow.x).toBeLessThanOrEqual(0);
    expect(overflow.y).toBeLessThanOrEqual(0);
  });

  test('persists relative dimensions across reload and viewport change, then Reset clears them', async ({ page }) => {
    await page.locator('[data-layout-splitter="left"]').focus();
    await page.keyboard.press('ArrowRight');
    await page.locator('[data-layout-splitter="bottom"]').focus();
    await page.keyboard.press('ArrowUp');

    const stored = await storedLayout(page);
    expect(stored.v).toBe(3);
    expect(stored.zones.left.dimension).toBeGreaterThan(0);
    expect(stored.zones.left.dimension).toBeLessThan(1);
    expect(stored.zones.bottom.dimension).toBeGreaterThan(0);
    expect(JSON.stringify(stored)).not.toMatch(/(?:position|leftPx|topPx|widthPx|heightPx)/i);

    await page.locator('#sidebar .rail-toggle').click();
    expect(Math.round(await page.locator('#sidebar')
      .evaluate(element => element.getBoundingClientRect().width))).toBe(28);
    await page.locator('#sidebar .rail-toggle').click();
    await expect.poll(() => page.locator('#sidebar')
      .evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(224);

    await page.reload();
    await page.setViewportSize({ width: 900, height: 700 });
    await expect.poll(() => page.locator('#stage')
      .evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThanOrEqual(360);
    expect((await storedLayout(page)).zones.left.dimension).toBe(stored.zones.left.dimension);

    await page.locator('#sidebar .rail-index').click();
    await page.locator('[data-index-reset]').click();
    const reset = await storedLayout(page);
    expect(reset.zones.left.dimension).toBeNull();
    expect(reset.zones.bottom.dimension).toBeNull();
    await expect(page.locator('[data-layout-splitter="left"]')).toBeVisible();
  });

  test('degrades a malformed stored dimension to the whole default', async ({ page }) => {
    await page.evaluate(key => {
      const raw = {
        v: 3,
        zones: {
          left: { collapsed: true, maximised: false, dimension: 4 },
          right: { collapsed: false, maximised: false, dimension: null },
          bottom: { collapsed: false, maximised: false, dimension: null },
        },
        panels: [],
      };
      localStorage.setItem(key, JSON.stringify(raw));
    }, LAYOUT_KEY);
    await page.reload();
    await expect(page.locator('#sidebar')).not.toHaveClass(/zone--collapsed/);
    await expect(page.locator('#sidebar-scroll .panel:not([hidden])')).toHaveCount(5);
    expect(Math.round(await page.locator('#sidebar')
      .evaluate(element => element.getBoundingClientRect().width))).toBe(224);
  });

  test('degrades a malformed or duplicate v3 panel entry without preserving valid non-default siblings', async ({ page }) => {
    const readProjection = () => page.evaluate(() => ({
      left: [...document.querySelectorAll('#sidebar-scroll > .panel')].map(panel => panel.dataset.panelId),
      right: [...document.querySelectorAll('#info-body-zone > .panel')].map(panel => panel.dataset.panelId),
      bottom: [...document.querySelectorAll('#dock > .panel')].map(panel => panel.dataset.panelId),
      hidden: [...document.querySelectorAll('.panel[hidden]')].map(panel => panel.dataset.panelId),
      short: [...document.querySelectorAll('.panel--short')].map(panel => panel.dataset.panelId),
      leftCollapsed: document.getElementById('sidebar').classList.contains('zone--collapsed'),
      leftWidth: Math.round(document.getElementById('sidebar').getBoundingClientRect().width),
      stageHeight: Math.round(document.getElementById('cy-wrap').getBoundingClientRect().height),
    }));
    const exactDefault = await readProjection();

    for (const defect of ['invalid-zone', 'duplicate']) {
      await page.evaluate(([key, kind]) => {
        const panels = [
          { id: 'node-types', zone: 'left', closed: false, short: true, size: null },
          { id: 'search', zone: 'left', closed: true, short: true, size: null },
          { id: 'node-catalog', zone: 'left', closed: false, short: false, size: null },
          { id: 'edge-types', zone: kind === 'invalid-zone' ? 'floating' : 'left', closed: false, short: false, size: null },
          { id: 'inspector', zone: 'right', closed: false, short: false, size: null },
          { id: 'graph-stats', zone: 'right', closed: false, short: true, size: null },
          { id: 'activity', zone: 'bottom', closed: false, short: false, size: null },
        ];
        if (kind === 'duplicate') panels.push({ ...panels[0] });
        localStorage.setItem(key, JSON.stringify({
          v: 3,
          zones: {
            left: { collapsed: true, maximised: false, dimension: 0.24 },
            right: { collapsed: false, maximised: false, dimension: 0.3 },
            bottom: { collapsed: false, maximised: true, dimension: 0.25 },
          },
          panels,
        }));
      }, [LAYOUT_KEY, defect]);
      await page.reload();
      expect(await readProjection(), defect).toEqual(exactDefault);
    }

    // NON-VACUITY: all valid sibling fields in the hostile fixture differed from this control —
    // order, placement, close, short, collapse, width and maximised dock height.
    // `right` is DOM order and therefore includes the closed Assistant panel: a closed
    // panel is HIDDEN, never removed, which is what `hidden` below asserts. So the default is no
    // longer "nothing hidden" — it is "exactly the Assistant hidden", which is a stronger control
    // than the empty array was, because it would also catch the panel being dropped entirely.
    expect(exactDefault).toMatchObject({
      left: ['search', 'node-types', 'node-catalog', 'edge-types', 'graph-stats'],
      right: ['inspector', 'assistant'], bottom: ['activity'], hidden: ['assistant'], short: [],
      leftCollapsed: false, leftWidth: 224,
    });
  });

  test('keyboard expands and then resizes either collapsed side splitter', async ({ page }) => {
    for (const spec of [
      { zone: 'left', column: '#sidebar', toggle: '#sidebar .rail-toggle', outward: 'ArrowRight', inward: 'ArrowLeft', min: 148 },
      { zone: 'right', column: '#info', toggle: '#info .rail-toggle', outward: 'ArrowLeft', inward: 'ArrowRight', min: 228 },
    ]) {
      await page.evaluate(key => localStorage.removeItem(key), LAYOUT_KEY);
      await page.reload();
      const splitter = page.locator(`[data-layout-splitter="${spec.zone}"]`);
      await page.locator(spec.toggle).click();
      await expect(page.locator(spec.toggle)).toHaveAttribute('aria-expanded', 'false');
      const collapsedAria = await splitter.getAttribute('aria-valuenow');
      const collapsedEdge = (await splitter.boundingBox()).x;

      await splitter.focus();
      await page.keyboard.press(spec.inward);
      expect(Math.round(await page.locator(spec.column)
        .evaluate(element => element.getBoundingClientRect().width))).toBe(28);
      expect((await storedLayout(page)).zones[spec.zone].dimension).toBeNull();

      await page.keyboard.press(spec.outward);
      await expect(page.locator(spec.column)).not.toHaveClass(/zone--collapsed/);
      await expect(page.locator(spec.toggle)).toHaveAttribute('aria-expanded', 'true');
      const firstWidth = Math.round(await page.locator(spec.column)
        .evaluate(element => element.getBoundingClientRect().width));
      expect(firstWidth).toBeGreaterThan(spec.min);
      await expect(splitter).not.toHaveAttribute('aria-valuenow', collapsedAria);
      const expandedEdge = (await splitter.boundingBox()).x;
      expect(spec.zone === 'left' ? expandedEdge > collapsedEdge : expandedEdge < collapsedEdge).toBe(true);

      await page.keyboard.press(spec.outward);
      await expect.poll(() => page.locator(spec.column)
        .evaluate(element => Math.round(element.getBoundingClientRect().width))).toBeGreaterThan(firstWidth);
      const stored = await storedLayout(page);
      expect(stored.zones[spec.zone].collapsed).toBe(false);
      expect(stored.zones[spec.zone].dimension).toBeGreaterThan(0);
      expect(stored.zones[spec.zone].dimension).toBeLessThan(1);

      await page.reload();
      await expect(page.locator(spec.column)).not.toHaveClass(/zone--collapsed/);
      await expect.poll(() => page.locator(spec.column)
        .evaluate(element => Math.round(element.getBoundingClientRect().width))).toBeGreaterThan(firstWidth);
    }
  });

  test('pointer expands and resizes either collapsed side splitter, and Reset clears it', async ({ page }) => {
    for (const spec of [
      { zone: 'left', column: '#sidebar', toggle: '#sidebar .rail-toggle', delta: 48, min: 148 },
      { zone: 'right', column: '#info', toggle: '#info .rail-toggle', delta: -48, min: 228 },
    ]) {
      await page.evaluate(key => localStorage.removeItem(key), LAYOUT_KEY);
      await page.reload();
      const splitter = page.locator(`[data-layout-splitter="${spec.zone}"]`);
      await page.locator(spec.toggle).click();
      const collapsedAria = await splitter.getAttribute('aria-valuenow');
      const box = await splitter.boundingBox();
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.down();
      await page.mouse.move(box.x + box.width / 2 + spec.delta, box.y + box.height / 2, { steps: 4 });
      await page.mouse.up();

      await expect(page.locator(spec.column)).not.toHaveClass(/zone--collapsed/);
      await expect(page.locator(spec.toggle)).toHaveAttribute('aria-expanded', 'true');
      await expect.poll(() => page.locator(spec.column)
        .evaluate(element => Math.round(element.getBoundingClientRect().width))).toBeGreaterThan(spec.min + 40);
      await expect(splitter).not.toHaveAttribute('aria-valuenow', collapsedAria);
      const stored = await storedLayout(page);
      expect(stored.zones[spec.zone].collapsed).toBe(false);
      expect(stored.zones[spec.zone].dimension).toBeGreaterThan(0);
    }

    await page.locator('#sidebar .rail-index').click();
    await page.locator('[data-index-reset]').click();
    const reset = await storedLayout(page);
    expect(reset.zones.left.dimension).toBeNull();
    expect(reset.zones.right.dimension).toBeNull();
    expect(Math.round(await page.locator('#info').evaluate(element => element.getBoundingClientRect().width))).toBe(360);
  });

  test('disables an empty side boundary until a panel is reopened', async ({ page }) => {
    for (const spec of [
      {
        zone: 'left', column: '#sidebar', panelIds: ['search', 'node-types', 'node-catalog', 'edge-types', 'graph-stats'],
        reopen: 'search', outward: 'ArrowRight', pointerDelta: 36,
      },
      {
        zone: 'right', column: '#info', panelIds: ['inspector'],
        reopen: 'inspector', outward: 'ArrowLeft', pointerDelta: -36,
      },
    ]) {
      await page.evaluate(key => localStorage.removeItem(key), LAYOUT_KEY);
      await page.reload();
      const splitter = page.locator(`[data-layout-splitter="${spec.zone}"]`);
      const column = page.locator(spec.column);

      for (const id of spec.panelIds) {
        await page.locator(`.panel[data-panel-id="${id}"] [data-action="panel-close"]`).click();
      }
      await expect(column).toHaveClass(/zone--collapsed/);
      await expect(splitter).toBeVisible();
      await expect(splitter).toHaveAttribute('aria-disabled', 'true');
      await expect(splitter).toHaveAttribute('tabindex', '-1');
      await expect(splitter).toHaveCSS('cursor', 'default');
      const emptyLayout = await storedLayout(page);
      const emptyAria = await splitter.getAttribute('aria-valuenow');

      await splitter.dispatchEvent('keydown', { key: spec.outward });
      const box = await splitter.boundingBox();
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.down();
      await page.mouse.move(box.x + box.width / 2 + spec.pointerDelta, box.y + box.height / 2, { steps: 3 });
      await page.mouse.up();
      expect(await storedLayout(page)).toEqual(emptyLayout);
      await expect(splitter).toHaveAttribute('aria-valuenow', emptyAria);

      await page.reload();
      await expect(splitter).toHaveAttribute('aria-disabled', 'true');
      await expect(splitter).toHaveAttribute('tabindex', '-1');
      expect(await storedLayout(page)).toEqual(emptyLayout);

      await page.locator(`${spec.column} [data-rail-panel="${spec.reopen}"]`).click();
      await expect(column).not.toHaveClass(/zone--collapsed/);
      await expect(splitter).toHaveAttribute('aria-disabled', 'false');
      await expect(splitter).toHaveAttribute('tabindex', '0');

      await page.locator(`${spec.column} .rail-toggle`).click();
      await splitter.focus();
      await page.keyboard.press(spec.outward);
      await expect(column).not.toHaveClass(/zone--collapsed/);
      const keyboardWidth = await column.evaluate(element => element.getBoundingClientRect().width);
      const reopenedBox = await splitter.boundingBox();
      await page.mouse.move(reopenedBox.x + reopenedBox.width / 2, reopenedBox.y + reopenedBox.height / 2);
      await page.mouse.down();
      await page.mouse.move(reopenedBox.x + reopenedBox.width / 2 + spec.pointerDelta,
        reopenedBox.y + reopenedBox.height / 2, { steps: 3 });
      await page.mouse.up();
      await expect.poll(() => column.evaluate(element => element.getBoundingClientRect().width))
        .toBeGreaterThan(keyboardWidth);
    }

    await page.locator('#sidebar .rail-index').click();
    await page.locator('[data-index-reset]').click();
    for (const zone of ['left', 'right']) {
      const splitter = page.locator(`[data-layout-splitter="${zone}"]`);
      await expect(splitter).toHaveAttribute('aria-disabled', 'false');
      await expect(splitter).toHaveAttribute('tabindex', '0');
    }
    const reset = await storedLayout(page);
    expect(reset.zones.left.dimension).toBeNull();
    expect(reset.zones.right.dimension).toBeNull();
    await page.reload();
    for (const zone of ['left', 'right']) {
      const splitter = page.locator(`[data-layout-splitter="${zone}"]`);
      await expect(splitter).toHaveAttribute('aria-disabled', 'false');
      await expect(splitter).toHaveAttribute('tabindex', '0');
    }
  });

  test('holds the horizontal stage at exactly 360px under an extreme column resize', async ({ page }) => {
    await page.setViewportSize({ width: 900, height: 700 });
    const splitter = page.locator('[data-layout-splitter="left"]');
    const box = await splitter.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + 600, box.y + box.height / 2, { steps: 6 });
    await page.mouse.up();
    expect(Math.round(await page.locator('#stage')
      .evaluate(element => element.getBoundingClientRect().width))).toBe(360);
  });

  test('maximises only the dock, preserves 204 exactly, and restores its prior dimension', async ({ page }) => {
    await page.locator('.panel[data-panel-id="activity"] [data-action="panel-menu"]').click();
    await expect(page.locator('[data-menu-command="maximise"]')).toHaveText('Maximise dock');
    await page.locator('[data-menu-command="maximise"]').click();
    expect(Math.round(await page.locator('#cy-wrap')
      .evaluate(element => element.getBoundingClientRect().height))).toBe(204);
    expect((await storedLayout(page)).zones.bottom.maximised).toBe(true);

    await page.locator('.panel[data-panel-id="activity"] [data-action="panel-menu"]').click();
    await expect(page.locator('[data-menu-command="restore"]')).toHaveText('Restore dock size');
    await page.locator('[data-menu-command="restore"]').click();
    await expect.poll(() => page.locator('#cy-wrap')
      .evaluate(element => Math.round(element.getBoundingClientRect().height))).toBeGreaterThan(204);
    expect((await storedLayout(page)).zones.bottom.dimension).toBeNull();

    await page.locator('.panel[data-panel-id="search"] [data-action="panel-menu"]').click();
    await expect(page.locator('[data-menu-command="maximise"]')).toHaveCount(0);
  });

  test('would observe a 203px stage if the measured dock floor were weakened', async ({ page }) => {
    await page.addStyleTag({ content: `
      #dock { max-height:none !important; height:calc(100% - 204px) !important;
              flex-basis:calc(100% - 204px) !important; }
    ` });
    // 203px stage + 1px visible splitter = the deliberately weakened 204px reservation.
    expect(Math.round(await page.locator('#cy-wrap')
      .evaluate(element => element.getBoundingClientRect().height))).toBe(203);
    // The real maximisation path above is pinned to 204, so this control proves the measurement
    // assertion is capable of detecting the one-pixel mutation.
  });
});

// ══ CLOSE, THE RAIL, THE PANELS INDEX, PERSISTENCE ══════════════════════════════════════════════

const LAYOUT_KEY = 'ravenroot.ui.panel-layout';
const storedLayout = page => page.evaluate(key => JSON.parse(localStorage.getItem(key) || 'null'), LAYOUT_KEY);

test.describe('the rail', () => {
  test('is 28px on each column outer edge and never collapses to zero', async ({ page }) => {
    await page.goto('/');

    const box = await page.evaluate(() => {
      const rect = selector => document.querySelector(selector).getBoundingClientRect();
      return {
        sidebar: Math.round(rect('#sidebar').width),
        leftRail: Math.round(rect('#sidebar .rail').width),
        leftRailX: Math.round(rect('#sidebar .rail').left),
        leftBodyX: Math.round(rect('#sidebar-scroll').left),
        info: Math.round(rect('#info').width),
        rightRailX: Math.round(rect('#info .rail').left),
        rightBodyX: Math.round(rect('#info-body-zone').left),
      };
    });
    // Carved OUT of the column, not added to it: the column is still 224px.
    expect(box.sidebar).toBe(224);
    expect(box.leftRail).toBe(28);
    // Each rail is on its column's OUTER edge.
    expect(box.leftRailX).toBeLessThan(box.leftBodyX);
    expect(box.rightRailX).toBeGreaterThan(box.rightBodyX);

    await page.locator('#sidebar .rail-toggle').click();
    await page.waitForTimeout(200);
    const collapsed = await page.evaluate(() => ({
      column: Math.round(document.getElementById('sidebar').getBoundingClientRect().width),
      rail: Math.round(document.querySelector('#sidebar .rail').getBoundingClientRect().width),
      toggleVisible: document.querySelector('#sidebar .rail-toggle').getBoundingClientRect().width > 0,
      expanded: document.querySelector('#sidebar .rail-toggle').getAttribute('aria-expanded'),
    }));
    // NEVER ZERO. The route back has to still be on screen, and focusable.
    expect(collapsed.column).toBe(28);
    expect(collapsed.rail).toBe(28);
    expect(collapsed.toggleVisible).toBe(true);
    expect(collapsed.expanded).toBe('false');
  });

  test('keeps the way back available without a pointer', async ({ page }) => {
    await page.goto('/');
    // The hover strip is a convenience layered on top; the rail toggle must be reachable by
    // keyboard alone, or hiding a column becomes a trap for anyone not using a mouse.
    await page.locator('#sidebar .rail-toggle').focus();
    await page.keyboard.press('Enter');
    await page.waitForTimeout(200);
    await expect(page.locator('#sidebar')).toHaveClass(/zone--collapsed/);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(200);
    await expect(page.locator('#sidebar')).not.toHaveClass(/zone--collapsed/);
  });
});

test.describe('closing a panel', () => {
  test('hides it rather than detaching it, so app.js can still write to it', async ({ page }) => {
    await page.goto('/');
    await page.locator('.panel[data-panel-id="activity"] [data-action="panel-close"]').click();
    await page.waitForTimeout(200);

    await expect(page.locator('.panel[data-panel-id="activity"]')).toBeHidden();
    // CONTROL for the absence: the element must still BE there. `toBeHidden` would also pass for
    // an element that had been removed, which is exactly the outcome that would break app.js's
    // six unguarded innerHTML writes.
    await expect(page.locator('.panel[data-panel-id="activity"]')).toHaveCount(1);
    await expect(page.locator('#activity-log')).toHaveCount(1);
  });

  test('leaves the dock absent when its last panel is closed', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#dock')).toBeVisible();
    await page.locator('.panel[data-panel-id="activity"] [data-action="panel-close"]').click();
    await page.waitForTimeout(250);
    await expect(page.locator('#dock')).toBeHidden();

    // And the stage takes the space back rather than it becoming a permanent empty lip.
    const stage = await page.evaluate(() => ({
      wrap: Math.round(document.getElementById('cy-wrap').getBoundingClientRect().height),
      main: Math.round(document.getElementById('main').getBoundingClientRect().height),
    }));
    expect(stage.wrap).toBe(stage.main);
  });

  test('collapses a column to its rail when every panel in it is closed', async ({ page }) => {
    await page.goto('/');
    for (const id of ['search', 'node-types', 'node-catalog', 'edge-types', 'graph-stats']) {
      await page.locator(`.panel[data-panel-id="${id}"] [data-action="panel-close"]`).click();
      await page.waitForTimeout(80);
    }
    await expect(page.locator('#sidebar')).toHaveClass(/zone--collapsed/);
    expect(await page.evaluate(() => Math.round(document.getElementById('sidebar').getBoundingClientRect().width))).toBe(28);
    // The toggle would do nothing while the column is empty, so it says so rather than sitting
    // there inert.
    await expect(page.locator('#sidebar .rail-toggle')).toBeDisabled();
    // The route back is still one click away.
    await expect(page.locator('#sidebar .rail-index')).toBeEnabled();
  });

  test('reopens the Inspector when a node is selected, rather than swallowing the selection', async ({ page }) => {
    await page.goto('/');
    await page.locator('#btn-new').click();
    await page.waitForTimeout(300);
    await page.locator('.panel[data-panel-id="inspector"] [data-action="panel-close"]').click();
    await page.waitForTimeout(200);
    await expect(page.locator('.panel[data-panel-id="inspector"]')).toBeHidden();

    await page.evaluate(() => {
      const host = document.querySelector('.doc-canvas') || document.getElementById('cy');
      const instance = host._cyreg.cy;
      const node = instance.nodes()[0];
      node.emit('tap');
      instance.$(node).select();
    });
    await page.waitForTimeout(300);
    await expect(page.locator('.panel[data-panel-id="inspector"]')).toBeVisible();
  });
});

test.describe('the Panels index', () => {
  test('lists every panel with where it is, and brings a closed one back', async ({ page }) => {
    await page.goto('/');
    await page.locator('.panel[data-panel-id="graph-stats"] [data-action="panel-close"]').click();
    await page.waitForTimeout(200);
    await page.locator('#sidebar .rail-index').click();
    await page.waitForTimeout(200);

    const index = page.locator('#panels-index');
    await expect(index).toBeVisible();
    // The index lists all eight panels that EXIST, which is precisely what makes a
    // closed one reachable — and the Assistant ships closed, so it is the case this route serves.
    await expect(index.locator('[data-index-panel]')).toHaveCount(8);
    await expect(index.locator('[data-index-panel="graph-stats"]')).toHaveAttribute('data-closed', 'true');
    // CONTROL: an open panel must read as open, or "closed" would be meaningless.
    await expect(index.locator('[data-index-panel="search"]')).toHaveAttribute('data-closed', 'false');

    await index.locator('[data-index-panel="graph-stats"]').click();
    await page.waitForTimeout(250);
    await expect(page.locator('.panel[data-panel-id="graph-stats"]')).toBeVisible();
  });

  test('is reachable from a collapsed column, which is what makes closing safe', async ({ page }) => {
    await page.goto('/');
    await page.locator('#sidebar .rail-toggle').click();
    await page.waitForTimeout(200);
    await page.locator('#sidebar .rail-index').click();
    await page.waitForTimeout(200);
    await expect(page.locator('#panels-index')).toBeVisible();
  });

  test('resets the layout', async ({ page }) => {
    await page.goto('/');
    await page.locator('.panel[data-panel-id="graph-stats"] [data-action="panel-close"]').click();
    await page.locator('#sidebar .rail-toggle').click();
    await page.waitForTimeout(200);
    await page.locator('#sidebar .rail-index').click();
    await page.locator('[data-index-reset]').click();
    await page.waitForTimeout(250);
    await expect(page.locator('.panel[data-panel-id="graph-stats"]')).toBeVisible();
    await expect(page.locator('#sidebar')).not.toHaveClass(/zone--collapsed/);
  });
});

test.describe('the overflow menu', () => {
  test('offers no command that would do nothing', async ({ page }) => {
    await page.goto('/');
    await page.locator('.panel[data-panel-id="search"] [data-action="panel-menu"]').click();
    await page.waitForTimeout(200);
    const labels = await page.locator('#panel-menu .popover-item').allTextContents();

    // Already in the left column, so that move is not offered.
    expect(labels).not.toContain('Move to left column');
    // CONTROL: the other two zones ARE offered, so the absence above is not vacuous.
    expect(labels).toContain('Move to right column');
    expect(labels).toContain('Move to bottom dock');
    // First panel in its zone: nothing above it.
    expect(labels).not.toContain('Move up');
    expect(labels).toContain('Move down');
    // Maximise is dock-only: a column is already maximal on its vertical axis at rest.
    expect(labels.join(' ')).not.toContain('Maximise');
    // Search has a short form, so the menu wiring is expected to offer it. The CONTROL for
    // "offers no command that would do
    // nothing" is that Search's menu still omits the commands that genuinely do not apply to it —
    // Move to its own zone and Move up, both asserted above.
    expect(labels).toContain('Shorten');
  });

  test('moves a panel between zones', async ({ page }) => {
    await page.goto('/');
    await page.locator('.panel[data-panel-id="search"] [data-action="panel-menu"]').click();
    await page.waitForTimeout(150);
    await page.locator('#panel-menu [data-menu-command="move-zone"][data-menu-zone="right"]').click();
    await page.waitForTimeout(250);
    await expect(page.locator('#info-body-zone .panel[data-panel-id="search"]')).toHaveCount(1);
    await expect(page.locator('#sidebar-scroll .panel[data-panel-id="search"]')).toHaveCount(0);
  });

  test('Escape dismisses the menu and never closes a panel', async ({ page }) => {
    await page.goto('/');
    await page.locator('.panel[data-panel-id="search"] [data-action="panel-menu"]').click();
    await page.waitForTimeout(150);
    await expect(page.locator('#panel-menu')).toBeVisible();

    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);
    await expect(page.locator('#panel-menu')).toBeHidden();
    // Escape is a reflex key and closing changes persisted layout: it must cost nothing that
    // survives a reload.
    await expect(page.locator('.panel[data-panel-id="search"]')).toBeVisible();
    expect(await storedLayout(page)).toBeNull();
  });
});

test.describe('persistence', () => {
  test('survives a reload', async ({ page }) => {
    await page.goto('/');
    await page.locator('.panel[data-panel-id="graph-stats"] [data-action="panel-close"]').click();
    await page.locator('#sidebar .rail-toggle').click();
    await page.waitForTimeout(250);

    await page.reload();
    await page.waitForTimeout(400);
    await expect(page.locator('.panel[data-panel-id="graph-stats"]')).toBeHidden();
    await expect(page.locator('#sidebar')).toHaveClass(/zone--collapsed/);
  });

  test('stores structure and never a pixel measurement', async ({ page }) => {
    await page.goto('/');
    await page.locator('.panel[data-panel-id="graph-stats"] [data-action="panel-close"]').click();
    await page.waitForTimeout(200);
    const layout = await storedLayout(page);
    expect(layout.v).toBe(3);
    const numbers = JSON.stringify(layout).match(/:\s*-?\d+(\.\d+)?/g) || [];
    // The version is the only number a layout may contain.
    expect(numbers).toEqual([':3']);
  });

  test.describe('degrades to the default rather than half-applying', () => {
    for (const [label, value] of [
      ['unparseable', 'not json at all'],
      ['a wrong version', '{"v":99,"panels":[]}'],
      ['a valid shape with a hostile zone', '{"v":1,"zones":{},"panels":[{"id":"inspector","zone":"__proto__"}]}'],
    ]) {
      test(label, async ({ page }) => {
        await page.goto('/');
        await page.evaluate(([key, raw]) => localStorage.setItem(key, raw), [LAYOUT_KEY, value]);
        await page.reload();
        await page.waitForTimeout(400);

        // Every panel is where the default puts it, and nothing is missing.
        await expect(page.locator('.panel')).toHaveCount(8);
        await expect(page.locator('#sidebar-scroll .panel:not([hidden])')).toHaveCount(5);
        await expect(page.locator('#info-body-zone .panel[data-panel-id="inspector"]')).toBeVisible();
        await expect(page.locator('#dock .panel[data-panel-id="activity"]')).toBeVisible();
      });
    }
  });

  test('keeps working when storage refuses to accept anything', async ({ page }) => {
    // Private mode, a quota that is already full, or a sandboxed frame. The layout stops being
    // remembered; the UI must not stop working.
    await page.addInitScript(() => {
      const boom = () => { throw new Error('storage is unavailable'); };
      Object.defineProperty(window, 'localStorage', {
        configurable: true,
        get: () => ({ getItem: boom, setItem: boom, removeItem: boom, key: boom, length: 0 }),
      });
    });
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));

    await page.goto('/');
    await page.locator('.panel[data-panel-id="graph-stats"] [data-action="panel-close"]').click();
    await page.waitForTimeout(250);

    await expect(page.locator('.panel[data-panel-id="graph-stats"]')).toBeHidden();
    await expect(page.locator('.panel[data-panel-id="search"]')).toBeVisible();
    expect(errors).toEqual([]);
  });
});

test.describe('the rail identity marks', () => {
  test('carries one named mark per panel, and never drops the accessible name', async ({ page }) => {
    await page.goto('/');
    // When a column is collapsed the rail has no visible labels at all, so the mark IS the title.
    // A rail that dropped the accessible name would leave those panels unnameable.
    const marks = page.locator('#sidebar .rail-panel-btn');
    await expect(marks).toHaveCount(5);
    for (const id of PANELS.slice(0, 5)) {
      const mark = page.locator(`#sidebar .rail-panel-btn[data-rail-panel="${id}"]`);
      await expect(mark).toHaveCount(1);
      await expect(mark).not.toHaveAttribute('aria-label', '');
      // The mark itself must be a real glyph reference, not an empty box.
      await expect(mark.locator(`use[href="#i-p-${id}"]`)).toHaveCount(1);
    }
    await expect(page.locator('#info .rail-panel-btn[data-rail-panel="inspector"]')).toHaveCount(1);
  });

  test('shows open state with a shape and aria, never with colour alone', async ({ page }) => {
    await page.goto('/');
    const search = page.locator('#sidebar .rail-panel-btn[data-rail-panel="search"]');
    await expect(search).toHaveAttribute('aria-expanded', 'true');

    await page.locator('.panel[data-panel-id="search"] [data-action="panel-close"]').click();
    await page.waitForTimeout(200);
    await expect(search).toHaveAttribute('aria-expanded', 'false');

    // The accent bar is a SHAPE that exists nowhere else in the rail. Read it from the computed
    // pseudo-element rather than trusting the class.
    const bars = await page.evaluate(() => {
      const read = id => getComputedStyle(
        document.querySelector(`#sidebar .rail-panel-btn[data-rail-panel="${id}"]`), '::before',
      ).backgroundColor;
      return { closed: read('search'), open: read('node-types') };
    });
    expect(bars.closed).toBe('rgba(0, 0, 0, 0)');
    // CONTROL: an open panel must actually paint the bar, or "no bar when closed" is vacuous.
    expect(bars.open).not.toBe('rgba(0, 0, 0, 0)');
  });

  test('reopens a closed panel from its rail mark', async ({ page }) => {
    await page.goto('/');
    await page.locator('.panel[data-panel-id="graph-stats"] [data-action="panel-close"]').click();
    await page.waitForTimeout(200);
    await page.locator('#sidebar .rail-panel-btn[data-rail-panel="graph-stats"]').click();
    await page.waitForTimeout(250);
    await expect(page.locator('.panel[data-panel-id="graph-stats"]')).toBeVisible();
  });
});

test.describe('the dock capacity floor', () => {
  // A PREREQUISITE OF PERSISTENCE, not a polish item: the crushed arrangement is storable, so
  // without this one experiment would restore it on every launch, permanently.
  const fillDock = async (page, ids) => {
    for (const id of ids) {
      await page.locator(`.panel[data-panel-id="${id}"] [data-action="panel-menu"]`).click();
      await page.waitForTimeout(120);
      await page.locator('#panel-menu [data-menu-command="move-zone"][data-menu-zone="bottom"]').click();
      await page.waitForTimeout(180);
    }
  };
  const dockGeometry = page => page.evaluate(() => {
    const dock = document.getElementById('dock');
    const panels = [...dock.querySelectorAll('.panel:not([hidden])')];
    return {
      heights: panels.map(panel => Math.round(panel.getBoundingClientRect().height)),
      crushed: panels.filter(panel => panel.getBoundingClientRect().height < 32).length,
      scrolls: dock.scrollHeight > dock.clientHeight,
    };
  });

  test('keeps every docked panel taller than its own header, and scrolls past that', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');

    // ── RED: the defect reproduces through this same probe with the floor disabled ─────────────
    await page.addStyleTag({
      content: '.zone--bottom .panel{min-height:0 !important} #dock{overflow-y:hidden !important}',
    });
    await fillDock(page, ['search', 'node-types', 'edge-types']);
    const without = await dockGeometry(page);
    expect(without.crushed, 'the crushed-panel control did not reproduce, so the floor assertion proves nothing')
      .toBeGreaterThan(0);

    // ── GREEN: with the shipped rule, four occupants stay survivable ───────────────────────────
    await page.reload();
    await page.waitForTimeout(400);
    const withFloor = await dockGeometry(page);
    expect(withFloor.crushed).toBe(0);
    expect(Math.min(...withFloor.heights)).toBeGreaterThanOrEqual(72);
    expect(withFloor.scrolls).toBe(true);
  });
});

test('the commit action stays bounded when the Inspector is moved into the dock', async ({ page }) => {
  // A SAFETY PROPERTY, not a cosmetic one. `delete-node` deletes immediately with no confirmation,
  // and the weight hierarchy that keeps it distinguishable was measured in a 332px column. Moving
  // the Inspector into the dock puts the same row at
  // ~1188px, where an unbounded `flex: 1` primary sits a few pixels from that Delete.
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/');

  await page.locator('.panel[data-panel-id="inspector"] [data-action="panel-menu"]').click();
  await page.waitForTimeout(140);
  await page.locator('#panel-menu [data-menu-command="move-zone"][data-menu-zone="bottom"]').click();
  await page.waitForTimeout(220);
  await page.locator('#btn-new').click();
  await page.locator('#btn-modify').click();
  await page.waitForTimeout(250);
  await page.evaluate(() => {
    const host = document.querySelector('.doc-canvas') || document.getElementById('cy');
    const instance = host._cyreg.cy;
    const node = instance.nodes()[0];
    node.emit('tap');
    instance.$(node).select();
  });
  await page.waitForSelector('#node-editor', { timeout: 10_000 });
  await page.waitForTimeout(300);

  const geometry = () => page.evaluate(() => {
    const danger = document.querySelector('.editor-actions .btn.danger').getBoundingClientRect();
    const primary = document.querySelector('.editor-actions .btn.primary').getBoundingClientRect();
    return { primary: Math.round(primary.width), gap: Math.round(primary.left - danger.right) };
  });

  // ── RED: the hazard reproduces through this probe with the bound removed ──────────────────────
  await page.addStyleTag({
    content: '.editor-actions .btn.primary{max-width:none !important;margin-left:0 !important}',
  });
  await page.waitForTimeout(150);
  const unbounded = await geometry();
  expect(unbounded.primary, 'the unbounded case did not reproduce, so the bound proves nothing')
    .toBeGreaterThan(900);
  expect(unbounded.gap).toBeLessThan(20);

  // ── GREEN: with the shipped bound ─────────────────────────────────────────────────────────────
  await page.reload();
  await page.waitForTimeout(400);
  await page.locator('#btn-modify').click();
  await page.waitForTimeout(200);
  await page.evaluate(() => {
    const host = document.querySelector('.doc-canvas') || document.getElementById('cy');
    const instance = host._cyreg.cy;
    const node = instance.nodes()[0];
    node.emit('tap');
    instance.$(node).select();
  });
  await page.waitForSelector('#node-editor', { timeout: 10_000 });
  await page.waitForTimeout(300);
  const bounded = await geometry();
  expect(bounded.primary).toBeLessThanOrEqual(320);
  expect(bounded.gap).toBeGreaterThan(400);
});

// ══ DRAGGING A PANEL ═══════════════════════════════════════════════════════════════════════════
//
// The grip assertion above is COUPLED to this block. Drag is a PROGRESSIVE ENHANCEMENT over the `⋮`
// menu — every outcome here is reachable from that menu, which is what
// satisfies WCAG 2.5.7 rather than a bolted-on alternative.
test.describe('dragging a panel', () => {
  const headerBox = (page, id) => page.locator(`.panel[data-panel-id="${id}"] .panel-hd`).boundingBox();
  const zoneOf = (page, id) => page.evaluate(
    panelId => document.querySelector(`.panel[data-panel-id="${panelId}"]`).closest('.zone').id, id);
  const leftOrder = page => page.evaluate(
    () => [...document.querySelectorAll('#sidebar-scroll > .panel')].map(panel => panel.dataset.panelId));

  test('moves a panel to another zone, showing a carrier and a gap while it does', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');

    const from = await headerBox(page, 'node-catalog');
    const to = await page.locator('#info-body-zone').boundingBox();
    await page.mouse.move(from.x + 40, from.y + 16);
    await page.mouse.down();
    await page.mouse.move(from.x + 60, from.y + 40, { steps: 5 });
    await page.mouse.move(to.x + 150, to.y + 80, { steps: 10 });

    // Mid-gesture the feedback is a GAP (a shape that exists nowhere else) plus a carrier that is a
    // chip rather than the panel — dragging a full panel across a stage that is 46% chrome hides
    // the target being aimed at.
    const mid = await page.evaluate(() => ({
      marker: document.getElementById('panel-drop-marker') !== null,
      carrier: !document.getElementById('panel-carrier').hidden,
      carrierText: document.getElementById('panel-carrier').textContent,
      carrierWidth: Math.round(document.getElementById('panel-carrier').getBoundingClientRect().width),
      dimmed: document.querySelector('.panel--dragging')?.dataset.panelId ?? null,
    }));
    expect(mid.marker).toBe(true);
    expect(mid.carrier).toBe(true);
    expect(mid.carrierText).toBe('Node Catalog');
    expect(mid.carrierWidth).toBe(200);
    expect(mid.dimmed).toBe('node-catalog');

    await page.mouse.up();
    await page.waitForTimeout(250);
    expect(await zoneOf(page, 'node-catalog')).toBe('info');
    // And it is persisted, exactly as the menu route is — drag is not a second implementation.
    const stored = await storedLayout(page);
    expect(stored.panels.find(panel => panel.id === 'node-catalog').zone).toBe('right');
  });

  test('drops at the position released, not merely at the end', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    expect((await leftOrder(page))[0]).toBe('search');

    const from = await headerBox(page, 'edge-types');
    const first = await headerBox(page, 'search');
    await page.mouse.move(from.x + 40, from.y + 16);
    await page.mouse.down();
    await page.mouse.move(from.x + 60, from.y - 20, { steps: 5 });
    await page.mouse.move(first.x + 60, first.y + 2, { steps: 10 });
    await page.mouse.up();
    await page.waitForTimeout(250);

    // Appending would have left it last. A drag says exactly where, and landing anywhere else
    // would make the gesture a lie.
    expect((await leftOrder(page))[0]).toBe('edge-types');
  });

  test('keeps the gesture while the pointer is over the Cytoscape canvas', async ({ page }) => {
    // MEASURED, NOT ASSUMED. `#cy-wrap` is `role="application"` and Cytoscape owns its own pointer
    // handling, so "pointer capture should keep delivering to us" is exactly the kind of claim that
    // needs a measurement rather than confidence.
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    await page.locator('#btn-new').click();
    await page.waitForTimeout(400);

    const from = await headerBox(page, 'node-types');
    const canvas = await page.locator('#cy-wrap').boundingBox();
    await page.mouse.move(from.x + 40, from.y + 16);
    await page.mouse.down();
    await page.mouse.move(from.x + 60, from.y + 40, { steps: 5 });
    await page.mouse.move(canvas.x + canvas.width / 2, canvas.y + canvas.height / 2, { steps: 12 });

    const overCanvas = await page.evaluate(() => ({
      carrier: !document.getElementById('panel-carrier').hidden,
      dragging: document.body.classList.contains('panels-dragging'),
    }));
    expect(overCanvas.carrier).toBe(true);
    expect(overCanvas.dragging).toBe(true);
    await page.keyboard.press('Escape');
    await page.mouse.up();
  });

  test('Escape abandons the drag and leaves the layout exactly as it was', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    const before = await leftOrder(page);

    const from = await headerBox(page, 'node-types');
    const to = await page.locator('#info-body-zone').boundingBox();
    await page.mouse.move(from.x + 40, from.y + 16);
    await page.mouse.down();
    await page.mouse.move(from.x + 60, from.y + 40, { steps: 5 });
    await page.mouse.move(to.x + 150, to.y + 80, { steps: 10 });
    // CONTROL: the drag must really be in progress, or "Escape cancelled it" is vacuous.
    expect(await page.evaluate(() => document.body.classList.contains('panels-dragging'))).toBe(true);

    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);
    const after = await page.evaluate(() => ({
      dragging: document.body.classList.contains('panels-dragging'),
      carrierHidden: document.getElementById('panel-carrier').hidden,
      markerGone: document.getElementById('panel-drop-marker') === null,
    }));
    expect(after).toEqual({ dragging: false, carrierHidden: true, markerGone: true });
    await page.mouse.up();
    await page.waitForTimeout(150);
    expect(await leftOrder(page)).toEqual(before);
    expect(await zoneOf(page, 'node-types')).toBe('sidebar');
    // Escape must not have closed anything either — it is a reflex key.
    expect(await storedLayout(page)).toBeNull();
  });

  test('treats a press that barely moves as a click, not a drag', async ({ page }) => {
    // Without a threshold every click on a header that shifts one pixel under the finger becomes a
    // drag. It is the classic defect in hand-rolled dragging and cheap to prevent.
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    const before = await leftOrder(page);

    const from = await headerBox(page, 'edge-types');
    await page.mouse.move(from.x + 40, from.y + 16);
    await page.mouse.down();
    await page.mouse.move(from.x + 42, from.y + 17);
    await page.mouse.up();
    await page.waitForTimeout(200);

    expect(await leftOrder(page)).toEqual(before);
    expect(await page.evaluate(() => document.body.classList.contains('panels-dragging'))).toBe(false);
  });

  test('a press on a header control stays a control press', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    // The ✕ must close, not begin a drag, even though it lives inside the drag surface.
    await page.locator('.panel[data-panel-id="graph-stats"] [data-action="panel-close"]').click();
    await page.waitForTimeout(200);
    await expect(page.locator('.panel[data-panel-id="graph-stats"]')).toBeHidden();
    expect(await page.evaluate(() => document.body.classList.contains('panels-dragging'))).toBe(false);
  });

  test('offers the absent dock a target, since otherwise drag would be weaker than the menu', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    await page.locator('.panel[data-panel-id="activity"] [data-action="panel-close"]').click();
    await page.waitForTimeout(250);
    await expect(page.locator('#dock')).toBeHidden();
    // CONTROL: the band is NOT standing chrome — it must be invisible until a drag is under way.
    await expect(page.locator('#dock-drop-band')).toBeHidden();

    const from = await headerBox(page, 'search');
    await page.mouse.move(from.x + 40, from.y + 16);
    await page.mouse.down();
    await page.mouse.move(from.x + 60, from.y + 40, { steps: 5 });
    await expect(page.locator('#dock-drop-band')).toBeVisible();

    const band = await page.locator('#dock-drop-band').boundingBox();
    await page.mouse.move(band.x + band.width / 2, band.y + band.height / 2, { steps: 8 });
    await page.mouse.up();
    await page.waitForTimeout(300);

    expect(await zoneOf(page, 'search')).toBe('dock');
    await expect(page.locator('#dock')).toBeVisible();
    await expect(page.locator('#dock-drop-band')).toBeHidden();
  });
});

// ══ THE SHORT FORM ═════════════════════════════════════════════════════════════════════════════
test.describe('the short form', () => {
  const catalogFixture = Array.from({ length: 12 }, (_, index) => ({
    behavior: `catalog-${index}`,
    displayName: index === 0 ? 'Alpha template' : index === 1 ? 'Beta template' : `Catalog type ${index}`,
    category: index % 2 === 0 ? 'core' : 'extension',
    description: `Creates catalog type ${index}`,
    visualType: 'flow',
    agentic: false,
    capabilities: [],
    properties: [],
  }));
  const serveCatalog = async page => {
    await page.route('**/v1/node-types', route => route.fulfill({
      status: 200,
      contentType: 'application/json; charset=utf-8',
      body: JSON.stringify(catalogFixture),
    }));
    await page.route('**/v1/events', route => route.fulfill({ status: 204, body: '' }));
  };
  const shorten = async (page, id) => {
    await page.locator(`.panel[data-panel-id="${id}"] [data-action="panel-menu"]`).click();
    await page.waitForTimeout(130);
    await page.locator('#panel-menu [data-menu-command="shorten"]').click();
    await page.waitForTimeout(200);
  };
  const column = page => page.evaluate(() => {
    const scroll = document.getElementById('sidebar-scroll');
    const statsPanel = document.querySelector('.panel[data-panel-id="graph-stats"]');
    const stats = statsPanel.getBoundingClientRect();
    const originalScrollTop = scroll.scrollTop;
    scroll.scrollTop = scroll.scrollHeight;
    const scrollBounds = scroll.getBoundingClientRect();
    const statsAtEnd = statsPanel.getBoundingClientRect();
    const endReachable = statsAtEnd.bottom <= scrollBounds.bottom + 1;
    scroll.scrollTop = originalScrollTop;
    return {
      overflow: Math.max(0, scroll.scrollHeight - scroll.clientHeight),
      statsAboveFold: stats.bottom <= window.innerHeight,
      endReachable,
    };
  });

  test('closes the measured palette overflow', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    await page.waitForTimeout(300);

    // THE CONTROL IS THE DEFECT ITSELF. If the column did not overflow to begin with, "it no longer
    // overflows" would be satisfied by doing nothing at all.
    const before = await column(page);
    expect(before.overflow, 'the measured overflow did not reproduce, so the compact-form assertion proves nothing')
      .toBeGreaterThan(0);
    expect(before.statsAboveFold).toBe(false);

    await shorten(page, 'node-types');
    await shorten(page, 'edge-types');

    const after = await column(page);
    expect(after.overflow).toBe(0);
    expect(after.statsAboveFold).toBe(true);
  });

  test('reflows the list into a wrapped grid, which is the only reason it saves anything', async ({ page }) => {
    // ICON-ONLY ALONE SAVES NOTHING: stripping labels makes rows NARROWER, not SHORTER. The saving
    // exists only if the layout also reflows, so assert the reflow rather than the hidden label.
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    const rowsBefore = await page.evaluate(() => {
      const items = [...document.querySelectorAll('#leg-nodes .li')];
      return new Set(items.map(item => Math.round(item.getBoundingClientRect().top))).size;
    });
    await shorten(page, 'node-types');
    const grid = await page.evaluate(() => {
      const items = [...document.querySelectorAll('#leg-nodes .li')];
      const rows = new Set(items.map(item => Math.round(item.getBoundingClientRect().top)));
      const first = items[0].getBoundingClientRect();
      return { count: items.length, rows: rows.size, cell: Math.round(first.width) };
    });
    // Ten items on ten rows becomes ten items on two.
    expect(rowsBefore).toBe(10);
    expect(grid.count).toBe(10);
    expect(grid.rows).toBe(2);
    // The cell clears the 24px WCAG 2.5.8 floor that the 23px full-form row misses by one pixel.
    expect(grid.cell).toBe(32);
  });

  test('never lets colour become the only carrier of a node type', async ({ page }) => {
    // `agent` and `flow` already share #58a6ff, so colour cannot separate them even in the full
    // form. With the label gone the chip must carry the same mark the canvas draws.
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    await shorten(page, 'node-types');

    const marks = await page.evaluate(() => [...document.querySelectorAll('#leg-nodes .li')].map(item => ({
      name: item.getAttribute('aria-label'),
      glyph: item.querySelector('.li-glyph')?.textContent ?? '',
      glyphShown: getComputedStyle(item.querySelector('.li-glyph')).display !== 'none',
      labelHidden: getComputedStyle(item.querySelector('.li-label')).display === 'none',
    })));
    expect(marks).toHaveLength(10);
    for (const mark of marks) {
      expect(mark.name, 'a chip lost its accessible name').toBeTruthy();
      expect(mark.glyph, `${mark.name} has no glyph`).not.toBe('');
      expect(mark.glyphShown).toBe(true);
      expect(mark.labelHidden).toBe(true);
    }
  });

  test('gives the Node Catalog a real icon-only grid without losing identity or focus', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await serveCatalog(page);
    await page.goto('/');
    await page.waitForFunction(() => document.querySelectorAll('#node-catalog [data-catalog-add]').length > 0);

    const full = await page.evaluate(() => {
      const catalog = document.getElementById('node-catalog').getBoundingClientRect();
      const first = document.querySelector('#node-catalog .catalog-item').getBoundingClientRect();
      return { catalogHeight: catalog.height, itemWidth: first.width };
    });
    await shorten(page, 'node-catalog');
    const compact = await page.evaluate(() => {
      const catalog = document.getElementById('node-catalog').getBoundingClientRect();
      const items = [...document.querySelectorAll('#node-catalog .catalog-item')];
      const rows = new Set(items.map(item => Math.round(item.getBoundingClientRect().top)));
      return {
        catalogHeight: catalog.height,
        rows: rows.size,
        items: items.map(item => ({
          width: item.getBoundingClientRect().width,
          height: item.getBoundingClientRect().height,
          copyDisplay: getComputedStyle(item.querySelector('.catalog-item-copy')).display,
          iconDisplay: getComputedStyle(item.querySelector('.catalog-item-icon')).display,
          name: item.getAttribute('aria-label'),
          tooltip: item.dataset.tooltip,
        })),
      };
    });
    expect(compact.items).toHaveLength(catalogFixture.length);
    expect(compact.catalogHeight).toBeLessThan(full.catalogHeight);
    expect(compact.items[0].width).toBeLessThan(full.itemWidth);
    expect(compact.rows).toBeLessThan(catalogFixture.length);
    for (const item of compact.items) {
      expect(item.width).toBe(32);
      expect(item.height).toBe(32);
      expect(item.copyDisplay).toBe('none');
      expect(item.iconDisplay).not.toBe('none');
      expect(item.name).toMatch(/^Add .+ node, category (core|extension)$/);
      expect(item.tooltip).toMatch(/^Add .+ · (core|extension)$/);
    }

    const first = page.locator('[data-catalog-add="catalog-0"]');
    await first.focus();
    await page.keyboard.press('Tab');
    await expect(page.locator('[data-catalog-add="catalog-1"]')).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(first).toBeFocused();
    await expect(first).toHaveCSS('outline-width', '2px');
    await expect(first).toHaveAttribute('aria-label', 'Add Alpha template node, category core');
    await expect(first).toHaveAttribute('data-tooltip', 'Add Alpha template · core');
    await expect(first).not.toHaveAttribute('title', /.+/);
    await page.keyboard.press('Enter');
    await expect(page.locator('#info-title')).toHaveText('Add Alpha template');
  });

  test('persists Shorten through reload, inserts by keyboard, then Lengthen restores copy and layout', async ({ page }) => {
    await page.setViewportSize({ width: 1180, height: 800 });
    await serveCatalog(page);
    await page.goto('/');
    await page.waitForFunction(() => document.querySelectorAll('#node-catalog [data-catalog-add]').length > 0);

    const order = () => page.locator('#node-catalog .catalog-item').evaluateAll(items =>
      items.map(item => item.dataset.catalogAdd));
    const originalOrder = await order();
    await shorten(page, 'node-catalog');
    await expect(page.locator('.panel[data-panel-id="node-catalog"]'))
      .toHaveAttribute('data-panel-state', 'short');

    await page.reload();
    await page.waitForFunction(() => document.querySelectorAll('#node-catalog [data-catalog-add]').length > 0);
    await expect(page.locator('.panel[data-panel-id="node-catalog"]'))
      .toHaveAttribute('data-panel-state', 'short');
    expect(await order()).toEqual(originalOrder);
    await expect(page.locator('[data-catalog-add="catalog-0"] .catalog-item-copy')).toBeHidden();

    await page.locator('#btn-new').click();
    await page.locator('#btn-modify').click();
    const before = await page.evaluate(() => window.cy.$('node').length);
    const beta = page.locator('[data-catalog-add="catalog-1"]');
    await beta.focus();
    await page.keyboard.press('Space');
    await expect(page.locator('#info-title')).toHaveText('Add Beta template');
    await page.locator('#node-editor button[type="submit"]').click();
    await expect.poll(() => page.evaluate(() => window.cy.$('node').length)).toBe(before + 1);

    await page.locator('.panel[data-panel-id="node-catalog"] [data-action="panel-menu"]').click();
    await page.locator('#panel-menu [data-menu-command="lengthen"]').click();
    await expect(page.locator('.panel[data-panel-id="node-catalog"]'))
      .toHaveAttribute('data-panel-state', 'full');
    await expect(page.locator('[data-catalog-add="catalog-0"] .catalog-item-copy')).toBeVisible();
    await expect(page.locator('[data-catalog-add="catalog-0"] b')).toHaveText('Alpha template');
    await expect(page.locator('[data-catalog-add="catalog-0"] small')).toHaveText('core');
    expect(await page.locator('[data-catalog-add="catalog-0"]').evaluate(item =>
      Math.round(item.getBoundingClientRect().width))).toBeGreaterThan(32);
    expect((await storedLayout(page)).panels.find(panel => panel.id === 'node-catalog').short).toBe(false);

    const search = page.locator('#search-inp');
    await search.fill('Beta');
    await expect(search).toHaveValue('Beta');
    await page.locator('[data-catalog-add="catalog-11"]').scrollIntoViewIfNeeded();
    await expect(page.locator('[data-catalog-add="catalog-11"]')).toBeVisible();
    expect(await order()).toEqual(originalOrder);
  });

  test('survives a reload, while the descriptor before dimensions degrades to defaults', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    await shorten(page, 'edge-types');
    await expect(page.locator('.panel[data-panel-id="edge-types"]')).toHaveAttribute('data-panel-state', 'short');

    await page.reload();
    await page.waitForTimeout(400);
    await expect(page.locator('.panel[data-panel-id="edge-types"]')).toHaveAttribute('data-panel-state', 'short');

    // Dimensions change the schema. A v1 descriptor is invalid as a WHOLE rather than being
    // half-applied beside v3 geometry.
    await page.evaluate(() => localStorage.setItem('ravenroot.ui.panel-layout', JSON.stringify({
      v: 1,
      zones: { left: { collapsed: false }, right: { collapsed: false }, bottom: { collapsed: false } },
      panels: [
        { id: 'search', zone: 'left', closed: false },
        { id: 'node-types', zone: 'left', closed: true },
        { id: 'node-catalog', zone: 'left', closed: false },
        { id: 'edge-types', zone: 'left', closed: false },
        { id: 'graph-stats', zone: 'left', closed: false },
        { id: 'inspector', zone: 'right', closed: false },
        { id: 'activity', zone: 'bottom', closed: false },
      ],
    })));
    await page.reload();
    await page.waitForTimeout(400);
    await expect(page.locator('.panel[data-panel-id="node-types"]')).toBeVisible();
    await expect(page.locator('.panel[data-panel-id="edge-types"]')).toHaveAttribute('data-panel-state', 'full');
  });

  // ── SEARCH AND GRAPH STATS GET A SHORT FORM TOO ────────────────────────────────────────────
  // With every palette shortened, 1180x800 still overflowed by 77px and could not close further:
  // Search and
  // Graph Stats offered no short command at all, so 77px was a floor, not a starting point. This
  // gives both a short form so the column can close the rest of the way.
  const shortenAll = async page => {
    for (const id of ['node-types', 'edge-types', 'node-catalog', 'search', 'graph-stats']) {
      await shorten(page, id);
    }
  };

  test('closes the remaining 1180x800 overflow', async ({ page }) => {
    await page.setViewportSize({ width: 1180, height: 800 });
    await page.goto('/');
    await page.waitForTimeout(300);

    // THE CONTROL IS THE DEFECT ITSELF: the column MUST be shown overflowing before the correction
    // is asserted, so "it no longer overflows"
    // cannot pass vacuously on a build that never overflowed here.
    const before = await column(page);
    expect(before.overflow, 'the 1180x800 overflow did not reproduce, so the compact-form assertion proves nothing')
      .toBeGreaterThan(0);
    expect(before.statsAboveFold).toBe(false);

    await shortenAll(page);

    const after = await column(page);
    expect(after.overflow).toBe(0);
    expect(after.statsAboveFold).toBe(true);
  });

  test('offers Search and Graph Stats a Shorten command', async ({ page }) => {
    await page.goto('/');
    for (const id of ['search', 'graph-stats']) {
      await page.locator(`.panel[data-panel-id="${id}"] [data-action="panel-menu"]`).click();
      await page.waitForTimeout(130);
      const labels = await page.locator('#panel-menu .popover-item').allTextContents();
      expect(labels, `${id} does not offer Shorten`).toContain('Shorten');
      await page.keyboard.press('Escape');
      await page.waitForTimeout(120);
    }
  });

  test('the compact forms keep every stat and the full query reachable, not colour- or hover-only', async ({ page }) => {
    // stays in force: a compact form that only reaches its content through hover or through
    // colour would be a new accessibility regression wearing the shape of a space saving.
    await page.setViewportSize({ width: 1180, height: 800 });
    await page.goto('/');
    await shorten(page, 'graph-stats');
    await shorten(page, 'search');

    // Graph Stats: every row that existed in the full form still has its label and its value as
    // TEXT after reflow — nothing here is reduced to an icon or a swatch the way the palette rule
    // reduces `.li` labels, because a stat has no icon to fall back to.
    const rowsShort = await page.evaluate(() => [...document.querySelectorAll('#graph-stats .graph-stat-line')]
      .map(row => row.textContent.trim()));
    expect(rowsShort.length).toBeGreaterThan(0);
    for (const row of rowsShort) expect(row.length).toBeGreaterThan(0);

    // Search: full placeholder survives, and the input stays focusable and typeable — a search
    // box that got shorter by getting narrower or by losing its hit target would fail the same
    // "narrower is not shorter" rule the palette comment states, just on the other axis.
    const input = page.locator('#search-inp');
    await expect(input).toHaveAttribute('placeholder', 'Search nodes…');
    await input.focus();
    await expect(input).toBeFocused();
    await input.fill('start');
    await expect(input).toHaveValue('start');
  });

  test('keeps the compact panel content reachable with the narrow runtime row', async ({ page }) => {
    // The existing shorten set uses node-types and edge-types only, never touching
    // the two new short forms, because THOSE viewports must remain closed through their existing
    // mechanism rather than requiring the new short forms there.
    const resetTo = async (width, height) => {
      await page.setViewportSize({ width, height });
      await page.goto('/');
      // Each call must start from the FULL form, not from whatever the previous viewport's
      // `shorten` calls persisted to localStorage — otherwise the second and third call find
      // `node-types` already short, the menu offers Lengthen instead, and the `shorten` helper's
      // `[data-menu-command="shorten"]` locator never resolves.
      await page.evaluate(() => localStorage.removeItem('ravenroot.ui.panel-layout'));
      await page.reload();
      await page.waitForTimeout(300);
    };
    const at = async (width, height) => {
      await resetTo(width, height);
      await shorten(page, 'node-types');
      await shorten(page, 'edge-types');
      return column(page);
    };

    await resetTo(1280, 800);
    const before1280 = await column(page);
    await shorten(page, 'node-types');
    await shorten(page, 'edge-types');
    const at1280 = await column(page);
    // REDUNDANT WITH panel-system.spec.js's block, on purpose: this is the SAME
    // measurement (same two panels, same viewport, same `column`/`sidebarOverflow` computation)
    // written a second time because it lives in a different describe block. Kept in sync
    // deliberately rather than deduplicated — the two blocks test different things around it
    // (the narrow-runtime-row story here, the per-viewport pin there) — but a reader who
    // does not know that would reasonably think they cover two different claims. They do not.
    //
    // The 31px cap was a rendering measurement, not a declared constant. Replacing it with
    // `after <= before * 0.35` looks like the same relative technique
    // that works for the decomposition below. It is NOT: a width sweep (1180 vs 1220-1400, product
    // unchanged) shows this ratio at 0.4322 vs 0.2353 — one extra palette-icon wrap row roughly
    // DOUBLES it, because the numerator moves +104 across that boundary while the denominator
    // moves only +47. The ratio amplifies host drift instead of absorbing it, unlike the
    // decomposition fractions below, which stayed byte-identical across three widths. No fixed cap
    // can be both tight and safe against that amplification, so the ratio is abandoned here rather
    // than re-tuned a second time. What actually holds, host to host: shortening the two palettes
    // measurably reduces the overflow (never leaves it unchanged or larger), and the bottom of the
    // column stays reachable — endReachable is the genuinely host-independent half; the reduction
    // check was the other half, and at the time it was written it was intentionally NOT a
    // magnitude claim.
    //
    // A magnitude form does exist: `before.overflow - after.overflow` isolates
    // exactly the height these two panels give up between full and short form, because every
    // OTHER panel in the column contributes the identical number to both terms and cancels in the
    // subtraction — measured invariant at 325px across an 11-point width sweep (1200-1400), an
    // 11-point height sweep (700-900), where the ratio this comment already rejected ranges 0 to
    // 0.3810, and four perturbations of panels outside the shortened set. `after.overflow` alone
    // does NOT share this invariance — it moves freely with untouched panels' font metrics (e.g.
    // `#graph-stats`, 35 to 175 across a line-height sweep) — it is specifically the SUBTRACTION
    // that cancels that movement, not either term alone. Two downward channels bound the margin:
    // the shortened panels' own full-form `.li` rows (styles.css:811, no explicit `line-height`,
    // floors at 21px/row for node rows via `.li-dot` but NOT for edge rows via the thinner
    // `.li-line`/`.li-dash`), and `after` clamping at 0. Both measured to their own limit rather
    // than assumed. Kept in sync with the block's '1280x800' test, which carries the full
    // sweep, both channels' measurements, and the cross-check with reproducible
    // mutations against FLOOR_OVERFLOW_MIN and the decomposition fractions.
    expect(before1280.overflow, 'did not reproduce overflow at 1280x800 before shortening').toBeGreaterThan(0);
    expect(at1280.overflow, 'shortening the two palettes did not reduce the overflow at all').toBeLessThan(before1280.overflow);
    expect(before1280.overflow - at1280.overflow, 'the two palettes saved far less height than their short form should — '
      + 'see the 1280x800 test for the sweep behind this floor').toBeGreaterThanOrEqual(200);
    expect(at1280.endReachable).toBe(true);

    const at1440 = await at(1440, 900);
    expect(at1440.overflow).toBe(0);
    expect(at1440.statsAboveFold).toBe(true);

    const at1920 = await at(1920, 1080);
    expect(at1920.overflow).toBe(0);
    expect(at1920.statsAboveFold).toBe(true);
  });
});

// ══ A SHORT STACK CAN BECOME ONE INLINE CELL ══════════════════════════════════════════════════
test.describe('compact side stacks', () => {
  const shortenable = ['search', 'node-types', 'node-catalog', 'edge-types', 'graph-stats'];
  const shortenAll = async page => {
    for (const id of shortenable) await shortenPanel(page, id);
  };

  test('changes density without changing width and gives every compact control real geometry', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    for (const id of shortenable.slice(0, -1)) await shortenPanel(page, id);
    await expect(page.locator('#sidebar')).not.toHaveClass(/zone--compact/);
    expect(Math.round(await page.locator('#sidebar').evaluate(element => element.getBoundingClientRect().width)))
      .toBe(224);

    await shortenPanel(page, shortenable.at(-1));
    await expect(page.locator('#sidebar')).toHaveClass(/zone--compact/);
    const geometry = await page.evaluate(() => {
      const box = selector => document.querySelector(selector).getBoundingClientRect();
      const style = selector => getComputedStyle(document.querySelector(selector));
      return {
        zone: Math.round(box('#sidebar').width),
        rail: Math.round(box('#sidebar .rail').width),
        body: Math.round(box('#sidebar-scroll').width),
        cell: Math.round(box('#li-node-start').width),
        header: Math.round(box('[data-panel-id="node-types"] .panel-hd').height),
        grip: Math.round(box('[data-panel-id="node-types"] .panel-grip').width),
        gripOpacity: style('[data-panel-id="node-types"] .panel-grip').opacity,
        menu: [Math.round(box('[data-panel-id="node-types"] .panel-menu').width),
          Math.round(box('[data-panel-id="node-types"] .panel-menu').height)],
        close: [Math.round(box('[data-panel-id="node-types"] .panel-close').width),
          Math.round(box('[data-panel-id="node-types"] .panel-close').height)],
        closeDisplay: style('[data-panel-id="node-types"] .panel-close').display,
        titleWidth: Math.round(box('[data-panel-id="node-types"] .panel-title').width),
        nodeColumns: new Set([...document.querySelectorAll('#leg-nodes .li')]
          .map(item => Math.round(item.getBoundingClientRect().left))).size,
        xOverflow: document.documentElement.scrollWidth - innerWidth,
      };
    });
    expect(geometry).toEqual({
      zone: 224, rail: 28, body: 195, cell: 32, header: 32, grip: 24, gripOpacity: '1',
      menu: [24, 24], close: [24, 24], closeDisplay: 'flex', titleWidth: 1,
      nodeColumns: 5, xOverflow: 0,
    });
    expect((await storedLayout(page)).zones.left.dimension).toBeNull();

    const widePath = testInfo.outputPath('short-panels-wide-zoom-100.png');
    await page.screenshot({ path: widePath, fullPage: true });
    await testInfo.attach('short-panels-wide-zoom-100', { path: widePath, contentType: 'image/png' });

    const search = page.locator('#search-inp');
    await search.fill('Start node');
    await expect(search).toHaveValue('Start node');
    await search.press('ControlOrMeta+A');
    await search.fill('End');
    await expect(search).toHaveValue('End');
    expect(await page.locator('#graph-stats').evaluate(element => element.scrollWidth <= element.clientWidth))
      .toBe(true);

    const menu = page.locator('[data-panel-id="node-types"] [data-action="panel-menu"]');
    await menu.click();
    await expect(page.locator('#panel-menu [data-menu-command="lengthen"]')).toBeVisible();
    await expect(page.locator('#panel-menu [data-menu-command="close"]')).toBeVisible();
    await expect(page.locator('#panel-menu [data-menu-command="move-zone"]')).toHaveCount(2);
  });

  test('recomputes for close, reopen and cross-zone blockers without persisting another mode', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    await shortenAll(page);
    await page.locator('[data-panel-id="graph-stats"] [data-action="panel-menu"]').click();
    await page.locator('#panel-menu [data-menu-command="close"]').click();
    await expect(page.locator('#sidebar')).toHaveClass(/zone--compact/);
    await page.locator('#sidebar [data-rail-panel="graph-stats"]').click();
    await expect(page.locator('#sidebar')).toHaveClass(/zone--compact/);

    await page.locator('[data-panel-id="inspector"] [data-action="panel-menu"]').click();
    await page.locator('#panel-menu [data-menu-command="move-zone"][data-menu-zone="left"]').click();
    await expect(page.locator('#sidebar')).not.toHaveClass(/zone--compact/);
    expect(await page.locator('#sidebar').evaluate(element => element.getBoundingClientRect().width))
      .toBeGreaterThanOrEqual(148);
    await page.locator('[data-panel-id="inspector"] [data-action="panel-menu"]').click();
    await page.locator('#panel-menu [data-menu-command="move-zone"][data-menu-zone="right"]').click();
    await expect(page.locator('#sidebar')).toHaveClass(/zone--compact/);

    const stored = await storedLayout(page);
    expect(stored.v).toBe(3);
    expect(stored.zones.left).toEqual({ collapsed: false, maximised: false, dimension: null });
    expect(JSON.stringify(stored)).not.toMatch(/compact/i);
  });

  test('projects either side and keeps the visible compact grip draggable', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.addInitScript(key => localStorage.setItem(key, JSON.stringify({
      v: 3,
      zones: {
        left: { collapsed: false, maximised: false, dimension: null },
        right: { collapsed: false, maximised: false, dimension: null },
        bottom: { collapsed: false, maximised: false, dimension: null },
      },
      panels: [
        { id: 'inspector', zone: 'left', closed: false, short: false, size: null },
        { id: 'search', zone: 'right', closed: false, short: true, size: null },
        { id: 'node-types', zone: 'right', closed: false, short: true, size: null },
        { id: 'node-catalog', zone: 'right', closed: false, short: true, size: null },
        { id: 'edge-types', zone: 'right', closed: false, short: true, size: null },
        { id: 'graph-stats', zone: 'right', closed: false, short: true, size: null },
        { id: 'activity', zone: 'bottom', closed: false, short: false, size: null },
      ],
    })), LAYOUT_KEY);
    await page.goto('/');
    await expect(page.locator('#info')).toHaveClass(/zone--compact/);
    expect(Math.round(await page.locator('#info').evaluate(element => element.getBoundingClientRect().width)))
      .toBe(360);
    await expect(page.locator('#sidebar')).not.toHaveClass(/zone--compact/);

    const grip = await page.locator('[data-panel-id="node-types"] .panel-grip').boundingBox();
    const target = await page.locator('[data-panel-id="graph-stats"]').boundingBox();
    // The dotted grip remains visible and owns usable pixels beyond the inner-edge hover strip.
    await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2);
    await page.mouse.down();
    await page.mouse.move(grip.x + grip.width + 8, grip.y + grip.height + 12, { steps: 3 });
    await expect(page.locator('#panel-carrier')).toBeVisible();
    await page.mouse.move(target.x + 12, target.y + target.height - 2, { steps: 8 });
    await page.mouse.up();
    await expect(page.locator('#info')).toHaveClass(/zone--compact/);
    // Open panels only. The right column also holds the closed Assistant, which is not on
    // screen and therefore cannot be what a drop "landed after" — asserting against the raw zone
    // order would be measuring a panel the user cannot see.
    const rightOrder = (await storedLayout(page)).panels
      .filter(panel => panel.zone === 'right' && !panel.closed)
      .map(panel => panel.id);
    expect(rightOrder.at(-1)).toBe('node-types');
  });

  test('preserves chosen width through density, reload and Collapse, then clamps pointer and keyboard at the adaptive floor', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    const sidebar = page.locator('#sidebar');
    const splitter = page.locator('[data-layout-splitter="left"]');
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(224);

    await splitter.focus();
    await page.keyboard.press('ArrowLeft');
    const chosenWidth = Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width));
    expect(chosenWidth).toBe(192);
    const chosenShare = (await storedLayout(page)).zones.left.dimension;
    expect(chosenShare).toBeGreaterThan(0);

    for (const id of shortenable) {
      await shortenPanel(page, id);
      expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(chosenWidth);
    }
    await expect(sidebar).toHaveClass(/zone--compact/);
    expect((await storedLayout(page)).zones.left.dimension).toBeCloseTo(chosenShare, 8);

    await page.locator('[data-panel-id="search"] [data-action="panel-menu"]').click();
    await page.locator('#panel-menu [data-menu-command="lengthen"]').click();
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(chosenWidth);
    expect((await storedLayout(page)).zones.left.dimension).toBeCloseTo(chosenShare, 8);
    await page.locator('[data-panel-id="search"] [data-action="panel-menu"]').click();
    await page.locator('#panel-menu [data-menu-command="shorten"]').click();
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(chosenWidth);
    await page.reload();
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(chosenWidth);
    expect((await storedLayout(page)).zones.left.dimension).toBeCloseTo(chosenShare, 8);

    await page.locator('#sidebar .rail-toggle').click();
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(28);
    expect((await storedLayout(page)).zones.left.dimension).toBeCloseTo(chosenShare, 8);
    await page.locator('#sidebar .rail-toggle').click();
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(chosenWidth);

    const splitterBox = await splitter.boundingBox();
    await page.mouse.move(splitterBox.x + splitterBox.width / 2, splitterBox.y + 100);
    await page.mouse.down();
    await page.mouse.move(splitterBox.x - 500, splitterBox.y + 100, { steps: 4 });
    await page.mouse.up();
    const minimum = Math.round(await sidebar.evaluate(element => Number.parseFloat(getComputedStyle(element).minWidth)));
    expect(minimum).toBe(101);
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(minimum);
    const columns = () => page.locator('#leg-nodes .li').evaluateAll(items =>
      new Set(items.map(item => Math.round(item.getBoundingClientRect().left))).size);
    const statsContainment = () => page.evaluate(() => {
      const stats = document.getElementById('graph-stats');
      const content = stats.closest('.panel-content');
      const rows = [...stats.querySelectorAll('.graph-stat-line')];
      const statsBox = stats.getBoundingClientRect();
      const contentBox = content.getBoundingClientRect();
      const measuredRows = rows.map(row => {
        const box = row.getBoundingClientRect();
        return {
          text: row.textContent.trim(),
          fits: row.scrollWidth <= row.clientWidth
            && box.left >= statsBox.left && box.right <= statsBox.right
            && box.left >= contentBox.left && box.right <= contentBox.right,
        };
      });
      return {
        contentFits: content.scrollWidth <= content.clientWidth,
        statsFits: stats.scrollWidth <= stats.clientWidth,
        rows: measuredRows,
        lastFits: measuredRows.at(-1)?.fits === true,
        longestFits: measuredRows.toSorted((a, b) => b.text.length - a.text.length)[0]?.fits === true,
      };
    });
    const expectStatsContained = geometry => {
      expect(geometry.contentFits).toBe(true);
      expect(geometry.statsFits).toBe(true);
      expect(geometry.rows.length).toBeGreaterThan(0);
      expect(geometry.rows.every(row => row.fits), geometry.rows).toBe(true);
      expect(geometry.lastFits).toBe(true);
      expect(geometry.longestFits).toBe(true);
    };
    expect(await columns()).toBe(1);
    expectStatsContained(await statsContainment());
    await expect(page.locator('[data-panel-id="node-types"] .panel-menu')).toBeVisible();
    await expect(page.locator('[data-panel-id="node-types"] .panel-close')).toBeVisible();
    await expect(page.locator('[data-panel-id="node-types"] .panel-grip')).toBeVisible();

    await splitter.focus();
    await page.keyboard.press('ArrowLeft');
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(minimum);
    expectStatsContained(await statsContainment());
    await page.keyboard.press('ArrowRight');
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(minimum + 32);
    expect(await columns()).toBe(2);
    await page.keyboard.press('ArrowRight');
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(minimum + 64);
    expect(await columns()).toBe(3);

    const adaptivePath = testInfo.outputPath('short-panels-adaptive-zoom-100.png');
    await page.screenshot({ path: adaptivePath, fullPage: true });
    await testInfo.attach('short-panels-adaptive-zoom-100', { path: adaptivePath, contentType: 'image/png' });

    await splitter.focus();
    await page.keyboard.press('Home');
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(224);
    expect((await storedLayout(page)).zones.left.dimension).toBeNull();
    await page.locator('#sidebar .rail-index').click();
    await page.locator('[data-index-reset]').click();
    expect((await storedLayout(page)).panels.every(panel => panel.short === false)).toBe(true);
  });

  test('keeps labelled geometry on coarse/no-hover input without erasing compact intent', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 900, height: 700 }, hasTouch: true });
    const page = await context.newPage();
    await page.goto('/');
    await shortenAll(page);
    await expect(page.locator('#sidebar')).toHaveClass(/zone--compact/);
    expect(await page.evaluate(() => ({
      coarse: matchMedia('(pointer: coarse)').matches,
      noHover: matchMedia('(hover: none)').matches,
      width: Math.round(document.getElementById('sidebar').getBoundingClientRect().width),
    }))).toEqual({ coarse: true, noHover: true, width: 188 });
    await expect(page.locator('#li-node-start .li-label')).toBeVisible();
    await expect(page.locator('[data-panel-id="node-types"] .panel-close')).toBeVisible();
    expect((await storedLayout(page)).zones.left.dimension).toBeNull();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBe(0);
    await context.close();
  });

  test('keeps compact controls and content usable in forced colors', async ({ browser }, testInfo) => {
    const context = await browser.newContext({
      viewport: { width: 1180, height: 800 },
      forcedColors: 'active',
    });
    const page = await context.newPage();
    await page.goto('/');
    await shortenAll(page);
    await expect(page.locator('#sidebar')).toHaveClass(/zone--compact/);
    const sidebar = page.locator('#sidebar');
    const splitter = page.locator('[data-layout-splitter="left"]');
    await splitter.focus();
    await page.keyboard.press('ArrowLeft');
    await page.keyboard.press('ArrowLeft');
    await page.keyboard.press('ArrowLeft');
    expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(101);

    const ownership = await page.evaluate(() => [...document.querySelectorAll(
      '#sidebar .panel:not([hidden]) .panel-grip, #sidebar .panel:not([hidden]) .panel-menu, '
      + '#sidebar .panel:not([hidden]) .panel-close',
    )].map(control => {
      const box = control.getBoundingClientRect();
      const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
      return {
        panel: control.closest('.panel').dataset.panelId,
        kind: control.classList.contains('panel-grip') ? 'grip'
          : control.classList.contains('panel-menu') ? 'menu' : 'close',
        owned: hit === control || control.contains(hit),
      };
    }));
    expect(ownership).toHaveLength(shortenable.length * 3);
    expect(ownership.every(control => control.owned), ownership).toBe(true);

    for (const id of shortenable) {
      const grip = await page.locator(`[data-panel-id="${id}"] .panel-grip`).boundingBox();
      await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2);
      await page.mouse.down();
      await page.mouse.move(grip.x + grip.width / 2 + 2, grip.y + grip.height / 2 + 2);
      await page.mouse.up();
      await expect(page.locator('#panel-carrier')).toBeHidden();

      const menu = page.locator(`[data-panel-id="${id}"] .panel-menu`);
      await menu.click();
      await expect(page.locator('#panel-menu [data-menu-command="lengthen"]')).toBeVisible();
      await page.keyboard.press('Escape');

      await page.locator(`[data-panel-id="${id}"] .panel-close`).click();
      await expect(page.locator(`[data-panel-id="${id}"]`)).toBeHidden();
      await page.locator(`#sidebar [data-rail-panel="${id}"]`).click();
      await expect(page.locator(`[data-panel-id="${id}"]`)).toBeVisible();
      expect(Math.round(await sidebar.evaluate(element => element.getBoundingClientRect().width))).toBe(101);
    }

    await page.locator('#search-inp').fill('Outcome');
    await expect(page.locator('#search-inp')).toHaveValue('Outcome');
    expect(await page.evaluate(() => ({
      forced: matchMedia('(forced-colors: active)').matches,
      xOverflow: document.documentElement.scrollWidth - innerWidth,
      contentFits: document.querySelector('[data-panel-id="graph-stats"] .panel-content').scrollWidth
        <= document.querySelector('[data-panel-id="graph-stats"] .panel-content').clientWidth,
      statsFits: document.getElementById('graph-stats').scrollWidth
        <= document.getElementById('graph-stats').clientWidth,
      rowsFit: [...document.querySelectorAll('#graph-stats .graph-stat-line')].every(row => {
        const rowBox = row.getBoundingClientRect();
        const statsBox = document.getElementById('graph-stats').getBoundingClientRect();
        return row.scrollWidth <= row.clientWidth
          && rowBox.left >= statsBox.left && rowBox.right <= statsBox.right;
      }),
    }))).toEqual({ forced: true, xOverflow: 0, contentFits: true, statsFits: true, rowsFit: true });
    const path = testInfo.outputPath('short-panels-forced-colors-floor-101.png');
    await page.screenshot({ path, fullPage: true });
    await testInfo.attach('short-panels-forced-colors-floor-101', { path, contentType: 'image/png' });
    const statsPath = testInfo.outputPath('graph-stats-forced-colors-floor-101.png');
    await page.locator('[data-panel-id="graph-stats"] .panel-body')
      .evaluate(element => { element.scrollTop = element.scrollHeight; });
    await page.locator('[data-panel-id="graph-stats"]').screenshot({ path: statsPath });
    await testInfo.attach('graph-stats-forced-colors-floor-101', {
      path: statsPath, contentType: 'image/png',
    });
    await context.close();
  });
});

// ══ QA-10 / UI-13: pinning the left column's overflow measurements in the suite ════════════════
//
// The suite covers all four measured viewports: 1440x900, 1280x800, 1920x1080 and 1180x800.
// At 1180x800, UI-13 reaches 0px overflow ONLY with a specific five-panel configuration. With the
// intentional narrow runtime row, GRAPH STATS remains load-bearing: it saves 92px against the
// current 107px floor, leaving 15px; Search saves 16px. Pinning only UI-13's five-panel total would stay
// green if Search's short form disappeared entirely, so the contributions are also decomposed.
//
// A PIN NEEDS ITS CONFIGURATION, NOT JUST ITS NUMBER. "1180x800 -> 0px" is not a property of the
// viewport alone — it is a property of the viewport AND the specific set of panels shortened. A
// test asserting the number without naming the panels would pass or fail for reasons unrelated to
// the property it exists to defend: change what "default" means, or change how many panels start
// short, and the assertion moves for a reason that has nothing to do with any of the five panels'
// own short-form CSS. So every test below names its panels explicitly, in the order they are
// shortened, rather than deriving them from `canShorten()` or looping over `PANELS`.
//
// STRUCTURAL NOTE: this file also carries the splitter tests ('the workspace splitters', above).
// `.layout-splitter[data-layout-splitter="left"]` is a DOM SIBLING of `#sidebar` inside
// `#main` (index.html) — declared AFTER `#sidebar`'s closing tag, never inside it, and therefore
// never inside `#sidebar-scroll` either. `sidebarOverflow()` above reads only
// `#sidebar-scroll`'s own `scrollHeight`/`clientHeight`, so the splitter cannot enter that
// computation regardless of where it sits in the row. That is why the splitter never shows up as
// a term in any of the arithmetic below, not why the pixel counts below it are stable across hosts.
// Raw residual pixel counts are NOT stable across hosts: see the comment immediately below.
// None of the raw pixel figures this section originally pinned (107px at 1180x800, 31px at
// 1280x800) are declared CSS constants — grep confirms neither 76px, 31px nor 107px appears
// literally in styles.css. They are MEASURED figures: text/line-height-derived residuals that move
// with font metrics, and — for the FULL count of rows the palette icon grid wraps to, WITHIN one
// host, as viewport width crosses the `@media (max-width: 1180px)` breakpoint — with a declared
// pixel constant instead (styles.css:1587). Two hosts measured the 1180x800 floor at 107px and
// 176px, and the 1280x800 residual at <=31px and 100px — the identical +69px delta in both
// configurations. An extra wrapped row in the palette icon grid cannot explain the cross-host
// delta: the wrap boundary is a declared pixel breakpoint (188px vs 224px `#sidebar` width)
// feeding a fixed-cell `auto-fill` grid, with no font metric anywhere in that computation — so row
// count at a GIVEN viewport is identical on every host, never the source of a cross-host delta.
// Both hosts sit on the same side of it at 1280 (2/2 rows), 69px apart regardless. The two
// shortened palettes' own FULL-form `.li` rows (styles.css:811, no explicit `line-height`) cannot
// drive the raw residual `after.overflow`: `after` is read AFTER shortening,
// when both palettes are already in their fixed-px short-form cells — `.li`'s line-height stops
// applying at all once a panel is `.panel--short`. Measured directly: sweeping `.li` line-height
// from the default through 0, 0.5, 0.8, 1.0, 1.9 and 2.4 (seven points) leaves `after.overflow`
// at exactly 100 every time, while `before.overflow` moves freely (459 down to 293 across that
// same sweep on this host). The raw residual DOES move with font metrics on a panel
// shortening never touches — `#graph-stats`, at fixed 1280x800 with the palettes untouched, takes
// `after` from 35 (line-height 1.3) to 175 (line-height 2.6). A text/line-height-driven panel that
// the two-panel shorten never touches accounts for the residual — never the shortened palettes'
// own full form, which
// stops mattering the moment they shorten. Pinning either raw figure fails on any host whose font
// metrics differ from whichever one first recorded it, which is exactly what happened here — that
// part IS measured, on both hosts.
// What must hold on EVERY host is the property the numbers stood in for: after the three palettes
// are shortened, a meaningful residual survives that they did not create and cannot close alone
// (1180x800); after the two palettes are shortened, the overflow is reduced — never left unchanged
// or made larger — and the column stays scrollable to its end (1280x800). See FLOOR_OVERFLOW_MIN
// below and the relative/comparative thresholds inline for what would make each fail. A width sweep
// (viewport width standing in for font-metric drift, product unchanged) exercises a relative
// threshold's sensitivity; mutation shows that a
// threshold CAN fail, a sweep shows what it does under the specific drift these hosts exhibited.
const FLOOR_OVERFLOW_MIN = 40;

test.describe('the left column overflow, pinned for every measured viewport', () => {
  test.beforeEach(async ({ page }) => {
    // Explicit, not assumed — see the comment on `resetToDefaultLayout` above. Belt-and-braces
    // with Playwright's already-isolated per-test storage, and that redundancy is the point.
    await page.goto('/');
    await resetToDefaultLayout(page);
    await page.reload();
    await page.waitForTimeout(300);
  });

  test('1920x1080: 0px, with the same two-panel configuration 1440x900 already pins', async ({ page }) => {
    await page.setViewportSize({ width: 1920, height: 1080 });
    await page.reload();
    await page.waitForTimeout(300);

    // RED HALF: the column must be shown overflowing before anything is shortened, or the 0px
    // below would pass on a build where this viewport was never in overflow to begin with.
    const before = await sidebarOverflow(page);
    expect(before.overflow, 'did not reproduce overflow at 1920x1080 before shortening').toBeGreaterThan(0);

    await shortenPanel(page, 'node-types');
    await shortenPanel(page, 'edge-types');
    const after = await sidebarOverflow(page);
    expect(after.overflow).toBe(0);
    expect(after.statsAboveFold).toBe(true);
  });

  test('1280x800: shortening the two palettes saves at least 200px and keeps the column end reachable', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.reload();
    await page.waitForTimeout(300);

    const before = await sidebarOverflow(page);
    expect(before.overflow, 'did not reproduce overflow at 1280x800 before shortening').toBeGreaterThan(0);

    await shortenPanel(page, 'node-types');
    await shortenPanel(page, 'edge-types');
    const after = await sidebarOverflow(page);
    // REDUNDANT WITH the compact-panel reachability test above in the `the short form` describe
    // block, on purpose: same two panels, same viewport, same
    // `sidebarOverflow`/`column` computation. It is the SAME measurement written twice because it
    // lives in a different describe block with a different story around it, not two independent
    // checks — kept in sync deliberately.
    //
    // Neither the rendering measurement `toBeLessThanOrEqual(31)` nor the relative threshold
    // `after <= before * 0.35` is portable. A width sweep (1180 vs 1220-1400, product unchanged)
    // shows that technique does NOT transfer here: the ratio is 0.4322 at 1180 vs 0.2353 at
    // 1220-1400 — one extra palette-icon wrap row roughly doubles it, because the numerator moves
    // +104 across that boundary while the denominator moves only +47. The ratio amplifies host
    // drift instead of absorbing it, unlike the decomposition fractions below, which stayed
    // byte-identical across three widths. No fixed cap can be both tight and
    // safe against that amplification, so the ratio is abandoned here. What holds host to host:
    // shortening the two palettes measurably reduces the overflow, and the bottom of the column
    // stays reachable. `endReachable` is the genuinely host-independent half; the reduction check
    // is the other half, and it is intentionally NOT a magnitude claim — see the sibling comment
    // above for the full argument, and the addendum right below for why the title no longer
    // promises a bound at all.
    //
    // The title must not promise "bounded" scroll because no assertion establishes a bound. It now
    // names exactly what the two `expect`s below establish: reduction, a magnitude floor and
    // reachability.
    //
    // The row-wrap boundary (188px vs 224px `#sidebar` width, `@media (max-width: 1180px)`,
    // styles.css:1587) is a DECLARED PIXEL BREAKPOINT feeding `grid-template-columns:
    // repeat(auto-fill, 32px)` with a 4px gap — one fewer column fits below it, hence one more
    // wrapped row. No font metric enters that computation, so it is IDENTICAL on every host at a
    // given viewport: a width sweep confirms 188px/3-rows at 1180 and 224px/2-rows at every point
    // from 1200 to 1400, on this host alone. The two recorded host values, 31px and 100px (this run
    // reproduces 100px), sit on the same side of that breakpoint at 1280 — so both read
    // 2/2 rows — and still differ by 69px. That is not a case the wrap boundary can explain, and
    // the wrap boundary therefore cannot explain the difference.
    //
    // The deeper question is whether a host-independent MAGNITUDE claim exists at all
    // for this residual, given that the raw pixel value and the before/after ratio were both
    // already shown host-dependent (comment above). Measured, not guessed, with a fresh build
    // (`npm run build`) against a width sweep (1200-1400, 20px step), a height sweep (700-900,
    // 20px step), and two sets of CSS perturbations at the fixed 1280x800 viewport:
    //
    // 1. NORMALIZE BY THE COLUMN'S OWN AVAILABLE HEIGHT (`scroll.clientHeight`) instead of by
    // `before.overflow`. Rejected because `before` and
    // `after` both move in lockstep with `clientHeight` at a fixed width (before: 525->325px,
    // after: 200->0px from height 700 to 900), so dividing by `clientHeight` rescales the same
    // numerator by a denominator that has no more relationship to the untouched-panel content
    // driving the residual than `before.overflow` did. Neither denominator ISOLATES the two
    // shortened panels' own contribution — only subtraction does (option 3 below).
    //
    // 2. COUNT WRAPPED ROWS IN THE PALETTE ICON GRIDS (`#leg-nodes`, `#leg-edges`) INSTEAD OF
    // PIXELS. Measured directly (grouping grid children by rounded `getBoundingClientRect()
    // .top`): row count is a genuinely host-invariant 2/2 across the whole width band both
    // recorded hosts sit in, and across the whole height sweep — invariant BECAUSE it comes
    // from the deterministic breakpoint above, not despite host differences. Correctly closed,
    // not because it is unstable but because it answers a question this test does not ask: it
    // would guard the wrap boundary itself (a real property, just not this one), and the two
    // hosts' 69px gap at 1280 sits entirely outside it, per point 1180's identical row count.
    //
    // 3. THE SAVING ITSELF: `before.overflow - after.overflow`. Analytically this is JUST the
    // height the two palettes give up between full and short form at this viewport — every
    // other panel in the column is unchanged between the two measurements, on the same page,
    // same host, same render, so its contribution to `before` and to `after` is the identical
    // number and cancels in the subtraction. It does not need to BE host-independent to cancel
    // — it only needs to be the same in both terms, which is guaranteed by construction, not
    // by luck. Measured: 325 exactly, unmoved, at every one of the 11 width-sweep points
    // (1200-1400) and every one of the 11 height-sweep points (700-900), where the RATIO the
    // ratio ranges from 0 to 0.3810 and `after/clientHeight` from 0 to 0.3670
    // over that same height sweep. Also unmoved (325 exactly) under four perturbations of
    // panels OUTSIDE the shortened set — `#search-inp` font-size, `#graph-stats` line-height
    // (2.6, chosen to probe the rule already at `line-height: 1.9` in the base sheet),
    // `.catalog-item-copy` line-height and `.node-catalog` gap — the two that actually moved
    // rendered height (`#graph-stats` to after=175, ratio=0.35, after/clientHeight=0.2713;
    // `#search-inp` to after=112) left the saving at 325 regardless.
    //
    // ONE CHANNEL DOES move it, and it is inside the shortened panels' own full form: `.li`
    // (styles.css:811) sets no explicit `line-height`, so the FULL, unshortened list rows for
    // node-types/edge-types inherit the browser's `normal` value — a real font-metric-driven
    // quantity, unlike the fixed-px short-form cell. Two DOWNWARD channels, each measured to
    // its own limit rather than left as "plausible drift":
    //
    // CHANNEL A — `.li` line-height forced low. Per-row heights (not just the sum) were
    // measured across the sweep. Node rows carry `.li-dot`, a 13px `flex-shrink: 0` icon, so
    // with `align-items: center` and the row's 4px+4px padding, node rows floor at 21px and
    // STAY there from line-height 1.0 down to 0 (measured: all ten `#leg-nodes .li` read 21px
    // at 1.0, 0.8, 0.5 AND 0 — the floor is real, but it is reached, not approached, well
    // before the extreme). Edge rows carry `.li-line`/`.li-dash` instead, only 3px tall, so
    // they carry NO such floor and keep shrinking as line-height falls, down to ~10-12px per
    // row at the CSS extreme of 0 (measured, all nine `#leg-edges .li`). Combined, the true
    // floor for this channel — forcing line-height to 0, its actual minimum, not a value any
    // `normal` keyword resolves to on a real host — is `before=293`, `saving=193`: 7px BELOW
    // this test's 200 floor. That is not a threat from real host drift: `normal` never resolves
    // near 0 on any mainstream browser/OS combination, and the closest realistic proxy tested
    // — line-height forced to 1.0, near the low end of `normal`'s actual cross-host range —
    // gives `saving=278`, 78px clear of 200. A regression that collapsed `.li` line-height
    // toward its CSS floor would be exactly the kind of real defect this test exists to catch,
    // not environmental noise it owes tolerance to.
    //
    // CHANNEL B — `after` clamping at 0. `sidebarOverflow` reads `Math.max(0,
    // scrollHeight-clientHeight)`, so a host whose column renders short enough that the
    // shortened state no longer overflows would read `after=0` and `saving=before`. That is
    // not dangerous in itself (`before` on every host measured is comfortably above 200) — but
    // it marks where the measured 325px invariant stops holding, and the honest
    // bound is how far a host would have to move to reach it: from this host's `before=425`,
    // `saving` only risks dropping under 200 if `before` itself falls under 200 — a ~225px
    // contraction of the WHOLE column, over three times the 69px gap the two recorded
    // hosts actually showed. A drift of that size would be visible everywhere in this suite,
    // not just here.
    //
    // NEITHER channel threatens 200 under realistic drift; CHANNEL A's own CSS-extreme (not a
    // real host state) sits 7px past it, which is the reason the margin is stated as measured
    // bounds rather than as an unqualified "plausible drift, comfortably clear" claim.
    //
    // MUTATION SENSITIVITY — checked with two reproducible mutations of the same two panels,
    // run through both this test's scenario and the 1180x800 floor scenario below (node-types and
    // edge-types are two of the three panels shortened there):
    //
    // MUTATION A — full form precisely restored for both panels (revert every `.panel--short`
    // rule touching `#leg-nodes`/`#leg-edges`/`.li`/`.li-dot`/`.li-line`/`.li-dash`/`.li-glyph`
    // back to their full-form values via `page.addStyleTag`). At 1280x800: `saving=24` — RED
    // against this test's floor. At the 1180x800 floor scenario: `floor=420 < before=472` and
    // `floor=420 > 40` — GREEN. NOT because of `node-catalog`: that panel's short form saves
    // 28px on its own (panel height 187.50->159.56, scrollHeight 1117->1089, overflow 472->444,
    // three independent quantities agreeing), and the floor test would pass without it anyway —
    // under mutation A the two mutated palettes still give up 12px each of bare padding, so the
    // counterfactual sequence reads `448 > 40` and `448 < 472`, both green. The real reason is
    // that the floor test's reduction check is DIRECTIONAL WITH NO MAGNITUDE FLOOR: `floor <
    // before` passes on any positive saving at all, 24px of padding included. That is precisely
    // the gap this test's 200px floor fills, which makes the asymmetry COVERAGE THIS TEST ADDS,
    // not a disagreement between thresholds to reconcile. The SAME regression in node-types/edge-types reddens
    // one sibling and not the other. That is a real coverage difference, not a hypothetical one.
    //
    // That is not a contradiction between the two thresholds: `FLOOR_OVERFLOW_MIN` and this
    // floor are NOT asserting about the same quantity, and neither is expressed as a fraction
    // of the other (unlike Search's 16px cap and `FLOOR_OVERFLOW_MIN` in `docs/qa/what-the-
    // testkits-do-not-cover.md`, which WERE algebraically entangled). The 1180x800 test can
    // stay green here because it has a THIRD lever — `node-catalog` — that this 1280x800 test
    // does not: shortening two of three panels there can still legitimately reduce the residual
    // even if one of the two contributes nothing, which is exactly why the 1180x800 block also
    // carries a DECOMPOSITION (Graph Stats / Search) that this two-panel test has no analogue
    // for. A sibling staying green under a mutation this test catches is coverage this test
    // ADDS, not a disagreement to resolve.
    //
    // MUTATION B — only the grid reflow cancelled (`.panel--short #leg-nodes, .panel--short
    // #leg-edges { display: block; }`, short-form cell sizing on `.li` left untouched). At
    // 1280x800: `saving=-147` — RED. At the 1180x800 floor scenario: `floor=576 >= before=472`
    // — RED too (the floor test's own reduction assertion fails). Under this STRONGER mutation
    // both siblings agree; under mutation A only the more sensitive one (this test) catches it.
    // Which siblings redden depends on the mutation's strength and locus, not on the thresholds
    // disagreeing. The sufficient claim here is narrower and
    // true: no sibling in the block ASSERTS anything incompatible with this floor, because none
    // shares an algebraic identity with it the way the entangled pair in `docs/qa/what-the-
    // testkits-do-not-cover.md` did. `FLOOR_OVERFLOW_MIN` remains 40 on that ground alone.
    expect(after.overflow, 'shortening the two palettes did not reduce the overflow at all').toBeLessThan(before.overflow);
    expect(before.overflow - after.overflow, 'the two palettes saved far less height than their short form should — '
      + 'floor chosen with a ~125px margin below the 325px this host measures; both downward channels (.li line-height, '
      + 'the after=0 clamp) are measured and bounded in the comment above')
      .toBeGreaterThanOrEqual(200);
    expect(after.endReachable).toBe(true);
  });

  test('1180x800: a residual floor survives — the palettes alone cannot close it to 0', async ({ page }) => {
    // [UI-13]'s own measurement was built on this floor, and it is pinned on purpose as its own
    // test: it is not supposed to reach 0. If it ever does, the palette short forms themselves
    // grew, which is a DIFFERENT regression than the one the next test guards — the two must not
    // be allowed to blur into one number the way a single "1180 -> 0" assertion would blur them.
    // The floor was originally pinned at the literal 107px one host measured; see the
    // FLOOR_OVERFLOW_MIN comment above for why the title no longer names a figure the body
    // abandoned — this host measures 176px for the same property.
    await page.setViewportSize({ width: 1180, height: 800 });
    await page.reload();
    await page.waitForTimeout(300);

    const before = await sidebarOverflow(page);
    expect(before.overflow, 'did not reproduce the founding 1180x800 overflow').toBeGreaterThan(0);
    expect(before.statsAboveFold).toBe(false);

    await shortenPanel(page, 'node-types');
    await shortenPanel(page, 'edge-types');
    await shortenPanel(page, 'node-catalog');
    const floor = await sidebarOverflow(page);
    // the panel floor plus the intentional second command-bar line — see FLOOR_OVERFLOW_MIN
    // above for why this is a threshold and not the 107px one host measured. Two ways this fails:
    // reaching (or nearing) zero means the palettes closed the residual on their own, which is the
    // regression this test exists to catch; reaching or exceeding `before.overflow` means
    // shortening the three palettes did nothing at all, which would be a different, cruder break.
    expect(floor.overflow, 'the palettes closed the structural residual on their own').toBeGreaterThan(FLOOR_OVERFLOW_MIN);
    expect(floor.overflow, 'shortening the three palettes reduced nothing').toBeLessThan(before.overflow);
    expect(floor.statsAboveFold).toBe(false);
    expect(floor.endReachable).toBe(true);
  });

  test('1180x800: 0px, but only with the full five-panel configuration named explicitly', async ({ page }) => {
    await page.setViewportSize({ width: 1180, height: 800 });
    await page.reload();
    await page.waitForTimeout(300);

    const before = await sidebarOverflow(page);
    expect(before.overflow, 'did not reproduce the founding 1180x800 overflow').toBeGreaterThan(0);
    expect(before.statsAboveFold).toBe(false);

    // Exact set, named in shorten order — NOT derived from `PANELS.filter(canShorten)` or any
    // other generic loop. A generic loop would make this test pass for a future sixth panel of
    // kind `control` or `bounded` without anyone deciding it should join this configuration; a
    // literal list forces that decision, here, by hand.
    for (const id of ['node-types', 'edge-types', 'node-catalog', 'search', 'graph-stats']) {
      await shortenPanel(page, id);
    }
    const after = await sidebarOverflow(page);
    expect(after.overflow).toBe(0);
    expect(after.statsAboveFold).toBe(true);
  });

  // ── The decomposition ──────────────────────────────────────────────────────────────────────
  // A STRONGER guard than the total above: each of these fails when its OWN load-bearing panel
  // regresses, rather than only when the five-panel sum does. Both start from the SAME residual
  // floor as the test above — reasserted here rather than assumed, because a decomposition that
  // trusted an unverified precondition would not be proving what it claims to. What actually
  // needs protecting is not the floor's raw pixel count (see FLOOR_OVERFLOW_MIN above) but the
  // SHAPE of the decomposition — Graph Stats does most of the remaining work, Search does little of
  // it. That shape held on both measured hosts (floor 107px: 92px vs 16px saved; floor
  // 176px: 141px vs 16px saved) even though the floor itself did not, so it is asserted as a
  // fraction of that test's own floor rather than as either side's absolute pixel count.

  test('the decomposition: Graph Stats removes most of the floor and remains the load-bearing panel', async ({ page }) => {
    await page.setViewportSize({ width: 1180, height: 800 });
    await page.reload();
    await page.waitForTimeout(300);

    await shortenPanel(page, 'node-types');
    await shortenPanel(page, 'edge-types');
    await shortenPanel(page, 'node-catalog');
    const floor = await sidebarOverflow(page);
    expect(floor.overflow, 'the residual floor this decomposes did not reproduce').toBeGreaterThan(FLOOR_OVERFLOW_MIN);

    await shortenPanel(page, 'graph-stats');
    const after = await sidebarOverflow(page);
    const graphStatsSaving = floor.overflow - after.overflow;
    // A 0.7 threshold is too tight for the observed 80-86% margin at floors 107/176. The two
    // points fit ratio ~= 0.71 + 16/floor —
    // Search's own fixed 16px saving (see the sibling test) riding on top of an asymptote just
    // above 0.7 — and the fit converges FROM ABOVE as floor grows, in the same direction the two
    // hosts already differ (107 -> 176). Two points make that fit fragile, and a THIRD measured
    // host would be needed to defend 0.7 with any margin below the asymptote. The 0.6 threshold
    // sits clearly outside the convergence band, so it does not carry the same risk of
    // going false-red on a legitimate third host the way 0.7 would.
    expect(graphStatsSaving, 'graph-stats stopped being the load-bearing short form').toBeGreaterThan(floor.overflow * 0.6);
    expect(after.endReachable).toBe(true);
  });

  test('the decomposition: Search alone leaves most of the floor standing — it is not load-bearing for the zero', async ({ page }) => {
    await page.setViewportSize({ width: 1180, height: 800 });
    await page.reload();
    await page.waitForTimeout(300);

    await shortenPanel(page, 'node-types');
    await shortenPanel(page, 'edge-types');
    await shortenPanel(page, 'node-catalog');
    const floor = await sidebarOverflow(page);
    expect(floor.overflow, 'the residual floor this decomposes did not reproduce').toBeGreaterThan(FLOOR_OVERFLOW_MIN);

    await shortenPanel(page, 'search');
    const after = await sidebarOverflow(page);
    const searchSaving = floor.overflow - after.overflow;
    // Search's own short form saves a FIXED 16px — measured identical on three different floors
    // (72, 107, 176). That makes this ratio exactly `16 / floor.overflow`,
    // not an independent quantity: whatever cap C is chosen here is equivalent to requiring
    // `floor.overflow > 16 / C`, which must stay consistent with FLOOR_OVERFLOW_MIN above rather
    // than be picked on its own. A cap of 0.25 would require floor > 64, but FLOOR_OVERFLOW_MIN
    // only requires floor > 40, so on
    // any floor in 41-64 (reachable and admitted by the sibling test above) the two thresholds
    // contradicted each other: the floor test says "fine", this one says "search became
    // load-bearing" even though Search never moved. Mutating the floor down to 43 without touching
    // Search demonstrates the contradiction.
    //
    // The constraint, for whoever picks these two numbers next: C * FLOOR_OVERFLOW_MIN >= 16 (the
    // invariant Search saving), the two thresholds chosen TOGETHER, never one at a time. 0.4 is the
    // tight solution: 0.4 * 40 = 16 exactly, and FLOOR_OVERFLOW_MIN's own strict `>` means floor is
    // at least 41 in practice, giving 0.4 * 41 = 16.4 > 16 — a real, if thin, margin at the
    // boundary. The measurement therefore supports 0.4.
    expect(searchSaving, 'search became load-bearing for the zero, which the Graph Stats test above must own instead').toBeLessThan(floor.overflow * 0.4);
    expect(after.statsAboveFold).toBe(false);
    expect(after.endReachable).toBe(true);
  });
});

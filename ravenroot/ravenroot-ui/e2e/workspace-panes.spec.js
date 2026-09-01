import { expect, test } from '@playwright/test';
import { createAppCommands } from '../src/app-commands.js';

const catalogCommands = Object.fromEntries(createAppCommands(
  new Proxy({}, { get: () => () => {} })).map(command => [command.id, command]));

// UI-03 pane geometry, pane identity and focus contract. Documents have independent records and
// corrected lifecycles; panes lay them side by side and give each one a header strip that says which
// document it is. Without panes, every test in this file fails its first structural assertion because
// `.doc-pane` does not exist: the canvases are absolutely-positioned siblings inside `#cy` and only
// the active one is displayed.
//
// Two things are asserted here rather than argued anywhere:
//
// * pane focus reaches the workspace through `workspace.activate(id)` and drags the elastic teardown
// and the layout chrome along with it. The spy on
// `workspace.activate` is installed on the real object the editor calls, so a private path into
// the workspace would leave the spy empty and fail the test.
// * Cytoscape actually PAINTS inside its pane. Moving an existing `.doc-canvas` in the DOM stops
// the instance painting and `resize()`/`fit()`/`forceRender()` do not recover it, so the pane has
// to exist before the document does. The proof is pixels, not a call count.

const WIDE = { width: 1800, height: 900 };
// 1280 with the inspector open is the measured case where a two-way split cannot clear the 360px
// floor. It is a viewport, not a constant to be trusted: the test reads the real available width.
const MEASURED_NARROW = { width: 1280, height: 800 };

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

// Records every id handed to the workspace's own `activate`, on the real object app.js calls.
async function spyOnActivate(page) {
  await page.evaluate(() => {
    window.__activateCalls = [];
    const workspace = window.ravenroot.workspace;
    const original = workspace.activate.bind(workspace);
    workspace.activate = id => {
      window.__activateCalls.push(String(id));
      return original(id);
    };
  });
}

const paneIds = page => page.evaluate(() =>
  [...document.querySelectorAll('#cy .doc-pane')]
    // Responsive fallback parks hidden Cytoscape panes in a nonzero invisible box so their own
    // ResizeObserver never receives 0×0. The explicit projection class, not offsetParent, is the
    // workspace's source of truth for which panes are presented.
    .filter(pane => pane.classList.contains('doc-pane--shown'))
    .map(pane => pane.dataset.documentId));

// A freshly opened, unpositioned document runs the 'n8n' layout with `animate: true` (app.js,
// `setLayout`) — an elk computation followed by a real animated fit — so its zoom keeps changing
// on its own clock for a while after `openDocument()` returns: the call only promises a pane
// exists, not that its layout has settled. Reading `.zoom()` before that animation's real
// `layoutstop` has fired reads a value that is still in flight, and how long that takes is a fact
// about the machine's current load, not a duration worth guessing. Measured: under contention this
// animation alone runs 600-650ms end to end (elk computation + tween), well past a single
// `page.evaluate` round trip.
//
// This does NOT poll `cy.scratch('_rrLayoutRunning')` — the same flag `syncPaneRenderer` reads in
// app.js to know whether a re-fit is safe — for "not running" as a snapshot. Instrumented traces
// (`DIAG created layoutRunning=
// undefined`) prove `_rrLayoutRunning` is still `undefined`, not `true`, in the SAME synchronous
// tick that creates the document. `cytoscape-elk` defers emitting `layoutstart` itself — unlike a
// native layout, `.layout({name:'elk',...}).run()` does not raise it before returning. A poll (or
// a same-tick check) for "not running" therefore reads `!undefined === true` and resolves on its
// FIRST check, having waited for nothing. The incidental delay can lower the flake rate while the
// race survives untouched, so such a guard passes for the wrong reason.
//
// The helper waits for the TRANSITION, not a snapshot of the state: if a layout is already
// running, wait for ITS stop; if it has not started yet, wait for the next `layoutstart` — which
// for this document's 'n8n' layout is guaranteed to fire exactly once, whenever `cytoscape-elk`
// gets around to it — and only then for its matching stop. Both branches are registered on the new
// document's OWN cy instance inside the SAME synchronous `page.evaluate` call that creates it, so
// nothing can slip through the gap between reading the flag and attaching the listener for it.
async function openDocumentAndWaitForOwnLayout(page, options) {
  const id = await page.evaluate(spec => window.ravenroot.openDocument(spec), options);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  return id;
}

// "Cytoscape is painting in this pane" stated as pixels. Cytoscape renders into <canvas> layers
// inside its container; a same-origin canvas can be read back, so a pane that draws nothing at all
// is distinguishable from one that draws a graph.
const paintedPixels = (page, documentId) => page.evaluate(id => {
  const pane = document.querySelector(`#cy .doc-pane[data-document-id="${id}"]`);
  const canvases = [...pane.querySelectorAll('canvas')];
  return canvases.reduce((painted, canvas) => {
    if (!canvas.width || !canvas.height) return painted;
    const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
    let opaque = 0;
    for (let index = 3; index < data.length; index += 4) if (data[index] !== 0) opaque += 1;
    return painted + opaque;
  }, 0);
}, documentId);

test.describe('two documents side by side', () => {
  test.use({ viewport: WIDE });

  test('lays the open documents out as panes, each carrying its own name', async ({ page }) => {
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'orders.graphml' }));

    // Both panes are on screen at once. This is the whole increment: before it, the second document
    // existed but was `display:none`.
    await expect.poll(() => paneIds(page)).toEqual([first, second]);
    await expect(page.locator('#cy .doc-pane')).toHaveCount(2);

    // Side by side, never stacked: the splits are horizontal.
    const boxes = await page.evaluate(() =>
      [...document.querySelectorAll('#cy .doc-pane')].map(pane => {
        const box = pane.getBoundingClientRect();
        return { left: box.left, top: box.top, width: box.width };
      }));
    expect(boxes[0].top).toBeCloseTo(boxes[1].top, 0);
    expect(boxes[1].left).toBeGreaterThan(boxes[0].left + boxes[0].width - 1);
    expect(Math.min(...boxes.map(box => box.width))).toBeGreaterThanOrEqual(360);

    // Identity is carried by the NAME, as text. Not by a colour, and not by position.
    const headers = page.locator('#cy .doc-pane .doc-pane-header');
    await expect(headers).toHaveCount(2);
    await expect(headers.nth(0)).toContainText('untitled.graphml');
    await expect(headers.nth(1)).toContainText('orders.graphml');

    // The strip spends height, which the product has, and the measured 24px is what it spends.
    const headerHeights = await page.evaluate(() =>
      [...document.querySelectorAll('#cy .doc-pane-header')].map(el => el.getBoundingClientRect().height));
    expect(headerHeights).toEqual([24, 24]);

    // Closing is available directly on every pane and names its exact target.
    const closeButtons = page.locator('#cy .doc-pane-header .doc-pane-close');
    await expect(closeButtons).toHaveCount(2);
    await expect(closeButtons.nth(0)).toHaveAccessibleName('Close untitled.graphml');
    await expect(closeButtons.nth(1)).toHaveAccessibleName('Close orders.graphml');
  });

  test('a pane close button uses the shared close command without activating its background document', async ({ page }) => {
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'orders.graphml' }));
    await expect.poll(() => paneIds(page)).toEqual([first, second]);
    await expect(page.locator(`#cy .doc-pane[data-document-id="${second}"]`)).toHaveAttribute('aria-current', 'true');

    await page.locator(`#cy .doc-pane[data-document-id="${first}"] .doc-pane-close`).click();

    await expect.poll(() => paneIds(page)).toEqual([second]);
    await expect(page.locator(`#cy .doc-pane[data-document-id="${second}"]`)).toHaveAttribute('aria-current', 'true');
  });

  // `.doc-pane-close` stays out of the Tab order (`tabIndex = -1`) so keyboard traversal follows
  // pane -> separator -> pane; a native <button> with no explicit tabIndex would land between the
  // pane and separator. The `focusin` handler's `[data-pane-document-close]` exclusion still handles
  // programmatic focus on a background pane's close button, but Tab does not take that route. The
  // accessible keyboard path activates the pane on the
  // way: Tab lands on the pane,
  // which is a real Tab stop and always activates on focus (documented above), and the ordinary
  // File > Close command reaches it from there. This test pins that reachable keyboard path; it does
  // not require a separate "close without activating" shortcut.
  test('a background pane is still closable purely from the keyboard, by Tab-activating it then File > Close', async ({ page }) => {
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'orders.graphml' }));
    await expect.poll(() => paneIds(page)).toEqual([first, second]);
    // `second` is active after opening; `first` is the background pane this route targets.
    await expect(page.locator(`#cy .doc-pane[data-document-id="${first}"]`)).not.toHaveAttribute('aria-current', 'true');

    await page.locator(`#cy .doc-pane[data-document-id="${first}"]`).focus();
    expect(await page.evaluate(() => window.ravenroot.workspace.activeId)).toBe(first);

    await page.locator('#menu-file').focus();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('End');
    await expect(page.getByRole('menuitem', { name: catalogCommands['file.close'].label })).toBeFocused();
    await page.keyboard.press('Enter');

    await expect.poll(() => paneIds(page)).toEqual([second]);
  });

  test('paints Cytoscape inside every pane, because the pane exists before the document', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'orders.graphml' }));
    await expect.poll(() => paneIds(page)).toEqual([first, second]);

    // The container each instance was constructed against is inside its own pane and was never moved
    // there afterwards.
    expect(await page.evaluate(([a, b]) => {
      const inPane = id => {
        const record = window.ravenroot.workspace.find(id);
        const pane = document.querySelector(`#cy .doc-pane[data-document-id="${id}"]`);
        return pane.contains(record.cy.container()) && record.cy.container().dataset.documentId === id;
      };
      return [inPane(a), inPane(b)];
    }, [first, second])).toEqual([true, true]);

    // Both instances have real geometry, so neither is a zero-sized renderer that merely exists.
    const sizes = await page.evaluate(([a, b]) => [a, b].map(id => {
      const cy = window.ravenroot.workspace.find(id).cy;
      return { width: cy.width(), height: cy.height() };
    }), [first, second]);
    for (const size of sizes) {
      expect(size.width).toBeGreaterThanOrEqual(360);
      expect(size.height).toBeGreaterThan(100);
    }

    // And both are actually drawing. A canvas that has
    // stopped painting still answers `resize()` and `fit()` perfectly happily.
    await expect.poll(() => paintedPixels(page, first), { timeout: 15_000 }).toBeGreaterThan(0);
    await expect.poll(() => paintedPixels(page, second), { timeout: 15_000 }).toBeGreaterThan(0);

    expect(errors).toEqual([]);
  });

  test('marks the focused pane without relying on colour, and says which document is modified', async ({ page }) => {
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'orders.graphml' }));
    await expect.poll(() => paneIds(page)).toEqual([first, second]);

    const pane = id => page.locator(`#cy .doc-pane[data-document-id="${id}"]`);

    // Focus is a state the machine can read, not a shade a sighted user has to compare.
    await expect(pane(second)).toHaveAttribute('aria-current', 'true');
    await expect(pane(first)).not.toHaveAttribute('aria-current', 'true');
    // And it is spelled out in text for anyone who cannot see the treatment at all.
    await expect(pane(second).locator('.doc-pane-header')).toContainText('active document');

    await page.evaluate(id => window.ravenroot.activateDocument(id), first);
    await expect(pane(first)).toHaveAttribute('aria-current', 'true');
    await expect(pane(second)).not.toHaveAttribute('aria-current', 'true');

    // The `*`-equivalent: a modified document says so with a character, not with a tint.
    await expect(pane(first).locator('.doc-pane-modified')).toHaveCount(0);
    await page.locator('#btn-modify').click();
    await page.locator('#btn-add-node').click();
    await page.locator('#node-editor button[type="submit"]').click();
    await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');

    await expect(pane(first).locator('.doc-pane-modified')).toHaveText('*');
    await expect(pane(first).locator('.doc-pane-header')).toContainText('modified');
    // The document that was not edited is not marked, so the mark means something.
    await expect(pane(second).locator('.doc-pane-modified')).toHaveCount(0);
  });

  test('pane focus goes through workspace.activate and drags the shared chrome with it', async ({ page }) => {
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'orders.graphml' }));
    await page.evaluate(id => window.ravenroot.activateDocument(id), first);
    await expect.poll(() => paneIds(page)).toEqual([first, second]);

    // Two documents laid out differently, so "the toolbar followed" is a visible difference rather
    // than a repaint of the same state.
    await page.locator('#btn-monitoring').click();
    await expect(page.locator('#btn-monitoring')).toHaveClass(/active/);

    // The spy is installed after boot and before the gesture, on the object app.js actually calls.
    await spyOnActivate(page);

    // The gesture under test: focus lands on the other pane. Nothing else is touched.
    await page.evaluate(id => {
      document.querySelector(`#cy .doc-pane[data-document-id="${id}"]`).focus();
    }, second);

    // 1. It reached the workspace through the public seam, with the right id, exactly once.
    await expect.poll(() => page.evaluate(() => window.__activateCalls)).toEqual([second]);
    expect(await page.evaluate(() => window.ravenroot.workspace.activeId)).toBe(second);

    // 2. It went through the WHOLE of `activateDocument` rather than poking the workspace directly:
    // the layout chrome now describes the document in front of the user. A private path that
    // merely called `workspace.activate` would satisfy assertion 1 and fail this one.
    await expect(page.locator('#btn-monitoring')).not.toHaveClass(/active/);
    await expect(page.locator('#btn-design')).toHaveClass(/active/);
    // 3. And the per-document markers moved with it.
    await expect(page.locator(`#cy .doc-pane[data-document-id="${second}"]`))
      .toHaveAttribute('aria-current', 'true');
    await expect(page.locator(`#cy .doc-canvas.active-document`)).toHaveCount(1);
    expect(await page.getAttribute('#cy .doc-canvas.active-document', 'data-document-id')).toBe(second);

    // And back, because following focus has to work in both directions.
    await page.evaluate(id => {
      document.querySelector(`#cy .doc-pane[data-document-id="${id}"]`).focus();
    }, first);
    await expect.poll(() => page.evaluate(() => window.__activateCalls)).toEqual([second, first]);
    await expect(page.locator('#btn-monitoring')).toHaveClass(/active/);
  });

  test('keeps Monitoring and Design renderers alive in three visible panes while focus moves', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await openDocumentAndWaitForOwnLayout(page, { name: 'orders.graphml' });
    const third = await openDocumentAndWaitForOwnLayout(page, { name: 'shipping.graphml' });
    await page.evaluate(([monitoringId, designId]) => {
      window.ravenroot.activateDocument(monitoringId);
      document.querySelector('#btn-monitoring').click();
      window.ravenroot.activateDocument(designId);
      document.querySelector('#btn-design').click();
    }, [first, second]);
    await expect.poll(() => paneIds(page)).toEqual([first, second, third]);

    // The overlay is owned by the Elastic document rather than by the active document. Cose and
    // N8N remain real Cytoscape canvases in their own panes at the same time.
    await expect.poll(() => page.evaluate(() => {
      const elastic = window.ravenroot.workspace.documents.find(item => item.layoutMode === 'elastic');
      const overlay = elastic?.pane.querySelector('.d3-elastic');
      return {
        owner: overlay?.dataset.documentId,
        overlayActive: Boolean(overlay?.classList.contains('active')),
        uniqueHosts: document.querySelectorAll('.doc-elastic-host').length,
        elasticCanvasHidden: elastic?.container.classList.contains('doc-canvas--elastic'),
        svgNodes: overlay?.querySelectorAll('.d3-nodes circle').length || 0,
        sharedHostHidden: document.getElementById('cy').style.display === 'none',
        shown: [...document.querySelectorAll('#cy .doc-pane.doc-pane--shown')]
          .map(pane => pane.dataset.documentId),
      };
    })).toEqual({
      owner: first,
      overlayActive: true,
      uniqueHosts: 1,
      elasticCanvasHidden: true,
      // The elastic (D3) renderer draws one <circle> per graph node; the default document is
      // now start/dosomething/error/end (4), not start/end (2).
      svgNodes: 4,
      sharedHostHidden: false,
      shown: [first, second, third],
    });
    await expect(page.locator('#cy .pane-separator')).toHaveCount(2);
    await expect.poll(() => paintedPixels(page, second), { timeout: 15_000 }).toBeGreaterThan(0);
    await expect.poll(() => paintedPixels(page, third), { timeout: 15_000 }).toBeGreaterThan(0);
    const lifecycle = await page.evaluate(() => window.ravenroot.workspace.documents.map(owner => ({
      id: owner.id,
      kind: owner.renderer.kind,
      generation: owner.renderer.token.generation,
      hostOwned: owner.pane.contains(owner.renderer.host),
    })));
    expect(lifecycle.map(item => item.kind)).toEqual(['elastic', 'cytoscape', 'cytoscape']);
    expect(new Set(lifecycle.map(item => item.generation)).size).toBe(3);
    expect(lifecycle.every(item => item.hostOwned)).toBe(true);

    // Vertical requires three graph-height floors plus chrome; use a viewport that genuinely fits
    // the declared minima so this loop tests renderer preservation, not the responsive fallback.
    await page.setViewportSize({ width: 2400, height: 1800 });
    for (const mode of ['vertical', 'grid', 'horizontal']) {
      await page.evaluate(value => window.ravenroot.setWorkspaceLayout(value), mode);
      await expect.poll(() => paneIds(page)).toEqual([first, second, third]);
      for (const id of [third, first, second, first]) {
        await page.evaluate(documentId => window.ravenroot.activateDocument(documentId), id);
      }
      expect(await page.evaluate(id => {
        const owner = window.ravenroot.workspace.find(id);
        return {
          layout: owner.layoutMode,
          live: owner.renderer.kind === 'elastic',
          active: owner.pane.querySelector('.d3-elastic')?.classList.contains('active'),
          suspended: owner.pane.querySelector('.doc-elastic-host')?.classList.contains('suspended'),
        };
      }, first)).toEqual({ layout: 'elastic', live: true, active: true, suspended: false });
    }

    expect(errors).toEqual([]);
  });

  test('reaches the panes and the resize separator with Tab, and resizes from the keyboard', async ({ page }) => {
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'orders.graphml' }));
    await expect.poll(() => paneIds(page)).toEqual([first, second]);

    const separator = page.locator('#cy .pane-separator');
    await expect(separator).toHaveCount(1);
    await expect(separator).toHaveAttribute('role', 'separator');
    await expect(separator).toHaveAttribute('aria-orientation', 'vertical');
    await expect(separator).toHaveAttribute('data-splitter-kind', 'document');
    await expect(separator).toHaveAttribute('aria-controls', /\S+ \S+/);
    const separatorValues = await separator.evaluate(element => ({
      min: Number(element.getAttribute('aria-valuemin')),
      now: Number(element.getAttribute('aria-valuenow')),
      max: Number(element.getAttribute('aria-valuemax')),
      width: element.getBoundingClientRect().width,
    }));
    expect(separatorValues.width).toBe(1);
    expect(separatorValues.min).toBeLessThanOrEqual(separatorValues.now);
    expect(separatorValues.now).toBeLessThanOrEqual(separatorValues.max);

    // Tab walks pane, separator, pane — the route that needs no chord and no modifier.
    await page.locator(`#cy .doc-pane[data-document-id="${first}"]`).focus();
    await page.keyboard.press('Tab');
    await expect(separator).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.locator(`#cy .doc-pane[data-document-id="${second}"]`)).toBeFocused();
    // Landing on a pane activates it, by the same route as any other focus.
    expect(await page.evaluate(() => window.ravenroot.workspace.activeId)).toBe(second);

    const widths = () => page.evaluate(() =>
      [...document.querySelectorAll('#cy .doc-pane')].map(pane => Math.round(pane.getBoundingClientRect().width)));
    const before = await widths();

    await separator.focus();
    const positionBefore = Number(await separator.getAttribute('aria-valuenow'));
    for (let press = 0; press < 4; press += 1) await page.keyboard.press('ArrowLeft');

    await expect.poll(async () => (await widths())[0]).toBeLessThan(before[0]);
    expect(Number(await separator.getAttribute('aria-valuenow'))).toBeLessThan(positionBefore);
    // The total is conserved: a resize moves the boundary, it does not consume the canvas.
    expect((await widths()).reduce((sum, width) => sum + width, 0))
      .toBeCloseTo(before.reduce((sum, width) => sum + width, 0), -1);

    // Home restores the even split, so the keyboard has a way back out of any geometry.
    await page.keyboard.press('Home');
    await expect.poll(async () => {
      const [a, b] = await widths();
      return Math.abs(a - b) <= 2;
    }).toBe(true);

    // The floor holds: pushing far past it stops at 360 rather than collapsing a pane.
    for (let press = 0; press < 60; press += 1) await page.keyboard.press('ArrowLeft');
    expect(Math.min(...(await widths()))).toBeGreaterThanOrEqual(359);
  });
});

test.describe('the width the measurement was taken at', () => {
  test.use({ viewport: MEASURED_NARROW });

  test('does not tile two documents at 1280px with the inspector open', async ({ page }) => {
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'orders.graphml' }));

    // The reason, read from the running page rather than assumed: two panes cannot clear the floor.
    const available = await page.evaluate(() => document.getElementById('cy').clientWidth);
    expect(available / 2).toBeLessThan(360);

    // So the workspace shows the active document alone, kept deliberately
    // as the narrow state rather than reached by accident.
    await expect.poll(() => paneIds(page)).toEqual([second]);
    await expect(page.locator('#cy .pane-separator')).toHaveCount(0);
    // The pane still exists for the background document; it is simply not displayed.
    await expect(page.locator('#cy .doc-pane')).toHaveCount(2);

    // And the strip still names the document that IS shown, because identity is not a wide-screen
    // luxury.
    await expect(page.locator(`#cy .doc-pane[data-document-id="${second}"] .doc-pane-header`))
      .toContainText('orders.graphml');

    await page.evaluate(id => window.ravenroot.activateDocument(id), first);
    await expect.poll(() => paneIds(page)).toEqual([first]);
  });
});

// ── The two defects that pane geometry created, and the regressions that hold them shut ──────────
//
// Both of these were found by looking at a screenshot rather than by a failing test, which is
// exactly the condition that let them exist: nothing exercised a BACKGROUND document's renderer,
// because a background document without a size never repaints.
//
// The shared-chrome one matters beyond itself. `cy.on('select')` and `cy.on('unselect')` used to
// carry the same closed-over-canvas shape the zoom badge shed here — unreachable through a real
// pointer today, because selection needs interaction and interaction implies activation (the pane's
// `pointerdown` focuses and activates before Cytoscape's own gesture ever resolves a tap). The third
// test below drives a background document's OWN `cy` instance directly, the same way the zoom
// assertion above drives `.zoom()` directly, so the corrected shape is held shut without shipping any
// changing what a real pointer can reach.

test.describe('a background document has a renderer too', () => {
  test.use({ viewport: WIDE });

  test('a background pane refitting leaves the zoom badge describing the active document', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    await expect(page.locator('#b-zoom')).not.toHaveText('—');

    // Opening the second document HALVES the first document's pane, so the first document refits and
    // emits `zoom` while the workspace is midway through opening — the instant at which the working
    // view's `cy` has been repointed at the incoming record and is still null.
    //
    // A listener that reads module-level `cy` rather than the emitting instance throws inside
    // `openDocument` at this point, so the second document is never created.
    //
    // The helper waits two paint frames: opening is position-neutral, but the renderer still needs
    // to commit its fit after the pane geometry changes.
    const second = await openDocumentAndWaitForOwnLayout(page, { name: 'orders.graphml' });
    expect(second).toBeTruthy();
    await expect.poll(() => paneIds(page)).toEqual([first, second]);
    expect(errors).toEqual([]);

    // And the badge answers for the document in front of the user, not for whichever instance
    // happened to zoom last.
    await page.evaluate(id => { window.ravenroot.workspace.find(id).cy.zoom(0.31); }, first);
    const activeZoom = await page.evaluate(() => window.ravenroot.activeDocument().cy.zoom());
    // Without this the assertion below would pass in a world where the badge tracked the WRONG
    // document, simply because both documents happened to sit at the same zoom.
    expect(Math.round(activeZoom * 100)).not.toBe(31);
    await expect(page.locator('#b-zoom')).toHaveText(`${Math.round(activeZoom * 100)}%`);
  });

  test('refits a halved pane, and leaves a nudged one framed as the user left it', async ({ page }) => {
    await stubService(page);
    await page.goto('/');

    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const widthOf = id => page.evaluate(
      documentId => window.ravenroot.workspace.find(documentId).cy.width(), id);
    const zoomOf = id => page.evaluate(
      documentId => window.ravenroot.workspace.find(documentId).cy.zoom(), id);
    // Nothing of the graph falls outside its own pane. `resize()` keeps the zoom, so a document
    // framed for a full-width canvas does not shrink to fit a half-width one — it is simply clipped
    // at the boundary, which is what this reads.
    const graphFitsInsideItsPane = id => page.evaluate(documentId => {
      const cy = window.ravenroot.workspace.find(documentId).cy;
      const box = cy.elements().renderedBoundingBox();
      return box.x1 >= -1 && box.y1 >= -1 && box.x2 <= cy.width() + 1 && box.y2 <= cy.height() + 1;
    }, id);

    await expect.poll(() => widthOf(first)).toBeGreaterThan(1000);
    const fullWidth = await widthOf(first);

    // Open immediately. Halving the pane is a change the workspace made, not one the user chose a
    // framing for, so the retained coordinates must still be refitted into the new viewport.
    const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'orders.graphml' }));
    await expect.poll(() => paneIds(page)).toEqual([first, second]);
    await expect.poll(() => widthOf(first)).toBeLessThan(fullWidth * 0.7);

    // So it is refitted, and nothing is left hanging outside the pane.
    await expect.poll(() => graphFitsInsideItsPane(first), { timeout: 15_000 }).toBe(true);
    await expect.poll(() => graphFitsInsideItsPane(second), { timeout: 15_000 }).toBe(true);

    // A separator nudge is the user's OWN framing, so the per-document viewport must be preserved
    // rather than recomputed. Below the threshold the zoom is left alone and only the centre is kept;
    // this also prevents clipping from being addressed by refitting on every resize.
    const separator = page.locator('#cy .pane-separator');
    await separator.focus();
    const zoomBefore = await zoomOf(first);
    const widthBefore = await widthOf(first);
    await page.keyboard.press('ArrowLeft');

    await expect.poll(() => widthOf(first)).toBeLessThan(widthBefore);
    // 32px against ~600 is ~5%: a nudge, not a re-layout.
    expect((widthBefore - await widthOf(first)) / widthBefore).toBeLessThan(0.2);
    expect(await zoomOf(first)).toBeCloseTo(zoomBefore, 5);
  });

  // (UI-10). `cy.on('select')` and `cy.on('unselect')` still wrote `#b-sel` unconditionally from
  // whichever instance's event fired, rather than checking that instance was the active one — the
  // same shape the zoom badge shed above. A real pointer cannot reach this: a click on a background
  // pane focuses and activates it (see the pane's `pointerdown` handler in `documentPane`) BEFORE
  // Cytoscape resolves the gesture into a `select`/`unselect` event, so by the time the event fires,
  // the module-level `cy` the old handler closed over already matches the instance that emitted it.
  // So this drives a background document's own `cy` instance directly — exactly how the zoom
  // assertion above drives `.zoom()` directly — without shipping any change to what a pointer can
  // reach.
  test('a background pane selecting or unselecting a node leaves the selection badge describing the active document', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await stubService(page);
    await page.goto('/');

    // Every document opened without an explicit graph is the same minimal template
    // (`createWorkflowDocument`: start -> dosomething -> {end, error}), so both documents have
    // a `start` node and an `end` node under the same ids — chosen deliberately so a name collision
    // can never be blamed for the assertions below.
    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'orders.graphml' }));
    await expect.poll(() => paneIds(page)).toEqual([first, second]);
    expect(errors).toEqual([]);

    // The active document (second) selects its own "End" node.
    await page.evaluate(id => {
      window.ravenroot.workspace.find(id).cy.getElementById('end').select();
    }, second);
    await expect(page.locator('#b-sel')).toHaveText('End');

    // The background document (first) selects its OWN "start" node, on its OWN cy instance, never
    // touching the active one. "Start" != "End", so the assertion below cannot pass by coincidence —
    // the non-vacuity control the zoom-badge test above uses too.
    await page.evaluate(id => {
      window.ravenroot.workspace.find(id).cy.getElementById('start').select();
    }, first);
    // An unscoped background handler would overwrite this with "Start".
    await expect(page.locator('#b-sel')).toHaveText('End');

    // Unselecting that same background node must not blank the badge either.
    await page.evaluate(id => {
      window.ravenroot.workspace.find(id).cy.getElementById('start').unselect();
    }, first);
    // An unscoped background unselect handler would overwrite this with "-". It has no target to
    // check and fires for ANY unselect, so this is the more exposed direction.
    await expect(page.locator('#b-sel')).toHaveText('End');
    expect(errors).toEqual([]);
  });

  // (UI-11). Loading now paints and sizes labels synchronously without a layout. Opening and
  // immediately backgrounding a document must therefore size its own renderer while leaving the
  // active document and shared readout untouched.
  test('a background document opening without layout leaves the active document\'s label size alone', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await stubService(page);
    await page.goto('/');

    const nodeFontOf = (page_, id) => page_.evaluate(
      documentId => window.ravenroot.workspace.find(documentId).cy.nodes()[0].style('font-size'), id);
    const edgeFontOf = (page_, id) => page_.evaluate(
      documentId => window.ravenroot.workspace.find(documentId).cy.edges()[0].style('font-size'), id);

    // The page load already opened one document; the two below are opened on top of it, so the
    // workspace holds three and the pane assertion has to name all three rather than just the pair.
    const initial = await page.evaluate(() => window.ravenroot.activeDocument().id);

    // The document that will be ACTIVE for the assertions.
    const active = await openDocumentAndWaitForOwnLayout(page, { name: 'beta.graphml' });

    // The document that will be in the BACKGROUND, opened and backgrounded in a single tick.
    const background = await page.evaluate(activeId => {
      const background_ = window.ravenroot.openDocument({ name: 'alpha.graphml' });
      // Opening it made it active; hand the foreground straight back.
      window.ravenroot.activateDocument(activeId);
      return background_;
    }, active);
    // All three records remain visible while the background renderer paints.
    await expect.poll(() => paneIds(page)).toEqual([initial, active, background]);
    expect(await page.evaluate(() => window.ravenroot.activeDocument().id)).toBe(active);

    // The user sizes labels on the document in front of them, through the real control.
    await page.evaluate(() => {
      const slider = document.getElementById('font-slider');
      slider.value = '28';
      slider.dispatchEvent(new Event('input', { bubbles: true }));
    });

    // Non-vacuity, BEFORE the assertions it protects: 28 is not the 20 the stale callback writes, so
    // nothing below can pass by the two values happening to coincide.
    expect(await nodeFontOf(page, active)).toBe('28px');
    expect(await nodeFontOf(page, active)).not.toBe('20px');
    await expect(page.locator('#font-val')).toHaveText('28');

    // Loading paints and sizes the background renderer synchronously; it never starts a layout.
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));

    // Background paint must never resolve `cy` to the active document or reset shared chrome.
    expect(await nodeFontOf(page, active)).toBe('28px');
    await expect(page.locator('#font-val')).toHaveText('28');

    // And the background document got its OWN labels sized, which is the half a bare
    // Node font is not a discriminator here — `applyN8nNodeStyle` sets it during paint — but edge
    // font is: the stylesheet leaves edges at 10px and initial sizing raises them to 15px.
    expect(await edgeFontOf(page, background)).toBe('15px');

    // Permanent, not transitory: the values stay put once everything has settled, just as the zoom
    // badge and clipping state remain document-local.
    await page.waitForTimeout(700);
    expect(await nodeFontOf(page, active)).toBe('28px');
    await expect(page.locator('#font-val')).toHaveText('28');
    expect(await edgeFontOf(page, background)).toBe('15px');

    // Negative half. A later explicit layout on the background document must also leave the active
    // document alone.
    await page.evaluate(id => {
      const instance = window.ravenroot.workspace.find(id).cy;
      window.__rrBackgroundRelaidOut = new Promise(resolve => {
        instance.one('layoutstop', () => resolve());
      });
      instance.layout({ name: 'grid', animate: false }).run();
    }, background);
    await page.evaluate(() => window.__rrBackgroundRelaidOut);
    expect(await nodeFontOf(page, active)).toBe('28px');
    await expect(page.locator('#font-val')).toHaveText('28');

    expect(errors).toEqual([]);
  });
});

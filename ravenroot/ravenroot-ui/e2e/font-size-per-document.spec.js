import { expect, test } from '@playwright/test';

// UI-12: label font size is a property of the DOCUMENT, not the
// workspace — the same axis used for layout, filters and the trace pin. Without document-owned
// storage, `fontSize` has no home on the document record: `layoutMode`, `filterActive`,
// `traceActive`, `n8nActive` and `cursorId` were already captured out of and applied into the active
// document (`captureActiveDocument`/`applyActiveDocument`, `app.js`), but the font size lived only in
// the `#font-slider` DOM element, so `initCy` had nowhere per-document to read a value from and reset
// it to a hardcoded constant on every document it opened.
//
// THIS FILE IS THE LOAD-BEARING TEST. `test/font-size-per-document.test.js` is a cheap static scan
// over `app.js`'s source text — useful as an early warning, and deliberately written as a positive
// assertion (the reset path must trace back to the working-view `fontSize` variable) rather than a
// negative one (no literal `20` at the call site), because a negative scan passes for the wrong
// reason: rename the literal to a same-valued disconnected constant and the scan goes green while the
// document is still reset to a constant on every open. But even the positive version only proves the
// record has somewhere to keep the value and that the reset path is wired to read it — it cannot show
// the VALUE ACTUALLY SURVIVES a real open/switch/reactivate sequence in a running document. That is
// what this file proves, against a real browser: set one document's font, open a second, and confirm
// the first document's value is still there when the user switches back to it. Do not delete this
// file on the strength of the unit test passing.
//
// `initCy`'s `documentChanged = graphData !== gd` gate distinguishes genuinely new content from
// `rebuildGraph`. Loading and rebuilding are now position-neutral and size labels synchronously;
// the second test exercises the rebuild path directly.

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

const nodeFontOf = (page, id) => page.evaluate(
  documentId => window.ravenroot.workspace.find(documentId).cy.nodes()[0].style('font-size'), id);

// Loading does not run a layout. Two frames merely let Cytoscape commit its synchronous style paint
// before the browser-side value is measured.
async function waitForOwnLayout(page, id) {
  await page.evaluate(documentId => new Promise(resolve => requestAnimationFrame(() => {
    window.ravenroot.workspace.find(documentId).cy.forceRender();
    requestAnimationFrame(resolve);
  })), id);
}

async function openDocumentAndWaitForOwnLayout(page, options) {
  const id = await page.evaluate(spec => window.ravenroot.openDocument(spec), options);
  await waitForOwnLayout(page, id);
  return id;
}

async function setFontViaSlider(page, value) {
  await page.evaluate(px => {
    const slider = document.getElementById('font-slider');
    slider.value = String(px);
    slider.dispatchEvent(new Event('input', { bubbles: true }));
  }, value);
}

const liveFontState = page => page.evaluate(() => {
  const owner = window.ravenroot.workspace.find(window.ravenroot.workspace.activeId);
  return {
    recordFont: owner.fontSize,
    style: owner.visualStyle,
    nodeFonts: [...new Set(window.cy.nodes().map(node => node.style('font-size')))],
    edgeFonts: [...new Set(window.cy.edges().map(edge => edge.style('font-size')))],
    slider: document.getElementById('font-slider').value,
    readout: document.getElementById('font-val').textContent,
  };
});

test.describe('font size is a property of the document (UI-12)', () => {
  test('same-task style changes consume the live non-default slider value before capture', async ({ page }) => {
    await stubService(page);
    await page.goto('/');

    // Do not call activeDocument(), open, activate, or wait for a frame between the slider input and
    // these semantic mode commands: each is a capture boundary or scheduling opportunity that can
    // mask a stale-font regression.
    await setFontViaSlider(page, 28);
    for (const selector of ['#btn-design', '#btn-monitoring', '#btn-design']) {
      await page.locator(selector).click();
      expect(await liveFontState(page)).toEqual({
        recordFont: 28,
        style: 'cyto',
        nodeFonts: ['28px'],
        edgeFonts: ['21px'],
        slider: '28',
        readout: '28',
      });
    }
  });

  test('a document keeps its own font size across being backgrounded and reactivated', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await stubService(page);
    await page.goto('/');

    const a = await page.evaluate(() => window.ravenroot.activeDocument().id);
    await waitForOwnLayout(page, a);

    // The user customises A's font through the real control — not by poking the working view.
    await setFontViaSlider(page, 26);
    expect(await nodeFontOf(page, a)).toBe('26px');
    await expect(page.locator('#font-val')).toHaveText('26');

    // Opening B changes the screen to B's own value. Because font size is document-scoped,
    // this correctly changes what is on screen — to B's OWN value, the default, because B has never
    // had one set. Showing that default is the required document-scoped behavior.
    const b = await openDocumentAndWaitForOwnLayout(page, { name: 'orders.graphml' });

    // Non-vacuity before the assertions it protects: 20 is not 26, so nothing below can pass by the
    // two documents' sizes happening to coincide.
    expect(await nodeFontOf(page, b)).not.toBe(await nodeFontOf(page, a));
    expect(await nodeFontOf(page, b)).toBe('20px');
    await expect(page.locator('#font-val')).toHaveText('20');
    expect(await page.locator('#font-slider').inputValue()).toBe('20');

    // Switching back to A must show A's OWN value on its canvas AND on the chrome describing it — not
    // B's, and not the hardcoded default. Without per-document storage, both read whatever the shared
    // `#font-slider` element last held, which by this point is B's.
    await page.evaluate(id => window.ravenroot.activateDocument(id), a);
    expect(await nodeFontOf(page, a)).toBe('26px');
    await expect(page.locator('#font-val')).toHaveText('26');
    expect(await page.locator('#font-slider').inputValue()).toBe('26');

    // And B, revisited, still shows its own value — isolation has to hold in both directions, the
    // same property `workspace.test.js` pins at the record level for `layoutMode` and its siblings.
    await page.evaluate(id => window.ravenroot.activateDocument(id), b);
    expect(await nodeFontOf(page, b)).toBe('20px');
    await expect(page.locator('#font-val')).toHaveText('20');

    expect(errors).toEqual([]);
  });

  test('an ordinary edit does not reset the active document\'s own font size', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await stubService(page);
    await page.goto('/');

    const a = await page.evaluate(() => window.ravenroot.activeDocument().id);
    await waitForOwnLayout(page, a);
    await setFontViaSlider(page, 26);
    expect(await nodeFontOf(page, a)).toBe('26px');

    // A real edit, through the real controls — the same gesture `workspace-panes.spec.js` uses to
    // prove a pane is marked modified. It runs `rebuildGraph`, the only caller that re-enters `initCy`
    // against the SAME `graphData` object rather than a new one. It rebuilds synchronously without
    // running a layout, and the font-reset branch is never reached.
    await page.locator('#btn-modify').click();
    await page.locator('#btn-add-node').click();
    await page.locator('#node-editor button[type="submit"]').click();
    await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');

    expect(await nodeFontOf(page, a)).toBe('26px');
    await expect(page.locator('#font-val')).toHaveText('26');
    expect(errors).toEqual([]);
  });
});

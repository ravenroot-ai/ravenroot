import { expect, test } from '@playwright/test';

// UI-03 document lifecycle. This suite covers two conditions that become reachable the moment more
// than one document is in play:
//
// * Elastic began as a renderer built out of module-global state. It is now owned by the document
// pane, so activation must not redirect, stop, or recreate another visible pane's renderer.
// * Closing the last document empties the workspace, and the chrome had no empty state to fall
// back to — `updateStats` and `drawMinimap` return early when there is no graph, which left the
// closed document's numbers and thumbnail on screen.
//
// The tests drive `window.ravenroot`, the same seam the panes use.

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

// The state is addressed by owner id rather than by active document or a singleton DOM id. That is
// the contract: focus chooses shared chrome; it does not choose which renderer is allowed to live.
const elasticState = (page, documentId) => page.evaluate(ownerId => {
  const owner = window.ravenroot.workspace.find(ownerId);
  const overlay = owner?.pane.querySelector('.d3-elastic');
  const activePane = document.querySelector('#cy .doc-pane[aria-current="true"]');
  const activeCanvas = activePane?.querySelector('.doc-canvas');
  return {
    owner: overlay?.dataset.documentId ?? null,
    generation: owner?.renderer?.token?.generation ?? null,
    overlayActive: Boolean(overlay?.classList.contains('active')),
    overlayInOwnerPane: Boolean(owner?.pane.contains(overlay)),
    ownerCanvasHidden: owner?.container ? getComputedStyle(owner.container).display === 'none' : null,
    ownerIsActive: owner?.id === window.ravenroot.workspace.activeId,
    activeCanvasHidden: activeCanvas
      ? getComputedStyle(activeCanvas).display === 'none'
      : null,
    activeLayout: window.ravenroot.activeDocument()?.layoutMode ?? null,
    sharedHostHidden: document.getElementById('cy').style.display === 'none',
  };
}, documentId);

test('leaving a visible Elastic document keeps its renderer on that owner, not on the next document', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await stubService(page);
  await page.goto('/');

  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  // The template's own shape, read from `first` before anything touches its content -- elastic
  // is a layout mode, not an edit, so this is still the document exactly as opened. Used below
  // as the "same template, not a repaint" baseline instead of a hardcoded node-id list, so a
  // change to the default document's node count cannot fail this test on its own.
  const templateNodeIds = await page.evaluate(id =>
    window.ravenroot.workspace.find(id).cy.nodes().map(node => node.id()).sort(), first);
  const second = await page.evaluate(() => window.ravenroot.openDocument());
  await page.evaluate(id => window.ravenroot.activateDocument(id), first);

  // Turn elastic on for the first document, through the real toolbar.
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => elasticState(page, first)).toMatchObject({
    owner: first,
    overlayActive: true,
    overlayInOwnerPane: true,
    ownerCanvasHidden: true,
    ownerIsActive: true,
    activeCanvasHidden: true,
    activeLayout: 'elastic',
    sharedHostHidden: false,
  });
  const generation = await page.evaluate(id =>
    window.ravenroot.workspace.find(id).renderer.token.generation, first);

  // Switching changes the active canvas and chrome only. The first pane keeps the same generation;
  // the second never inherits its overlay or its hidden-canvas state.
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);

  await expect.poll(() => elasticState(page, first)).toEqual({
    owner: first,
    generation,
    overlayActive: true,
    overlayInOwnerPane: true,
    ownerCanvasHidden: true,
    ownerIsActive: false,
    activeCanvasHidden: false,
    activeLayout: 'cyto',
    sharedHostHidden: false,
  });
  // The user-facing consequence, stated in the user's terms: the active document is on screen.
  await expect(page.locator('#cy')).toBeVisible();
  await expect(page.locator('#cy .doc-canvas.active-document')).toHaveCount(1);

  // The second document is intact and still its own graph, not a repaint of the first. Compared
  // against the template's own node ids (captured above from `first`, untouched by this test)
  // rather than a literal list, so this can't fail just because the default document's shape
  // changes -- it fails only if `second` actually diverges from what opening the template gives.
  expect(await page.evaluate(id =>
    window.ravenroot.workspace.find(id).cy.nodes().map(node => node.id()).sort(), second))
    .toEqual(templateNodeIds);

  expect(errors).toEqual([]);
});

test('returning to a visible Elastic document preserves its renderer generation while chrome follows focus', async ({ page }) => {
  await stubService(page);
  await page.goto('/');

  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const second = await page.evaluate(() => window.ravenroot.openDocument());

  // The second document is the elastic one; the first keeps the default layout.
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => elasticState(page, second)).toMatchObject({
    owner: second, overlayActive: true, ownerIsActive: true, activeLayout: 'elastic',
  });
  const generation = await page.evaluate(id =>
    window.ravenroot.workspace.find(id).renderer.token.generation, second);

  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  await expect.poll(() => elasticState(page, second)).toMatchObject({
    generation, overlayActive: true, ownerIsActive: false, activeLayout: 'cyto',
  });

  // Layout is per document. Coming back must reveal the already-live renderer, not manufacture a
  // new generation as a side effect of focus.
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);
  await expect.poll(() => elasticState(page, second)).toMatchObject({
    generation, overlayActive: true, ownerIsActive: true, activeLayout: 'elastic',
  });
});

test('the layout toolbar describes the active document rather than the one just left', async ({ page }) => {
  await stubService(page);
  await page.goto('/');

  const chrome = () => page.evaluate(() => ({
    active: [...document.querySelectorAll('#topbar .btn.active')].map(button => button.id),
    forcesVisible: document.getElementById('elastic-ctrl').classList.contains('visible'),
    layout: window.ravenroot.activeDocument()?.layoutMode ?? null,
  }));

  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const second = await page.evaluate(() => window.ravenroot.openDocument());
  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  await expect.poll(chrome).toEqual({ active: ['btn-design'], forcesVisible: false, layout: 'cyto' });

  await page.locator('#btn-monitoring').click();
  await expect.poll(chrome).toEqual({ active: ['btn-monitoring'], forcesVisible: true, layout: 'elastic' });

  // The second document was never switched to elastic. A toolbar still highlighting Elastic — and
  // still offering the elastic force sliders — is the inspector-showing-the-wrong-graph problem
  // wearing different clothes, and it is what the panes would multiply.
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);
  await expect.poll(chrome).toEqual({ active: ['btn-design'], forcesVisible: false, layout: 'cyto' });

  // And back, because following the active document has to work in both directions.
  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  await expect.poll(chrome).toEqual({ active: ['btn-monitoring'], forcesVisible: true, layout: 'elastic' });
});

test('closing the last document empties the chrome instead of leaving the closed graph on screen', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await stubService(page);
  await page.goto('/');

  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  // The template's own node count, not a literal -- what matters here is that the count stays
  // put across an unrelated close, not what the default document happens to contain.
  const templateNodeCount = await page.evaluate(id =>
    String(window.ravenroot.workspace.find(id).cy.nodes().length), first);
  const second = await page.evaluate(() => window.ravenroot.openDocument());

  // A graph is loaded, so the statistics describe something.
  await expect(page.locator('#b-nodes')).toHaveText(templateNodeCount);

  await page.evaluate(id => window.ravenroot.closeDocument(id), second);
  await expect.poll(() => page.evaluate(() => window.ravenroot.documents().length)).toBe(1);
  // Closing a document that is not the last one still leaves a document described, and the count
  // is unchanged by closing the other one -- this fails if #b-nodes goes stale or drops early.
  await expect(page.locator('#b-nodes')).toHaveText(templateNodeCount);

  await page.evaluate(id => window.ravenroot.closeDocument(id), first);

  // Closing the last document is permitted, and the workspace is genuinely empty afterwards.
  await expect.poll(() => page.evaluate(() => window.ravenroot.documents().length)).toBe(0);
  expect(await page.evaluate(() => window.ravenroot.activeDocument())).toBeNull();

  // The chrome describes nothing, rather than continuing to describe the document that was closed.
  await expect(page.locator('#b-nodes')).toHaveText('0');
  await expect(page.locator('#b-edges')).toHaveText('0');
  await expect(page.locator('#graph-title')).toHaveText('No graph loaded');
  await expect(page.locator('#empty')).toBeVisible();
  await expect(page.locator('#btn-play')).toBeDisabled();
  await expect(page.locator('#cy .doc-canvas')).toHaveCount(0);

  expect(errors).toEqual([]);
});

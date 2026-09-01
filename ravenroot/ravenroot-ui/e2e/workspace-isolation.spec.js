import { expect, test } from '@playwright/test';

// UI-03 document ownership.
//
// The editor was built around one open graph held in module-level variables. This suite is the
// evidence that a second and a third document can now exist at the same time and keep their own
// selection, viewport, layout, filters and runtime monitoring — and that the single shared toolbar
// acts on the active document only.
//
// The tests drive `window.ravenroot`, which is the same seam the panes use —
// not a private back door opened for the tests.
//
// Without the workspace seam, `window.ravenroot` does not exist and a second document cannot be
// created at all.

const EXECUTIONS = ['exec-1', 'exec-2', 'exec-3'];

async function installRuntime(page) {
  let started = 0;
  let releaseEvents;
  const events = new Promise(resolve => { releaseEvents = resolve; });

  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));

  // Each submission gets its own execution id and graph version, so the three documents end up
  // bound to three different runs.
  await page.route('**/v1/executions*', route => {
    const index = started++;
    return route.fulfill({
      status: 200,
      contentType: 'application/json; charset=utf-8',
      body: JSON.stringify({ executionId: EXECUTIONS[index], graphVersion: `v${index + 1}` }),
    });
  });

  // The stream is held open until the test has finished binding the runs. Without this the client's
  // first request would be answered before any execution existed, and the retry timing would decide
  // the result of the test.
  await page.route('**/v1/events', async route => {
    const body = await events;
    await route.fulfill({ status: 200, contentType: 'text/event-stream', body });
  });

  return { releaseEvents };
}

const frame = payload => `event: execution\ndata: ${JSON.stringify(payload)}\n\n`;

// Opening a document runs its initial layout, which ends in a fit. Writing a viewport before that
// fit lands means measuring the layout instead of the isolation, so the test waits for every
// document's zoom to stop moving first. Each document fits its own canvas — that they settle on
// different values is itself the point — so this waits for quiet, not for a particular number.
async function waitForLayoutsToSettle(page) {
  const zooms = () => page.evaluate(() => window.ravenroot.documents().map(document_ => document_.cy.zoom()));
  await expect.poll(async () => {
    const before = await zooms();
    await page.waitForTimeout(250);
    return JSON.stringify(before) === JSON.stringify(await zooms());
  }, { timeout: 15_000 }).toBe(true);
}

// Plays the active document and waits until the client has bound the execution the service returned.
async function playActiveDocument(page, executionId) {
  await page.locator('#btn-play').click();
  await expect.poll(() => page.evaluate(
    () => window.ravenroot.activeDocument().execution.executionId)).toBe(executionId);
}

async function openSecondAndThirdDocument(page) {
  return page.evaluate(() => [
    window.ravenroot.openDocument(),
    window.ravenroot.openDocument(),
  ]);
}

test('three simultaneous documents keep selection, viewport, layout, filters and monitoring separate', async ({ page }) => {
  const { releaseEvents } = await installRuntime(page);
  await page.goto('/');

  // ── Three documents, three runs ────────────────────────────────────────────────────────────────
  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  await playActiveDocument(page, 'exec-1');
  const [second, third] = await openSecondAndThirdDocument(page);
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);
  await playActiveDocument(page, 'exec-2');
  await page.evaluate(id => window.ravenroot.activateDocument(id), third);
  await playActiveDocument(page, 'exec-3');

  expect(new Set([first, second, third]).size).toBe(3);
  await expect.poll(() => page.evaluate(() => window.ravenroot.documents().length)).toBe(3);

  // Each document really does own a distinct canvas, which is what makes the rest possible.
  expect(await page.evaluate(() => {
    const instances = window.ravenroot.documents().map(document_ => document_.cy);
    return { count: instances.length, distinct: new Set(instances).size, allLive: instances.every(Boolean) };
  })).toEqual({ count: 3, distinct: 3, allLive: true });
  await expect(page.locator('#cy .doc-canvas')).toHaveCount(3);
  // Only the active document is on screen in this ownership-level test.
  await expect(page.locator('#cy .doc-canvas.active-document')).toHaveCount(1);

  // ── Selection, viewport and layout ─────────────────────────────────────────────────────────────
  await waitForLayoutsToSettle(page);
  await page.evaluate(([a, b, c]) => {
    const workspace = window.ravenroot.workspace;
    const of = id => workspace.find(id);
    of(a).cy.getElementById('start').select();
    of(b).cy.getElementById('end').select();
    // The third document deliberately selects nothing.
    of(a).cy.zoom(0.5); of(a).cy.pan({ x: 10, y: 10 });
    of(b).cy.zoom(1.5); of(b).cy.pan({ x: 20, y: 20 });
    of(c).cy.zoom(2.5); of(c).cy.pan({ x: 30, y: 30 });
    of(a).layoutMode = 'dagre';
    of(b).layoutMode = 'elastic';
    of(c).layoutMode = 'n8n';
    of(a).filterActive = null;
    of(b).filterActive = { elType: 'node', type: 'agent' };
    of(c).filterActive = { elType: 'edge', type: 'failed' };
  }, [first, second, third]);

  const view = await page.evaluate(([a, b, c]) => {
    const of = id => window.ravenroot.workspace.find(id);
    const read = id => ({
      selected: of(id).cy.$(':selected').map(element => element.id()),
      zoom: Number(of(id).cy.zoom().toFixed(3)),
      pan: of(id).cy.pan(),
      layout: of(id).layoutMode,
      filter: of(id).filterActive,
    });
    return { a: read(a), b: read(b), c: read(c) };
  }, [first, second, third]);

  expect(view.a.selected).toEqual(['start']);
  expect(view.b.selected).toEqual(['end']);
  expect(view.c.selected).toEqual([]);
  expect([view.a.zoom, view.b.zoom, view.c.zoom]).toEqual([0.5, 1.5, 2.5]);
  expect([view.a.pan, view.b.pan, view.c.pan]).toEqual([{ x: 10, y: 10 }, { x: 20, y: 20 }, { x: 30, y: 30 }]);
  expect([view.a.layout, view.b.layout, view.c.layout]).toEqual(['dagre', 'elastic', 'n8n']);
  expect(view.a.filter).toBeNull();
  expect(view.b.filter).toEqual({ elType: 'node', type: 'agent' });
  expect(view.c.filter).toEqual({ elType: 'edge', type: 'failed' });

  // ── Monitoring: runtime events project onto the matching graph and version only ────────────────
  releaseEvents([
    // Belongs to the second document, which is not the one on screen.
    frame({ type: 'NODE_STARTED', executionId: 'exec-2', graphVersion: 'v2', nodeId: 'start', activeInstances: 3 }),
    // Right execution, stale version: dropped.
    frame({ type: 'NODE_FAILED', executionId: 'exec-1', graphVersion: 'v-old', nodeId: 'start' }),
    // No open document owns this run: dropped.
    frame({ type: 'NODE_FAILED', executionId: 'exec-9', graphVersion: 'v9', nodeId: 'start' }),
  ].join(''));

  await expect.poll(() => page.evaluate(id =>
    window.ravenroot.workspace.find(id).cy.getElementById('start').data('runtimeState'), second)).toBe('active');

  const monitoring = await page.evaluate(([a, b, c]) => {
    const of = id => window.ravenroot.workspace.find(id);
    const read = id => ({
      state: of(id).cy.getElementById('start').data('runtimeState') || 'idle',
      instances: Number(of(id).cy.getElementById('start').data('instances')) || 0,
      execution: of(id).execution.executionId,
    });
    return { a: read(a), b: read(b), c: read(c) };
  }, [first, second, third]);

  // The run that was projected landed on its own document, with its instance count.
  expect(monitoring.b).toEqual({ state: 'active', instances: 3, execution: 'exec-2' });
  // The stale-version event and the unknown-execution event changed nothing anywhere.
  expect(monitoring.a).toEqual({ state: 'idle', instances: 0, execution: 'exec-1' });
  expect(monitoring.c).toEqual({ state: 'idle', instances: 0, execution: 'exec-3' });
});

test('the shared toolbar acts on the active document and leaves the others untouched', async ({ page }) => {
  await installRuntime(page);
  await page.goto('/');

  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const [second, third] = await openSecondAndThirdDocument(page);
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);

  const nodesOf = id => page.evaluate(documentId =>
    window.ravenroot.workspace.find(documentId).cy.nodes().map(node => node.id()).sort(), id);

  // Baseline captured before the edit: all three documents open from the same template, so this
  // is "whatever the default document currently contains" rather than a number this test owns.
  // The property under test is isolation, not the template's shape -- so it must survive that
  // shape changing (verified by mutating the template and re-running, per).
  const before = { first: await nodesOf(first), second: await nodesOf(second), third: await nodesOf(third) };

  // Add a node through the real toolbar and inspector while the second document is active.
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill('Only in the second');
  await page.locator('#node-editor button[type="submit"]').click();

  // The active document gains exactly the one node the toolbar just created, on top of whatever
  // it started with. Fails if the add lands on the wrong document, is dropped, or duplicates.
  await expect.poll(() => nodesOf(second)).toEqual([...before.second, 'node-1'].sort());
  // The other two documents are byte-identical to their own pre-edit snapshot. Fails if an edit
  // made through the shared toolbar leaks onto a document that isn't active.
  expect(await nodesOf(first)).toEqual(before.first);
  expect(await nodesOf(third)).toEqual(before.third);

  // Undo is per document (UI-01): only the edited document is dirty, and the shared dirty indicator
  // describes the active one.
  expect(await page.evaluate(([a, b, c]) => {
    const of = id => window.ravenroot.workspace.find(id);
    return [of(a).history.isDirty(), of(b).history.isDirty(), of(c).history.isDirty()];
  }, [first, second, third])).toEqual([false, true, false]);
  await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');

  // Switching to a clean document switches the shared indicator and the undo button with it.
  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  await expect(page.locator('#dirty-state')).toHaveText('saved');
  await expect(page.locator('#btn-undo')).toBeDisabled();

  // And the edit made in the second document is still there, untouched by the switch.
  expect(await nodesOf(second)).toEqual([...before.second, 'node-1'].sort());
});

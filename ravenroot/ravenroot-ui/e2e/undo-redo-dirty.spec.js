import { expect, test } from '@playwright/test';

// Browser coverage for UI-01: the main editing paths driven through the real toolbar,
// inspector and keyboard, against the real Cytoscape renderer. The document is read back through
// `window.cy`, which app.js already exposes, so the assertions describe what the user sees rendered
// rather than what the model was told to do.

const DIRTY = 'unsaved changes';
const SAVED = 'saved';

async function startEditableWorkflow(page) {
  await page.goto('/');
  await expect(page.locator('#dirty-state')).toHaveText(SAVED);
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
}

// Sorted: Cytoscape collection order is a renderer detail and not part of any contract. Document
// order restoration is asserted where it is actually defined, in test/graph-commands.test.js.
function elementIds(page, selector) {
  return page.evaluate(sel => window.cy.$(sel).map(element => element.id()).sort(), selector);
}

// The base document is no longer just start/end -- it is start, dosomething, error and end
// (`createWorkflowDocument`), with edge-start-dosomething, edge-dosomething-end and
// edge-dosomething-error already in place. Named once here so every test below states what it adds
// or removes relative to this, rather than re-deriving the sorted base lists by hand.
const BASE_NODES = ['dosomething', 'end', 'error', 'start'];
const BASE_EDGES = ['edge-dosomething-end', 'edge-dosomething-error', 'edge-start-dosomething'];

test('adds a node, undoes it and redoes it from the toolbar', async ({ page }) => {
  await startEditableWorkflow(page);
  await expect(page.locator('#btn-undo')).toBeDisabled();

  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill('Review step');
  await page.locator('#node-editor button[type="submit"]').click();

  await expect.poll(() => elementIds(page, 'node')).toEqual([...BASE_NODES, 'node-1'].sort());
  await expect(page.locator('#dirty-state')).toHaveText(DIRTY);
  await expect(page.locator('#btn-undo')).toBeEnabled();
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', 'Undo Add node node-1');

  await page.locator('#btn-undo').click();
  await expect.poll(() => elementIds(page, 'node')).toEqual(BASE_NODES);
  // Undoing back to the load point is the save point again, so the document is clean.
  await expect(page.locator('#dirty-state')).toHaveText(SAVED);
  await expect(page.locator('#btn-undo')).toBeDisabled();

  await page.locator('#btn-redo').click();
  await expect.poll(() => elementIds(page, 'node')).toEqual([...BASE_NODES, 'node-1'].sort());
  await expect(page.locator('#dirty-state')).toHaveText(DIRTY);
  await expect(page.evaluate(() => window.cy.getElementById('node-1').data('name')))
    .resolves.toBe('Review step');
});

test('undoes a delete that took incident edges with it as a single step', async ({ page }) => {
  await startEditableWorkflow(page);
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor button[type="submit"]').click();
  await page.locator('#btn-add-edge').click();
  await page.locator('#edge-editor select[name="source"]').selectOption('start');
  await page.locator('#edge-editor select[name="target"]').selectOption('node-1');
  await page.locator('#edge-editor button[type="submit"]').click();
  await expect.poll(() => elementIds(page, 'edge')).toEqual([...BASE_EDGES, 'edge-1'].sort());

  // Block body, no implicit return -- every Cytoscape mutator returns the core or the
  // collection, and Playwright would copy that whole cyclic object graph back. See
  // `e2e/edge-authoring.spec.js:fitPinnedGraph` for the measurement and
  // `test/e2e-evaluate-return.test.js` for the guard.
  await page.evaluate(() => { window.cy.getElementById('start').select(); });
  await page.locator('body').press('Delete');

  // `start`'s two incident edges (to node-1 and to dosomething) go with it; dosomething's own two
  // edges (to end and to error) are untouched, since dosomething itself isn't deleted.
  await expect.poll(() => elementIds(page, 'node')).toEqual(['dosomething', 'end', 'error', 'node-1']);
  await expect.poll(() => elementIds(page, 'edge')).toEqual(['edge-dosomething-end', 'edge-dosomething-error']);

  // One gesture removed one node and two edges; one undo must bring all three back.
  await page.locator('#btn-undo').click();
  await expect.poll(() => elementIds(page, 'node')).toEqual([...BASE_NODES, 'node-1'].sort());
  await expect.poll(() => elementIds(page, 'edge')).toEqual([...BASE_EDGES, 'edge-1'].sort());
  await expect(page.evaluate(() => window.cy.getElementById('edge-1').data('source'))).resolves.toBe('start');
});

// The opening layout is animated, so a position read too early is a frame of an animation rather
// than the document's coordinates. Poll until two consecutive reads agree.
async function settledPositions(page) {
  const read = () => page.evaluate(() => ({
    start: window.cy.getElementById('start').position(),
    end: window.cy.getElementById('end').position(),
  }));
  let previous = await read();
  let stable = 0;
  // Three consecutive agreeing reads, not one: the layout is computed asynchronously and only then
  // animated, so two early reads can agree simply because the animation has not started yet.
  for (let attempt = 0; attempt < 40 && stable < 3; attempt += 1) {
    await page.waitForTimeout(150);
    const current = await read();
    stable = JSON.stringify(current) === JSON.stringify(previous) ? stable + 1 : 0;
    previous = current;
  }
  expect(stable, 'graph positions never settled').toBeGreaterThanOrEqual(3);
  return previous;
}

test('undoes a multi-node drag as a single step and restores both positions', async ({ page }) => {
  // Viewer owns node positioning; Editing deliberately turns the same drag into edge authoring.
  await page.goto('/');
  await expect(page.locator('#dirty-state')).toHaveText(SAVED);
  const before = await settledPositions(page);

  await page.evaluate(() => {
    window.cy.getElementById('start').select();
    window.cy.getElementById('end').select();
  });
  // A real pointer drag on the Cytoscape canvas: grabbing one node of a multi-selection moves the
  // whole selection, which is the gesture the command model has to fold into one step.
  const box = await page.locator('#cy').boundingBox();
  const from = await page.evaluate(() => window.cy.getElementById('start').renderedPosition());
  await page.mouse.move(box.x + from.x, box.y + from.y);
  await page.mouse.down();
  await page.mouse.move(box.x + from.x + 60, box.y + from.y + 45, { steps: 12 });
  await page.mouse.up();

  await expect.poll(() => page.evaluate(() => window.cy.getElementById('start').position().x))
    .toBeGreaterThan(before.start.x);
  await expect(page.locator('#dirty-state')).toHaveText(DIRTY);
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', 'Undo Move 2 nodes');

  await page.locator('#btn-undo').click();
  expect(await settledPositions(page)).toEqual(before);
  await expect(page.locator('#btn-undo')).toBeDisabled();
});

test('drives undo, redo and save from the keyboard and clears the dirty state on save', async ({ page }) => {
  await startEditableWorkflow(page);
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor button[type="submit"]').click();
  await expect(page.locator('#dirty-state')).toHaveText(DIRTY);

  const modifier = process.platform === 'darwin' ? 'Meta' : 'Control';
  await page.locator('body').press(`${modifier}+z`);
  await expect.poll(() => elementIds(page, 'node')).toEqual(BASE_NODES);
  await page.locator('body').press(`${modifier}+Shift+z`);
  await expect.poll(() => elementIds(page, 'node')).toEqual([...BASE_NODES, 'node-1'].sort());
  await expect(page.locator('#dirty-state')).toHaveText(DIRTY);

  const download = page.waitForEvent('download');
  await page.locator('body').press(`${modifier}+s`);
  expect((await download).suggestedFilename()).toBe('untitled.graphml');
  await expect(page.locator('#dirty-state')).toHaveText(SAVED);

  // A further edit is unsaved work again, and undoing back to the save point is clean again.
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor button[type="submit"]').click();
  await expect(page.locator('#dirty-state')).toHaveText(DIRTY);
  await page.locator('#btn-undo').click();
  await expect(page.locator('#dirty-state')).toHaveText(SAVED);
});

test('New preserves unsaved work in its original document and activates a clean document', async ({ page }) => {
  await startEditableWorkflow(page);
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor button[type="submit"]').click();
  await expect(page.locator('#dirty-state')).toHaveText(DIRTY);

  await page.locator('#btn-new').click();
  await expect.poll(() => elementIds(page, 'node')).toEqual(BASE_NODES);
  await expect(page.locator('#dirty-state')).toHaveText(SAVED);
  await expect(page.locator('#btn-undo')).toBeDisabled();
  await expect.poll(() => page.evaluate(() => window.ravenroot.documents().map(document_ => ({
    name: document_.displayName,
    dirty: document_.history.isDirty(),
  })))).toEqual([
    { name: 'untitled.graphml', dirty: true },
    { name: 'untitled-2.graphml', dirty: false },
  ]);
});

test('a clean document neither prompts on New nor warns on unload', async ({ page }) => {
  await startEditableWorkflow(page);
  let dialogs = 0;
  page.on('dialog', dialog => { dialogs += 1; return dialog.dismiss(); });

  await page.locator('#btn-new').click();
  await expect.poll(() => elementIds(page, 'node')).toEqual(BASE_NODES);
  expect(dialogs).toBe(0);

  await page.close({ runBeforeUnload: true });
  expect(dialogs).toBe(0);
});

test('a dirty document raises the browser leave-site confirmation on unload', async ({ page }) => {
  await startEditableWorkflow(page);
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor button[type="submit"]').click();
  await expect(page.locator('#dirty-state')).toHaveText(DIRTY);

  const beforeUnload = page.waitForEvent('dialog');
  await page.close({ runBeforeUnload: true });
  const dialog = await beforeUnload;
  expect(dialog.type()).toBe('beforeunload');
  await dialog.dismiss();
});

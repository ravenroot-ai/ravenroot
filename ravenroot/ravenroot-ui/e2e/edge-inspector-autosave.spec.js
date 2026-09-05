import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const EDGE_ID = 'edge-dosomething-end';

async function openEditable(page) {
  await page.goto('/');
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
}

async function selectOnly(page, id) {
  await page.evaluate(elementId => {
    window.cy.$(':selected').unselect();
    if (elementId) window.cy.getElementById(elementId).select();
  }, id);
}

const edgeState = page => page.evaluate(edgeId => {
  const document_ = window.ravenroot.activeDocument();
  return {
    edge: structuredClone(document_.graph.edges.find(edge => edge.id === edgeId)),
    depth: document_.history.depth(),
  };
}, EDGE_ID);

async function downloadGraphML(page) {
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('#btn-export').click(),
  ]);
  return readFile(await download.path(), 'utf8');
}

test('existing edge text autosaves as one focus-session undo and manual Save is idempotent', async ({ page }) => {
  await openEditable(page);
  await selectOnly(page, EDGE_ID);
  const description = page.locator('#edge-editor textarea[name="description"]');
  const before = await edgeState(page);

  await description.fill('');
  await description.pressSequentially('Autosaved edge description', { delay: 15 });
  await expect.poll(async () => (await edgeState(page)).edge.description)
    .toBe('Autosaved edge description');
  expect((await edgeState(page)).depth).toBe(before.depth + 1);

  const autosavedGraphML = await downloadGraphML(page);
  await page.getByRole('button', { name: 'Save edge' }).click();
  expect((await edgeState(page)).depth).toBe(before.depth + 1);
  expect(await downloadGraphML(page)).toBe(autosavedGraphML);
  await page.locator('#btn-undo').click();
  await expect.poll(async () => (await edgeState(page)).edge.description).toBe(before.edge.description);
  await expect(page.locator('#edge-editor textarea[name="description"]')).toHaveValue(before.edge.description);
  await page.locator('#btn-redo').click();
  await expect.poll(async () => (await edgeState(page)).edge.description)
    .toBe('Autosaved edge description');
});

test('edge autosave preserves endpoints, routing, ordinary fields, and custom typed properties', async ({ page }) => {
  await openEditable(page);
  await selectOnly(page, EDGE_ID);
  const form = page.locator('#edge-editor');

  await form.locator('select[name="source"]').selectOption('start');
  await expect.poll(async () => (await edgeState(page)).edge.source).toBe('start');
  await form.locator('select[name="target"]').selectOption('dosomething');
  await expect.poll(async () => (await edgeState(page)).edge.target).toBe('dosomething');
  await form.locator('input[name="outcome"]').fill('completed');
  await expect.poll(async () => (await edgeState(page)).edge.outcome).toBe('completed');
  await form.locator('input[name="edgeName"]').fill('Finished route');
  await form.locator('input[name="status"]').fill('0');
  await form.locator('input[name="trafficWeight"]').fill('0');
  await expect.poll(async () => (await edgeState(page)).edge.trafficWeight).toBe(0);
  await form.locator('input[name="trafficWeight"]').fill('0.25');
  await expect.poll(async () => (await edgeState(page)).edge.trafficWeight).toBe(0.25);
  await form.locator('input[name="parallel"]').check();

  await page.locator('[data-add-property="edge-properties"]').click();
  const row = page.locator('#edge-properties .property-row').last();
  await row.locator('[data-property-name]').fill('retry.count');
  await row.locator('[data-property-type]').selectOption('long');
  await row.locator('[data-property-value]').fill('7');
  await expect.poll(async () => (await edgeState(page)).edge.properties['retry.count']).toBe('7');

  await form.locator('input[name="failureRoute"]').check();
  await expect.poll(async () => (await edgeState(page)).edge.properties['failure.route']).toBe('true');
  const saved = (await edgeState(page)).edge;
  expect(saved).toMatchObject({
    id: EDGE_ID,
    source: 'start',
    target: 'dosomething',
    outcome: 'continue',
    label: 'failure',
    edgeType: 'failure',
    edgeName: 'Finished route',
    status: 0,
    trafficWeight: 0.25,
    parallel: true,
    properties: { 'retry.count': '7', 'failure.route': 'true' },
    propertyTypes: { 'retry.count': 'long', 'failure.route': 'string' },
  });

  await form.locator('input[name="failureRoute"]').uncheck();
  await expect.poll(async () => Object.hasOwn((await edgeState(page)).edge.properties, 'failure.route'))
    .toBe(false);
});

test('Autosave OFF and invalid edge drafts use the accessible transition decision without losing state', async ({ page }) => {
  await openEditable(page);
  await page.locator('#btn-autosave').click();
  await selectOnly(page, EDGE_ID);
  const description = page.locator('#edge-editor textarea[name="description"]');
  const before = await edgeState(page);
  await description.fill('Pending edge draft');

  await selectOnly(page, EDGE_ID);
  await expect(page.locator('#inspector-unsaved-dialog')).not.toBeVisible();
  expect((await edgeState(page)).edge.description).toBe(before.edge.description);
  await selectOnly(page, 'start');
  const dialog = page.locator('#inspector-unsaved-dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog).toHaveAttribute('aria-labelledby', 'inspector-unsaved-title');
  await expect(dialog).toHaveAttribute('aria-describedby', 'inspector-unsaved-description');
  await expect(dialog.locator('[data-inspector-unsaved-action="cancel"]')).toBeFocused();
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(description).toHaveValue('Pending edge draft');
  await expect(description).toBeFocused();

  await selectOnly(page, 'start');
  await dialog.locator('[data-inspector-unsaved-action="save"]').click();
  await expect.poll(async () => (await edgeState(page)).edge.description).toBe('Pending edge draft');

  await selectOnly(page, EDGE_ID);
  await page.locator('#btn-autosave').click();
  await page.locator('#edge-editor select[name="source"]').evaluate(control => {
    control.value = '';
    control.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await selectOnly(page, 'end');
  await expect(dialog).toBeVisible();
  await expect(dialog.locator('#inspector-unsaved-description')).toContainText('invalid Inspector changes');
  expect((await edgeState(page)).edge.source).toBe(before.edge.source);
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
});

test('document switching flushes only the owning edge draft and new-edge creation stays explicit', async ({ page }) => {
  await openEditable(page);
  const ids = await page.evaluate(() => {
    const first = window.ravenroot.activeDocument().id;
    const second = window.ravenroot.openDocument({ name: 'second.graphml' });
    return { first, second };
  });
  await page.locator('#document-switcher').click();
  await page.locator(`[data-document-activate="${ids.first}"]`).click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().id)).toBe(ids.first);
  if ((await page.locator('#btn-modify').getAttribute('aria-pressed')) !== 'true') {
    await page.locator('#btn-modify').click();
  }
  await selectOnly(page, EDGE_ID);
  await page.locator('#edge-editor textarea[name="description"]').fill('First document edge');
  await page.locator('#document-switcher').click();
  await page.locator(`[data-document-activate="${ids.second}"]`).click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().id)).toBe(ids.second);
  expect(await page.evaluate(({ first, edgeId }) =>
    window.ravenroot.workspace.find(first).graph.edges.find(edge => edge.id === edgeId).description,
  { first: ids.first, edgeId: EDGE_ID })).toBe('First document edge');

  const before = await page.evaluate(() => window.ravenroot.activeDocument().graph.edges.length);
  await expect(page.locator('.doc-pane--layout-busy')).toHaveCount(0);
  await page.locator('#btn-add-edge').click();
  await expect(page.locator('#edge-editor')).toBeVisible();
  await page.waitForTimeout(250);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.edges.length)).toBe(before);
  await page.locator('#edge-editor button[type="submit"]').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().graph.edges.length))
    .toBe(before + 1);
});

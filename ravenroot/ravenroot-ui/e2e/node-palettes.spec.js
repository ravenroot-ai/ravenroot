import { expect, test } from '@playwright/test';

const ALICE = 'alice-palette-token';
const BOB = 'bob-palette-token';
const DENIED = 'denied-palette-token';
const DAILY = '11111111-1111-4111-8111-111111111111';
const ARCHIVE = '22222222-2222-4222-8222-222222222222';
const BOB_PALETTE = '33333333-3333-4333-8333-333333333333';
const END_TEMPLATE = '44444444-4444-4444-8444-444444444444';

const configuration = { schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024 };
const palette = (id, name, version = 1) => ({
  id, name, version, createdAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z',
});
const template = (id, paletteId, name, node, version = 1) => ({
  id, paletteId, name, kind: node.kind, version,
  createdAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z',
  node: { name, behavior: '', nodeType: 'flow', classname: '', description: '',
    width: 80, height: 52, properties: {}, propertyTypes: {}, workspaceReferences: [], ...node },
});

function tokenOf(request) {
  return String(request.headers().authorization || '').replace(/^Bearer /, '');
}

async function installPaletteRoutes(page) {
  const state = new Map([
    [ALICE, {
      palettes: [palette(DAILY, 'Daily'), palette(ARCHIVE, 'Archive')],
      templates: [template(END_TEMPLATE, DAILY, 'Saved End', { kind: 'END', nodeType: 'end' })],
    }],
    [BOB, { palettes: [palette(BOB_PALETTE, 'Bob only')], templates: [] }],
  ]);
  const requests = [];
  let sequence = 0;
  await page.route('**/v1/configuration', route => {
    const token = tokenOf(route.request());
    if (token === DENIED) return route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({
      schemaVersion: 1, code: 'ACCESS_DENIED', message: 'access denied', correlationId: 'denied-test',
    }) });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(configuration) });
  });
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json', body: '[]',
  }));
  await page.route('**/v1/events**', route => route.fulfill({
    status: 200, contentType: 'text/event-stream', body: '',
  }));
  await page.route('**/v1/node-palettes**', async route => {
    const request = route.request();
    const token = tokenOf(request);
    const owned = state.get(token) || { palettes: [], templates: [] };
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    requests.push({ token, path, method, body: request.postDataJSON?.() });
    const json = (status, body) => route.fulfill({
      status, contentType: 'application/json', body: JSON.stringify(body),
    });
    if (path === '/v1/node-palettes' && method === 'GET') {
      return json(200, { schemaVersion: 1, palettes: owned.palettes, templates: owned.templates });
    }
    if (path === '/v1/node-palettes' && method === 'POST') {
      const created = palette(`aaaaaaaa-aaaa-4aaa-8aaa-${String(++sequence).padStart(12, '0')}`,
        request.postDataJSON().name);
      owned.palettes.push(created);
      return json(200, created);
    }
    if (path === '/v1/node-palettes/templates' && method === 'POST') {
      const body = request.postDataJSON();
      const created = template(`bbbbbbbb-bbbb-4bbb-8bbb-${String(++sequence).padStart(12, '0')}`,
        body.paletteId, body.name, body.node);
      owned.templates.push(created);
      return json(201, created);
    }
    const validate = path.match(/^\/v1\/node-palettes\/templates\/([^/]+)\/validate$/);
    if (validate && method === 'POST') return json(200, { valid: true });
    const templateId = path.match(/^\/v1\/node-palettes\/templates\/([^/]+)$/)?.[1];
    if (templateId && method === 'PATCH') {
      const body = request.postDataJSON();
      const current = owned.templates.find(candidate => candidate.id === templateId);
      Object.assign(current, { paletteId: body.paletteId, name: body.name, version: current.version + 1 });
      return json(200, current);
    }
    if (templateId && method === 'DELETE') {
      owned.templates = owned.templates.filter(candidate => candidate.id !== templateId);
      state.set(token, owned);
      return json(200, { deleted: true });
    }
    const paletteId = path.match(/^\/v1\/node-palettes\/([^/]+)$/)?.[1];
    if (paletteId && method === 'PATCH') {
      const body = request.postDataJSON();
      const current = owned.palettes.find(candidate => candidate.id === paletteId);
      Object.assign(current, { name: body.name, version: current.version + 1 });
      return json(200, current);
    }
    return json(404, { error: 'UNKNOWN_RESOURCE' });
  });
  return { state, requests };
}

async function authenticate(page, token) {
  await page.locator('#access-token').fill(token);
  await page.locator('#btn-authenticate').click();
  if (token !== DENIED) {
    await expect.poll(() => page.evaluate(() =>
      window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
  }
}

async function openPalette(page, name) {
  const details = page.locator('.node-palette').filter({ has: page.locator('summary', { hasText: name }) });
  if (!(await details.evaluate(element => element.open))) await details.locator('summary').click();
  return details;
}

const graphState = page => page.evaluate(() => ({
  nodes: window.cy.nodes().map(node => node.id()).sort(),
  depth: window.ravenroot.activeDocument().history.depth(),
}));

test('saves and manages a real selected node, then inserts by click and drop with atomic undo', async ({ page }) => {
  const routes = await installPaletteRoutes(page);
  await page.goto('/');
  await authenticate(page, ALICE);
  await expect(page.locator('.node-palette summary')).toContainText(['Daily', 'Archive']);
  await page.locator('#btn-modify').click();
  await page.evaluate(() => window.cy.getElementById('dosomething').select());

  const daily = await openPalette(page, 'Daily');
  page.once('dialog', dialog => dialog.accept('Saved behavior'));
  await daily.getByRole('button', { name: 'Save selected node', exact: true }).click();
  const savedButton = page.getByRole('button', { name: 'Insert saved node Saved behavior' });
  await expect(savedButton).toBeVisible();
  const saveRequest = routes.requests.find(request =>
    request.method === 'POST' && request.path === '/v1/node-palettes/templates');
  expect(saveRequest.body.node).toMatchObject({ kind: 'PASSTHROUGH', behavior: '' });

  page.once('dialog', dialog => dialog.accept('Daily renamed'));
  await daily.getByRole('button', { name: 'Rename', exact: true }).click();
  await expect(page.locator('.node-palette summary')).toContainText(['Daily renamed', 'Archive']);

  const currentTemplate = routes.state.get(ALICE).templates.find(candidate => candidate.name === 'Saved behavior');
  const archive = await openPalette(page, 'Archive');
  const move = page.locator(`select[aria-label="Move saved node ${currentTemplate.name} to palette"]`);
  await move.selectOption(ARCHIVE);
  await expect(archive.getByRole('button', { name: 'Insert saved node Saved behavior' })).toBeVisible();

  const beforeClick = await graphState(page);
  await archive.getByRole('button', { name: 'Insert saved node Saved behavior' }).click();
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: expect.arrayContaining(beforeClick.nodes), depth: beforeClick.depth + 1,
  });
  expect((await graphState(page)).nodes).toHaveLength(beforeClick.nodes.length + 1);
  await page.locator('#btn-undo').click();
  await expect.poll(() => graphState(page)).toEqual(beforeClick);

  await archive.getByRole('button', { name: 'Insert saved node Saved behavior' }).evaluate(button => {
    const transfer = new DataTransfer();
    button.dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer: transfer }));
    const target = document.getElementById('cy-wrap');
    const bounds = target.getBoundingClientRect();
    target.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer,
      clientX: bounds.left + bounds.width * 0.7, clientY: bounds.top + bounds.height * 0.65 }));
  });
  await expect.poll(() => graphState(page)).toMatchObject({ depth: beforeClick.depth + 1 });
  expect((await graphState(page)).nodes).toHaveLength(beforeClick.nodes.length + 1);
  await page.locator('#btn-undo').click();
  await expect.poll(() => graphState(page)).toEqual(beforeClick);
});

test('refuses duplicate terminals and clears private palette DOM on failure, replacement, and revoke', async ({ page }) => {
  await installPaletteRoutes(page);
  await page.goto('/');
  await authenticate(page, ALICE);
  await page.locator('#btn-modify').click();
  const daily = await openPalette(page, 'Daily');
  const before = await graphState(page);
  await daily.getByRole('button', { name: 'Insert saved node Saved End' }).click();
  await expect(page.locator('#info-body')).toContainText('already has a END terminal');
  expect(await graphState(page)).toEqual(before);

  await authenticate(page, DENIED);
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.workspacePersistence().authority.state)).toBe('failed');
  await expect(page.locator('#node-palettes')).not.toContainText('Daily');
  await expect(page.locator('#node-palettes')).not.toContainText('Saved End');

  await authenticate(page, BOB);
  await expect(page.locator('#node-palettes')).toContainText('Bob only');
  await expect(page.locator('#node-palettes')).not.toContainText('Daily');
  await page.locator('#btn-revoke').click();
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.workspacePersistence().authority.state)).toBe('revoked');
  await expect(page.locator('#node-palettes')).not.toContainText('Bob only');
  await expect(page.locator('#node-palettes')).toContainText('Create a palette to save reusable configured nodes.');
});

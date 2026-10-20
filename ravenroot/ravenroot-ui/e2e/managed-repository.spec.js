import { expect, test } from '@playwright/test';
import { keepAuthenticationInMemory } from './memory-only-auth.mjs';

const graph = version => `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
<key id="gid" for="graph" attr.name="ravenroot.authoring.graphId" attr.type="string"/>
<key id="ver" for="graph" attr.name="ravenroot.authoring.releaseVersion" attr.type="long"/>
<key id="kind" for="node" attr.name="kind" attr.type="string"/>
<graph id="orders" edgedefault="directed"><data key="gid">orders</data><data key="ver">${version}</data>
<node id="start"><data key="kind">START</data></node></graph></graphml>`;

async function setup(page, { initialVersion = 1, canWrite = true, supported = true, authenticated = false, legacy = false } = {}) {
  await keepAuthenticationInMemory(page);
  let version = initialVersion, draft = legacy ? graph(version).replace('id="start"', 'id="legacy"') : graph(version), revision = 1;
  const requests = [];
  const envelope = source => ({ documentId: 'orders.graphml', displayPath: 'orders.graphml', graphId: 'orders',
    releaseVersion: source === 'release' ? 1 : version, releasedVersion: 1, releasedGraphId: 'orders',
    released: true, published: false, draftDeleted: source === 'release', canWrite,
    revision: { draft: `d${revision}`, release: 'r1', publication: 'absent' },
    graphMl: Buffer.from(source === 'release' ? graph(1) : draft).toString('base64') });
  await page.route('**/v1/configuration', route => route.fulfill(authenticated
    && route.request().headers().authorization !== 'Bearer managed-token'
    ? { status: 401, json: { error: 'authentication required' } }
    : { json: { schemaVersion: 1, graphDocumentMaxBytes: 1048576,
      graphAuthoring: { mode: 'git', provider: 'github', operations: ['OPEN', 'SAVE', ...(supported ? ['CREATE_NEXT_VERSION'] : [])],
        repositoryDisplay: 'example/graphs', releaseBranch: 'main' } } }));
  await page.route('**/v1/node-types', route => route.fulfill({ json: [] }));
  await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
  await page.route('**/v1/graph-authoring**', route => {
    const req = route.request(), url = new URL(req.url());
    requests.push({ method: req.method(), path: url.pathname, headers: req.headers(), body: req.postData() });
    if (url.pathname.endsWith('/versions')) {
      if (version > 1) return route.fulfill({ status: 409, json: { error: 'CONFLICT' } });
      version = 2; draft = graph(2); revision++;
      return route.fulfill({ json: envelope('draft') });
    }
    if (req.method() === 'PUT') {
      draft = req.postData(); revision++;
      return route.fulfill({ json: envelope('draft') });
    }
    if (url.pathname === '/v1/graph-authoring') return route.fulfill({ json: { items: [envelope('draft')], nextCursor: '' } });
    return route.fulfill({ json: envelope(url.searchParams.get('source')) });
  });
  return requests;
}

test('authenticated managed draft launch saves the same identity without prompts and reopens', async ({ page }) => {
  const requests = await setup(page, { initialVersion: 2, authenticated: true });
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#repository-notice')).toBeVisible();
  await page.locator('#access-token').fill('managed-token');
  await page.locator('#btn-authenticate').click();
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  await expect(page.locator('#repository-next-version')).toBeHidden();
  page.on('dialog', dialog => {
    expect(dialog.type(), 'Managed saves must not prompt for a filename').not.toBe('prompt');
    throw new Error(`Unexpected ${dialog.type()} dialog after an acknowledged managed save`);
  });
  await page.locator('#btn-modify').click();
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill('Saved managed edit');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.locator('#btn-export').click();
  await expect.poll(() => requests.filter(r => r.method === 'PUT').length).toBe(1);
  expect(requests.find(r => r.method === 'PUT').headers['x-ravenroot-expected-release']).toBe('r1');
  expect(requests.find(r => r.method === 'PUT').body).toContain('Saved managed edit');
  await expect.poll(() => page.evaluate(() => ({
    dirty: window.ravenroot.workspace.active.history.isDirty(),
    saving: window.ravenroot.workspace.active.authoring.saveFlight,
  }))).toEqual({ dirty: false, saving: null });
  await page.reload();
  await page.locator('#access-token').fill('managed-token');
  await page.locator('#btn-authenticate').click();
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  expect(await page.evaluate(() => window.ravenroot.workspace.active.graph.nodes.some(n => n.name === 'Saved managed edit'))).toBe(true);
});

test('release is read-only and requires explicit confirmation before creating unpublished successor', async ({ page }) => {
  const requests = await setup(page);
  await page.goto('/?repositoryDocument=orders.graphml&source=release');
  await expect(page.locator('#graph-mode-label')).toContainText('Released source · v1');
  await expect(page.locator('#btn-modify')).toBeDisabled();
  await expect(page.locator('#repository-next-version')).toHaveText('Create next version');
  page.once('dialog', dialog => { expect(dialog.message()).toContain('v2'); return dialog.dismiss(); });
  await page.locator('#repository-next-version').click();
  expect(requests.filter(r => r.method === 'POST')).toHaveLength(0);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#repository-next-version').click();
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  await expect(page.locator('#graph-mode-label')).toContainText('Publication pending or failed');
  await expect(page.locator('#repository-next-version')).toBeHidden();
  expect(requests.filter(r => r.method === 'POST')).toHaveLength(1);
});

test('existing external successor is offered for opening without another increment', async ({ page }) => {
  const requests = await setup(page, { initialVersion: 2 });
  await page.goto('/?repositoryDocument=orders.graphml&source=release');
  await expect(page.locator('#repository-next-version')).toHaveText('Open existing draft');
  await page.locator('#repository-next-version').click();
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  expect(requests.some(r => r.method === 'POST')).toBe(false);
});

test('legacy divergent same-version draft is preserved with reconciliation guidance', async ({ page }) => {
  const requests = await setup(page, { legacy: true });
  await page.goto('/?repositoryDocument=orders.graphml&source=release');
  await expect(page.locator('#repository-notice')).toContainText('Back up all work');
  await expect(page.locator('#repository-next-version')).toBeHidden();
  expect(requests.some(r => r.method === 'POST')).toBe(false);
});

for (const options of [{ canWrite: false }, { supported: false }]) {
  test(`release action respects authenticated permission and capability ${JSON.stringify(options)}`, async ({ page }) => {
    await setup(page, options);
    await page.goto('/?repositoryDocument=orders.graphml&source=release');
    await expect(page.locator('#graph-mode-label')).toContainText('Released source');
    await expect(page.locator('#repository-next-version')).toBeHidden();
  });
}

test('invalid managed launch remains visible and never imports or opens repository', async ({ page }) => {
  const requests = await setup(page);
  await page.goto('/?repositoryDocument=orders.graphml&source=release&file=orders.graphml');
  await expect(page.locator('#repository-notice')).toContainText('Invalid repository launch');
  expect(requests).toHaveLength(0);
  await expect(page.locator('#startup-import')).toBeHidden();
});

test('refused managed save remains visible with panels closed and preserves dirty edits', async ({ page }) => {
  await setup(page, { initialVersion: 2 });
  await page.route('**/v1/graph-authoring/orders.graphml', route => route.request().method() === 'PUT'
    ? route.fulfill({ status: 409, json: { error: 'CONFLICT' } }) : route.fallback());
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  await page.locator('#btn-modify').click();
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill('Preserved work');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.locator('[data-action="panel-close"][data-panel="inspector"]').click();
  await page.locator('[data-action="panel-close"][data-panel="activity"]').click();
  await page.locator('#btn-export').click();
  await expect(page.locator('#repository-notice')).toContainText('Your edits are intact');
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.active.history.isDirty())).toBe(true);
  expect(await page.evaluate(() => window.ravenroot.workspace.active.graph.nodes.some(n => n.name === 'Preserved work'))).toBe(true);
  if (process.env.RAVENROOT_EVIDENCE_DIR) await page.screenshot({ path: `${process.env.RAVENROOT_EVIDENCE_DIR}/issue588-save-error.png` });
});

test('picker exposes initial errors, retry and empty inventory', async ({ page }) => {
  await setup(page);
  let unavailable = true;
  await page.route('**/v1/graph-authoring', route => {
    if (unavailable) { unavailable = false; return route.fulfill({ status: 503, json: { error: 'unavailable' } }); }
    return route.fulfill({ json: { items: [], nextCursor: '' } });
  });
  await page.goto('/');
  await page.locator('#btn-open').click();
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('This repository has no documents');
});

test('picker searches all paginated sources and preserves partial failure with retry', async ({ page }) => {
  await setup(page);
  let failed = true;
  await page.route('**/v1/graph-authoring?cursor=**', route => {
    if (failed) { failed = false; return route.fulfill({ status: 503, json: { error: 'unavailable' } }); }
    return route.fulfill({ json: { items: [{ documentId: 'later.graphml', displayPath: 'later.graphml',
      releaseVersion: 3, releasedVersion: 2, released: true, published: false }], nextCursor: '' } });
  });
  await page.route('**/v1/graph-authoring', route => route.fulfill({ json: { items: [{ documentId: 'orders.graphml',
    releaseVersion: 1, releasedVersion: 1, released: true, published: false }, ...Array.from({ length: 30 }, (_, i) => ({ documentId: `graph-${i}.graphml`, releaseVersion: 1, releasedVersion: 0, released: false, published: false }))], nextCursor: 'page2' } }));
  await page.goto('/');
  await page.locator('#btn-open').click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: 'More documents are available' })).toBeVisible();
  expect(await page.locator('.repository-picker-list').evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true);
  await page.getByRole('button', { name: 'Load more' }).click();
  await expect(page.getByRole('button', { name: 'Retry', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await page.getByRole('searchbox').fill('later');
  await expect(page.getByRole('button', { name: /later.graphml · Editable draft v3/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /orders.graphml · Editable/ })).toHaveCount(0);
  await page.getByRole('combobox', { name: 'Source filter' }).selectOption('release');
  await expect(page.getByRole('button', { name: /later.graphml · Read-only release v2/ })).toBeVisible();
  if (process.env.RAVENROOT_EVIDENCE_DIR) await page.screenshot({ path: `${process.env.RAVENROOT_EVIDENCE_DIR}/issue588-picker.png` });
});

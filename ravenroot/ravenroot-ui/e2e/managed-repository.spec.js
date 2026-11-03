import { expect, test } from '@playwright/test';
import { keepAuthenticationInMemory } from './memory-only-auth.mjs';
import { PID, deferred, deploymentService } from './deployment-run-fixture.mjs';

test.use({ viewport: { width: 1600, height: 1000 } });

const graph = version => `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
<key id="gid" for="graph" attr.name="ravenroot.authoring.graphId" attr.type="string"/>
<key id="ver" for="graph" attr.name="ravenroot.authoring.releaseVersion" attr.type="long"/>
<key id="kind" for="node" attr.name="kind" attr.type="string"/>
<graph id="orders" edgedefault="directed"><data key="gid">orders</data><data key="ver">${version}</data>
<node id="start"><data key="kind">START</data></node></graph></graphml>`;

async function setup(page, { initialVersion = 1, canWrite = true, supported = true, authenticated = false, legacy = false, automaticSuccessor = false, configuration = {} } = {}) {
  await keepAuthenticationInMemory(page);
  let version = initialVersion, draft = legacy ? graph(version).replace('id="start"', 'id="legacy"') : graph(version), revision = 1;
  const requests = [];
  requests.setDraft = (nextVersion, xml = graph(nextVersion)) => { version = nextVersion; draft = xml; revision++; };
  const envelope = source => ({ documentId: 'orders.graphml', displayPath: 'orders.graphml', graphId: 'orders',
    releaseVersion: source === 'release' ? 1 : version, releasedVersion: 1, releasedGraphId: 'orders',
    released: true, published: false, draftDeleted: source === 'release', canWrite,
    revision: { draft: `d${revision}`, release: 'r1', publication: 'absent' },
    graphMl: Buffer.from(source === 'release' ? graph(1) : draft).toString('base64') });
  await page.route('**/v1/configuration', route => route.fulfill(authenticated
    && route.request().headers().authorization !== 'Bearer managed-token'
    ? { status: 401, json: { error: 'authentication required' } }
    : { json: { schemaVersion: 1, graphDocumentMaxBytes: 1048576,
      ...(configuration.tenantId ? { workspace: { tenantId: configuration.tenantId } } : {}),
      graphAuthoring: { mode: 'git', provider: configuration.provider || 'github', operations: ['OPEN', 'SAVE', ...(supported ? ['CREATE_NEXT_VERSION'] : [])],
        repositoryDisplay: configuration.repositoryDisplay || 'example/graphs', releaseBranch: 'main',
        ...(configuration.releaseAutomation ? { releaseAutomation: configuration.releaseAutomation } : {}) } } }));
  await page.route('**/v1/node-types', route => route.fulfill({ json: [] }));
  await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
  await page.route('**/v1/graph-authoring**', route => {
    const req = route.request(), url = new URL(req.url());
    requests.push({ method: req.method(), path: url.pathname, source: url.searchParams.get('source'), headers: req.headers(), body: req.postData() });
    if (url.pathname.endsWith('/versions')) {
      if (version > 1) return route.fulfill({ status: 409, json: { error: 'CONFLICT' } });
      version = 2; draft = graph(2); revision++;
      return route.fulfill({ json: envelope('draft') });
    }
    if (req.method() === 'PUT') {
      draft = req.postData(); revision++;
      if (automaticSuccessor && version === 1 && draft !== graph(1)) {
        version = 2; draft = draft.replace(/(<data key="ver">)1(<\/data>)/, '$12$2');
      }
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

async function addUnsavedNode(page, name = 'Unsaved sentinel') {
  if (await page.locator('#btn-add-node').isDisabled()) await page.locator('#btn-modify').click();
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill(name);
  await page.locator('#node-editor button[type="submit"]').click();
}

async function selectRepositorySource(page, source = 'draft') {
  await page.locator('#menu-file').click();
  await page.locator('#application-menu [data-command-id="file.open"]').click();
  await page.getByRole('button', { name: source === 'draft'
    ? /orders.graphml · Editable draft/ : /orders.graphml · Read-only release/ }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
}

const activeManagedState = page => page.evaluate(() => {
  const target = window.ravenroot.activeDocument();
  return { id: target.id, incarnation: target.incarnation,
    nodes: target.graph.nodes.map(node => ({ id: node.id, name: node.name })),
    history: target.history.state(), authoring: structuredClone(target.authoring) };
});

async function expectUniqueWorkspaceIds(page) {
  expect(await page.evaluate(() => {
    const ids = window.ravenroot.workspace.documents.map(document_ => document_.id);
    return ids.length === new Set(ids).size;
  })).toBe(true);
}

function forbidUnexpectedSideEffects(page) {
  page.on('dialog', dialog => { throw new Error(`Unexpected ${dialog.type()} dialog: ${dialog.message()}`); });
  page.on('download', () => { throw new Error('Reopening must not download or export a document'); });
}

for (const entry of ['picker', 'successor', 'refused-save']) {
  test(`reopening an existing dirty managed draft through ${entry} preserves graph, binding and undo`, async ({ page }) => {
    const requests = await setup(page, { initialVersion: 2 });
    let refusedSaves = 0;
    if (entry === 'refused-save') await page.route('**/v1/graph-authoring/orders.graphml', route =>
      route.request().method() === 'PUT'
        ? (refusedSaves++, route.fulfill({ status: 409, json: { error: 'CONFLICT' } })) : route.fallback());
    await page.goto('/?repositoryDocument=orders.graphml&source=draft');
    await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
    forbidUnexpectedSideEffects(page);
    await addUnsavedNode(page);
    if (entry === 'refused-save') {
      await page.locator('#btn-export').click();
      await expect(page.locator('#repository-notice')).toContainText('Your edits are intact');
      await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.active.authoring.saveFlight)).toBe(null);
    }
    const before = await activeManagedState(page);
    expect(before.history.dirty).toBe(true);
    expect(before.history.canUndo).toBe(true);
    if (entry === 'successor') {
      await selectRepositorySource(page, 'release');
      await expect(page.locator('#repository-next-version')).toHaveText('Open existing draft');
      await page.locator('#repository-next-version').click();
    } else await selectRepositorySource(page);
    await expect(page.locator('#repository-notice')).toContainText('Your local graph and undo history were kept');
    expect(await activeManagedState(page)).toEqual(before);
    if (entry === 'refused-save') await expect(page.locator('#repository-notice')).toContainText('previous save is still refused');
    await expectUniqueWorkspaceIds(page);
    if (entry === 'picker' && process.env.RAVENROOT_EVIDENCE_DIR) await page.screenshot({ path: `${process.env.RAVENROOT_EVIDENCE_DIR}/issue588-attempt4-preserved-draft.png` });
    expect(await page.evaluate(() => window.ravenroot.workspace.documents.filter(d =>
      d.authoring.providerDocumentId === 'orders.graphml' && !d.authoring.draftDeleted).length)).toBe(1);
    expect(requests.filter(request => ['POST', 'DELETE'].includes(request.method))).toHaveLength(0);
    expect(refusedSaves).toBe(entry === 'refused-save' ? 1 : 0);
    expect(requests.filter(request => request.method === 'PUT')).toHaveLength(0); // refused route was handled separately
    await page.locator('#btn-undo').click();
    expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.nodes.some(n => n.name === 'Unsaved sentinel'))).toBe(false);
    await page.locator('#btn-redo').click();
    expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.nodes.some(n => n.name === 'Unsaved sentinel'))).toBe(true);
  });
}

test('clean managed reopen refreshes the current external successor in the same workspace record', async ({ page }) => {
  const requests = await setup(page);
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v1');
  forbidUnexpectedSideEffects(page);
  const before = await activeManagedState(page);
  requests.setDraft(2);
  await selectRepositorySource(page);
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  const after = await activeManagedState(page);
  expect(after.id).toBe(before.id);
  expect(after.authoring.revision.draft).toBe('d2');
  expect(after.history.dirty).toBe(false);
  expect(after.history.canUndo).toBe(false);
  await expectUniqueWorkspaceIds(page);
  expect(requests.filter(request => request.method !== 'GET')).toHaveLength(0);
});

test('local edits made during a clean managed fetch preserve the original revision and undo history', async ({ page }) => {
  const requests = await setup(page);
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v1');
  forbidUnexpectedSideEffects(page);
  requests.setDraft(2);
  let release, started;
  const gate = new Promise(resolve => { release = resolve; });
  const entered = new Promise(resolve => { started = resolve; });
  await page.route('**/v1/graph-authoring/orders.graphml?source=draft', async route => {
    started(); await gate; await route.fallback();
  });
  try {
    await selectRepositorySource(page);
    await entered;
    await addUnsavedNode(page, 'Edited during fetch');
    const before = await activeManagedState(page);
    release();
    await expect(page.locator('#repository-notice')).toContainText('Your local graph and undo history were kept');
    expect(await activeManagedState(page)).toEqual(before);
    expect(before.authoring.revision.draft).toBe('d1');
    await expectUniqueWorkspaceIds(page);
    expect(requests.filter(request => request.method !== 'GET')).toHaveLength(0);
  } finally { release(); }
});

test('restored unsaved managed source survives a direct startup link without fetching over local work', async ({ page, context }) => {
  await setup(page, { initialVersion: 2, configuration: { tenantId: 'tenant-a' } });
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  await addUnsavedNode(page, 'Restored unsaved sentinel');
  const before = await activeManagedState(page);
  await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
  const restored = await context.newPage();
  const requests = await setup(restored, { initialVersion: 2, configuration: { tenantId: 'tenant-a' } });
  forbidUnexpectedSideEffects(restored);
  await restored.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(restored.locator('#repository-notice')).toContainText('Your local graph and undo history were kept');
  const after = await activeManagedState(restored);
  expect(after.id).toBe(before.id);
  expect(after.nodes).toEqual(before.nodes);
  expect(after.authoring.revision).toEqual(before.authoring.revision);
  expect(after.authoring.repositoryScope).toBe(before.authoring.repositoryScope);
  expect(after.authoring.localChanges).toBe(true);
  await expect(restored.locator('#graph-mode-label')).toContainText('Unsaved edits');
  await expectUniqueWorkspaceIds(restored);
  expect(await restored.evaluate(() => {
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event); return event.defaultPrevented;
  })).toBe(true);
  await restored.evaluate(() => window.ravenroot.requestCloseDocument(window.ravenroot.workspace.active.id));
  await expect(restored.locator('#unsaved-document-dialog')).toBeVisible();
  await restored.locator('[data-unsaved-action="cancel"]').click();
  expect(requests).toHaveLength(0);
  let refuse = true;
  await restored.route('**/v1/graph-authoring/orders.graphml', route =>
    route.request().method() === 'PUT' && refuse
      ? route.fulfill({ status: 409, json: { error: 'CONFLICT' } }) : route.fallback());
  await restored.locator('#btn-export').click();
  await expect(restored.locator('#repository-notice')).toContainText('Your edits are intact');
  expect((await activeManagedState(restored)).authoring.localChanges).toBe(true);
  await selectRepositorySource(restored);
  await expect(restored.locator('#repository-notice')).toContainText('previous save is still refused');
  expect((await activeManagedState(restored)).nodes).toEqual(before.nodes);
  refuse = false;
  let releaseSave, saveEntered;
  const saveGate = new Promise(resolve => { releaseSave = resolve; });
  const enteredSave = new Promise(resolve => { saveEntered = resolve; });
  await restored.route('**/v1/graph-authoring/orders.graphml', async route => {
    if (route.request().method() === 'PUT') { saveEntered(); await saveGate; }
    await route.fallback();
  });
  try {
    await restored.locator('#btn-export').click();
    await enteredSave;
    await addUnsavedNode(restored, 'Changed during restored save');
    releaseSave();
    await expect.poll(() => restored.evaluate(() => window.ravenroot.workspace.active.authoring.saveFlight)).toBe(null);
    expect((await activeManagedState(restored)).history.dirty).toBe(true);
    expect((await activeManagedState(restored)).authoring.localChanges).toBe(true);
    await expect(restored.locator('#dirty-state')).toContainText('unsaved changes');
    await restored.locator('#btn-export').click();
  } finally { releaseSave(); }
  await expect.poll(() => restored.evaluate(() => ({
    dirty: window.ravenroot.workspace.active.history.isDirty(),
    protected: window.ravenroot.workspace.active.authoring.localChanges,
    saving: window.ravenroot.workspace.active.authoring.saveFlight,
  }))).toEqual({ dirty: false, protected: false, saving: null });
  expect(requests.filter(request => request.method === 'PUT')).toHaveLength(2);
  await restored.close();
});

for (const changedAuthority of ['repository', 'provider']) {
test(`different configured ${changedAuthority} cannot adopt or save an older managed record and legacy sources stay separate`, async ({ page }) => {
  const configuration = { repositoryDisplay: 'example/graphs' };
  const requests = await setup(page, { initialVersion: 2, configuration });
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  await addUnsavedNode(page);
  const before = await activeManagedState(page);
  if (changedAuthority === 'repository') configuration.repositoryDisplay = 'example/other-graphs';
  else configuration.provider = 'gitlab';
  const authorityGeneration = await page.evaluate(() => window.ravenroot.workspacePersistence().authority.generation);
  await page.locator('#access-token').fill('other-repository-token');
  await page.locator('#btn-authenticate').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.generation)).toBeGreaterThan(authorityGeneration);
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
  forbidUnexpectedSideEffects(page);
  await page.locator('#btn-export').click();
  await expect(page.locator('#repository-notice')).toContainText('earlier or different repository connection');
  expect(await activeManagedState(page)).toEqual(before);
  await selectRepositorySource(page);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().id)).not.toBe(before.id);
  const current = await activeManagedState(page);
  expect(current.authoring.repositoryScope).not.toBe(before.authoring.repositoryScope);
  await page.evaluate(id => {
    window.ravenroot.activateDocument(id);
    // A pre-binding snapshot is kept intact; it cannot gain the current repository's authority.
    window.ravenroot.workspace.active.authoring.repositoryScope = null;
  }, before.id);
  await page.locator('#btn-export').click();
  await expect(page.locator('#repository-notice')).toContainText('Download GraphML to protect your work');
  await selectRepositorySource(page);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().id)).toBe(current.id);
  expect(await page.evaluate(id => window.ravenroot.workspace.find(id).graph.nodes.some(n => n.name === 'Unsaved sentinel'), before.id)).toBe(true);
  await expectUniqueWorkspaceIds(page);
  expect(requests.filter(request => request.method !== 'GET')).toHaveLength(0);
});
}

test('concurrent managed opens resolve one workspace identity after their remote reads complete', async ({ page }) => {
  const requests = await setup(page, { initialVersion: 2 });
  await page.goto('/');
  forbidUnexpectedSideEffects(page);
  let release, entered = 0, bothEntered;
  const gate = new Promise(resolve => { release = resolve; });
  const both = new Promise(resolve => { bothEntered = resolve; });
  await page.route('**/v1/graph-authoring/orders.graphml?source=draft', async route => {
    if (++entered === 2) bothEntered();
    await gate; await route.fallback();
  });
  try {
    await selectRepositorySource(page);
    await selectRepositorySource(page);
    await both;
    release();
    await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
    await expect.poll(() => requests.filter(r => r.path.endsWith('/orders.graphml')).length).toBe(2);
    await expectUniqueWorkspaceIds(page);
    expect(await page.evaluate(() => window.ravenroot.workspace.documents.filter(d => d.authoring.providerDocumentId).length)).toBe(1);
    expect(requests.filter(request => request.method !== 'GET')).toHaveLength(0);
  } finally { release(); }
});

test('tenant restoration keeps same-name managed sources scoped and preserves prior local work', async ({ page }) => {
  const configuration = { tenantId: 'tenant-a' };
  const requests = await setup(page, { initialVersion: 2, configuration });
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  await addUnsavedNode(page);
  const before = await activeManagedState(page);
  await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
  configuration.tenantId = 'tenant-b';
  await page.locator('#access-token').fill('tenant-b-token');
  await page.locator('#btn-authenticate').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.tenantId)).toBe('tenant-b');
  forbidUnexpectedSideEffects(page);
  await selectRepositorySource(page);
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  const other = await activeManagedState(page);
  expect(other.id).not.toBe(before.id);
  expect(other.authoring.repositoryScope).not.toBe(before.authoring.repositoryScope);
  expect(other.nodes.some(n => n.name === 'Unsaved sentinel')).toBe(false);
  configuration.tenantId = 'tenant-a';
  await page.locator('#access-token').fill('tenant-a-token');
  await page.locator('#btn-authenticate').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.tenantId)).toBe('tenant-a');
  await selectRepositorySource(page);
  await expect(page.locator('#repository-notice')).toContainText('Your local graph and undo history were kept');
  const restored = await activeManagedState(page);
  expect(restored.id).toBe(before.id);
  expect(restored.nodes).toEqual(before.nodes);
  expect(restored.authoring.revision).toEqual(before.authoring.revision);
  expect(restored.authoring.localChanges).toBe(true);
  await expectUniqueWorkspaceIds(page);
  expect(requests.filter(request => request.method !== 'GET')).toHaveLength(0);
});


test('restored clean source refreshes to the current remote successor on direct managed startup', async ({ page, context }) => {
  await setup(page, { configuration: { tenantId: 'tenant-a' } });
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v1');
  const before = await activeManagedState(page);
  await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
  const restored = await context.newPage();
  const requests = await setup(restored, { initialVersion: 2, configuration: { tenantId: 'tenant-a' } });
  forbidUnexpectedSideEffects(restored);
  await restored.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(restored.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  const after = await activeManagedState(restored);
  expect(after.id).toBe(before.id);
  expect(after.authoring.releaseVersion).toBe(2);
  expect(after.authoring.localChanges).toBe(false);
  expect(after.history.dirty).toBe(false);
  await expectUniqueWorkspaceIds(restored);
  expect(requests.filter(request => request.method !== 'GET')).toHaveLength(0);
  await restored.close();
});

test('delayed history cannot restore an old source through a changed repository connection', async ({ page }) => {
  const configuration = { repositoryDisplay: 'example/graphs' };
  const requests = await setup(page, { initialVersion: 2, configuration });
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  await addUnsavedNode(page);
  const before = await activeManagedState(page);
  let releaseHistory, historyEntered, dialogs = 0;
  const gate = new Promise(resolve => { releaseHistory = resolve; });
  const entered = new Promise(resolve => { historyEntered = resolve; });
  await page.route('**/v1/graph-authoring/orders.graphml/history', async route => {
    historyEntered(); await gate;
    await route.fulfill({ json: { items: [{ revision: 'a'.repeat(40), summary: 'Original repository revision', author: 'Author' }], nextCursor: '' } });
  });
  page.on('dialog', dialog => {
    dialogs++;
    // Complete only the exact history flow if a regression lets it escape the scope fence.
    // This also exposes an unintended restore at the changed destination; other dialogs fail.
    if (dialog.type() === 'prompt' && dialog.message().startsWith('History for orders.graphml:'))
      return dialog.accept('a'.repeat(40));
    if (dialog.type() === 'confirm' && dialog.message() === 'Restore this revision as a new draft commit?')
      return dialog.accept();
    throw new Error(`Unexpected ${dialog.type()} dialog: ${dialog.message()}`);
  });
  page.on('download', () => { throw new Error('History inspection must not export a document'); });
  try {
    await page.locator('#menu-file').click();
    await page.getByRole('menuitem', { name: 'Repository history…', exact: true }).click();
    await entered;
    const generation = await page.evaluate(() => window.ravenroot.workspacePersistence().authority.generation);
    configuration.repositoryDisplay = 'example/other-graphs';
    await page.locator('#access-token').fill('changed-history-token');
    await page.locator('#btn-authenticate').click();
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.generation)).toBeGreaterThan(generation);
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
    releaseHistory();
    await expect(page.locator('#repository-notice')).toContainText('The service or authenticated workspace changed');
    expect(dialogs).toBe(0);
    expect(requests.filter(r => r.path.endsWith('/restore'))).toHaveLength(0);
    expect(requests.filter(r => r.method !== 'GET')).toHaveLength(0);
    expect(await activeManagedState(page)).toEqual(before);
  } finally { releaseHistory(); }
});

test('a delayed discard response cannot replace local work after the configured repository changes', async ({ page }) => {
  const configuration = { repositoryDisplay: 'example/graphs' };
  const requests = await setup(page, { initialVersion: 2, configuration });
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  await addUnsavedNode(page);
  const before = await activeManagedState(page);
  let releaseDiscard, discardEntered;
  const gate = new Promise(resolve => { releaseDiscard = resolve; });
  const entered = new Promise(resolve => { discardEntered = resolve; });
  await page.route('**/v1/graph-authoring/orders.graphml/discard', async route => {
    discardEntered(); await gate; await route.fallback();
  });
  page.once('dialog', dialog => {
    expect(dialog.type()).toBe('confirm');
    expect(dialog.message()).toBe('Discard draft changes and restore the reviewed release?');
    return dialog.accept();
  });
  try {
    await page.locator('#menu-file').click();
    await page.getByRole('menuitem', { name: 'Discard draft changes', exact: true }).click();
    await entered;
    forbidUnexpectedSideEffects(page);
    const generation = await page.evaluate(() => window.ravenroot.workspacePersistence().authority.generation);
    configuration.repositoryDisplay = 'example/other-graphs';
    await page.locator('#access-token').fill('changed-discard-token');
    await page.locator('#btn-authenticate').click();
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.generation)).toBeGreaterThan(generation);
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
    releaseDiscard();
    await expect(page.locator('#repository-notice')).toContainText('The service or authenticated workspace changed');
    expect(await activeManagedState(page)).toEqual(before);
    expect(requests.filter(r => r.path.endsWith('/discard'))).toHaveLength(1);
    expect(requests.filter(r => r.method !== 'GET' && !r.path.endsWith('/discard'))).toHaveLength(0);
  } finally { releaseDiscard(); }
});

for (const kind of ['dirty reopen', 'clean successor']) test(`pending first Run respects managed ${kind} incarnation`, async ({ page }) => {
  const gate = deferred(), backend = await deploymentService(page, { gate });
  const requests = await setup(page, { configuration: { tenantId: 'tenant' } });
  page.on('dialog', dialog => dialog.accept());
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v1');
  if (kind === 'dirty reopen') await addUnsavedNode(page, 'Keep local draft');
  const before = await activeManagedState(page);
  await page.locator('#btn-run').click(); const id = await backend.registered;
  if (kind === 'clean successor') requests.setDraft(2);
  await selectRepositorySource(page);
  if (kind === 'dirty reopen') {
    await expect(page.locator('#repository-notice')).toContainText('Your local graph and undo history were kept');
    expect((await activeManagedState(page)).incarnation).toBe(before.incarnation);
  } else {
    await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
    expect((await activeManagedState(page)).incarnation).not.toBe(before.incarnation);
  }
  gate.resolve();
  if (kind === 'dirty reopen') {
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.processInstanceId)).toBe(PID);
    expect(await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun.deploymentId)).toBe(id);
    expect(backend.calls.filter(c => c.action === 'initial-start')).toHaveLength(1);
  } else {
    await expect.poll(() => backend.calls.filter(c => c.method === 'POST' && !c.action).length).toBe(1);
    expect(await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun)).toBeNull();
    expect(backend.calls.filter(c => c.action === 'start' || c.action === 'initial-start')).toHaveLength(0);
  }
});


test('changed released draft advertises and saves one automatic successor', async ({ page }) => {
  const requests = await setup(page, { automaticSuccessor: true });
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v1');
  await addUnsavedNode(page, 'Successor edit');
  await expect(page.locator('#btn-export')).toContainText('Save as draft v2');
  await page.locator('#btn-export').click();
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  await expect(page.locator('#btn-export')).toContainText('Save draft to repository');
  expect(requests.filter(request => request.method === 'PUT')).toHaveLength(1);
  expect(requests.filter(request => request.path.endsWith('/versions'))).toHaveLength(0);
  await addUnsavedNode(page, 'Another edit');
  await expect(page.locator('#btn-export')).toContainText('Save draft to repository');
});

test('first repository save renames the active document and browser title immediately', async ({ page }) => {
  const requests = await setup(page);
  await page.route('**/v1/graph-authoring/orders.graphml', route => {
    if (route.request().method() !== 'PUT') return route.fallback();
    requests.push({ method: 'PUT' });
    return route.fulfill({ json: { documentId: 'orders.graphml', graphId: 'orders',
      releaseVersion: 1, released: false, published: false, draftDeleted: false,
      revision: { draft: 'saved-draft', release: 'absent', publication: 'absent' },
      graphMl: Buffer.from(graph(1)).toString('base64') } });
  });
  await page.goto('/');
  await expect(page.locator('#btn-new')).toBeEnabled();
  await page.locator('#btn-new').click();
  await expect(page.locator('#graph-title')).toContainText('untitled');
  page.once('dialog', dialog => dialog.accept('orders.graphml'));
  await page.locator('#btn-export').click();
  await expect.poll(() => requests.filter(r => r.method === 'PUT').length).toBe(1);
  await expect(page.locator('#graph-title')).toHaveText('orders.graphml');
  await expect(page).toHaveTitle('orders.graphml — Ravenroot UI');
  expect(await page.evaluate(() => ({ name: window.ravenroot.workspace.active.name,
    displayName: window.ravenroot.workspace.active.displayName,
    documentId: window.ravenroot.workspace.active.authoring.providerDocumentId,
    dirty: window.ravenroot.workspace.active.history.isDirty() })))
    .toEqual({ name: 'orders.graphml', displayName: 'orders.graphml', documentId: 'orders.graphml', dirty: false });
  await page.locator('#btn-export').click();
  await expect.poll(() => requests.filter(r => r.method === 'PUT').length).toBe(2);
  await expect(page.locator('#graph-title')).toHaveText('orders.graphml');
});

for (const outcome of ['success', 'failure']) test(`release review modal shows work before ${outcome} and retains the result`, async ({ page }) => {
  await setup(page);
  let finish;
  const gate = new Promise(resolve => { finish = resolve; });
  await page.route('**/v1/graph-authoring/orders.graphml/release', async route => {
    await gate;
    return route.fulfill(outcome === 'success'
      ? { json: { reused: true, url: 'https://github.com/example/graphs/pull/9' } }
      : { status: 409, json: { error: 'CONFLICT' } });
  });
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft');
  await page.getByRole('menuitem', { name: 'File', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Propose release review', exact: true }).click();
  const dialog = page.locator('.repository-operation-dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('status')).toContainText('Preparing the release review');
  await expect(dialog.getByRole('button', { name: 'Close', exact: true })).toBeDisabled();
  finish();
  await expect(dialog.getByRole('button', { name: 'Close', exact: true })).toBeEnabled();
  if (outcome === 'success') {
    await expect(dialog.getByRole('link', { name: 'Open review' })).toHaveAttribute('href', 'https://github.com/example/graphs/pull/9');
    await expect(dialog.getByRole('status')).toContainText('requested version is released');
  } else await expect(dialog.getByRole('alert')).toContainText('Your edits are intact');
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(dialog).toHaveCount(0);
});

for (const mode of ['ordinary', 'reconcile', 'refused']) test(`release dialog observes exact candidate and ${mode} admission`, async ({ page }) => {
  const automation = { mode: 'isolated-automerge', deploymentId: 'ci', registrationIdentity: '11111111-1111-1111-1111-111111111111', graphVersion: 'a'.repeat(64), sourceNodeId: 'schedule' };
  await setup(page, { initialVersion: 2, configuration: mode === 'ordinary' ? {} : { releaseAutomation: automation } });
  const triggers = []; let published = mode === 'ordinary', reads = 0;
  await page.route('**/v1/deployments/ci', route => route.fulfill({ json: { ...automation, desiredState: 'RUNNING', observedState: 'READY', runtimeIncarnationId: 'physical' } }));
  await page.route('**/v1/deployments/ci/trigger', route => {
    triggers.push(route.request().postDataJSON());
    if (mode === 'refused') return route.fulfill({ status: 409, json: { outcome: 'REFUSED', requestId: triggers.at(-1).requestId } });
    if (triggers.length === 1) return route.fulfill({ status: 503, json: { outcome: 'RECONCILE', requestId: triggers.at(-1).requestId } });
    published = true;
    return route.fulfill({ status: 200, json: { outcome: 'DUPLICATE', requestId: triggers.at(-1).requestId } });
  });
  await page.route('**/v1/graph-authoring/orders.graphml/release', route => route.fulfill({ json: {
    reused: true, url: 'https://github.com/example/graphs/pull/10', draftHead: 'a'.repeat(40), releaseHead: 'b'.repeat(40),
  } }));
  await page.route('**/v1/graph-authoring/orders.graphml?source=release', route => {
    reads++;
    return route.fulfill({ json: { documentId: 'orders.graphml', graphId: 'orders', releaseVersion: published ? 2 : 3,
      releasedVersion: published ? 2 : 3, releasedGraphId: 'orders', released: true, published: true,
      revision: { draft: 'd1', release: 'r2', publication: 'published' }, graphMl: Buffer.from(graph(published ? 2 : 3)).toString('base64') } });
  });
  const propose = async () => {
    await page.getByRole('menuitem', { name: 'File', exact: true }).click();
    await page.getByRole('menuitem', { name: 'Propose release review', exact: true }).click();
    await expect(page.locator('.repository-operation-dialog')).toBeVisible();
  };
  await page.goto('/?repositoryDocument=orders.graphml&source=draft');
  await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
  await propose();
  let dialog = page.locator('.repository-operation-dialog');
  if (mode === 'reconcile') {
    await expect(dialog.getByRole('status')).toContainText('could not be confirmed');
    await expect(dialog.getByRole('button', { name: 'Retry acknowledgement' })).toBeVisible();
    expect(await dialog.textContent()).not.toContain('released and published');
    await dialog.getByRole('button', { name: 'Close', exact: true }).click();
    await page.reload();
    await expect(page.locator('#graph-mode-label')).toContainText('Repository draft · v2');
    await propose(); dialog = page.locator('.repository-operation-dialog');
    await expect(dialog.getByRole('status')).toContainText('released and published', { timeout: 15000 });
    expect(triggers).toHaveLength(2);
    expect(triggers[0].requestId).toBe(triggers[1].requestId);
    expect(triggers[0]).toEqual(triggers[1]);
  } else if (mode === 'refused') {
    await expect(dialog.getByRole('alert')).toContainText('refused');
    expect(await dialog.textContent()).not.toContain('released and published');
  } else await expect(dialog.getByRole('status')).toContainText('released and published');
  await expect(dialog.getByRole('link', { name: 'Open review' })).toHaveAttribute('href', 'https://github.com/example/graphs/pull/10');
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await page.clock.install(); const stoppedReads = reads;
  await page.clock.fastForward(65000); expect(reads).toBe(stoppedReads);
});

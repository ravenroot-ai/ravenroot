import { expect, test } from '@playwright/test';

async function installWorkspaceService(page, tenant) {
  await page.route('**/v1/configuration', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024,
      workspace: { tenantId: tenant.value } }),
  }));
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json', body: '[]',
  }));
  await page.route('**/v1/events**', route => route.fulfill({
    status: 200, contentType: 'text/event-stream', body: '',
  }));
}

const waitForWorkspace = (page, tenantId) => expect.poll(() => page.evaluate(() => ({
  persistence: window.ravenroot.workspacePersistence(),
  documents: window.ravenroot.documents().map(document_ => ({
    id: document_.documentId, tenantId: document_.tenantId, mode: document_.mode,
  })),
}))).toMatchObject({ persistence: { writable: true, scope: { tenantId } } });

async function flush(page) {
  await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
}

test('reload restores durable ids, order, selection and modes without stale runtime references', async ({ page }) => {
  const tenant = { value: 'tenant-a' };
  await installWorkspaceService(page, tenant);
  await page.goto('/');
  await waitForWorkspace(page, tenant.value);

  const before = await page.evaluate(() => {
    const first = window.ravenroot.activeDocument();
    const secondId = window.ravenroot.openDocument({ name: 'second.graphml' });
    window.ravenroot.activateDocument(first.id);
    first.execution.executionId = 'stale-execution';
    first.execution.graphVersion = 'stale-version';
    first.sourceSession.sessionId = 'stale-session';
    return { first: first.documentId, second: secondId };
  });
  await page.locator('#btn-monitoring').click();
  await flush(page);
  await page.reload();
  await waitForWorkspace(page, tenant.value);

  expect(await page.evaluate(() => ({
    ids: window.ravenroot.documents().map(document_ => document_.documentId),
    active: window.ravenroot.activeDocument().documentId,
    renderMode: window.ravenroot.activeDocument().renderMode,
    executionId: window.ravenroot.activeDocument().execution.executionId,
    sourceSessionId: window.ravenroot.activeDocument().sourceSession.sessionId,
  }))).toEqual({ ids: [before.first, before.second], active: before.first,
    renderMode: 'monitoring', executionId: null, sourceSessionId: null });

  await page.evaluate(id => window.ravenroot.closeDocument(id), before.second);
  await flush(page);
  await page.reload();
  await waitForWorkspace(page, tenant.value);
  expect(await page.evaluate(() => window.ravenroot.documents().map(document_ => document_.documentId)))
    .toEqual([before.first]);
});

test('reauthentication switches exact tenant scopes without adopting either tenant workspace', async ({ page }) => {
  const tenant = { value: 'tenant-a' };
  await installWorkspaceService(page, tenant);
  await page.goto('/');
  await waitForWorkspace(page, 'tenant-a');
  const tenantA = await page.evaluate(() => {
    window.ravenroot.openDocument({ name: 'tenant-a-only.graphml' });
    return window.ravenroot.documents().map(document_ => document_.documentId);
  });
  await flush(page);

  tenant.value = 'tenant-b';
  await page.locator('#access-token').fill('tenant-b-token');
  await page.locator('#btn-authenticate').click();
  await waitForWorkspace(page, 'tenant-b');
  const tenantB = await page.evaluate(() => window.ravenroot.documents().map(document_ => document_.documentId));
  expect(tenantB).toHaveLength(1);
  expect(tenantA).not.toContain(tenantB[0]);
  await page.evaluate(() => window.ravenroot.openDocument({ name: 'tenant-b-only.graphml' }));
  await flush(page);

  tenant.value = 'tenant-a';
  await page.locator('#access-token').fill('tenant-a-token');
  await page.locator('#btn-authenticate').click();
  await waitForWorkspace(page, 'tenant-a');
  expect(await page.evaluate(() => window.ravenroot.documents().map(document_ => document_.documentId)))
    .toEqual(tenantA);
});

test('an unreadable stored schema remains intact and write-locked', async ({ page }) => {
  const tenant = { value: 'tenant-migration' };
  await installWorkspaceService(page, tenant);
  await page.goto('/');
  await waitForWorkspace(page, tenant.value);
  await page.evaluate(() => window.ravenroot.openDocument({ name: 'must-survive.graphml' }));
  await flush(page);
  const storedKey = await page.evaluate(() => new Promise((resolve, reject) => {
    const open = indexedDB.open('ravenroot-workspaces', 1);
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const database = open.result;
      const tx = database.transaction('tenant-workspaces', 'readwrite');
      const store = tx.objectStore('tenant-workspaces');
      const read = store.getAll();
      read.onsuccess = () => {
        const snapshot = read.result[0];
        snapshot.version = 99;
        store.put(snapshot);
        tx.oncomplete = () => { database.close(); resolve(snapshot.key); };
      };
    };
  }));
  await page.reload();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence()))
    .toMatchObject({ writable: false, reason: expect.stringContaining('could not be restored') });
  expect(await page.evaluate(key => new Promise((resolve, reject) => {
    const open = indexedDB.open('ravenroot-workspaces', 1);
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const database = open.result;
      const request = database.transaction('tenant-workspaces').objectStore('tenant-workspaces').get(key);
      request.onerror = () => reject(request.error);
      request.onsuccess = () => { database.close(); resolve({ version: request.result.version,
        names: request.result.documents.map(document_ => document_.name) }); };
    };
  }), storedKey)).toMatchObject({ version: 99,
    names: expect.arrayContaining(['must-survive.graphml']) });
});

test('Test creates an immutable exact snapshot and Fork creates a distinct editable draft', async ({ page }) => {
  const tenant = { value: 'tenant-test' };
  await installWorkspaceService(page, tenant);
  let postedGraph = '';
  await page.route('**/v1/executions**', async route => {
    if (route.request().method() === 'POST') {
      postedGraph = route.request().postData() || '';
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        executionId: 'execution-test', processInstanceId: 'process-test',
        graphVersion: 'graph-version-test', executionPolicy: 'TEST_PASSTHROUGH',
      }) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      status: 'COMPLETED', executionId: 'execution-test', handledFailureNodes: [],
      defaultedNodes: [], bypassedNodes: [],
    }) });
  });
  await page.goto('/');
  await waitForWorkspace(page, tenant.value);
  const draft = await page.evaluate(() => window.ravenroot.activeDocument().documentId);
  await page.locator('#btn-play').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().mode)).toBe('test');
  expect(postedGraph).toContain('<graphml');
  const tested = await page.evaluate(() => {
    const document_ = window.ravenroot.activeDocument();
    return { id: document_.documentId, mode: document_.mode, provenance: document_.provenance };
  });
  expect(tested).toMatchObject({ mode: 'test', provenance: { sourceDocumentId: draft,
    sourceGraphVersion: 'graph-version-test', deploymentId: null } });
  await expect(page.locator('#btn-modify')).toBeDisabled();
  await expect(page.locator('#graph-mode-label')).toContainText('Test · Read-only');
  const immutableBefore = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.cy.nodes()[0].select();
    return JSON.stringify(owner.graph);
  });
  await page.keyboard.press('Delete');
  expect(await page.evaluate(() => JSON.stringify(window.ravenroot.activeDocument().graph)))
    .toBe(immutableBefore);

  await page.locator('#replace-file-inp').setInputFiles({
    name: 'replacement.graphml', mimeType: 'application/xml',
    buffer: Buffer.from(postedGraph),
  });
  await expect(page.locator('#graph-mode-label')).toContainText('Test · Read-only');
  expect(await page.evaluate(() => JSON.stringify(window.ravenroot.activeDocument().graph)))
    .toBe(immutableBefore);

  await page.locator('#menu-file').click();
  await page.getByRole('menuitem', { name: 'Fork as Draft' }).click();
  const fork = await page.evaluate(() => {
    const document_ = window.ravenroot.activeDocument();
    return { id: document_.documentId, mode: document_.mode, provenance: document_.provenance };
  });
  expect(fork.id).not.toBe(tested.id);
  expect(fork).toMatchObject({ mode: 'draft', provenance: { originMode: 'test',
    sourceDocumentId: tested.id, sourceGraphVersion: 'graph-version-test', deploymentId: null } });
  await expect(page.locator('#btn-modify')).toBeEnabled();
  await flush(page);
  await page.reload();
  await waitForWorkspace(page, tenant.value);
  expect(await page.evaluate(id => window.ravenroot.workspace.find(id)?.provenance, fork.id))
    .toMatchObject({ sourceDocumentId: tested.id, sourceGraphVersion: 'graph-version-test' });
});

test('deployment registration persists the exact captured graph as an immutable deployed view', async ({ page }) => {
  const tenant = { value: 'tenant-deploy' };
  await installWorkspaceService(page, tenant);
  let capturedGraphMl = '';
  let releaseRegistration;
  const registrationGate = new Promise(resolve => { releaseRegistration = resolve; });
  const held = { deploymentId: 'orders', state: 'REGISTERED', sourceCount: 0,
    graphVersion: 'deployed-v1', scope: 'LOCAL_PROCESS', diagnostic: null };
  await page.route('**/v1/deployments**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'POST' && url.searchParams.has('id')) {
      capturedGraphMl = request.postData() || '';
      await registrationGate;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(held) });
      return;
    }
    if (request.method() === 'POST') held.state = 'READY';
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(
      request.method() === 'GET' && !url.pathname.match(/deployments\/.+/)
        ? { deployments: [held] } : held),
    });
  });
  await page.goto('/');
  await waitForWorkspace(page, tenant.value);
  const source = await page.evaluate(() => ({ id: window.ravenroot.activeDocument().documentId,
    name: window.ravenroot.activeDocument().graph.nodes[1].name }));
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Deployments…' }).click();
  await page.locator('#deployment-id-input').fill('orders');
  await page.locator('#deployment-register').click({ noWaitAfter: true });
  await expect.poll(() => capturedGraphMl.length).toBeGreaterThan(0);
  await page.evaluate(() => {
    window.ravenroot.activeDocument().graph.nodes[1].name = 'changed after registration request';
  });
  releaseRegistration();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().mode)).toBe('deployed');
  const deployed = await page.evaluate(() => {
    const document_ = window.ravenroot.activeDocument();
    return { id: document_.documentId, name: document_.graph.nodes[1].name,
      provenance: document_.provenance };
  });
  expect(deployed.name).toBe(source.name);
  expect(deployed.provenance).toEqual({ originMode: 'draft', sourceDocumentId: source.id,
    sourceGraphVersion: 'deployed-v1', deploymentId: 'orders' });
  await expect(page.locator('#btn-modify')).toBeDisabled();
  await flush(page);
  await page.reload();
  await waitForWorkspace(page, tenant.value);
  expect(await page.evaluate(id => window.ravenroot.workspace.find(id)?.mode, deployed.id)).toBe('deployed');
  await page.evaluate(id => window.ravenroot.activateDocument(id), deployed.id);
  const deployedFork = await page.evaluate(() => window.ravenroot.forkDocument());
  expect(await page.evaluate(id => window.ravenroot.workspace.find(id)?.provenance, deployedFork))
    .toMatchObject({ originMode: 'deployed', sourceDocumentId: deployed.id,
      sourceGraphVersion: 'deployed-v1', deploymentId: null });
});

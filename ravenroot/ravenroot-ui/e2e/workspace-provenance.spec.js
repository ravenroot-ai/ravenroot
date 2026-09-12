import { expect, test } from '@playwright/test';

async function installWorkspaceService(page, tenant) {
  await page.route('**/v1/configuration', async route => {
    const response = tenant.configuration
      ? await tenant.configuration(route.request()) : { tenantId: tenant.value };
    await route.fulfill(response?.status ? response : {
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024,
        workspace: { tenantId: response.tenantId }, ...(response.extra || {}) }),
    });
  });
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json', body: '[]',
  }));
  await page.route('**/v1/events**', route => route.fulfill({
    status: 200, contentType: 'text/event-stream', body: '',
  }));
}

test('pending and failed tenant changes leave old documents exportable but runtime-isolated', async ({ page }) => {
  const humanTasks = { schemaVersion: 1, confirmationPresentationVersions: [1],
    confirmationPromptMaxUtf8Bytes: 4096, confirmationActionLabelMaxUtf8Bytes: 64,
    commentMaxUtf8Bytes: 4096, attentionPollMillis: 1000, attentionBackoffMaxMillis: 10000,
    attentionPageSize: 25, attentionPageSizeMax: 1000 };
  const tenant = { configuration: async request => {
    if (request.headers().authorization === 'Bearer tenant-b-token') {
      return { tenantId: 'tenant-b', extra: { humanTasks } };
    }
    return { tenantId: 'tenant-a', extra: { humanTasks } };
  } };
  await installWorkspaceService(page, tenant);
  let executionPosts = 0;
  let deploymentPosts = 0;
  let humanTaskRequests = 0;
  await page.route('**/v1/executions**', route => {
    if (route.request().method() === 'POST') executionPosts += 1;
    return route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
  });
  await page.route('**/v1/deployments**', route => {
    if (route.request().method() === 'POST') deploymentPosts += 1;
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{"deployments":[]}' });
  });
  await page.route('**/v1/human-tasks/**', route => {
    humanTaskRequests += 1;
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{"items":[]}' });
  });
  await page.goto('/');
  await waitForWorkspace(page, 'tenant-a');
  const oldDocument = await page.evaluate(() => window.ravenroot.activeDocument().documentId);
  await page.evaluate(() => {
    let reject;
    window.__rejectTenantBRestore = () => reject?.(new Error('tenant B storage unavailable'));
    window.ravenroot._setWorkspaceSnapshotReaderForTest(scope => scope.tenantId === 'tenant-b'
      ? new Promise((_resolve, reject_) => { reject = reject_; }) : Promise.resolve(null));
  });
  await page.locator('#access-token').fill('tenant-b-token');
  await page.locator('#btn-authenticate').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().restoring)).toBe(true);
  await expect(page.locator('#btn-play')).toBeDisabled();
  await expect(page.locator('#btn-run')).toBeDisabled();
  await page.keyboard.press('ControlOrMeta+Enter');
  await page.evaluate(() => {
    document.querySelector('#btn-play').click();
    document.querySelector('#btn-run').click();
  });
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Deployments…' }).click();
  await page.locator('#deployment-id-input').fill('tenant-b-must-not-receive-a');
  await page.locator('#deployment-register').click();
  expect({ executionPosts, deploymentPosts }).toEqual({ executionPosts: 0, deploymentPosts: 0 });
  const humanTasksAtPending = humanTaskRequests;
  await page.evaluate(() => window.__rejectTenantBRestore());
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.state))
    .toBe('failed');
  await expect(page.locator('#btn-play')).toBeDisabled();
  expect(await page.evaluate(() => window.ravenroot.activeDocument().documentId)).toBe(oldDocument);
  expect({ executionPosts, deploymentPosts, humanTaskRequests }).toEqual({ executionPosts: 0,
    deploymentPosts: 0, humanTaskRequests: humanTasksAtPending });
  await page.locator('#deployment-close').click();
  await page.locator('#menu-file').click();
  await expect(page.getByRole('menuitem', { name: 'Save GraphML' })).toBeEnabled();
});

const waitForWorkspace = (page, tenantId) => expect.poll(() => page.evaluate(() => ({
  persistence: window.ravenroot.workspacePersistence(),
  documents: window.ravenroot.documents().map(document_ => ({
    id: document_.documentId, tenantId: document_.tenantId, mode: document_.mode,
  })),
}))).toMatchObject({ persistence: { writable: true, restoring: false,
  authority: { state: 'ready', tenantId }, scope: { tenantId } } });

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
    Object.assign(first.graph.nodeMap.dosomething, { instances: 9, arrivals: 5,
      runtimeState: 'failed', runtimeObserved: true, lastEventType: 'NODE_FAILED',
      lastOccurredAt: 'stale-time', processingDuration: 'PT3S', fallback: true,
      programPhase: 'READY', programReadinessState: { phase: 'READY', artifactId: 'stale' } });
    return { first: first.documentId, second: secondId };
  });
  await page.locator('#btn-modify').click();
  await page.evaluate(() => {
    window.cy.getElementById('dosomething').select();
  });
  await page.locator('#node-editor input[name="name"]').fill('Persisted without a flush hook');
  await page.locator('#node-editor input[name="name"]').press('Tab');
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.activeDocument().graph.nodeMap.dosomething.name))
    .toBe('Persisted without a flush hook');
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().pending)).toBe(false);
  await page.reload();
  await waitForWorkspace(page, tenant.value);

  expect(await page.evaluate(() => ({
    ids: window.ravenroot.documents().map(document_ => document_.documentId),
    active: window.ravenroot.activeDocument().documentId,
    renderMode: window.ravenroot.activeDocument().renderMode,
    nodeName: window.ravenroot.activeDocument().graph.nodeMap.dosomething.name,
    executionId: window.ravenroot.activeDocument().execution.executionId,
    sourceSessionId: window.ravenroot.activeDocument().sourceSession.sessionId,
    nodeProjection: ((node) => ({ instances: node.instances, arrivals: node.arrivals,
      runtimeState: node.runtimeState, runtimeObserved: node.runtimeObserved,
      lastEventType: node.lastEventType, lastOccurredAt: node.lastOccurredAt,
      processingDuration: node.processingDuration, fallback: node.fallback,
      hasProgramPhase: Object.hasOwn(node, 'programPhase'),
      hasProgramReadiness: Object.hasOwn(node, 'programReadinessState') }))(
      window.ravenroot.activeDocument().graph.nodeMap.dosomething),
  }))).toEqual({ ids: [before.first, before.second], active: before.first,
    renderMode: 'monitoring', nodeName: 'Persisted without a flush hook',
    executionId: null, sourceSessionId: null, nodeProjection: { instances: 0, arrivals: 0,
      runtimeState: 'idle', runtimeObserved: false, lastEventType: null, lastOccurredAt: null,
      processingDuration: null, fallback: false, hasProgramPhase: false,
      hasProgramReadiness: false } });

  await page.evaluate(id => window.ravenroot.closeDocument(id), before.second);
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().pending)).toBe(false);
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

test('overlapping A to B to A restore cannot replace A or leave its autosave suspended', async ({ page }) => {
  const tenant = { configuration: async request => ({ tenantId:
    request.headers().authorization === 'Bearer tenant-b-token' ? 'tenant-b' : 'tenant-a' }) };
  await installWorkspaceService(page, tenant);
  await page.goto('/');
  await waitForWorkspace(page, 'tenant-a');
  const originalA = await page.evaluate(() => window.ravenroot.activeDocument().documentId);
  await page.evaluate(() => {
    let release;
    window.__releaseTenantBRestore = () => release?.(null);
    window.ravenroot._setWorkspaceSnapshotReaderForTest(scope => scope.tenantId === 'tenant-b'
      ? new Promise(resolve => { release = resolve; }) : Promise.resolve(null));
  });
  await page.locator('#access-token').fill('tenant-b-token');
  await page.locator('#btn-authenticate').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().restoring)).toBe(true);
  await page.locator('#access-token').fill('tenant-a-token');
  await page.locator('#btn-authenticate').click();
  await waitForWorkspace(page, 'tenant-a');
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().restoring)).toBe(false);
  const added = await page.evaluate(() => window.ravenroot.openDocument({ name: 'after-overlap.graphml' }));
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().pending)).toBe(false);
  await page.evaluate(() => window.__releaseTenantBRestore());
  expect(await page.evaluate(() => window.ravenroot.documents().map(item => item.documentId)))
    .toEqual([originalA, added]);
  await page.reload();
  await waitForWorkspace(page, 'tenant-a');
  expect(await page.evaluate(() => window.ravenroot.documents().map(item => item.documentId)))
    .toEqual([originalA, added]);
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

test('legacy Test snapshot documents still restore and fork as editable drafts', async ({ page }) => {
  const tenant = { value: 'tenant-test' };
  await installWorkspaceService(page, tenant);
  await page.goto('/');
  await waitForWorkspace(page, tenant.value);
  const tested = await page.evaluate(() => {
    const source = window.ravenroot.activeDocument();
    const graph = structuredClone(source.graph);
    const id = window.ravenroot.openDocument({
      name: 'legacy-test.graphml', graph, mode: 'test',
      provenance: {
        originMode: 'draft', sourceDocumentId: source.documentId,
        sourceGraphVersion: 'legacy-graph-version', deploymentId: null,
      },
    });
    return { id, sourceId: source.documentId, graphJson: JSON.stringify(graph), semantic: {
      nodes: graph.nodes.map(node => ({ id: node.id, name: node.name, kind: node.kind })),
      edges: graph.edges.map(edge => ({ id: edge.id, source: edge.source, target: edge.target,
        outcome: edge.outcome })),
    } };
  });
  await expect(page.locator('#btn-modify')).toBeDisabled();
  await expect(page.locator('#graph-mode-label')).toContainText('Test · Read-only');
  await page.evaluate(() => { window.ravenroot.activeDocument().cy.nodes()[0].select(); });
  await page.keyboard.press('Delete');
  expect(await page.evaluate(() => JSON.stringify(window.ravenroot.activeDocument().graph)))
    .toBe(tested.graphJson);

  await page.locator('#btn-monitoring').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().pending)).toBe(false);
  await page.reload();
  await waitForWorkspace(page, tenant.value);
  await page.evaluate(id => window.ravenroot.activateDocument(id), tested.id);
  expect(await page.evaluate(() => {
    const document_ = window.ravenroot.activeDocument();
    return { semantic: {
      nodes: document_.graph.nodes.map(node => ({ id: node.id, name: node.name, kind: node.kind })),
      edges: document_.graph.edges.map(edge => ({ id: edge.id, source: edge.source,
        target: edge.target, outcome: edge.outcome })),
    }, mode: document_.mode,
      provenance: document_.provenance, renderMode: document_.renderMode };
  })).toEqual({ semantic: tested.semantic, mode: 'test', renderMode: 'monitoring',
    provenance: { originMode: 'draft', sourceDocumentId: tested.sourceId,
      sourceGraphVersion: 'legacy-graph-version', deploymentId: null } });
  await expect(page.locator('#graph-mode-label')).toContainText('Test · Read-only');

  await page.locator('#menu-file').click();
  await page.getByRole('menuitem', { name: 'Fork as Draft' }).click();
  const fork = await page.evaluate(() => {
    const document_ = window.ravenroot.activeDocument();
    return { id: document_.documentId, mode: document_.mode, provenance: document_.provenance };
  });
  expect(fork.id).not.toBe(tested.id);
  expect(fork).toMatchObject({ mode: 'draft', provenance: { originMode: 'test',
    sourceDocumentId: tested.id, sourceGraphVersion: 'legacy-graph-version', deploymentId: null } });
  await page.locator('#btn-design').click();
  await expect(page.locator('#btn-modify')).toBeEnabled();
  await flush(page);
  await page.reload();
  await waitForWorkspace(page, tenant.value);
  expect(await page.evaluate(id => window.ravenroot.workspace.find(id)?.provenance, fork.id))
    .toMatchObject({ sourceDocumentId: tested.id, sourceGraphVersion: 'legacy-graph-version' });
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

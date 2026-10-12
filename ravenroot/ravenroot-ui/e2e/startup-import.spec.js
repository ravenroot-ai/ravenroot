import { expect, test } from '@playwright/test';

import { SERVICE_ORIGIN, UI_ORIGIN } from './ports.mjs';

const CONFIGURATION = { schemaVersion: 1, graphDocumentMaxBytes: 1024 * 1024 };
const GRAPHML = `<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
  <graph id="startup" edgedefault="directed">
    <node id="start"><data key="kind">START</data></node>
    <node id="end"><data key="kind">END</data></node>
    <edge id="e1" source="start" target="end"/>
  </graph>
</graphml>`;
const GRAPHIFY = JSON.stringify({ directed: true,
  nodes: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }],
  edges: [{ source: 'a', target: 'b' }] });

function authoredGraphMl(graphId, releaseVersion) {
  return GRAPHML
    .replace('  <graph id="startup"', `  <key id="authoring-id" for="graph" attr.name="ravenroot.authoring.graphId" attr.type="string"/>\n  <key id="authoring-version" for="graph" attr.name="ravenroot.authoring.releaseVersion" attr.type="string"/>\n  <graph id="startup"`)
    .replace('    <node id="start">', `    <data key="authoring-id">${graphId}</data>\n    <data key="authoring-version">${releaseVersion}</data>\n    <node id="start">`);
}

function repositoryEnvelope(documentId, source = 'draft') {
  const graphId = documentId.replace(/\.graphml$/, '');
  const releaseVersion = 7;
  return {
    documentId, graphId, releaseVersion, releasedGraphId: graphId, releasedVersion: releaseVersion,
    released: true, published: true, draftDeleted: source === 'release',
    revision: { draft: source === 'release' ? 'absent' : 'draft-revision',
      release: 'release-revision', publication: 'publication-revision' },
    graphMl: Buffer.from(authoredGraphMl(graphId, releaseVersion), 'utf8').toString('base64'),
  };
}

async function stubRuntimeNoise(page) {
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json', body: '[]',
  }));
  await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
}

async function startupWorkspaceEvidence(page) {
  return page.evaluate(() => {
    const documents = window.ravenroot.documents();
    return {
      activeId: window.ravenroot.workspace.activeId,
      documents: documents.map(document_ => ({
        id: document_.id,
        incarnation: document_.incarnation,
        name: document_.name,
        graphMl: window.ravenroot.serializeGraphML(document_.graph),
        revision: document_.history.revision(),
        dirty: document_.history.isDirty(),
        authoring: JSON.stringify(document_.authoring),
      })),
    };
  });
}

async function setActiveFontSize(page, value) {
  await page.locator('#font-slider').fill(String(value));
  await page.locator('#font-slider').dispatchEvent('input');
  await expect(page.locator('#font-val')).toHaveText(String(value));
}

test('same-origin startup import waits for slow configuration and strips query and fragment from its filename', async ({ page }) => {
  let releaseConfiguration;
  const configurationGate = new Promise(resolve => { releaseConfiguration = resolve; });
  await page.route('**/v1/configuration', async route => {
    await configurationGate;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONFIGURATION) });
  });
  await page.route(`${UI_ORIGIN}/startup%20flow.graphml?signature=private`, route => route.fulfill({
    status: 200, contentType: 'application/graphml+xml', body: GRAPHML,
  }));
  await stubRuntimeNoise(page);
  await page.goto('/?file=%2Fstartup%2520flow.graphml%3Fsignature%3Dprivate%23ignored');

  await expect(page.locator('#startup-import')).toBeVisible();
  await expect(page.locator('#startup-import-file')).toHaveText('startup flow.graphml');
  await expect(page.locator('#startup-import-status')).toContainText('Waiting for the service');
  const placeholder = await page.evaluate(() => ({
    id: window.ravenroot.workspace.activeId,
    name: window.ravenroot.workspace.active.name,
    documents: window.ravenroot.workspace.documents.length,
    nodes: window.ravenroot.workspace.active.graph.nodes.length,
    edges: window.ravenroot.workspace.active.graph.edges.length,
  }));
  expect(placeholder).toMatchObject({ name: 'untitled.graphml', documents: 1, nodes: 0, edges: 0 });

  releaseConfiguration();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  await expect.poll(() => page.evaluate(() => ({
    name: window.ravenroot.workspace.active.name,
    format: window.ravenroot.workspace.active.graph.format,
    nodes: window.ravenroot.workspace.active.graph.nodes.length,
    authoring: window.ravenroot.workspace.active.authoring.providerDocumentId,
    documents: window.ravenroot.workspace.documents.length,
    id: window.ravenroot.workspace.activeId,
  }))).toEqual({ name: 'startup flow.graphml', format: 'graphml', nodes: 2, authoring: null,
    documents: 1, id: placeholder.id });
});

test('ordinary startup keeps the established four-node starter workflow', async ({ page }) => {
  await stubRuntimeNoise(page);
  await page.goto('/');

  await expect.poll(() => page.evaluate(() => ({
    documents: window.ravenroot.workspace.documents.length,
    name: window.ravenroot.workspace.active.name,
    nodeKinds: window.ravenroot.workspace.active.graph.nodes.map(node => node.kind),
    edges: window.ravenroot.workspace.active.graph.edges.length,
    startupImportHidden: document.getElementById('startup-import').hidden,
  }))).toEqual({ documents: 1, name: 'untitled.graphml',
    nodeKinds: ['START', 'PASSTHROUGH', 'ERROR', 'END'], edges: 3, startupImportHidden: true });
});

test('unavailable configuration keeps the startup import recoverable until one deliberate Retry', async ({ page }) => {
  let configurationReady = false;
  let releaseInitialConfiguration;
  const initialConfigurationGate = new Promise(resolve => { releaseInitialConfiguration = resolve; });
  let firstConfiguration = true;
  let graphRequests = 0;
  await page.route('**/v1/configuration', async route => {
    if (firstConfiguration) {
      firstConfiguration = false;
      await initialConfigurationGate;
    }
    return route.fulfill(configurationReady
      ? { status: 200, contentType: 'application/json', body: JSON.stringify(CONFIGURATION) }
      : { status: 503, contentType: 'application/json',
        body: '{"error":"private upstream configuration detail"}' });
  });
  await page.route(`${UI_ORIGIN}/configuration-retry.graphml`, route => {
    graphRequests += 1;
    return route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await stubRuntimeNoise(page);
  await page.goto('/?file=/configuration-retry.graphml');

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-runtime');
  const beforeFailure = await startupWorkspaceEvidence(page);
  releaseInitialConfiguration();

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
  await expect(page.locator('#startup-import-status')).toContainText('valid graph-loading configuration');
  await expect(page.locator('#startup-import-status')).not.toContainText('private upstream');
  await expect(page.locator('#startup-import-retry')).toBeVisible();
  await expect(page.locator('#startup-import-cancel')).toBeVisible();
  expect(graphRequests).toBe(0);
  expect(await startupWorkspaceEvidence(page)).toEqual(beforeFailure);
  expect(beforeFailure.documents).toHaveLength(1);
  expect(beforeFailure.documents[0]).toMatchObject({ name: 'untitled.graphml', dirty: false });

  configurationReady = true;
  await page.locator('#startup-import-retry').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  expect(graphRequests).toBe(1);
  await expect.poll(() => page.evaluate(() => ({
    documents: window.ravenroot.workspace.documents.length,
    name: window.ravenroot.workspace.active.name,
  }))).toEqual({ documents: 1, name: 'configuration-retry.graphml' });
});

test('malformed configuration leaves the startup workspace unchanged and Cancel is terminal', async ({ page }) => {
  let configurationReady = false;
  let releaseInitialConfiguration;
  const initialConfigurationGate = new Promise(resolve => { releaseInitialConfiguration = resolve; });
  let firstConfiguration = true;
  let graphRequests = 0;
  await page.route('**/v1/configuration', async route => {
    if (firstConfiguration) {
      firstConfiguration = false;
      await initialConfigurationGate;
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(configurationReady ? CONFIGURATION : {
        schemaVersion: 1, graphDocumentMaxBytes: 0, privateDetail: 'malformed-private-detail',
      }),
    });
  });
  await page.route(`${UI_ORIGIN}/malformed-configuration.graphml`, route => {
    graphRequests += 1;
    return route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await stubRuntimeNoise(page);
  await page.goto('/?file=/malformed-configuration.graphml');

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-runtime');
  const beforeCancel = await startupWorkspaceEvidence(page);
  releaseInitialConfiguration();

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
  await expect(page.locator('#startup-import-status')).toContainText('valid graph-loading configuration');
  await expect(page.locator('#startup-import-status')).not.toContainText('malformed-private-detail');
  await expect(page.locator('#startup-import-retry')).toBeVisible();
  await expect(page.locator('#startup-import-cancel')).toBeVisible();
  expect(graphRequests).toBe(0);
  expect(await startupWorkspaceEvidence(page)).toEqual(beforeCancel);
  expect(beforeCancel.documents).toHaveLength(1);
  expect(beforeCancel.documents[0]).toMatchObject({ name: 'untitled.graphml', dirty: false });

  await page.locator('#startup-import-cancel').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'cancelled');
  await expect(page.locator('#startup-import-retry')).toBeHidden();
  await expect(page.locator('#startup-import-cancel')).toBeHidden();
  configurationReady = true;
  await page.locator('#service-url').evaluate(element => {
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
  expect(graphRequests).toBe(0);
  expect(await startupWorkspaceEvidence(page)).toEqual(beforeCancel);
});

test('cross-origin Graphify import waits for origin confirmation and sends no bearer token', async ({ page }, testInfo) => {
  let authorization = 'not requested';
  const source = `${SERVICE_ORIGIN}/graphs/graphify%20view.json?private=hidden#fragment`;
  await page.route(`${SERVICE_ORIGIN}/graphs/**`, route => {
    authorization = route.request().headers().authorization || '';
    return route.fulfill({
      status: 200,
      headers: { 'Access-Control-Allow-Origin': '*' },
      contentType: 'application/json',
      body: GRAPHIFY,
    });
  });
  await stubRuntimeNoise(page);
  await page.goto(`/?file=${encodeURIComponent(source)}`);

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-origin-confirmation');
  await expect(page.locator('#startup-import-status')).toContainText(SERVICE_ORIGIN);
  await expect(page.locator('#startup-import-status')).not.toContainText('private=hidden');
  await page.screenshot({ path: testInfo.outputPath('cross-origin-confirmation.png'), fullPage: true });
  await page.locator('#startup-import-confirm').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  expect(authorization).toBe('');
  await expect.poll(() => page.evaluate(() => ({
    name: window.ravenroot.workspace.active.name,
    format: window.ravenroot.workspace.active.graph.format,
    documents: window.ravenroot.workspace.documents.length,
  }))).toEqual({ name: 'graphify view.json', format: 'graphify', documents: 1 });
});

test('work started while configuration loads is kept in its own document when import begins', async ({ page }) => {
  let releaseConfiguration;
  const configurationGate = new Promise(resolve => { releaseConfiguration = resolve; });
  await page.route('**/v1/configuration', async route => {
    await configurationGate;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONFIGURATION) });
  });
  await page.route(`${UI_ORIGIN}/separate.graphml`, route => route.fulfill({
    status: 200, contentType: 'application/graphml+xml', body: GRAPHML,
  }));
  await stubRuntimeNoise(page);
  await page.goto('/?file=/separate.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-runtime');

  await page.locator('#btn-modify').click();
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill('Local work before import');
  await page.locator('#node-editor button[type="submit"]').click();
  releaseConfiguration();

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  await expect.poll(() => page.evaluate(() => ({
    documents: window.ravenroot.workspace.documents.length,
    active: window.ravenroot.workspace.active.name,
    localWork: window.ravenroot.workspace.documents.some(document_ =>
      document_.graph.nodes.some(node => node.name === 'Local work before import')),
  }))).toEqual({ documents: 2, active: 'separate.graphml', localWork: true });
});

test('a populated clean-history replacement cannot be reused as the startup placeholder', async ({ page }) => {
  let releaseConfiguration;
  const configurationGate = new Promise(resolve => { releaseConfiguration = resolve; });
  await page.route('**/v1/configuration', async route => {
    await configurationGate;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONFIGURATION) });
  });
  await page.route(`${UI_ORIGIN}/after-clean-replacement.graphml`, route => route.fulfill({
    status: 200, contentType: 'application/graphml+xml', body: GRAPHML,
  }));
  await stubRuntimeNoise(page);
  await page.goto('/?file=/after-clean-replacement.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-runtime');
  const originalId = await page.evaluate(() => {
    const document_ = window.ravenroot.workspace.active;
    const node = { id: 'local-scaffold', name: 'Local scaffold', kind: 'PASSTHROUGH',
      behavior: '', nodeType: 'flow', isStart: false, isEnd: false, isActor: false,
      classname: '', description: '', fillColor: '#21262d', shapeType: 'roundrectangle',
      ox: 0, oy: 0, ow: 80, oh: 52, instances: 0, properties: {}, propertyTypes: {} };
    document_.graph.nodes.push(node);
    document_.graph.nodeMap[node.id] = node;
    return window.ravenroot.workspace.activeId;
  });
  expect(await page.evaluate(() => window.ravenroot.workspace.active.history.isDirty())).toBe(false);

  releaseConfiguration();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  await expect.poll(() => page.evaluate(id => {
    const original = window.ravenroot.workspace.find(id);
    return {
      documents: window.ravenroot.workspace.documents.length,
      originalName: original?.name,
      originalNodes: original?.graph.nodes.length,
      importedName: window.ravenroot.workspace.active.name,
    };
  }, originalId)).toEqual({ documents: 2, originalName: 'untitled.graphml', originalNodes: 1,
    importedName: 'after-clean-replacement.graphml' });
});

test('a font choice made while configuration loads is preserved in a separate document', async ({ page }) => {
  let releaseConfiguration;
  const configurationGate = new Promise(resolve => { releaseConfiguration = resolve; });
  await page.route('**/v1/configuration', async route => {
    await configurationGate;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CONFIGURATION) });
  });
  await page.route(`${UI_ORIGIN}/after-font-choice.graphml`, route => route.fulfill({
    status: 200, contentType: 'application/graphml+xml', body: GRAPHML,
  }));
  await stubRuntimeNoise(page);
  await page.goto('/?file=/after-font-choice.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-runtime');
  const originalId = await page.evaluate(() => window.ravenroot.workspace.activeId);

  await setActiveFontSize(page, 27);
  releaseConfiguration();

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  await expect.poll(() => page.evaluate(id => {
    const original = window.ravenroot.workspace.find(id);
    return {
      documents: window.ravenroot.workspace.documents.length,
      originalId: original?.id,
      originalFont: original?.fontSize,
      importedName: window.ravenroot.workspace.active.name,
      importedFont: window.ravenroot.workspace.active.fontSize,
    };
  }, originalId)).toEqual({ documents: 2, originalId, originalFont: 27,
    importedName: 'after-font-choice.graphml', importedFont: 20 });
});

test('restored workspace documents remain separate from the startup import', async ({ page }) => {
  await page.route('**/v1/configuration', route => {
    const authenticated = route.request().headers().authorization === 'Bearer restored-token';
    return route.fulfill(authenticated
      ? { status: 200, contentType: 'application/json', body: JSON.stringify({
        ...CONFIGURATION, workspace: { tenantId: 'tenant-restored' },
      }) }
      : { status: 401, contentType: 'application/json', body: '{"error":"authentication required"}' });
  });
  await page.route(`${UI_ORIGIN}/after-restored.graphml`, route => route.fulfill({
    status: 200, contentType: 'application/graphml+xml', body: GRAPHML,
  }));
  await stubRuntimeNoise(page);
  await page.goto('/?file=/after-restored.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-authentication');
  await page.evaluate(() => {
    const graph = structuredClone(window.ravenroot.activeDocument().graph);
    delete graph.nodeMap;
    graph.nodes.push({ id: 'restored-work', name: 'Restored workspace work', kind: 'PASSTHROUGH',
      behavior: '', nodeType: 'flow', isStart: false, isEnd: false, isActor: false,
      classname: '', description: '', fillColor: '#21262d', shapeType: 'roundrectangle',
      ox: 0, oy: 0, ow: 80, oh: 52, instances: 0, properties: {}, propertyTypes: {} });
    const restored = {
      documentId: 'restored-document', name: 'restored.graphml', displayName: 'restored.graphml',
      tenantId: 'tenant-restored', mode: 'draft', graph, authoring: null, presentation: null,
      provenance: { originMode: 'draft', sourceDocumentId: null, sourceGraphVersion: null,
        deploymentId: null },
    };
    window.ravenroot._setWorkspaceSnapshotReaderForTest(() => ({
      documents: [restored], activeDocumentId: restored.documentId, recoveredStaleSelection: false,
    }));
  });
  await page.locator('#access-token').fill('restored-token');
  await page.locator('#btn-authenticate').click();

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  await expect.poll(() => page.evaluate(() => ({
    documents: window.ravenroot.workspace.documents.map(document_ => document_.name),
    restored: window.ravenroot.workspace.documents.some(document_ =>
      document_.graph.nodes.some(node => node.name === 'Restored workspace work')),
    active: window.ravenroot.workspace.active.name,
  }))).toEqual({ documents: ['restored.graphml', 'after-restored.graphml'], restored: true,
    active: 'after-restored.graphml' });
});

test('cross-origin consent remains actionable through authentication and resumes only once', async ({ page }) => {
  let requests = 0;
  const source = `${SERVICE_ORIGIN}/graphs/authenticated-flow.graphml`;
  await page.route('**/v1/configuration', route => {
    const authenticated = route.request().headers().authorization === 'Bearer cross-token';
    return route.fulfill(authenticated
      ? { status: 200, contentType: 'application/json', body: JSON.stringify(CONFIGURATION) }
      : { status: 401, contentType: 'application/json', body: '{"error":"authentication required"}' });
  });
  await page.route(source, route => {
    requests += 1;
    expect(route.request().headers().authorization).toBeUndefined();
    return route.fulfill({ status: 200, headers: { 'Access-Control-Allow-Origin': '*' },
      contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await stubRuntimeNoise(page);
  await page.goto(`/?file=${encodeURIComponent(source)}`);
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-authentication');
  await expect(page.locator('#startup-import-confirm')).toBeVisible();
  await page.locator('#startup-import-confirm').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-authentication');
  await page.locator('#access-token').fill('cross-token');
  await page.locator('#btn-authenticate').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  expect(requests).toBe(1);
});

test('cross-origin consent remains actionable through external-service confirmation and resumes once', async ({ page }) => {
  let requests = 0;
  const source = `${SERVICE_ORIGIN}/graphs/confirmed-service.graphml`;
  await page.addInitScript(serviceOrigin => {
    localStorage.setItem('ravenroot.human-task.selection.v1', JSON.stringify({
      serviceOrigin, taskId: 'startup-service-confirmation', generation: 1,
    }));
  }, SERVICE_ORIGIN);
  await page.route(`${SERVICE_ORIGIN}/v1/configuration`, route => route.fulfill({
    status: 200, headers: { 'Access-Control-Allow-Origin': UI_ORIGIN },
    contentType: 'application/json', body: JSON.stringify(CONFIGURATION),
  }));
  await page.route(source, route => {
    requests += 1;
    return route.fulfill({ status: 200, headers: { 'Access-Control-Allow-Origin': UI_ORIGIN },
      contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await stubRuntimeNoise(page);
  await page.goto(`/?file=${encodeURIComponent(source)}`);
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-service-confirmation');
  await expect(page.locator('#startup-import-confirm')).toBeVisible();
  await page.locator('#startup-import-confirm').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-service-confirmation');

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').evaluate(element => {
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  expect(requests).toBe(1);
});

test('authentication resumes one pending import and retry recovers a missing file without duplicates', async ({ page }) => {
  let available = false;
  let graphRequests = 0;
  await page.route('**/v1/configuration', route => {
    const authenticated = route.request().headers().authorization === 'Bearer startup-token';
    return route.fulfill(authenticated
      ? { status: 200, contentType: 'application/json', body: JSON.stringify(CONFIGURATION) }
      : { status: 401, contentType: 'application/json', body: '{"error":"authentication required"}' });
  });
  await page.route(`${UI_ORIGIN}/recover.graphml`, route => {
    graphRequests += 1;
    return route.fulfill(available
      ? { status: 200, contentType: 'application/graphml+xml', body: GRAPHML }
      : { status: 404, contentType: 'text/plain', body: 'missing' });
  });
  await stubRuntimeNoise(page);
  await page.goto('/?file=/recover.graphml');

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-authentication');
  expect(graphRequests).toBe(0);
  await page.locator('#access-token').fill('startup-token');
  await page.locator('#btn-authenticate').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
  await expect(page.locator('#startup-import-status')).toContainText('not found');
  expect(graphRequests).toBe(1);
  expect(await page.evaluate(() => window.ravenroot.workspace.documents.length)).toBe(1);

  available = true;
  await page.locator('#startup-import-retry').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  expect(graphRequests).toBe(2);
  expect(await page.evaluate(() => window.ravenroot.workspace.documents.length)).toBe(1);
});

test('an edit made during slow workspace restoration remains open and the stored workspace remains uncommitted', async ({ page }) => {
  await page.route('**/v1/configuration', route => {
    const authenticated = route.request().headers().authorization === 'Bearer workspace-token';
    return route.fulfill(authenticated
      ? { status: 200, contentType: 'application/json', body: JSON.stringify({
        ...CONFIGURATION,
        workspace: { tenantId: 'tenant-restoring' },
      }) }
      : { status: 401, contentType: 'application/json', body: '{"error":"authentication required"}' });
  });
  let graphRequested = false;
  await page.route(`${UI_ORIGIN}/after-restore.graphml`, route => {
    graphRequested = true;
    return route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await stubRuntimeNoise(page);
  await page.goto('/?file=/after-restore.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-authentication');

  await page.evaluate(() => {
    window.__storedRestore = { documents: [{ documentId: 'restored-document' }],
      activeDocumentId: 'restored-document' };
    window.__restorePromise = new Promise(resolve => { window.__resolveRestore = resolve; });
    window.ravenroot._setWorkspaceSnapshotReaderForTest(() => window.__restorePromise);
  });
  await page.locator('#access-token').fill('workspace-token');
  await page.locator('#btn-authenticate').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().restoring)).toBe(true);

  await page.locator('#btn-modify').click();
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill('Work started during restore');
  await page.locator('#node-editor button[type="submit"]').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.active.history.isDirty())).toBe(true);
  await page.evaluate(() => window.__resolveRestore(window.__storedRestore));

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
  await expect(page.locator('#startup-import-status')).toContainText('current documents changed');
  expect(graphRequested).toBe(false);
  await expect.poll(() => page.evaluate(() => ({
    documents: window.ravenroot.workspace.documents.map(document_ => document_.id),
    dirty: window.ravenroot.workspace.active.history.isDirty(),
    edited: window.ravenroot.workspace.active.graph.nodes.some(node => node.name === 'Work started during restore'),
    reason: window.ravenroot.workspacePersistence().reason,
  }))).toMatchObject({ dirty: true, edited: true,
    reason: expect.stringContaining('current session changed') });
  expect(await page.evaluate(() => window.__storedRestore.documents[0].documentId)).toBe('restored-document');
});

test('a font choice made during slow restoration preserves current and stored workspaces', async ({ page }) => {
  await page.route('**/v1/configuration', route => {
    const authenticated = route.request().headers().authorization === 'Bearer font-restore-token';
    return route.fulfill(authenticated
      ? { status: 200, contentType: 'application/json', body: JSON.stringify({
        ...CONFIGURATION, workspace: { tenantId: 'tenant-font-restoring' },
      }) }
      : { status: 401, contentType: 'application/json', body: '{"error":"authentication required"}' });
  });
  let graphRequested = false;
  await page.route(`${UI_ORIGIN}/font-after-restore.graphml`, route => {
    graphRequested = true;
    return route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await stubRuntimeNoise(page);
  await page.goto('/?file=/font-after-restore.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-authentication');
  const originalId = await page.evaluate(() => window.ravenroot.workspace.activeId);
  await page.evaluate(() => {
    window.__storedFontRestore = { documents: [{ documentId: 'stored-font-document', fontSize: 18 }],
      activeDocumentId: 'stored-font-document' };
    window.__fontRestorePromise = new Promise(resolve => { window.__resolveFontRestore = resolve; });
    window.ravenroot._setWorkspaceSnapshotReaderForTest(() => window.__fontRestorePromise);
  });
  await page.locator('#access-token').fill('font-restore-token');
  await page.locator('#btn-authenticate').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().restoring)).toBe(true);

  await setActiveFontSize(page, 27);
  await page.evaluate(() => window.__resolveFontRestore(window.__storedFontRestore));

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
  await expect(page.locator('#startup-import-status')).toContainText('current documents changed');
  expect(graphRequested).toBe(false);
  await expect.poll(() => page.evaluate(id => ({
    documents: window.ravenroot.workspace.documents.length,
    id: window.ravenroot.workspace.activeId,
    fontSize: window.ravenroot.workspace.active.fontSize,
    stored: window.__storedFontRestore,
  }), originalId)).toEqual({ documents: 1, id: originalId, fontSize: 27,
    stored: { documents: [{ documentId: 'stored-font-document', fontSize: 18 }],
      activeDocumentId: 'stored-font-document' } });
});

test('a failed stored-workspace read reports sanitized recovery guidance and changes no documents', async ({ page }) => {
  await page.route('**/v1/configuration', route => {
    const authenticated = route.request().headers().authorization === 'Bearer restore-token';
    return route.fulfill(authenticated
      ? { status: 200, contentType: 'application/json', body: JSON.stringify({
        ...CONFIGURATION, workspace: { tenantId: 'tenant-unreadable' },
      }) }
      : { status: 401, contentType: 'application/json', body: '{"error":"authentication required"}' });
  });
  let graphRequested = false;
  await page.route(`${UI_ORIGIN}/restore-failure.graphml`, route => {
    graphRequested = true;
    return route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await stubRuntimeNoise(page);
  await page.goto('/?file=/restore-failure.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-authentication');
  await page.evaluate(() => {
    window.ravenroot._setWorkspaceSnapshotReaderForTest(() =>
      Promise.reject(new Error('private indexed storage detail')));
  });
  await page.locator('#access-token').fill('restore-token');
  await page.locator('#btn-authenticate').click();

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
  await expect(page.locator('#startup-import-status')).toContainText('could not be restored');
  await expect(page.locator('#startup-import-status')).not.toContainText('private indexed storage detail');
  expect(graphRequested).toBe(false);
  expect(await page.evaluate(() => ({ name: window.ravenroot.workspace.active.name,
    documents: window.ravenroot.workspace.documents.length })))
    .toEqual({ name: 'untitled.graphml', documents: 1 });
});

test('invalid and oversized inputs leave the placeholder unchanged until an explicit successful retry', async ({ page }) => {
  let responseKind = 'invalid';
  await page.route('**/v1/configuration', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({
      ...CONFIGURATION, graphDocumentMaxBytes: 4096,
    }),
  }));
  await page.route(`${UI_ORIGIN}/validated.graphml`, route => {
    if (responseKind === 'invalid') {
      return route.fulfill({ status: 200, contentType: 'text/plain', body: 'not a graph' });
    }
    if (responseKind === 'oversized') {
      return route.fulfill({ status: 200, contentType: 'application/graphml+xml',
        headers: { 'Content-Length': '5000' }, body: GRAPHML });
    }
    return route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await stubRuntimeNoise(page);
  await page.goto('/?file=/validated.graphml');

  await expect(page.locator('#startup-import-status')).toContainText('not valid supported');
  await expect.poll(() => page.evaluate(() => ({ name: window.ravenroot.workspace.active.name,
    documents: window.ravenroot.workspace.documents.length })))
    .toEqual({ name: 'untitled.graphml', documents: 1 });

  responseKind = 'oversized';
  await page.locator('#startup-import-retry').click();
  await expect(page.locator('#startup-import-status')).toContainText('larger than the connected service allows');
  expect(await page.evaluate(() => window.ravenroot.workspace.active.name)).toBe('untitled.graphml');

  responseKind = 'valid';
  await page.locator('#startup-import-retry').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  expect(await page.evaluate(() => window.ravenroot.workspace.active.name)).toBe('validated.graphml');
});

test('an unsupported startup URL can be dismissed without changing the placeholder', async ({ page }) => {
  await stubRuntimeNoise(page);
  await page.goto(`/?file=${encodeURIComponent('file:///private/graph.graphml')}`);
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
  await expect(page.locator('#startup-import-status')).toContainText('must use HTTP or HTTPS');
  await expect(page.locator('#startup-import-cancel')).toBeVisible();
  await page.locator('#startup-import-cancel').click();
  await expect.poll(() => page.evaluate(() => {
    const surface = document.getElementById('startup-import');
    return { hidden: surface.hidden, attribute: surface.getAttribute('hidden'),
      display: getComputedStyle(surface).display, phase: surface.dataset.phase };
  })).toEqual({ hidden: true, attribute: '', display: 'none', phase: 'failed' });
  expect(await page.evaluate(() => ({ name: window.ravenroot.workspace.active.name,
    documents: window.ravenroot.workspace.documents.length })))
    .toEqual({ name: 'untitled.graphml', documents: 1 });
});

test('Cancel aborts a slow download and never mutates the workspace', async ({ page }) => {
  let releaseDownload;
  const downloadGate = new Promise(resolve => { releaseDownload = resolve; });
  await page.route(`${UI_ORIGIN}/slow.graphml`, async route => {
    await downloadGate;
    await route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML }).catch(() => {});
  });
  await stubRuntimeNoise(page);
  await page.goto('/?file=/slow.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'downloading');
  await page.locator('#startup-import-cancel').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'cancelled');
  releaseDownload();
  await page.waitForTimeout(50);
  expect(await page.evaluate(() => ({ name: window.ravenroot.workspace.active.name,
    documents: window.ravenroot.workspace.documents.length })))
    .toEqual({ name: 'untitled.graphml', documents: 1 });
  await expect(page.locator('#startup-import-retry')).toBeHidden();
});

test('a font choice made during download preserves the original and imports separately', async ({ page }) => {
  let releaseDownload;
  const downloadGate = new Promise(resolve => { releaseDownload = resolve; });
  await page.route(`${UI_ORIGIN}/font-during-download.graphml`, async route => {
    await downloadGate;
    await route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await stubRuntimeNoise(page);
  await page.goto('/?file=/font-during-download.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'downloading');
  const originalId = await page.evaluate(() => window.ravenroot.workspace.activeId);

  await setActiveFontSize(page, 27);
  releaseDownload();

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  await expect.poll(() => page.evaluate(id => {
    const original = window.ravenroot.workspace.find(id);
    return {
      documents: window.ravenroot.workspace.documents.length,
      originalId: original?.id,
      originalFont: original?.fontSize,
      importedName: window.ravenroot.workspace.active.name,
      importedFont: window.ravenroot.workspace.active.fontSize,
    };
  }, originalId)).toEqual({ documents: 2, originalId, originalFont: 27,
    importedName: 'font-during-download.graphml', importedFont: 20 });
});

test('a Test started during download preserves its empty source document and imports separately', async ({ page }) => {
  let releaseDownload;
  let releaseInspection;
  const downloadGate = new Promise(resolve => { releaseDownload = resolve; });
  const inspectionGate = new Promise(resolve => { releaseInspection = resolve; });
  let inspectionRequests = 0;
  await page.route(`${UI_ORIGIN}/during-test.graphml`, async route => {
    await downloadGate;
    await route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await page.route('**/v1/graphs/inspect**', async route => {
    inspectionRequests += 1;
    await inspectionGate;
    await route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ valid: false, findings: [{ phase: 'VALIDATION', reason: 'EMPTY_GRAPH' }] }) });
  });
  await page.route('**/v1/executions**', route => route.fulfill({
    status: 500, contentType: 'application/json', body: '{"error":"unexpected execution"}',
  }));
  await stubRuntimeNoise(page);
  await page.goto('/?file=/during-test.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'downloading');
  const originalId = await page.evaluate(() => window.ravenroot.workspace.activeId);

  await page.locator('#btn-play').click();
  await expect.poll(() => inspectionRequests).toBe(1);
  releaseDownload();

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  await expect.poll(() => page.evaluate(id => {
    const original = window.ravenroot.workspace.find(id);
    return {
      documents: window.ravenroot.workspace.documents.length,
      originalId: original?.id,
      originalNodes: original?.graph.nodes.length,
      commandFlight: Boolean(original?.execution.commandFlight),
      importedName: window.ravenroot.workspace.active.name,
    };
  }, originalId)).toEqual({ documents: 2, originalId, originalNodes: 0, commandFlight: true,
    importedName: 'during-test.graphml' });
  releaseInspection();
});

test('a failed Test still preserves its empty source document when the download finishes', async ({ page }) => {
  let releaseDownload;
  const downloadGate = new Promise(resolve => { releaseDownload = resolve; });
  await page.route(`${UI_ORIGIN}/after-failed-test.graphml`, async route => {
    await downloadGate;
    await route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML });
  });
  await page.route('**/v1/graphs/inspect**', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ valid: true, findings: [] }),
  }));
  await page.route('**/v1/executions**', route => route.fulfill({
    status: 500, contentType: 'application/json', body: '{"error":"execution rejected"}',
  }));
  await stubRuntimeNoise(page);
  await page.goto('/?file=/after-failed-test.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'downloading');
  const originalId = await page.evaluate(() => window.ravenroot.workspace.activeId);

  await page.locator('#btn-play').click();
  await expect.poll(() => page.evaluate(() => ({
    generation: window.ravenroot.workspace.active.execution.generation,
    intentGeneration: window.ravenroot.workspace.active.execution.intentGeneration,
    executionId: window.ravenroot.workspace.active.execution.executionId,
    commandFlight: window.ravenroot.workspace.active.execution.commandFlight,
  }))).toMatchObject({ generation: 2, intentGeneration: 1, executionId: null, commandFlight: null });
  releaseDownload();

  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  await expect.poll(() => page.evaluate(id => ({
    documents: window.ravenroot.workspace.documents.length,
    originalGeneration: window.ravenroot.workspace.find(id)?.execution.generation,
    originalIntentGeneration: window.ravenroot.workspace.find(id)?.execution.intentGeneration,
    originalNodes: window.ravenroot.workspace.find(id)?.graph.nodes.length,
    importedName: window.ravenroot.workspace.active.name,
  }), originalId)).toEqual({ documents: 2, originalGeneration: 2, originalIntentGeneration: 1, originalNodes: 0,
    importedName: 'after-failed-test.graphml' });
});

test('a service change aborts an old flight and Retry binds a fresh import to the new authority', async ({ page }) => {
  let releaseFirst;
  const firstGate = new Promise(resolve => { releaseFirst = resolve; });
  let requests = 0;
  await page.route(`${UI_ORIGIN}/authority.graphml`, async route => {
    requests += 1;
    if (requests === 1) await firstGate;
    await route.fulfill({ status: 200, contentType: 'application/graphml+xml', body: GRAPHML }).catch(() => {});
  });
  await page.route(`${SERVICE_ORIGIN}/v1/configuration`, route => route.fulfill({
    status: 200,
    headers: { 'Access-Control-Allow-Origin': UI_ORIGIN },
    contentType: 'application/json',
    body: JSON.stringify(CONFIGURATION),
  }));
  await stubRuntimeNoise(page);
  await page.goto('/?file=/authority.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'downloading');

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  await page.locator('#service-url').press('Tab');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
  await expect(page.locator('#startup-import-status')).toContainText('service or workspace changed');
  releaseFirst();
  expect(await page.evaluate(() => window.ravenroot.workspace.active.name)).toBe('untitled.graphml');

  await page.locator('#startup-import-retry').click();
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  expect(requests).toBe(2);
  expect(await page.evaluate(() => window.ravenroot.workspace.active.name)).toBe('authority.graphml');
});

test('Git authoring keeps a URL import unbound while repository Open and Save remain selected', async ({ page }) => {
  const graphAuthoringRequests = [];
  await page.route('**/v1/configuration', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({
      ...CONFIGURATION,
      graphAuthoring: { mode: 'git', provider: 'github', operations: ['OPEN', 'SAVE'],
        repositoryDisplay: 'example/graphs', releaseBranch: 'main', maxDocumentBytes: 1024 * 1024 },
    }),
  }));
  await page.route(`${UI_ORIGIN}/git-import.graphml`, route => route.fulfill({
    status: 200, contentType: 'application/graphml+xml', body: GRAPHML,
  }));
  await page.route('**/v1/graph-authoring**', route => {
    const request = route.request();
    const url = new URL(request.url());
    graphAuthoringRequests.push({ method: request.method(), pathname: url.pathname,
      source: url.searchParams.get('source') });
    if (request.method() === 'PUT') {
      return route.fulfill({ status: 409, contentType: 'application/json',
        body: '{"error":"repository revision conflict"}' });
    }
    if (url.pathname === '/v1/graph-authoring') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        items: [{ documentId: 'managed.graphml', releaseVersion: 7, releasedVersion: 7,
          released: true, published: true, draftDeleted: false }],
      }) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify(repositoryEnvelope('managed.graphml', url.searchParams.get('source'))) });
  });
  await stubRuntimeNoise(page);
  await page.goto('/?file=/git-import.graphml');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
  await expect.poll(() => page.evaluate(() => {
    const document_ = window.ravenroot.workspace.active;
    return { providerDocumentId: document_.authoring.providerDocumentId,
      revision: document_.authoring.revision, released: document_.authoring.released };
  })).toEqual({ providerDocumentId: null, revision: null, released: false });
  await expect(page.locator('#btn-open')).toContainText('Open from repository');
  await expect(page.locator('#btn-export')).toContainText('Save draft to repository');
  await expect(page.locator('#btn-export')).toHaveAttribute('title', /conflict protection/i);

  page.once('dialog', async dialog => {
    expect(dialog.type()).toBe('prompt');
    expect(dialog.message()).toContain('Repository filename');
    await dialog.accept('chosen-destination.graphml');
  });
  await page.locator('#btn-export').click();
  await expect.poll(() => graphAuthoringRequests.some(request => request.method === 'PUT'
    && request.pathname.endsWith('/chosen-destination.graphml'))).toBe(true);
  await expect.poll(() => page.evaluate(() => ({
    name: window.ravenroot.workspace.active.name,
    providerDocumentId: window.ravenroot.workspace.active.authoring.providerDocumentId,
    nodes: window.ravenroot.workspace.active.graph.nodes.length,
  }))).toEqual({ name: 'git-import.graphml', providerDocumentId: null, nodes: 2 });
  expect(new URL(page.url()).searchParams.get('file')).toBe('/git-import.graphml');

  page.once('dialog', async dialog => {
    expect(dialog.type()).toBe('prompt');
    expect(dialog.message()).toContain('Open a repository graph');
    await dialog.accept('managed.graphml [released]');
  });
  await page.locator('#menu-file').click();
  await page.getByRole('menuitem', { name: 'Open from repository…' }).click();
  await expect.poll(() => graphAuthoringRequests.some(request => request.method === 'GET'
    && request.pathname.endsWith('/managed.graphml') && request.source === 'release')).toBe(true);
  await expect.poll(() => page.evaluate(() => ({
    name: window.ravenroot.workspace.active.name,
    providerDocumentId: window.ravenroot.workspace.active.authoring.providerDocumentId,
    draftDeleted: window.ravenroot.workspace.active.authoring.draftDeleted,
  }))).toEqual({ name: 'managed.graphml', providerDocumentId: 'managed.graphml', draftDeleted: true });
  await expect(page.locator('#btn-modify')).toBeDisabled();
  await page.locator('#menu-file').click();
  await expect(page.getByRole('menuitem', { name: 'Open repository draft' })).toBeVisible();
});

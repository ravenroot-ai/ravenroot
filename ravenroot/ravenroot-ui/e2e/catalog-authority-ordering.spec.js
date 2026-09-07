import { expect, test } from '@playwright/test';

const CONFIGURATION = { schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024 };
const REPLACEMENT_CATALOG = [{
  behavior: 'replacement.safe', displayName: 'Replacement safe node', category: 'test',
  description: 'Catalog owned by the replacement authority.', visualType: 'flow',
  agentic: false, capabilities: [], properties: [],
}];

function deferred() {
  let resolve;
  const promise = new Promise(resolve_ => { resolve = resolve_; });
  return { promise, resolve };
}

function tokenOf(request) {
  return String(request.headers().authorization || '').replace(/^Bearer /, '');
}

async function installRuntimeRoutes(page, { configuration, catalog }) {
  const configurationArrivals = new Map();
  const catalogArrivals = new Map();
  const arrival = (map, token) => {
    if (!map.has(token)) map.set(token, deferred());
    return map.get(token);
  };
  await page.route('**/v1/configuration', async route => {
    const token = tokenOf(route.request());
    const response = token ? configuration(token) : { status: 200, body: CONFIGURATION };
    await route.fulfill({
      status: response.status,
      contentType: 'application/json',
      body: JSON.stringify(response.body),
    });
    arrival(configurationArrivals, token).resolve();
  });
  await page.route('**/v1/node-types', async route => {
    const token = tokenOf(route.request());
    const response = token ? catalog(token) : { status: 200, body: [] };
    if (response.abort) await route.abort('connectionrefused');
    else await route.fulfill({
      status: response.status,
      contentType: 'application/json',
      body: JSON.stringify(response.body),
    });
    arrival(catalogArrivals, token).resolve();
  });
  await page.route('**/v1/events**', route => route.fulfill({
    status: 200, contentType: 'text/event-stream', body: '',
  }));
  return {
    configurationArrived: token => arrival(configurationArrivals, token).promise,
    catalogArrived: token => arrival(catalogArrivals, token).promise,
  };
}

async function openReadyWorkspace(page) {
  await page.goto('/');
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
  await expect(page.locator('#node-catalog .catalog-empty'))
    .toHaveAttribute('data-catalog-state', 'empty');
}

async function observeCatalogFailureTurn(page, token) {
  await page.evaluate(expectedToken => {
    let failureTurn;
    window.__catalogFailureContinuationTurn = new Promise(resolve => { failureTurn = resolve; });
    // Runtime-client and app continuations run as promise jobs. Crossing one browser task after the
    // matching fetch settles proves that the rejection reached those jobs while authority is held.
    const afterPromiseContinuations = callback => {
      const channel = new MessageChannel();
      channel.port1.onmessage = () => {
        channel.port1.close();
        channel.port2.close();
        callback();
      };
      channel.port2.postMessage(null);
    };
    const fetch_ = window.fetch.bind(window);
    window.fetch = async (input, init) => {
      const url = typeof input === 'string' ? input : input.url;
      const headers = new Headers(init?.headers || (typeof input === 'string' ? undefined : input.headers));
      const observesFailure = new URL(url, window.location.href).pathname === '/v1/node-types'
        && headers.get('Authorization') === `Bearer ${expectedToken}`;
      try {
        const response = await fetch_(input, init);
        if (observesFailure && !response.ok) afterPromiseContinuations(failureTurn);
        return response;
      } catch (error) {
        if (observesFailure) afterPromiseContinuations(failureTurn);
        throw error;
      }
    };
  }, token);
  return () => page.evaluate(() => window.__catalogFailureContinuationTurn);
}

async function holdWorkspaceRestore(page, tenantId) {
  await page.evaluate(tenant => {
    let release;
    let entered;
    let exited;
    const gate = new Promise(resolve => { release = resolve; });
    window.__catalogAuthorityRestoreEntered = new Promise(resolve => { entered = resolve; });
    window.__catalogAuthorityRestoreExited = new Promise(resolve => { exited = resolve; });
    window.__catalogAuthorityRestoreWasEntered = false;
    window.__releaseCatalogAuthorityRestore = release;
    window.ravenroot._setWorkspaceSnapshotReaderForTest(scope => {
      if (scope.tenantId !== tenant) return Promise.resolve(null);
      window.__catalogAuthorityRestoreWasEntered = true;
      entered();
      return gate.then(() => {
        exited();
        return null;
      });
    });
  }, tenantId);
  return {
    entered: () => page.evaluate(() => window.__catalogAuthorityRestoreEntered),
    release: () => page.evaluate(async () => {
      const waitForExit = window.__catalogAuthorityRestoreWasEntered;
      window.__releaseCatalogAuthorityRestore();
      if (waitForExit) await window.__catalogAuthorityRestoreExited;
      await new Promise(afterPromiseContinuations => {
        const channel = new MessageChannel();
        channel.port1.onmessage = () => {
          channel.port1.close();
          channel.port2.close();
          afterPromiseContinuations();
        };
        channel.port2.postMessage(null);
      });
    }),
  };
}

async function connectWithToken(page, token) {
  await page.locator('#access-token').fill(token);
  await page.locator('#btn-authenticate').click();
}

async function pendingCatalogFailure(page, routes, restore, failureTurn, token) {
  await connectWithToken(page, token);
  await Promise.all([
    routes.catalogArrived(token),
    restore.entered(),
    failureTurn(),
  ]);
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence()))
    .toMatchObject({ restoring: true, authority: { state: 'pending' } });
  const empty = page.locator('#node-catalog .catalog-empty');
  await expect(empty).toHaveAttribute('data-catalog-state', 'empty');
  await expect(empty).not.toContainText('401');
  await expect(empty).not.toContainText('unreachable');
}

test('publishes a catalog 401 only after its workspace authority becomes ready', async ({ page }) => {
  const token = 'delayed-401';
  const routes = await installRuntimeRoutes(page, {
    configuration: () => ({ status: 200, body: { ...CONFIGURATION,
      workspace: { tenantId: 'tenant-delayed-401' } } }),
    catalog: () => ({ status: 401, body: { error: 'unauthorized' } }),
  });
  await openReadyWorkspace(page);
  const failureTurn = await observeCatalogFailureTurn(page, token);
  const restore = await holdWorkspaceRestore(page, 'tenant-delayed-401');
  try {
    await pendingCatalogFailure(page, routes, restore, failureTurn, token);
    await restore.release();
    await expect.poll(() => page.evaluate(() =>
      window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
    const empty = page.locator('#node-catalog .catalog-empty');
    await expect(empty).toHaveAttribute('data-catalog-state', 'authentication-required');
    await expect(empty).toContainText('HTTP 401');
    await expect(empty).not.toContainText('unreachable');
  } finally {
    await restore.release();
  }
});

test('publishes an unavailable catalog only after its workspace authority becomes ready', async ({ page }) => {
  const token = 'delayed-unavailable';
  const routes = await installRuntimeRoutes(page, {
    configuration: () => ({ status: 200, body: { ...CONFIGURATION,
      workspace: { tenantId: 'tenant-delayed-unavailable' } } }),
    catalog: () => ({ abort: true }),
  });
  await openReadyWorkspace(page);
  const failureTurn = await observeCatalogFailureTurn(page, token);
  const restore = await holdWorkspaceRestore(page, 'tenant-delayed-unavailable');
  try {
    await pendingCatalogFailure(page, routes, restore, failureTurn, token);
    await restore.release();
    await expect.poll(() => page.evaluate(() =>
      window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
    const empty = page.locator('#node-catalog .catalog-empty');
    await expect(empty).toHaveAttribute('data-catalog-state', 'unreachable');
    await expect(empty).not.toContainText('401');
  } finally {
    await restore.release();
  }
});

test('does not publish a catalog error when configuration fails', async ({ page }) => {
  const token = 'failed-configuration';
  const routes = await installRuntimeRoutes(page, {
    configuration: () => ({ status: 503, body: { error: 'configuration unavailable' } }),
    catalog: () => ({ status: 401, body: { error: 'unauthorized' } }),
  });
  await openReadyWorkspace(page);
  const failureTurn = await observeCatalogFailureTurn(page, token);

  await connectWithToken(page, token);
  await Promise.all([routes.configurationArrived(token), routes.catalogArrived(token), failureTurn()]);
  await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence()))
    .toMatchObject({ authority: { state: 'failed' }, writable: false,
      reason: expect.stringContaining('Workspace authority could not be verified') });
  const empty = page.locator('#node-catalog .catalog-empty');
  await expect(empty).toHaveAttribute('data-catalog-state', 'empty');
  await expect(empty).not.toContainText('401');
  await expect(empty).not.toContainText('unreachable');
});

test('a replacement client cannot receive a delayed catalog error from its predecessor', async ({ page }) => {
  const firstToken = 'superseded-client';
  const replacementToken = 'replacement-client';
  const routes = await installRuntimeRoutes(page, {
    configuration: token => ({ status: 200, body: token === firstToken
      ? { ...CONFIGURATION, workspace: { tenantId: 'tenant-superseded' } }
      : CONFIGURATION }),
    catalog: token => token === firstToken
      ? { status: 401, body: { error: 'unauthorized' } }
      : { status: 200, body: REPLACEMENT_CATALOG },
  });
  await openReadyWorkspace(page);
  const failureTurn = await observeCatalogFailureTurn(page, firstToken);
  const restore = await holdWorkspaceRestore(page, 'tenant-superseded');
  try {
    await pendingCatalogFailure(page, routes, restore, failureTurn, firstToken);
    await connectWithToken(page, replacementToken);
    await routes.catalogArrived(replacementToken);
    await expect.poll(() => page.evaluate(() =>
      window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
    await expect(page.locator('[data-catalog-add="replacement.safe"]')).toBeVisible();

    await restore.release();
    await expect.poll(() => page.evaluate(() =>
      window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
    await expect(page.locator('[data-catalog-add="replacement.safe"]')).toBeVisible();
    await expect(page.locator('#node-catalog .catalog-empty')).toHaveCount(0);
  } finally {
    await restore.release();
  }
});

test('revocation cannot be overwritten by a delayed catalog error', async ({ page }) => {
  const token = 'revoked-client';
  const routes = await installRuntimeRoutes(page, {
    configuration: () => ({ status: 200, body: { ...CONFIGURATION,
      workspace: { tenantId: 'tenant-revoked' } } }),
    catalog: () => ({ status: 401, body: { error: 'unauthorized' } }),
  });
  await openReadyWorkspace(page);
  const failureTurn = await observeCatalogFailureTurn(page, token);
  const restore = await holdWorkspaceRestore(page, 'tenant-revoked');
  try {
    await pendingCatalogFailure(page, routes, restore, failureTurn, token);
    await expect(page.locator('#btn-revoke')).toBeEnabled();
    await page.locator('#btn-revoke').click();
    await expect.poll(() => page.evaluate(() =>
      window.ravenroot.workspacePersistence().authority.state)).toBe('revoked');

    await restore.release();
    await expect.poll(() => page.evaluate(() =>
      window.ravenroot.workspacePersistence().authority.state)).toBe('revoked');
    const empty = page.locator('#node-catalog .catalog-empty');
    await expect(empty).toHaveAttribute('data-catalog-state', 'disconnected');
    await expect(empty).not.toContainText('401');
  } finally {
    await restore.release();
  }
});

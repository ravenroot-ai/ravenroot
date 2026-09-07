import { expect, test } from '@playwright/test';

const CONFIGURATION = { schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024 };
const REPLACEMENT_CATALOG = [{
  behavior: 'replacement.safe', displayName: 'Replacement safe node', category: 'test',
  description: 'Catalog owned by the replacement credential.', visualType: 'flow',
  agentic: false, capabilities: [], properties: [],
}];

function deferred() {
  let resolve;
  const promise = new Promise(resolve_ => { resolve = resolve_; });
  return { promise, resolve };
}

function bearer(request) {
  return request.headers().authorization || '';
}

async function observeOldCatalogAuthorizationResponse(page, token) {
  await page.evaluate(expectedToken => {
    let processed;
    window.__oldCatalogAuthorizationProcessed = new Promise(resolve => { processed = resolve; });
    const fetch_ = window.fetch.bind(window);
    window.fetch = async (input, init) => {
      const url = typeof input === 'string' ? input : input.url;
      const headers = new Headers(init?.headers || (typeof input === 'string' ? undefined : input.headers));
      const response = await fetch_(input, init);
      if (new URL(url, window.location.href).pathname === '/v1/node-types'
          && headers.get('Authorization') === `Bearer ${expectedToken}`
          && (response.status === 401 || response.status === 403)) {
        const channel = new MessageChannel();
        channel.port1.onmessage = () => {
          channel.port1.close();
          channel.port2.close();
          processed();
        };
        // The transport and app rejection continuations are promise jobs. The next browser task
        // begins only after they have consumed this response; this is an ordering barrier, not a delay.
        channel.port2.postMessage(null);
      }
      return response;
    };
  }, token);
  return () => page.evaluate(() => window.__oldCatalogAuthorizationProcessed);
}

async function installService(page, oldToken, releaseOldCatalog) {
  const oldCatalogEntered = deferred();
  const executionAuthorization = deferred();
  await page.route('**/v1/configuration', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(CONFIGURATION),
  }));
  await page.route('**/v1/node-types', async route => {
    const authorization = bearer(route.request());
    if (authorization === `Bearer ${oldToken}`) {
      oldCatalogEntered.resolve();
      const status = await releaseOldCatalog.promise;
      await route.fulfill({
        status, contentType: 'application/json', body: JSON.stringify({ error: 'authorization rejected' }),
      });
      return;
    }
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify(authorization ? REPLACEMENT_CATALOG : []),
    });
  });
  await page.route('**/v1/events**', route => route.fulfill({
    status: 200, contentType: 'text/event-stream', body: ': connected\n\n',
  }));
  await page.route('**/v1/executions**', async route => {
    executionAuthorization.resolve(bearer(route.request()));
    await route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({
        executionId: 'execution-b', graphVersion: 'graph-b', executionPolicy: 'test',
      }),
    });
  });
  return { oldCatalogEntered: oldCatalogEntered.promise, executionAuthorization: executionAuthorization.promise };
}

async function authenticate(page, token) {
  await page.locator('#access-token').fill(token);
  await page.locator('#btn-authenticate').click();
}

for (const status of [401, 403]) {
  test(`a delayed ${status} from token A cannot erase ready token B`, async ({ page }) => {
    const oldToken = `old-${status}`;
    const replacementToken = `replacement-${status}`;
    const releaseOldCatalog = deferred();
    const service = await installService(page, oldToken, releaseOldCatalog);
    await page.goto('/');
    await expect.poll(() => page.evaluate(() =>
      window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
    const oldAuthorizationProcessed = await observeOldCatalogAuthorizationResponse(page, oldToken);

    try {
      await authenticate(page, oldToken);
      await service.oldCatalogEntered;
      await expect.poll(() => page.evaluate(() =>
        window.ravenroot.workspacePersistence().authority.state)).toBe('ready');

      await authenticate(page, replacementToken);
      await expect.poll(() => page.evaluate(() =>
        window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
      await expect(page.locator('[data-catalog-add="replacement.safe"]')).toBeVisible();

      releaseOldCatalog.resolve(status);
      await oldAuthorizationProcessed();
      await expect(page.locator('[data-catalog-add="replacement.safe"]')).toBeVisible();
      await expect(page.locator('#btn-play')).toBeEnabled();
      await page.locator('#btn-play').click();

      await expect(service.executionAuthorization).resolves.toBe(`Bearer ${replacementToken}`);
    } finally {
      releaseOldCatalog.resolve(status);
    }
  });
}

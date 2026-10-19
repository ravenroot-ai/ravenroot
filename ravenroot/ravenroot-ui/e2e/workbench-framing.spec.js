import { test, expect } from '@playwright/test';
import { createServer as createHttpServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import { fileURLToPath } from 'node:url';

import { configuration, createServer as createUiServer } from '../../../ui-server/server.mjs';

const DIST = fileURLToPath(new URL('../dist/', import.meta.url));
const TOKEN = 'framed-workbench-server-token';
const SAME_ORIGIN_PORTAL = `<!doctype html><html lang="en"><title>Portal</title>
  <iframe id="workbench" title="Ravenroot Workbench" allow="fullscreen" src="/"></iframe>`;

const listen = (server, address = '127.0.0.1', advertisedHost = address) => new Promise(resolve => {
  server.listen(0, address, () => {
    const host = advertisedHost.includes(':') ? `[${advertisedHost}]` : advertisedHost;
    resolve(`http://${host}:${server.address().port}`);
  });
});

const close = server => new Promise(resolve => {
  server.closeAllConnections();
  server.close(resolve);
});

function authenticatedBackend(requests) {
  return createHttpServer((request, response) => {
    requests.push({ path: request.url, authorization: request.headers.authorization });
    if (request.headers.authorization !== `Bearer ${TOKEN}`) {
      response.writeHead(401, { 'Content-Type': 'application/json' }).end('{}');
      return;
    }
    if (request.url === '/v1/configuration') {
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({
        schemaVersion: 1,
        graphDocumentMaxBytes: 10 * 1024 * 1024,
        workspace: { tenantId: 'framed-workbench' },
      }));
      return;
    }
    if (request.url === '/v1/node-types') {
      response.writeHead(200, { 'Content-Type': 'application/json' }).end('[]');
      return;
    }
    if (request.url.startsWith('/v1/events')) {
      response.writeHead(200, { 'Content-Type': 'text/event-stream' });
      response.write(': ready\n\n');
      return;
    }
    response.writeHead(404, { 'Content-Type': 'application/json' }).end('{}');
  });
}

function portalServer(target) {
  return createHttpServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    response.end(`<!doctype html><html lang="en"><title>Portal</title>
      <iframe id="workbench" title="Ravenroot Workbench" allow="fullscreen" src="${target()}/"></iframe>`);
  });
}

async function framedWorkbench(page, portalUrl, uiOrigin) {
  await page.goto(portalUrl);
  const workbench = page.frameLocator('#workbench');
  await expect(workbench.locator('.logo-wordmark')).toHaveText('Ravenroot Flow Editor');
  await expect(workbench.locator('#runtime-connection')).toContainText('connected');
  const frame = page.frames().find(candidate => candidate.url() === `${uiOrigin}/`);
  expect(frame).toBeTruthy();
  await expect.poll(() => frame.evaluate(
    () => window.ravenroot?.workspacePersistence().authority.state,
  )).toBe('ready');
  return frame;
}

test('an authorized cross-origin portal loads the built Workbench and its real opaque plugin bootstrap', async ({ page }) => {
  const requests = [];
  const backend = authenticatedBackend(requests);
  const upstream = await listen(backend);
  let uiOrigin = '';
  const portal = portalServer(() => uiOrigin);
  const portalOrigin = await listen(portal);
  const ui = createUiServer(configuration({
    RAVENROOT_UI_ROOT: DIST,
    RAVENROOT_UI_BACKEND_URL: upstream,
    RAVENROOT_UI_BACKEND_TOKEN: TOKEN,
    RAVENROOT_UI_FRAME_ANCESTORS: portalOrigin,
  }));
  uiOrigin = await listen(ui);

  try {
    const frame = await framedWorkbench(page, portalOrigin, uiOrigin);
    const pluginResult = await frame.evaluate(async () => {
      const sandbox = window.ravenroot._createFrontendPluginSandboxForTest(
        "export default { layout() { return { loaded: 'inside-framed-workbench' }; } }",
        { timeout: 2000 },
      );
      try {
        return {
          layout: await sandbox.layout({}),
          sandbox: sandbox.element.getAttribute('sandbox'),
        };
      } finally {
        sandbox.destroy();
      }
    });
    expect(pluginResult).toEqual({
      layout: { loaded: 'inside-framed-workbench' },
      sandbox: 'allow-scripts',
    });
    expect(requests.some(request => request.path === '/v1/configuration')).toBe(true);
    expect(requests.every(request => request.authorization === `Bearer ${TOKEN}`)).toBe(true);

    const health = await page.request.get(`${uiOrigin}/health`);
    expect(await health.json()).toEqual({ status: 'UP' });
    const workbenchResponse = await page.request.get(`${uiOrigin}/`);
    expect(workbenchResponse.headers()['x-frame-options']).toBeUndefined();
    expect(workbenchResponse.headers()['content-security-policy'])
      .toContain(`frame-ancestors ${portalOrigin}`);
    const sandboxResponse = await page.request.get(`${uiOrigin}/frontend-plugin-sandbox.html`);
    expect(sandboxResponse.headers()['content-security-policy'])
      .toContain(`frame-ancestors 'self' ${portalOrigin}`);
    expect(sandboxResponse.headers()['content-security-policy']).toContain('sandbox allow-scripts');
  } finally {
    await close(ui);
    await close(portal);
    await close(backend);
  }
});

test('CSP-representable explicit DNS and IPv4 portal classes load the built Workbench', async ({ playwright }) => {
  const externalIpv4 = Object.values(networkInterfaces()).flat()
    .find(address => address?.family === 'IPv4' && !address.internal)?.address;
  expect(externalIpv4).toBeTruthy();
  const aliases = ['portal.123corp', 'portal-tools.example', 'xn--bcher-kva.123corp'];
  const browser = await playwright.chromium.launch({
    headless: true,
    args: [`--host-resolver-rules=${aliases.map(host => `MAP ${host} 127.0.0.1`).join(', ')}`,
      '--no-proxy-server'],
  });
  const page = await browser.newPage();
  const backend = authenticatedBackend([]);
  const upstream = await listen(backend);
  const cases = [
    ['digit-leading nonnumeric DNS label', 'portal.123corp', '127.0.0.1'],
    ['hyphenated DNS label', 'portal-tools.example', '127.0.0.1'],
    ['localhost', 'localhost', '127.0.0.1'],
    ['canonical Punycode DNS label', 'xn--bcher-kva.123corp', '127.0.0.1'],
    ['canonical loopback IPv4', '127.0.0.1', '127.0.0.1'],
    ['canonical non-loopback IPv4', externalIpv4, externalIpv4],
  ];

  try {
    for (const [name, host, bindAddress] of cases) {
      let uiOrigin = '';
      const portal = portalServer(() => uiOrigin);
      const portalOrigin = await listen(portal, bindAddress, host);
      const ui = createUiServer(configuration({
        RAVENROOT_UI_ROOT: DIST,
        RAVENROOT_UI_BACKEND_URL: upstream,
        RAVENROOT_UI_BACKEND_TOKEN: TOKEN,
        RAVENROOT_UI_FRAME_ANCESTORS: portalOrigin,
      }));
      uiOrigin = await listen(ui);
      try {
        await test.step(name, () => framedWorkbench(page, portalOrigin, uiOrigin));
      } finally {
        await close(ui);
        await close(portal);
      }
    }
  } finally {
    await close(backend);
    await page.close();
    await browser.close();
  }
});

test('self allows a same-origin host page to load the built Workbench', async ({ page }) => {
  const requests = [];
  const backend = authenticatedBackend(requests);
  const upstream = await listen(backend);
  const ui = createUiServer(configuration({
    RAVENROOT_UI_ROOT: DIST,
    RAVENROOT_UI_BACKEND_URL: upstream,
    RAVENROOT_UI_BACKEND_TOKEN: TOKEN,
    RAVENROOT_UI_FRAME_ANCESTORS: 'self',
  }));
  const uiOrigin = await listen(ui);
  const portalUrl = `${uiOrigin}/portal.html`;
  await page.route(portalUrl, route => route.fulfill({
    contentType: 'text/html; charset=utf-8',
    body: SAME_ORIGIN_PORTAL,
  }));

  try {
    await framedWorkbench(page, portalUrl, uiOrigin);
    expect(requests.some(request => request.path === '/v1/configuration')).toBe(true);
  } finally {
    await close(ui);
    await close(backend);
  }
});

test('self allows a same-origin IPv6 host page to load the built Workbench', async ({ page }) => {
  const requests = [];
  const backend = authenticatedBackend(requests);
  const upstream = await listen(backend);
  const ui = createUiServer(configuration({
    RAVENROOT_UI_ROOT: DIST,
    RAVENROOT_UI_BACKEND_URL: upstream,
    RAVENROOT_UI_BACKEND_TOKEN: TOKEN,
    RAVENROOT_UI_FRAME_ANCESTORS: 'self',
  }));
  const uiOrigin = await listen(ui, '::1');
  const portalUrl = `${uiOrigin}/portal.html`;
  await page.route(portalUrl, route => route.fulfill({
    contentType: 'text/html; charset=utf-8',
    body: SAME_ORIGIN_PORTAL,
  }));

  try {
    await framedWorkbench(page, portalUrl, uiOrigin);
    expect(requests.some(request => request.path === '/v1/configuration')).toBe(true);
  } finally {
    await close(ui);
    await close(backend);
  }
});

for (const policy of [undefined, 'http://127.0.0.1:1']) {
  test(`${policy === undefined ? 'the closed default' : 'an unlisted portal'} is denied by the browser`, async ({ page }) => {
    let uiOrigin = '';
    const portal = portalServer(() => uiOrigin);
    const portalOrigin = await listen(portal);
    const environment = { RAVENROOT_UI_ROOT: DIST };
    if (policy !== undefined) environment.RAVENROOT_UI_FRAME_ANCESTORS = policy;
    const ui = createUiServer(configuration(environment));
    uiOrigin = await listen(ui);

    try {
      await page.goto(portalOrigin);
      await page.locator('#workbench').evaluate(element => new Promise(resolve => {
        element.addEventListener('load', resolve, { once: true });
        setTimeout(resolve, 1000);
      }));
      expect(page.frames().some(frame => frame.url() === `${uiOrigin}/`)).toBe(false);
      expect(await page.locator('#workbench').getAttribute('src')).toBe(`${uiOrigin}/`);
    } finally {
      await close(ui);
      await close(portal);
    }
  });
}

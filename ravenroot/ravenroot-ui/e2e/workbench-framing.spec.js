import { test, expect } from '@playwright/test';
import { createServer as createHttpServer, request as httpRequest } from 'node:http';
import { fileURLToPath } from 'node:url';

import { configuration, createServer as createUiServer } from '../../../ui-server/server.mjs';

const DIST = fileURLToPath(new URL('../dist/', import.meta.url));
const TOKEN = 'framed-workbench-server-token';

const listen = server => new Promise(resolve => {
  server.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${server.address().port}`));
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

function sameOriginFacade(target) {
  return createHttpServer((request, response) => {
    if (new URL(request.url, 'http://127.0.0.1').pathname === '/portal.html') {
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      response.end(`<!doctype html><html lang="en"><title>Portal</title>
        <iframe id="workbench" title="Ravenroot Workbench" allow="fullscreen" src="/"></iframe>`);
      return;
    }
    const upstream = new URL(request.url, target());
    const forwarded = httpRequest(upstream, {
      method: request.method,
      headers: { ...request.headers, host: upstream.host },
    }, upstreamResponse => {
      response.writeHead(upstreamResponse.statusCode, upstreamResponse.headers);
      upstreamResponse.pipe(response);
    });
    forwarded.on('error', error => response.destroy(error));
    request.pipe(forwarded);
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
  const facade = sameOriginFacade(() => uiOrigin);
  const facadeOrigin = await listen(facade);

  try {
    await framedWorkbench(page, `${facadeOrigin}/portal.html`, facadeOrigin);
    expect(requests.some(request => request.path === '/v1/configuration')).toBe(true);
  } finally {
    await close(facade);
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

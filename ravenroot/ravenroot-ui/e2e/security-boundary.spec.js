import { createServer } from 'node:http';

import { expect, test } from '@playwright/test';

import { SERVICE_ORIGIN, SERVICE_PORT, UI_ORIGIN, UI_PORT } from './ports.mjs';


let service;
let preflights;
let deniedPreflights;
let catalogRequests;

test.beforeAll(async () => {
  service = createServer((request, response) => {
    if (request.method === 'OPTIONS') {
      preflights += 1;
      const allowed = request.headers.origin === UI_ORIGIN
        && request.headers['access-control-request-method'] === 'GET'
        && String(request.headers['access-control-request-headers'] || '').toLowerCase() === 'authorization';
      if (!allowed) deniedPreflights += 1;
      response.writeHead(allowed ? 204 : 403, allowed ? {
        'Access-Control-Allow-Origin': UI_ORIGIN,
        'Access-Control-Allow-Methods': 'GET',
        'Access-Control-Allow-Headers': 'authorization',
        Vary: 'Origin',
      } : {});
      response.end();
      return;
    }
    if (request.url === '/v1/node-types') {
      catalogRequests += 1;
      response.writeHead(200, {
        'Access-Control-Allow-Origin': UI_ORIGIN,
        Vary: 'Origin',
        'Content-Type': 'application/json; charset=utf-8',
      });
      response.end('[{"behavior":"safe-template","displayName":"Safe template","category":"test","description":"test","visualType":"flow","agentic":false,"capabilities":[],"properties":[]}]');
      return;
    }
    if (request.url === '/v1/events') {
      response.writeHead(403, {
        'Access-Control-Allow-Origin': UI_ORIGIN,
        Vary: 'Origin',
        'Content-Type': 'application/json; charset=utf-8',
      });
      response.end('{"error":"access revoked"}');
      return;
    }
    response.writeHead(404).end();
  });
  preflights = 0;
  deniedPreflights = 0;
  catalogRequests = 0;
  await new Promise((resolve, reject) => service.once('error', reject).listen(SERVICE_PORT, '127.0.0.1', resolve));
});

test.afterAll(async () => new Promise(resolve => service.close(resolve)));

test('requires explicit acceptance before a bearer token can be sent cross-origin', async ({ page }) => {
  await page.goto('/');
  await page.locator('#access-token').fill('browser-memory-token');
  await page.locator('#btn-authenticate').click();
  const requestsBeforeExternalOrigin = catalogRequests;

  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => {
    expect(dialog.type()).toBe('confirm');
    expect(dialog.message()).toContain(SERVICE_ORIGIN);
    return dialog.dismiss();
  });
  await page.locator('#service-url').press('Tab');
  await expect(page.locator('#runtime-connection')).toContainText('authentication required');
  // The explanation has to survive. The event stream retries roughly once a second, and until
  // those retries overwrote the state a moment after the user's refusal set it — so this assertion
  // used to pass only by catching a window between two of them.
  await page.waitForTimeout(1_500);
  await expect(page.locator('#runtime-connection')).toContainText('authentication required');
  await expect(page.locator('#service-url')).toHaveValue('');
  expect(catalogRequests).toBe(requestsBeforeExternalOrigin);

  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await expect.poll(() => catalogRequests).toBeGreaterThan(0);
  await expect.poll(() => preflights).toBeGreaterThan(0);
  await expect(page.locator('#node-catalog')).toContainText('Safe template');
});

test('the browser blocks a denied Authorization preflight instead of exposing the cross-origin response', async ({ page }) => {
  await page.goto('/');
  await expect(page.evaluate(async serviceOrigin => {
    try {
      await fetch(`${serviceOrigin}/v1/node-types`, {
        headers: { Authorization: 'Bearer token', 'X-Unsafe': 'not-allowed' },
      });
      return 'unexpected-success';
    } catch (error) {
      return error.name;
    }
  }, SERVICE_ORIGIN)).resolves.toBe('TypeError');
  await expect.poll(() => deniedPreflights).toBeGreaterThan(0);
});

test('the browser enforces the delivered CSP for inline script and disallowed connection canaries', async ({ page }) => {
  const violations = [];
  await page.goto('/');
  // The port is passed IN because this body runs in the browser, where the Node-side import
  // is not in scope — a plain reference here throws inside the page and the canary never fires.
  await page.evaluate(uiPort => {
    document.addEventListener('securitypolicyviolation', event => {
      window.__cspViolations = [...(window.__cspViolations || []), event.violatedDirective];
    });
    const script = document.createElement('script');
    script.textContent = 'window.__inlineCanaryExecuted = true';
    document.body.append(script);
    // ── `localhost` IS LOAD-BEARING. DO NOT NORMALISE IT TO 127.0.0.1. ───────────────────────
    // MEASURED, three runs of this test:
    // * baseline -> PASSES
    // * `localhost` kept, port replaced by a garbage 59999 -> STILL PASSES
    // * port kept correct, host changed to 127.0.0.1 -> FAILS
    // So THE PORT HERE IS INERT AND THE HOST CARRIES THE ASSERTION. CSP is enforced BEFORE any
    // network attempt, so `localhost:<anything>` is a different origin from `'self'` and the
    // connect-src violation fires from the host mismatch alone — the canary never has to reach
    // anything, and nothing needs to be listening.
    //
    // Normalising the host to the dotted form makes this a SAME-ORIGIN fetch, which the policy
    // ALLOWS, and the assertion below silently stops testing anything. A consistency pass that
    // "tidied" this line would break it. The measurement belongs beside the canary so the host's
    // security significance remains explicit wherever the test is maintained.
    //
    // The port is derived anyway, so no stale literal survives the file — but the derivation is
    // cosmetic here and the host is not.
    void fetch(`http://localhost:${uiPort}/csp-connect-canary`).catch(() => {});
  }, UI_PORT);
  await expect.poll(() => page.evaluate(() => window.__cspViolations || [])).toEqual(
    expect.arrayContaining(['script-src-elem', 'connect-src']),
  );
  await expect(page.evaluate(() => window.__inlineCanaryExecuted)).resolves.toBeUndefined();
});

test('hostile GraphML and Graphify labels render as inert text rather than executable DOM', async ({ page }) => {
  const graphMl = `<?xml version="1.0"?><graphml xmlns="http://graphml.graphdrawing.org/xmlns"><key id="name" for="node" attr.name="name" attr.type="string"/><graph id="hostile" edgedefault="directed"><node id="graphml-node"><data key="name">&lt;img src=x onerror=window.__graphMlPwned=true&gt;</data></node></graph></graphml>`;
  const graphify = JSON.stringify({
    nodes: [{ id: 'graphify-node', label: '<script>window.__graphifyPwned=true</script>', type: 'file' }],
    edges: [],
  });
  await page.goto('/');
  await page.locator('#file-inp').setInputFiles({ name: 'hostile.graphml', mimeType: 'application/xml', buffer: Buffer.from(graphMl) });
  await expect.poll(() => page.evaluate(() => window.cy?.getElementById('graphml-node').length)).toBe(1);
  await page.locator('#file-inp').setInputFiles({ name: 'hostile.json', mimeType: 'application/json', buffer: Buffer.from(graphify) });
  await expect.poll(() => page.evaluate(() => window.cy?.getElementById('graphify-node').data('label')))
    .toContain('<script>window.__graphifyPwned=true</script>');
  await expect(page.evaluate(() => ({ graphMl: window.__graphMlPwned, graphify: window.__graphifyPwned })))
    .resolves.toEqual({ graphMl: undefined, graphify: undefined });
  await expect(page.locator('script:not([src])')).toHaveCount(0);
  await expect(page.locator('img[onerror]')).toHaveCount(0);
});

// ── What replaces `expect(app).not.toContain('localStorage')` ────────────────
//
// That assertion was a PROXY for "no token and no service trust decision is persisted", and it
// worked only while nothing in the UI legitimately needed storage. The panel layout does, so the
// proxy is replaced by a guard on what actually reaches storage in a real browser — which, unlike
// a text search, cannot be evaded by moving the store into another module, and unlike a narrowed
// text search does not have to guess which spellings of a token write to look for.
test('persists presentation preferences and never the token', async ({ page }) => {
  const TOKEN = 'tok-must-never-be-persisted-4f2b9c';

  await page.goto('/');

  // Give the UI a token the way a user would, and a layout change worth remembering.
  await page.locator('#access-token').fill(TOKEN);
  await page.locator('#btn-authenticate').click();
  await page.waitForTimeout(300);
  await page.locator('.panel[data-panel-id="graph-stats"] [data-action="panel-close"]').click();
  await page.waitForTimeout(200);
  await page.locator('#menu-view').click();
  await page.getByRole('menuitemradio', { name: 'Light theme' }).click();

  const readStorage = () => page.evaluate(() => {
    const out = {};
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      out[key] = localStorage.getItem(key);
    }
    return out;
  });

  // ── THE CONTROL, FIRST ────────────────────────────────────────────────────────────────────────
  // An assertion that nothing in storage contains the token is worth nothing unless it can SEE a
  // token in storage. Put one there by hand and require the same expression to catch it, before
  // any clean result is believed.
  await page.evaluate(token => localStorage.setItem('rr-control-token', token), TOKEN);
  const withPlantedToken = await readStorage();
  expect(Object.values(withPlantedToken).some(value => value.includes(TOKEN))).toBe(true);
  await page.evaluate(() => localStorage.removeItem('rr-control-token'));

  // ── THE CLAIM ─────────────────────────────────────────────────────────────────────────────────
  const stored = await readStorage();

  // The layout IS persisted — otherwise this test would pass on a UI that simply stores nothing,
  // which is the vacuous version of a storage guard.
  expect(Object.keys(stored)).toContain('ravenroot.ui.panel-layout');
  const layout = JSON.parse(stored['ravenroot.ui.panel-layout']);
  expect(layout.panels.find(panel => panel.id === 'graph-stats').closed).toBe(true);
  expect(stored['ravenroot.ui.theme']).toBe('light');

  // The token is in NO key and NO value, whichever module wrote them.
  for (const [key, value] of Object.entries(stored)) {
    expect(key, `key ${key} carried the token`).not.toContain(TOKEN);
    expect(value, `value at ${key} carried the token`).not.toContain(TOKEN);
  }

  // And it survives a reload without the token coming back with it.
  await page.reload();
  await page.waitForTimeout(300);
  await expect(page.locator('#access-token')).toHaveValue('');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  const afterReload = await readStorage();
  for (const value of Object.values(afterReload)) expect(value).not.toContain(TOKEN);
  // The layout, however, did survive — which is the whole point of persisting it.
  await expect(page.locator('.panel[data-panel-id="graph-stats"]')).toBeHidden();
});

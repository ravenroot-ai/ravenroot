import { createServer } from 'node:http';

import { expect, test } from '@playwright/test';

import { SERVICE_ORIGIN, SERVICE_PORT, UI_ORIGIN } from './ports.mjs';

// Against a loopback service that requires no authentication the palette rendered
// EMPTY, because the client refused to send the catalog request at all. These tests drive the real
// browser and assert what the user actually sees in the palette.


// THREE ENTRIES, AND WHAT THEY ARE FOR. The subject of every case below is "the palette fills from
// what the service answered", so what these types ARE is beside the point — what matters is that
// there is more than one and that each one is asserted by name, which is what tells a palette that
// rendered the catalog apart from one that rendered a placeholder.
//
// The fixture excludes `llm-prompt` and `agent`: those node types are supplied by a plugin bundle,
// so a stub asserting that the core catalog serves them would pin the opposite of what ships.
// `delay` and `json-path` are core behaviours (`DelayNodeBehaviorFactory`, `JsonPathNodeBehaviorFactory`)
// and stay in the catalog this test is about.
const LOOPBACK_CATALOG = JSON.stringify([
  { behavior: 'template', displayName: 'Template', category: 'core', description: 'Renders a template', visualType: 'flow', agentic: false, capabilities: [], properties: [] },
  { behavior: 'delay', displayName: 'Delay', category: 'Control flow', description: 'Pauses without blocking a worker', visualType: 'flow', agentic: false, capabilities: [], properties: [] },
  { behavior: 'json-path', displayName: 'JSONPath', category: 'Transformations', description: 'Selects data from JSON', visualType: 'flow', agentic: false, capabilities: [], properties: [] },
]);

let service;
let catalogStatus = 200;

function startService() {
  service = createServer((request, response) => {
    const headers = {
      'Access-Control-Allow-Origin': UI_ORIGIN,
      Vary: 'Origin',
      'Content-Type': 'application/json; charset=utf-8',
    };
    if (request.url === '/v1/node-types') {
      // A loopback service with authentication disabled: it authorises everyone and never asks
      // for a token. `catalogStatus` lets a single test flip it into a service that does.
      response.writeHead(catalogStatus, headers);
      response.end(catalogStatus === 200 ? LOOPBACK_CATALOG : '{"error":"unauthorized"}');
      return;
    }
    if (request.url === '/v1/events') {
      response.writeHead(204, headers);
      response.end();
      return;
    }
    response.writeHead(404, headers).end('{}');
  });
  return new Promise((resolve, reject) => service.once('error', reject).listen(SERVICE_PORT, '127.0.0.1', resolve));
}

// This helper is itself the evidence that the boot path was never covered. It fills
// `#service-url` and presses Tab, so every assertion below it ran only because a `change` event was
// fired by hand. The cases that open the page and touch nothing deliberately do NOT use it.
async function connectWithoutToken(page) {
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
}

// The service the page reaches at boot is its own origin: `#service-url` is empty and the client
// resolves that to same-origin relative paths. The fixture server that hosts the bundle only serves
// static files, so the loopback service is placed on that origin here.
async function serveLoopbackCatalogAtBootOrigin(page, { status = 200 } = {}) {
  const requests = [];
  await page.route('**/v1/node-types', async route => {
    requests.push(route.request().url());
    await route.fulfill({
      status,
      contentType: 'application/json; charset=utf-8',
      body: status === 200 ? LOOPBACK_CATALOG : '{"error":"unauthorized"}',
    });
  });
  await page.route('**/v1/events', route => route.fulfill({ status: 204, body: '' }));
  return requests;
}

test.beforeEach(async () => {
  catalogStatus = 200;
  await startService();
});

test.afterEach(async () => new Promise(resolve => service.close(resolve)));

test('an unauthenticated loopback service fills the palette with every type it answered', async ({ page }) => {
  await connectWithoutToken(page);

  const catalog = page.locator('#node-catalog');
  await expect(catalog.locator('[data-catalog-add="template"]')).toBeVisible();
  await expect(catalog.locator('[data-catalog-add="delay"]')).toBeVisible();
  await expect(catalog.locator('[data-catalog-add="json-path"]')).toBeVisible();
  await expect(catalog.locator('.catalog-empty')).toHaveCount(0);
  // No token was ever pasted: the access token field is still empty and revocation stays disabled.
  await expect(page.locator('#access-token')).toHaveValue('');
  await expect(page.locator('#btn-revoke')).toBeDisabled();
});

test('a service that answers 401 says so, and does not claim the catalog is unreachable', async ({ page }) => {
  catalogStatus = 401;

  await connectWithoutToken(page);

  const empty = page.locator('#node-catalog .catalog-empty');
  await expect(empty).toHaveAttribute('data-catalog-state', 'authentication-required');
  await expect(empty).toContainText('401');
  await expect(empty).toContainText('authentication is required');
  await expect(empty).not.toContainText('unreachable');
});

test('a service that does not answer at all is reported as unreachable, not as unauthorised', async ({ page }) => {
  await new Promise(resolve => service.close(resolve));

  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');

  const empty = page.locator('#node-catalog .catalog-empty');
  await expect(empty).toHaveAttribute('data-catalog-state', 'unreachable');
  await expect(empty).toContainText('unreachable');
  await expect(empty).not.toContainText('401');

  await startService();
});

// ── THE BOOT PATH ITSELF, WITH NO USER INTERACTION WHATSOEVER ────────────────────────────────
//
// These two cases never call `connectWithoutToken`. Nothing is typed, nothing is pressed, no token
// is pasted: the page is opened and that is all. `waitForRequest` proves the catalogue request
// leaves the browser and fails loudly if it does not.

test('opening the page attempts the connection: the catalogue arrives with zero user interaction', async ({ page }) => {
  const catalogRequests = await serveLoopbackCatalogAtBootOrigin(page);

  // The spy proves that page load asks the service for the catalog.
  const catalogRequest = page.waitForRequest('**/v1/node-types', { timeout: 10_000 });
  await page.goto('/');
  await catalogRequest;

  const catalog = page.locator('#node-catalog');
  await expect(catalog.locator('[data-catalog-add="template"]')).toBeVisible();
  await expect(catalog.locator('[data-catalog-add="delay"]')).toBeVisible();
  await expect(catalog.locator('[data-catalog-add="json-path"]')).toBeVisible();
  await expect(catalog.locator('.catalog-empty')).toHaveCount(0);
  // The call to action must not survive an attempt that succeeded.
  await expect(catalog).not.toContainText('Connect to the service to load programmable nodes');
  expect(catalogRequests).toHaveLength(1);
  // Still no credentials: the attempt is unauthenticated, exactly as a loopback service expects.
  await expect(page.locator('#access-token')).toHaveValue('');
  await expect(page.locator('#btn-revoke')).toBeDisabled();
});

test('a boot attempt refused with 401 keeps today\'s state and today\'s message', async ({ page }) => {
  await serveLoopbackCatalogAtBootOrigin(page, { status: 401 });

  const catalogRequest = page.waitForRequest('**/v1/node-types', { timeout: 10_000 });
  await page.goto('/');
  await catalogRequest;

  // The gate is the server's, and its answer is reported unchanged. This is not a weakened control:
  // the attempt is what changed, never the reaction to a refusal.
  const empty = page.locator('#node-catalog .catalog-empty');
  await expect(empty).toHaveAttribute('data-catalog-state', 'authentication-required');
  await expect(empty).toContainText('401');
  await expect(empty).toContainText('authentication is required');
  await expect(empty).not.toContainText('unreachable');
  // A failed attempt does not cost the user the editor.
  await expect(page.locator('#cy-wrap')).toBeVisible();
  await expect(page.locator('#btn-play')).toBeEnabled();
});

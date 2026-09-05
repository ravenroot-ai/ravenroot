import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';

import { expect, test } from '@playwright/test';

import { SERVICE_ORIGIN, SERVICE_PORT, UI_ORIGIN } from './ports.mjs';

// ADR 0024 §2, Inspector behavior. `/v1/catalog` (`/v1/node-types` on the wire — see
// runtime-client.js#nodeTypes) publishes `defaultNature`, `allowedNatures` and `natureProperty` per
// descriptor; the Inspector computes `declared ?? descriptor.defaultNature` client-side from exactly
// those three fields and offers a choice constrained to `allowedNatures` — never the full
// NodeRuntimeNature vocabulary. There is deliberately no per-node inspection route: every fixture
// below is shaped exactly like RavenrootServer#nodeTypeJson's output, nothing more.
//
// Fixture behaviors, each proving a different boundary:
// - example.worker-only allowedNatures = [WORKER] — nothing to escalate to at all
// - example.source-capable allowedNatures = [WORKER, SOURCE] — a real, safe choice
// - example.traversal-capable allowedNatures = [WORKER, TRAVERSAL] — the catalog choice
// - example.full-vocabulary allowedNatures = [WORKER, TRAVERSAL, SOURCE, AUTHORITY, KEYED] — the two
// declarable-but-refused-at-deploy values, to prove the help text and its association
// None of these four names exist anywhere else in this codebase — the same "no adapter has ever
// seen this before" genericity used by catalog-conditional-properties.spec.js.

function natureDescriptor(behavior, allowedNatures, defaultNature = 'WORKER') {
  return {
    behavior, displayName: behavior, category: 'Nature fixtures',
    description: `Fixture for ${behavior}.`, visualType: 'actor', agentic: false,
    capabilities: [], properties: [],
    defaultNature, allowedNatures, natureProperty: 'runtime.nature',
    defaultMaxConcurrency: 8, maxConcurrencyCeiling: 32,
    maxConcurrencyProperty: 'runtime.maxConcurrency',
  };
}

const WORKER_ONLY = natureDescriptor('example.worker-only', ['WORKER']);
const SOURCE_CAPABLE = natureDescriptor('example.source-capable', ['WORKER', 'SOURCE']);
const TRAVERSAL_CAPABLE = natureDescriptor('example.traversal-capable', ['WORKER', 'TRAVERSAL']);
const FULL_VOCABULARY = natureDescriptor(
  'example.full-vocabulary', ['WORKER', 'TRAVERSAL', 'SOURCE', 'AUTHORITY', 'KEYED'],
);

let service;
let catalog;

function startService() {
  service = createServer((request, response) => {
    const headers = {
      'Access-Control-Allow-Origin': UI_ORIGIN,
      Vary: 'Origin',
      'Content-Type': 'application/json; charset=utf-8',
    };
    if (request.url === '/v1/configuration') {
      response.writeHead(200, headers);
      response.end(JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024 }));
      return;
    }
    if (request.url === '/v1/node-types') {
      response.writeHead(200, headers);
      response.end(JSON.stringify(catalog));
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

async function connectAndModify(page) {
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await page.locator('#btn-new').click();
  await page.locator('#btn-modify').click();
}

async function addNode(page, behavior) {
  await page.locator(`#node-catalog [data-catalog-add="${behavior}"]`).click();
}

test.beforeEach(async () => {
  catalog = [WORKER_ONLY, SOURCE_CAPABLE, TRAVERSAL_CAPABLE, FULL_VOCABULARY];
  await startService();
});

test.afterEach(async () => new Promise(resolve => service.close(resolve)));

test('a new node shows the effective nature as inherited, not merely absent', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.source-capable');

  const select = page.locator('#node-nature-select');
  await expect(select).toHaveValue('');
  await expect(select.locator('option:checked')).toHaveText(/Inherit default \(WORKER\)/);
  const label = page.locator('.node-nature [data-nature-state]');
  await expect(label).toHaveText(/Inherited default \(WORKER\)/);
});

test('declaring a non-default nature is visible and distinguishable from inherited — not rendered identically', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.source-capable');

  const label = page.locator('.node-nature [data-nature-state]');
  await expect(label).toHaveText(/Inherited/);

  await page.locator('#node-nature-select').selectOption('SOURCE');

  await expect(label).toHaveText('Declared on this node.');
  await expect(label).not.toHaveText(/Inherited/);
});

test('catalog-driven runtime concurrency persists beside nature and can return to inherited', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.source-capable');

  const concurrency = page.locator('#node-max-concurrency');
  await expect(concurrency).toHaveAttribute('min', '1');
  await expect(concurrency).toHaveAttribute('max', '32');
  await expect(concurrency).toHaveValue('');
  await expect(page.locator('[data-max-concurrency-state]')).toHaveText(/Inherited default \(8\)/);

  await page.locator('#node-nature-select').selectOption('SOURCE');
  await concurrency.fill('1');
  await page.locator('#node-editor input[name="id"]').fill('runtime-policy-node');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.waitForSelector('#node-editor');
  await expect(page.locator('#node-nature-select')).toHaveValue('SOURCE');
  await expect(page.locator('#node-max-concurrency')).toHaveValue('1');

  await page.locator('#node-max-concurrency').fill('');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.waitForSelector('#node-editor');
  await expect(page.locator('#node-max-concurrency')).toHaveValue('');
  await expect(page.locator('[data-max-concurrency-state]')).toHaveText(/Inherited default \(8\)/);
});

test('the declared choice survives a save and reopening the Inspector — round-trips through the document, not just the form', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.traversal-capable');

  await page.locator('#node-nature-select').selectOption('TRAVERSAL');
  await page.locator('#node-editor input[name="id"]').fill('traversal-nature-node');
  await page.locator('#node-editor button[type="submit"]').click();

  await page.waitForSelector('#node-editor');
  await expect(page.locator('#node-editor input[name="id"]')).toHaveValue('traversal-nature-node');
  await expect(page.locator('#node-nature-select')).toHaveValue('TRAVERSAL');
  await expect(page.locator('.node-nature [data-nature-state]')).toHaveText('Declared on this node.');

  const downloadPromise = page.waitForEvent('download');
  await page.locator('#btn-export').click();
  const download = await downloadPromise;
  const exportedGraphml = readFileSync(await download.path(), 'utf8');
  expect(exportedGraphml).toContain('attr.name="runtime.nature"');
  expect(exportedGraphml).toContain('>TRAVERSAL</data>');

  await page.locator('#file-inp').setInputFiles({
    name: 'traversal-nature-roundtrip.graphml',
    mimeType: 'application/xml',
    buffer: Buffer.from(exportedGraphml),
  });
  await expect.poll(() => page.evaluate(() => {
    const active = window.ravenroot.activeDocument();
    return active?.name === 'traversal-nature-roundtrip.graphml' && !active.cy.scratch('_rrLayoutRunning');
  })).toBe(true);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('traversal-nature-node').data('properties')['runtime.nature'])).toBe('TRAVERSAL');

  await page.locator('#btn-modify').click();
  await page.evaluate(() => {
    window.ravenroot.activeDocument().cy.getElementById('traversal-nature-node').emit('tap');
  });
  await page.waitForSelector('#node-editor');
  await expect(page.locator('#node-nature-select')).toHaveValue('TRAVERSAL');
});

test('reverting to "Inherit default" and saving drops the declaration back to absent, not to a stored WORKER', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.source-capable');
  await page.locator('#node-nature-select').selectOption('SOURCE');
  await page.locator('#node-editor input[name="id"]').fill('nature-node-2');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.waitForSelector('#node-editor');
  await expect(page.locator('#node-nature-select')).toHaveValue('SOURCE');

  await page.locator('#node-nature-select').selectOption('');
  await page.locator('#node-editor button[type="submit"]').click();

  await page.waitForSelector('#node-editor');
  await expect(page.locator('#node-nature-select')).toHaveValue('');
  await expect(page.locator('.node-nature [data-nature-state]')).toHaveText(/Inherited default \(WORKER\)/);
});

test('a descriptor with nothing to escalate to never offers anything beyond its own allowlist', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.worker-only');

  const select = page.locator('#node-nature-select');
  await expect(select.locator('option')).toHaveCount(2); // "Inherit default" + WORKER, nothing else
  await expect(select.locator('option[value="SOURCE"]')).toHaveCount(0);
  await expect(select.locator('option[value="AUTHORITY"]')).toHaveCount(0);
  await expect(select.locator('option[value="KEYED"]')).toHaveCount(0);
});

test('a source-capable descriptor never offers AUTHORITY or KEYED — the allowlist, not the full vocabulary, bounds the control', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.source-capable');

  const select = page.locator('#node-nature-select');
  await expect(select.locator('option[value="AUTHORITY"]')).toHaveCount(0);
  await expect(select.locator('option[value="KEYED"]')).toHaveCount(0);
});

test('AUTHORITY carries a deploy-refusal warning, associated with the control via aria-describedby, that appears the moment it is chosen', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.full-vocabulary');

  const select = page.locator('#node-nature-select');
  const risk = page.locator('#nature-risk');
  await expect(risk).toBeHidden();
  await expect(select).toHaveAttribute('aria-describedby', /nature-risk/);
  const help = page.getByRole('button', { name: 'Help: Runtime nature' });
  await expect(help).toHaveAttribute('aria-controls', 'contextual-help-popover');
  await expect(help).toHaveAttribute('aria-expanded', 'false');
  await help.click();
  await expect(page.locator('#contextual-help-popover')).toContainText(/controls this node's lifecycle/i);
  await select.click();

  await select.selectOption('AUTHORITY');

  await expect(risk).toBeVisible();
  await expect(risk).toContainText(/refused/i);
  await expect(risk).toContainText(/AUTHORITY/);
  await expect(risk).toHaveAttribute('role', 'status');
  await expect(risk).toHaveAttribute('aria-live', 'polite');
});

test('KEYED carries its own distinct deploy-refusal warning, and switching back to WORKER clears it', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.full-vocabulary');

  const select = page.locator('#node-nature-select');
  const risk = page.locator('#nature-risk');

  await select.selectOption('KEYED');
  await expect(risk).toBeVisible();
  await expect(risk).toContainText(/KEYED/);

  await select.selectOption('WORKER');
  await expect(risk).toBeHidden();
});

test('AUTHORITY and KEYED are declarable and save successfully — the Inspector does not duplicate the server refusal', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.full-vocabulary');

  await page.locator('#node-nature-select').selectOption('AUTHORITY');
  await page.locator('#node-editor input[name="id"]').fill('authority-node-1');
  await page.locator('#node-editor button[type="submit"]').click();

  await page.waitForSelector('#node-editor');
  await expect(page.locator('#node-editor input[name="id"]')).toHaveValue('authority-node-1');
  await expect(page.locator('#node-nature-select')).toHaveValue('AUTHORITY');
});

test('a node that cannot declare a nature at all shows the fixed effective value with no control', async ({ page }) => {
  await connectAndModify(page);
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="id"]').fill('plain-node-1');
  await page.locator('#node-editor select[name="kind"]').selectOption('START');

  await expect(page.locator('#node-nature-select')).toHaveCount(0);
  await expect(page.locator('.node-nature-fixed .nature-value')).toHaveText('WORKER');
});

test('an unknown/custom behavior name (no catalog descriptor) also shows the fixed WORKER readout, never a reachable control', async ({ page }) => {
  await connectAndModify(page);
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="id"]').fill('custom-behavior-1');
  await page.locator('#node-editor select[name="kind"]').selectOption('BEHAVIOR');
  await page.locator('#node-editor input[name="behavior"]').fill('nobody.registered.this');

  await expect(page.locator('#node-nature-select')).toHaveCount(0);
  await expect(page.locator('.node-nature-fixed .nature-value')).toHaveText('WORKER');
});

test('switching the catalog type rebuilds the nature control against the NEW descriptor\'s allowlist', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.full-vocabulary');
  await expect(page.locator('#node-nature-select option[value="AUTHORITY"]')).toHaveCount(1);

  await page.locator('#node-editor select[name="catalogBehavior"]').selectOption('example.worker-only');

  await expect(page.locator('#node-nature-select option[value="AUTHORITY"]')).toHaveCount(0);
  await expect(page.locator('#node-nature-select option')).toHaveCount(2);
});

test('the declared nature never leaks into the generic Additional properties editor as a duplicate control', async ({ page }) => {
  await connectAndModify(page);
  await addNode(page, 'example.source-capable');
  await page.locator('#node-nature-select').selectOption('SOURCE');
  await page.locator('#node-editor input[name="id"]').fill('no-duplicate-1');
  await page.locator('#node-editor button[type="submit"]').click();

  await page.waitForSelector('#node-editor');
  await expect(page.locator('#node-nature-select')).toHaveValue('SOURCE');
  await expect(page.locator('#node-properties [data-property-name]')).toHaveCount(0);
});

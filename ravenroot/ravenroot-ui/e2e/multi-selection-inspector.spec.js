import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

import { expect, test } from '@playwright/test';

const graphDocumentModule = readFileSync(new URL('../src/graph-document.js', import.meta.url), 'utf8');

const property = (name, type, options = {}) => ({
  name, displayName: options.displayName || name, type, required: false,
  description: options.description || '',
  defaultValue: Object.hasOwn(options, 'defaultValue') ? options.defaultValue : '',
  allowedValues: options.allowedValues || [], adapterBinding: false,
  visibleWhen: options.visibleWhen || null, requiredWhen: options.requiredWhen || null,
});

const COMMON = [
  property('description', 'TEXT', { displayName: 'Description' }),
  property('endpoint', 'URI', { displayName: 'Endpoint' }),
  property('secretRef', 'SECRET_REFERENCE', { displayName: 'Secret reference' }),
];
const CATALOG = [
  { behavior: 'multi.alpha', displayName: 'Alpha', category: 'Tests', visualType: 'flow', agentic: false,
    capabilities: [], properties: [...COMMON, property('alphaOnly', 'STRING', { displayName: 'Alpha only' })] },
  { behavior: 'multi.beta', displayName: 'Beta', category: 'Tests', visualType: 'actor', agentic: false,
    capabilities: [], properties: [...COMMON, property('betaOnly', 'STRING', { displayName: 'Beta only' })] },
];
const MODE_IS_ON = {
  contract: 'ravenroot.property-condition/1', property: 'mode', operator: 'EQUALS', values: ['ON'],
};
const CONDITIONAL_CATALOG = [
  { behavior: 'conditional.off', displayName: 'Conditional off', category: 'Tests', visualType: 'flow', agentic: false,
    capabilities: [], properties: [
      property('mode', 'STRING', { displayName: 'Mode', defaultValue: 'OFF', allowedValues: ['OFF', 'ON'] }),
      property('callback', 'URI', { displayName: 'Callback', visibleWhen: MODE_IS_ON, requiredWhen: MODE_IS_ON }),
    ] },
  { behavior: 'conditional.default-alpha', displayName: 'Conditional default alpha', category: 'Tests', visualType: 'flow', agentic: false,
    capabilities: [], properties: [
      property('mode', 'STRING', { displayName: 'Mode', defaultValue: 'ON', allowedValues: ['OFF', 'ON'] }),
      property('detail', 'STRING', { displayName: 'Detail', visibleWhen: MODE_IS_ON, requiredWhen: MODE_IS_ON }),
    ] },
  { behavior: 'conditional.default-beta', displayName: 'Conditional default beta', category: 'Tests', visualType: 'actor', agentic: false,
    capabilities: [], properties: [
      property('mode', 'STRING', { displayName: 'Mode', defaultValue: 'ON', allowedValues: ['OFF', 'ON'] }),
      property('detail', 'STRING', { displayName: 'Detail', visibleWhen: MODE_IS_ON, requiredWhen: MODE_IS_ON }),
    ] },
];

async function open(page, catalog = CATALOG) {
  await page.route('**/e2e-graph-document.js', route => route.fulfill({
    status: 200, contentType: 'text/javascript; charset=utf-8', body: graphDocumentModule,
  }));
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json; charset=utf-8', body: JSON.stringify(catalog),
  }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
  await expect(page.locator('#node-catalog')).toContainText(catalog[0].displayName);
}

async function configure(page, { ids = ['start', 'dosomething'], behaviors, values }) {
  await page.evaluate(({ ids: nodeIds, behaviors: nodeBehaviors, values: nodeValues }) => {
    const owner = window.ravenroot.activeDocument();
    nodeIds.forEach((id, index) => {
      const node = owner.graph.nodeMap[id];
      node.behavior = nodeBehaviors[index];
      node.properties = { ...nodeValues[index] };
      node.propertyTypes = Object.fromEntries(Object.keys(nodeValues[index]).map(key => [key, 'string']));
    });
    owner.cy.$(':selected').unselect();
  }, { ids, behaviors, values });
}

const selectionCount = page => page.locator('[data-multi-node-inspector]').getAttribute('data-selection-count');

async function selectWithPointer(page, ids = ['start', 'dosomething']) {
  const points = await page.evaluate(nodeIds => nodeIds.map(id => {
    const point = window.cy.getElementById(id).renderedPosition();
    const canvas = window.cy.container().getBoundingClientRect();
    return { x: canvas.left + point.x, y: canvas.top + point.y };
  }), ids);
  await page.mouse.click(points[0].x, points[0].y);
  await page.keyboard.down('Control');
  await page.mouse.click(points[1].x, points[1].y);
  await page.keyboard.up('Control');
  await expect.poll(() => selectionCount(page)).toBe(String(ids.length));
}

async function expectSelectionInspector(page, mode, count = 2) {
  const root = page.locator('[data-multi-node-inspector]');
  await expect(root).toHaveAttribute('data-mode', mode);
  await expect(root).toHaveAttribute('data-selection-count', String(count));
  await expect(page.locator('[data-multi-selection-count]')).toContainText(`${count} nodes selected`);
  await expect(page.locator('[data-contextual-help-title="Batch editing"]'))
    .toHaveAttribute('data-contextual-help', /Only catalog properties/);
  return root;
}

async function activeDocumentSnapshot(page) {
  return page.evaluate(async () => {
    const owner = window.ravenroot.activeDocument();
    const { serializeGraphML } = await import('/e2e-graph-document.js');
    return {
      graph: JSON.stringify(owner.graph),
      nodeMap: JSON.stringify(owner.graph.nodeMap),
      history: owner.history.state(),
      dirty: owner.history.isDirty(),
      graphml: serializeGraphML(owner.graph),
    };
  });
}

async function expectRejectedBatch(page, snapshot, message) {
  await page.locator('[data-apply-multi-properties]').click();
  const errors = page.locator('#multi-node-editor .validation-list');
  await expect(errors).toBeVisible();
  expect(await errors.evaluate(element => element.tagName)).toBe('UL');
  await expect(errors).toContainText(message);
  expect(await activeDocumentSnapshot(page)).toEqual(snapshot);
}

test('descriptor intersection exposes equal selection without a primary and distinguishes mixed, absent and secret values', async ({ page }) => {
  await open(page);
  const secret = `runtime-canary-${randomUUID()}`;
  await configure(page, {
    behaviors: ['multi.alpha', 'multi.beta'],
    values: [
      { description: 'first description', endpoint: 'https://one.example.test', secretRef: secret, alphaOnly: 'alpha' },
      { description: 'second description', endpoint: 'https://two.example.test', secretRef: `${secret}-other`, betaOnly: 'beta' },
    ],
  });
  await selectWithPointer(page);
  await expectSelectionInspector(page, 'view');

  await expect(page.locator('[data-multi-property="description"]')).toHaveAttribute('data-property-state', 'mixed');
  await expect(page.locator('[data-multi-property="endpoint"]')).toHaveAttribute('data-property-state', 'mixed');
  await expect(page.locator('[data-multi-property="secretRef"]')).toHaveAttribute('data-property-state', 'mixed');
  await expect(page.locator('[data-multi-property="secretRef"]')).toHaveAttribute('data-property-secret', 'true');
  await expect(page.locator('[data-multi-property="description"] [data-multi-property-state]')).toContainText('Mixed values');
  await expect(page.locator('[data-multi-property="alphaOnly"]')).toHaveCount(0);
  await expect(page.locator('[data-multi-property="betaOnly"]')).toHaveCount(0);

  const leaked = await page.evaluate(canary => ({
    markup: document.documentElement.innerHTML.includes(canary),
    live: [...document.querySelectorAll('[aria-live], [role="status"]')].some(region => region.textContent.includes(canary)),
  }), secret);
  expect(leaked).toEqual({ markup: false, live: false });

  // The widget's keyboard path remains additive: Enter selects the cursor and arrows move it.
  await page.evaluate(() => { window.cy.$(':selected').unselect(); });
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('Enter');
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('Enter');
  await expectSelectionInspector(page, 'view');
});

test('editing batches only touched common fields after confirmation, fails atomically and undoes in one step', async ({ page }) => {
  await open(page);
  const secret = `runtime-canary-${randomUUID()}`;
  await configure(page, {
    behaviors: ['multi.alpha', 'multi.beta'],
    values: [
      { description: 'first', endpoint: 'https://one.example.test', secretRef: secret, alphaOnly: 'keep-alpha' },
      { description: 'second', endpoint: 'https://two.example.test', secretRef: `${secret}-other`, betaOnly: 'keep-beta' },
    ],
  });
  await selectWithPointer(page);
  await page.locator('#btn-modify').click();
  await expectSelectionInspector(page, 'edit');
  const description = page.locator('[data-multi-property-input="description"]');
  const endpoint = page.locator('[data-multi-property-input="endpoint"]');
  const secretInput = page.locator('[data-multi-property-input="secretRef"]');
  await expect(secretInput).toHaveValue('');
  await expect(secretInput).toHaveAttribute('autocomplete', 'off');

  // A validation error happens before native confirmation and leaves every graph map/history entry intact.
  await description.fill('must not apply');
  await endpoint.fill('not an absolute uri');
  await expect(description).toHaveAttribute('data-batch-touched', 'true');
  await expect(endpoint).toHaveAttribute('data-batch-touched', 'true');
  await page.locator('[data-apply-multi-properties]').click();
  await expect(page.locator('#multi-node-editor .validation-list')).toContainText('Endpoint must be an absolute URI');
  expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.start.properties.description)).toBe('first');
  expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.dosomething.properties.description)).toBe('second');
  await expect(page.locator('#btn-undo')).toBeDisabled();

  await endpoint.fill('https://shared.example.test');
  let cancelMessage = '';
  page.once('dialog', dialog => {
    cancelMessage = dialog.message();
    void dialog.dismiss();
  });
  await page.locator('[data-apply-multi-properties]').click();
  expect(cancelMessage).toContain('2 selected nodes');
  expect(cancelMessage).not.toContain(secret);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.start.properties.description)).toBe('first');

  let confirmMessage = '';
  page.once('dialog', dialog => {
    confirmMessage = dialog.message();
    void dialog.accept();
  });
  await page.locator('[data-apply-multi-properties]').click();
  expect(confirmMessage).not.toContain(secret);
  await expect.poll(() => page.evaluate(() => {
    const graph = window.ravenroot.activeDocument().graph;
    return [graph.nodeMap.start.properties.description, graph.nodeMap.dosomething.properties.description];
  })).toEqual(['must not apply', 'must not apply']);
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', 'Undo Edit properties on 2 selected nodes');
  expect(await page.evaluate(() => ({
    endpoint: [window.ravenroot.activeDocument().graph.nodeMap.start.properties.endpoint,
      window.ravenroot.activeDocument().graph.nodeMap.dosomething.properties.endpoint],
    alpha: window.ravenroot.activeDocument().graph.nodeMap.start.properties.alphaOnly,
    beta: window.ravenroot.activeDocument().graph.nodeMap.dosomething.properties.betaOnly,
  }))).toEqual({ endpoint: ['https://shared.example.test', 'https://shared.example.test'], alpha: 'keep-alpha', beta: 'keep-beta' });

  await page.locator('#btn-undo').click();
  await expect.poll(() => page.evaluate(() => {
    const graph = window.ravenroot.activeDocument().graph;
    return [graph.nodeMap.start.properties.description, graph.nodeMap.dosomething.properties.description];
  })).toEqual(['first', 'second']);
  await expect(page.locator('#btn-undo')).toBeDisabled();
});

test('a mode change that reveals a required callback is rejected before a batch command exists', async ({ page }) => {
  await open(page, CONDITIONAL_CATALOG);
  await configure(page, {
    behaviors: ['conditional.off', 'conditional.off'],
    values: [{ mode: 'OFF' }, { mode: 'OFF' }],
  });
  await selectWithPointer(page);
  await page.locator('#btn-modify').click();
  await expectSelectionInspector(page, 'edit');
  await expect(page.locator('[data-multi-property-input="callback"]')).toHaveCount(0);

  const before = await activeDocumentSnapshot(page);
  await page.locator('[data-multi-property-input="mode"]').selectOption('ON');
  await expectRejectedBatch(page, before, 'Callback is required for');
  await expect(page.locator('#btn-undo')).toBeDisabled();
  await expect(page.locator('#dirty-state')).toHaveText('saved');
});

test('a default-on sibling rejects clearing its conditionally required detail before a batch command exists', async ({ page }) => {
  await open(page, CONDITIONAL_CATALOG);
  await configure(page, {
    behaviors: ['conditional.default-alpha', 'conditional.default-beta'],
    values: [{ detail: 'alpha detail' }, { detail: 'beta detail' }],
  });
  await selectWithPointer(page);
  await page.locator('#btn-modify').click();
  await expectSelectionInspector(page, 'edit');
  await expect(page.locator('[data-multi-property-input="mode"]')).toHaveValue('');

  const before = await activeDocumentSnapshot(page);
  const detail = page.locator('[data-multi-property-input="detail"]');
  await detail.fill('replacement before clear');
  await detail.fill('');
  await expectRejectedBatch(page, before, 'Detail is required for');
  await expect(page.locator('#btn-undo')).toBeDisabled();
  await expect(page.locator('#dirty-state')).toHaveText('saved');
});

test('padded, malformed, raw-bracket and illegal-zone URI values leave model, GraphML and history exact before a valid atomic batch', async ({ page }) => {
  await open(page);
  await configure(page, {
    behaviors: ['multi.alpha', 'multi.beta'],
    values: [
      { description: 'first', endpoint: 'https://one.example.test' },
      { description: 'second', endpoint: 'https://two.example.test' },
    ],
  });
  await selectWithPointer(page);
  await page.locator('#btn-modify').click();
  const endpoint = page.locator('[data-multi-property-input="endpoint"]');
  const before = await activeDocumentSnapshot(page);

  await endpoint.fill(' https://shared.example.test ');
  await expectRejectedBatch(page, before, 'Endpoint must be an absolute URI');
  await endpoint.fill('https://shared.example.test/%zz');
  await expectRejectedBatch(page, before, 'Endpoint must be an absolute URI');
  await endpoint.fill('http://example.com/a[b');
  await expectRejectedBatch(page, before, 'Endpoint must be an absolute URI');
  await endpoint.fill('http://[fe80::1%25en-0]/');
  await expectRejectedBatch(page, before, 'Endpoint must be an absolute URI');

  await endpoint.fill('https://shared.example.test/callback');
  let confirmation = '';
  page.once('dialog', dialog => {
    confirmation = dialog.message();
    void dialog.accept();
  });
  await page.locator('[data-apply-multi-properties]').click();
  expect(confirmation).toContain('2 selected nodes');
  await expect.poll(() => page.evaluate(() => {
    const graph = window.ravenroot.activeDocument().graph;
    return [graph.nodeMap.start.properties.endpoint, graph.nodeMap.dosomething.properties.endpoint];
  })).toEqual(['https://shared.example.test/callback', 'https://shared.example.test/callback']);
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', 'Undo Edit properties on 2 selected nodes');
  await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');
});

test('a JVM-valid IPv6 zone URI remains byte-exact through a description-only batch and one undo', async ({ page }) => {
  const zoneUri = 'http://[fe80::1%eth0]/';
  await open(page);
  await configure(page, {
    behaviors: ['multi.alpha', 'multi.beta'],
    values: [
      { description: 'first', endpoint: zoneUri },
      { description: 'second', endpoint: zoneUri },
    ],
  });
  await selectWithPointer(page);
  await page.locator('#btn-modify').click();
  await expect(page.locator('[data-multi-property-input="endpoint"]')).toHaveValue(zoneUri);

  await page.locator('[data-multi-property-input="description"]').fill('shared description');
  let confirmation = '';
  page.once('dialog', dialog => {
    confirmation = dialog.message();
    void dialog.accept();
  });
  await page.locator('[data-apply-multi-properties]').click();
  expect(confirmation).toContain('2 selected nodes');
  await expect.poll(() => page.evaluate(() => {
    const graph = window.ravenroot.activeDocument().graph;
    return {
      description: [graph.nodeMap.start.properties.description, graph.nodeMap.dosomething.properties.description],
      endpoint: [graph.nodeMap.start.properties.endpoint, graph.nodeMap.dosomething.properties.endpoint],
    };
  })).toEqual({
    description: ['shared description', 'shared description'], endpoint: [zoneUri, zoneUri],
  });
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', 'Undo Edit properties on 2 selected nodes');

  await page.locator('#btn-undo').click();
  await expect.poll(() => page.evaluate(() => {
    const graph = window.ravenroot.activeDocument().graph;
    return {
      description: [graph.nodeMap.start.properties.description, graph.nodeMap.dosomething.properties.description],
      endpoint: [graph.nodeMap.start.properties.endpoint, graph.nodeMap.dosomething.properties.endpoint],
    };
  })).toEqual({ description: ['first', 'second'], endpoint: [zoneUri, zoneUri] });
  await expect(page.locator('#btn-undo')).toBeDisabled();
});

test('an empty descriptor intersection gives an informative empty state and deselection restores focus safely', async ({ page }) => {
  await open(page);
  await configure(page, {
    behaviors: ['multi.alpha', 'unknown.untrusted'],
    values: [{ description: 'known' }, { description: 'untrusted' }],
  });
  await selectWithPointer(page);
  await expectSelectionInspector(page, 'view');
  await expect(page.locator('[data-multi-selection-empty]')).toContainText('No catalog property is compatible');
  await expect(page.locator('[data-multi-property]')).toHaveCount(0);

  await page.locator('#info-close').focus();
  await page.locator('#info-close').click();
  await expect(page.locator('[data-multi-node-inspector]')).toHaveCount(0);
  await expect(page.locator('#info-close')).toBeFocused();
});

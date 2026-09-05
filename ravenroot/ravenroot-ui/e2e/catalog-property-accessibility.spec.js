import { expect, test } from '@playwright/test';

const CONTRACT = 'ravenroot.property-condition/1';

function property(name, displayName, type, description, overrides = {}) {
  return {
    name,
    displayName,
    type,
    required: false,
    description,
    defaultValue: '',
    allowedValues: [],
    adapterBinding: false,
    visibleWhen: null,
    requiredWhen: null,
    ...overrides,
  };
}

const CATALOG = JSON.stringify([{
  behavior: 'accessible.fixture',
  displayName: 'Accessible fixture',
  category: 'Test fixtures',
  description: 'Exercises every generated catalog property control.',
  visualType: 'actor',
  agentic: false,
  capabilities: [],
  properties: [
    property('adapterRuntime', 'Adapter runtime', 'STRING', 'Deployment-configured runtime.', {
      required: true,
      adapterBinding: true,
    }),
    property('requiredName', 'Required name', 'STRING', 'A name the document must declare.', {
      required: true,
    }),
    property('notes', 'Notes', 'TEXT', 'Long-form notes.'),
    property('expression', 'Expression', 'CEL_EXPRESSION', 'A CEL expression.'),
    property('mode', 'Mode', 'STRING', 'How the fixture behaves.', {
      required: true,
      defaultValue: 'BASIC',
      allowedValues: ['BASIC', 'ADVANCED'],
    }),
    property('enabled', 'Enabled', 'BOOLEAN', 'Whether the fixture is enabled.', {
      defaultValue: 'false',
    }),
    property('retries', 'Retries', 'INTEGER', 'Maximum retry count.', {
      defaultValue: '3',
    }),
    property('ratio', 'Ratio', 'DECIMAL', 'A decimal ratio.', {
      defaultValue: '0.5',
    }),
    property('endpoint', 'Endpoint', 'URI', 'A service endpoint.'),
    property('credentialRef', 'Credential', 'SECRET_REFERENCE', 'Credential reference.'),
    property('callbackUrl', 'Callback URL', 'URI', 'Where advanced callbacks are sent.', {
      visibleWhen: {
        contract: CONTRACT,
        property: 'mode',
        operator: 'EQUALS',
        values: ['ADVANCED'],
      },
      requiredWhen: {
        contract: CONTRACT,
        property: 'mode',
        operator: 'EQUALS',
        values: ['ADVANCED'],
      },
    }),
  ],
}]);

async function startEditableWorkflow(page) {
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200,
    contentType: 'application/json; charset=utf-8',
    body: CATALOG,
  }));
  await page.route('**/v1/events', route => route.fulfill({ status: 204, body: '' }));
  await page.goto('/');
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-catalog-add="accessible.fixture"]')).toBeVisible();
}

async function openFixtureEditor(page) {
  await page.locator('[data-catalog-add="accessible.fixture"]').click();
  await expect(page.locator('#node-editor')).toBeVisible();
}

const field = (page, name) => page.locator(`[data-catalog-property="${name}"]`);

async function propertyIds(page) {
  return page.locator('[data-catalog-property]').evaluateAll(controls => Object.fromEntries(
    controls.map(control => [control.dataset.catalogProperty, control.id]),
  ));
}

test('generated property controls expose their labels, descriptions, states, and validation semantics', async ({ page }, testInfo) => {
  await startEditableWorkflow(page);
  await openFixtureEditor(page);

  const names = {
    adapterRuntime: 'Adapter runtime',
    requiredName: 'Required name',
    notes: 'Notes',
    expression: 'Expression',
    mode: 'Mode',
    enabled: 'Enabled',
    retries: 'Retries',
    ratio: 'Ratio',
    endpoint: 'Endpoint',
    credentialRef: 'Credential',
  };
  for (const [name, accessibleName] of Object.entries(names)) {
    await expect(field(page, name)).toHaveAccessibleName(accessibleName);
  }

  await expect(field(page, 'requiredName')).toHaveAccessibleDescription('A name the document must declare.');
  await expect(field(page, 'notes')).toHaveAccessibleDescription('Long-form notes.');
  await expect(field(page, 'expression')).toHaveAccessibleDescription('A CEL expression.');
  await expect(field(page, 'mode')).toHaveAccessibleDescription('How the fixture behaves.');
  await expect(field(page, 'enabled')).toHaveAccessibleDescription('Whether the fixture is enabled.');
  await expect(field(page, 'retries')).toHaveAccessibleDescription('Maximum retry count.');
  await expect(field(page, 'ratio')).toHaveAccessibleDescription('A decimal ratio.');
  await expect(field(page, 'endpoint')).toHaveAccessibleDescription('A service endpoint.');
  await expect(field(page, 'credentialRef')).toHaveAccessibleDescription(
    /Credential reference.*only the reference is written to the graph/,
  );
  await expect(field(page, 'adapterRuntime')).toHaveAccessibleDescription(
    /Not configured yet.*Deployment-configured runtime/,
  );

  const tree = await page.locator('#catalog-properties').ariaSnapshot();
  expect(tree).toContain('textbox "Adapter runtime"');
  expect(tree).toContain('textbox "Required name"');
  expect(tree).toContain('textbox "Notes"');
  expect(tree).toContain('textbox "Expression"');
  expect(tree).toContain('combobox "Mode"');
  expect(tree).toContain('combobox "Enabled"');
  expect(tree).toContain('spinbutton "Retries"');
  expect(tree).toContain('spinbutton "Ratio"');
  expect(tree).toContain('textbox "Endpoint"');
  expect(tree).toContain('combobox "Credential"');
  expect(tree).not.toContain('Callback URL');

  const requiredName = field(page, 'requiredName');
  await expect(requiredName).toHaveAttribute('required', '');
  await expect(requiredName).toHaveJSProperty('validity.valid', false);
  await expect(field(page, 'adapterRuntime')).not.toHaveAttribute('required', '');
  await expect(field(page, 'adapterRuntime')).toHaveJSProperty('validity.valid', true);

  const requiredId = await requiredName.getAttribute('id');
  await page.locator(`label[for="${requiredId}"]`).click();
  await expect(requiredName).toBeFocused();

  const before = await page.evaluate(() => window.cy.$('node').length);
  await page.locator('#node-editor button[type="submit"]').click();
  await expect.poll(() => page.evaluate(() => window.cy.$('node').length)).toBe(before);

  await requiredName.fill('Accessible node');
  await field(page, 'notes').fill('Round-trip notes');
  await field(page, 'expression').fill('payload.ready');
  await field(page, 'enabled').selectOption('true');
  await field(page, 'retries').fill('9');
  await field(page, 'ratio').fill('0.75');
  await field(page, 'endpoint').fill('https://example.test/service');
  await page.locator('#node-editor button[type="submit"]').click();

  await expect.poll(() => page.evaluate(() => window.cy.$('node').length)).toBe(before + 1);
  await expect(page.locator('#node-editor input[name="id"]')).toHaveAttribute('readonly', '');
  await expect(field(page, 'adapterRuntime')).toHaveValue('');
  await expect(field(page, 'requiredName')).toHaveValue('Accessible node');
  await expect(field(page, 'notes')).toHaveValue('Round-trip notes');
  await expect(field(page, 'expression')).toHaveValue('payload.ready');
  await expect(field(page, 'enabled')).toHaveValue('true');
  await expect(field(page, 'retries')).toHaveValue('9');
  await expect(field(page, 'ratio')).toHaveValue('0.75');
  await expect(field(page, 'endpoint')).toHaveValue('https://example.test/service');

  await field(page, 'adapterRuntime').locator('xpath=ancestor::div[contains(@class,"catalog-property")]')
    .scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('catalog-properties-desktop.png'), fullPage: true });
});

test('property IDs stay stable through conditional rerenders and remain disjoint across documents', async ({ page }, testInfo) => {
  await startEditableWorkflow(page);
  const firstDocument = await page.evaluate(() => window.ravenroot.workspace.activeId);
  await openFixtureEditor(page);

  const firstNodeId = await page.locator('#node-editor input[name="id"]').inputValue();
  const firstIds = await propertyIds(page);
  expect(new Set(Object.values(firstIds)).size).toBe(Object.keys(firstIds).length);

  const mode = field(page, 'mode');
  const modeId = firstIds.mode;
  await mode.focus();
  await mode.selectOption('ADVANCED');
  await expect(mode).toBeFocused();
  await expect(mode).toHaveAttribute('id', modeId);

  const callback = field(page, 'callbackUrl');
  await expect(callback).toBeVisible();
  await expect(callback).toHaveAccessibleName('Callback URL');
  await expect(callback).toHaveAccessibleDescription('Where advanced callbacks are sent.');
  await expect(callback).toHaveAttribute('required', '');
  const callbackId = await callback.getAttribute('id');
  await callback.fill('https://example.test/callback');

  await mode.selectOption('BASIC');
  await expect(callback).toBeHidden();
  await mode.selectOption('ADVANCED');
  await expect(callback).toHaveAttribute('id', callbackId);
  await expect(callback).toHaveValue('https://example.test/callback');

  const secondDocument = await page.evaluate(() => window.ravenroot.openDocument({ name: 'second.graphml' }));
  await openFixtureEditor(page);
  const secondNodeId = await page.locator('#node-editor input[name="id"]').inputValue();
  expect(secondNodeId).toBe(firstNodeId);
  const secondIds = await propertyIds(page);
  for (const [name, id] of Object.entries(firstIds)) expect(secondIds[name]).not.toBe(id);

  await page.evaluate(id => window.ravenroot.activateDocument(id), firstDocument);
  await openFixtureEditor(page);
  expect(await propertyIds(page)).toEqual(firstIds);

  await page.evaluate(id => window.ravenroot.activateDocument(id), secondDocument);
  await openFixtureEditor(page);
  await page.setViewportSize({ width: 720, height: 900 });
  await expect(field(page, 'requiredName')).toHaveAccessibleName('Required name');
  await field(page, 'requiredName').locator('xpath=ancestor::div[contains(@class,"catalog-property")]')
    .scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('catalog-properties-narrow.png'), fullPage: true });
});

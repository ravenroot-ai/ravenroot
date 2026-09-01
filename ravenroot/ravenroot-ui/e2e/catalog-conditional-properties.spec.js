import { createServer } from 'node:http';

import { expect, test } from '@playwright/test';

import { SERVICE_ORIGIN, SERVICE_PORT, UI_ORIGIN } from './ports.mjs';

// CATALOG-UI descriptor-driven conditional visibility/required-ness in the palette and
// the Inspector. Every fixture below reuses the EXACT example
// (docs/getting-started/conditional-node-properties.md) — a neutral "example.source" node with a
// "mode" property (LONG_POLLING/WEBHOOK) and a "callbackUrl" property visible and required only when
// mode is WEBHOOK. Nothing in this file, or in the code it exercises, has ever seen this behavior
// name before. That genericity proves unknown plugins work without a separate name-specific test: no
// adapter name appears anywhere in this file or in src/app.js's handling of it.

const CONTRACT = 'ravenroot.property-condition/1';

function propertyCondition(property, values) {
  return { contract: CONTRACT, property, operator: values.length > 1 ? 'ONE_OF' : 'EQUALS', values };
}

const MODE_PROPERTY = {
  name: 'mode', displayName: 'Mode', type: 'STRING', required: true,
  description: 'How this source receives events.', defaultValue: 'LONG_POLLING',
  allowedValues: ['LONG_POLLING', 'WEBHOOK'], adapterBinding: false,
  visibleWhen: null, requiredWhen: null,
};

const CALLBACK_URL_PROPERTY = {
  name: 'callbackUrl', displayName: 'Callback URL', type: 'STRING', required: false,
  description: 'Where the provider posts events.', defaultValue: '',
  allowedValues: [], adapterBinding: false,
  visibleWhen: propertyCondition('mode', ['WEBHOOK']),
  requiredWhen: propertyCondition('mode', ['WEBHOOK']),
};

// `callbackUrl` above is an `<input>` (`allowedValues: []`) — it cannot exercise the
// failure mode, which lives entirely in the `<select>` branch of
// `catalogPropertyFieldsHtml` (the HTML "placeholder label option" only exists for a `<select>`, and
// only a closed choice renders one). This property is the same conditionally-required shape as
// `callbackUrl`, but CLOSED (`allowedValues` non-empty) and with NO default — the exact shape
// `NodeTypeDescriptorValidator.requiredWhenHasNoDefault` mints every conditionally-required closed
// choice in, on any node package. Without a fixture like this one, the e2e suite is blind to the
// regression by construction, no matter how green it runs.
const DELIVERY_GUARANTEE_PROPERTY = {
  name: 'deliveryGuarantee', displayName: 'Delivery guarantee', type: 'STRING', required: false,
  description: 'How a delivered webhook event may be retried.', defaultValue: '',
  allowedValues: ['AT_LEAST_ONCE', 'EXACTLY_ONCE'], adapterBinding: false,
  visibleWhen: propertyCondition('mode', ['WEBHOOK']),
  requiredWhen: propertyCondition('mode', ['WEBHOOK']),
};

const EXAMPLE_SOURCE = {
  behavior: 'example.source', displayName: 'Example source', category: 'Sources',
  description: 'A neutral example node with a conditional field.', visualType: 'actor',
  agentic: false, capabilities: [],
  properties: [MODE_PROPERTY, CALLBACK_URL_PROPERTY, DELIVERY_GUARANTEE_PROPERTY],
};

// The malformed-descriptor / robustness fixture: `visibleWhen`'s contract is one this evaluator has
// never seen, on a condition that WOULD otherwise be satisfied by the sibling's current value — this
// is what proves "hides rather than shows", not merely "hides because nothing matched anyway". A
// second property pair references each other's raw values (the shape a genuine declared cycle would
// have, per the doc); the backend rejects an actual cycle at catalog load and this can never really
// reach a browser, but the client must not hang or crash if it somehow did.
const UNKNOWN_CONTRACT_PROPERTY = {
  name: 'legacyHint', displayName: 'Legacy hint', type: 'STRING', required: false,
  description: 'Guarded by a condition this evaluator does not recognise.', defaultValue: '',
  allowedValues: [], adapterBinding: false,
  visibleWhen: { contract: 'ravenroot.property-condition/99-unknown', property: 'mode', operator: 'EQUALS', values: ['LONG_POLLING'] },
  requiredWhen: null,
};

const CYCLE_SHAPED_A = {
  name: 'cycleA', displayName: 'Cycle A', type: 'STRING', required: false,
  description: 'References cycleB.', defaultValue: 'match-b',
  allowedValues: [], adapterBinding: false,
  visibleWhen: propertyCondition('cycleB', ['match-a']), requiredWhen: null,
};
const CYCLE_SHAPED_B = {
  name: 'cycleB', displayName: 'Cycle B', type: 'STRING', required: false,
  description: 'References cycleA.', defaultValue: 'match-a',
  allowedValues: [], adapterBinding: false,
  visibleWhen: propertyCondition('cycleA', ['match-b']), requiredWhen: null,
};

const MALFORMED_SOURCE = {
  behavior: 'example.malformed', displayName: 'Example malformed source', category: 'Sources',
  description: 'Robustness fixture: an unrecognised contract and a cycle-shaped pair.',
  visualType: 'actor', agentic: false, capabilities: [],
  properties: [MODE_PROPERTY, UNKNOWN_CONTRACT_PROPERTY, CYCLE_SHAPED_A, CYCLE_SHAPED_B],
};

let service;
let catalog;

function startService() {
  service = createServer((request, response) => {
    const headers = {
      'Access-Control-Allow-Origin': UI_ORIGIN,
      Vary: 'Origin',
      'Content-Type': 'application/json; charset=utf-8',
    };
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

async function addExampleSourceNode(page) {
  await page.locator('#node-catalog [data-catalog-add="example.source"]').click();
}

test.beforeEach(async () => {
  catalog = [EXAMPLE_SOURCE, MALFORMED_SOURCE];
  await startService();
});

test.afterEach(async () => new Promise(resolve => service.close(resolve)));

test('a conditional field is hidden by default and reveals only when its sibling matches', async ({ page }) => {
  await connectAndModify(page);
  await addExampleSourceNode(page);

  const callbackUrl = page.locator('[data-catalog-property="callbackUrl"]');
  const wrapper = callbackUrl.locator('xpath=ancestor::div[contains(@class,"catalog-property")]');
  await expect(wrapper).toBeHidden();

  await page.locator('[data-catalog-property="mode"]').selectOption('WEBHOOK');

  await expect(wrapper).toBeVisible();
  await expect(callbackUrl).toHaveAttribute('required', '');
  await expect(page.locator('label', { hasText: 'Callback URL' })).toContainText('*');
});

test('switching back hides the field and drops its required-ness — required is conditional, not sticky', async ({ page }) => {
  await connectAndModify(page);
  await addExampleSourceNode(page);

  await page.locator('[data-catalog-property="mode"]').selectOption('WEBHOOK');
  const callbackUrl = page.locator('[data-catalog-property="callbackUrl"]');
  const wrapper = callbackUrl.locator('xpath=ancestor::div[contains(@class,"catalog-property")]');
  await expect(wrapper).toBeVisible();

  await page.locator('[data-catalog-property="mode"]').selectOption('LONG_POLLING');

  await expect(wrapper).toBeHidden();
});

test('focus returns to the control that triggered the change, not to document body or the newly revealed field', async ({ page }) => {
  await connectAndModify(page);
  await addExampleSourceNode(page);

  const mode = page.locator('[data-catalog-property="mode"]');
  await mode.focus();
  await mode.selectOption('WEBHOOK');

  // The concrete failure this proves absent: the field unmounts/remounts under `mode` and focus
  // falls to <body>, silently losing the keyboard user's place with no announcement of where they
  // went. It must land back on `mode` by name, not merely "somewhere in the form".
  await expect(mode).toBeFocused();
  await expect(page.locator('body')).not.toBeFocused();
});

test('the conditional change is announced through the existing polite live region, not merely present in the DOM unannounced', async ({ page }) => {
  await connectAndModify(page);
  await addExampleSourceNode(page);

  const live = page.locator('#graph-live');
  await expect(live).toHaveAttribute('aria-live', 'polite');
  await expect(live).toHaveAttribute('role', 'status');
  await expect(live).toBeEmpty();

  await page.locator('[data-catalog-property="mode"]').selectOption('WEBHOOK');

  await expect(live).toContainText('Callback URL');
  await expect(live).toContainText(/visible/i);
});

test('a value typed while visible survives being hidden, shown again, and a real submit while hidden', async ({ page }) => {
  await connectAndModify(page);
  await addExampleSourceNode(page);

  await page.locator('[data-catalog-property="mode"]').selectOption('WEBHOOK');
  await page.locator('[data-catalog-property="callbackUrl"]').fill('https://example.test/callback');

  // Hide it, then show it again, entirely client-side: the value must not have been reset by either
  // re-render, since catalogPropertyFieldsHtml keeps every property rendered with its resolved value
  // regardless of visibility.
  await page.locator('[data-catalog-property="mode"]').selectOption('LONG_POLLING');
  await page.locator('[data-catalog-property="mode"]').selectOption('WEBHOOK');
  await expect(page.locator('[data-catalog-property="callbackUrl"]')).toHaveValue('https://example.test/callback');

  // Hide it again and submit WHILE hidden — this is the exact data-loss shape: `hidden` fields are
  // still collected by readCatalogPropertyEditor, so a save must not drop the value just because the
  // field was not visible at the moment Save was clicked.
  await page.locator('[data-catalog-property="mode"]').selectOption('LONG_POLLING');
  await page.locator('#node-editor input[name="id"]').fill('conditional-node-1');
  await page.locator('#node-editor button[type="submit"]').click();

  // The submit handler calls showNodeInfo for the just-saved node, which re-renders the Inspector in
  // edit mode straight from the DOCUMENT's own updated model — reading back what was actually
  // persisted, not what happened to still be sitting in the form that submitted it.
  await page.waitForSelector('#node-editor');
  // readonly on the edit (not create) form, and set to the id just submitted — confirms this is the
  // just-saved node's own edit form, not still the create form.
  await expect(page.locator('#node-editor input[name="id"]')).toHaveValue('conditional-node-1');
  await expect(page.locator('#node-editor input[name="id"]')).toHaveAttribute('readonly', '');
  await page.locator('[data-catalog-property="mode"]').selectOption('WEBHOOK');
  await expect(page.locator('[data-catalog-property="callbackUrl"]')).toHaveValue('https://example.test/callback');
});

test('the reveal is reachable purely from the keyboard: mode changes by keyboard, Tab reaches the newly shown field', async ({ page }) => {
  await connectAndModify(page);
  await addExampleSourceNode(page);

  const mode = page.locator('[data-catalog-property="mode"]');
  await mode.focus();
  // Keyboard-only selection on a native <select>: type-ahead by first letter, matching how a screen
  // reader / keyboard-only user actually drives this control, not a synthetic value assignment.
  await mode.press('w');
  await expect(mode).toHaveValue('WEBHOOK');

  // The newly revealed row now begins with a real contextual-help button. It is intentionally in
  // the keyboard order, immediately before the field whose stable description it discloses.
  await page.keyboard.press('Tab');
  await expect(page.locator('[data-contextual-help-title="Callback URL"]')).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.locator('[data-catalog-property="callbackUrl"]')).toBeFocused();
});

test('an unrecognised condition contract hides the field even though the operator would otherwise match — never shown, fail-closed', async ({ page }) => {
  await connectAndModify(page);
  await page.locator('#node-catalog [data-catalog-add="example.malformed"]').click();

  // mode defaults to LONG_POLLING, which is exactly the value legacyHint's condition names — if the
  // unrecognised contract were ignored instead of refused, the field would be visible right now.
  const legacyHint = page.locator('[data-catalog-property="legacyHint"]');
  const wrapper = legacyHint.locator('xpath=ancestor::div[contains(@class,"catalog-property")]');
  await expect(wrapper).toBeHidden();
});

test('a cycle-shaped condition pair renders promptly without hanging or crashing the page', async ({ page }) => {
  await connectAndModify(page);

  const start = Date.now();
  await page.locator('#node-catalog [data-catalog-add="example.malformed"]').click();
  await page.waitForSelector('[data-catalog-property="cycleA"]', { timeout: 5_000 });
  expect(Date.now() - start).toBeLessThan(5_000);

  // Each defaults to the value that satisfies the OTHER's condition, so both are visible by
  // construction of the fixture — proving evaluation completed with an ordinary, correct answer
  // rather than merely not hanging.
  const cycleAWrapper = page.locator('[data-catalog-property="cycleA"]').locator('xpath=ancestor::div[contains(@class,"catalog-property")]');
  const cycleBWrapper = page.locator('[data-catalog-property="cycleB"]').locator('xpath=ancestor::div[contains(@class,"catalog-property")]');
  await expect(cycleAWrapper).toBeVisible();
  await expect(cycleBWrapper).toBeVisible();
});

// `deliveryGuarantee` is the fixture's own closed-choice, conditionally-required
// property (see its definition above). After `mode`
// changes to WEBHOOK, `refreshConditionalCatalogProperties` re-renders this same property from
// its current (still-blank) DOM value; "Not declared" must remain the FIRST, `''`,
// selected option so the browser's native `required` still refuses the save.
test('a conditionally required CLOSED CHOICE blocks submit until the author decides, and native validation ' +
  'is what refuses it — preserving the validator boundary', async ({ page }) => {
  await connectAndModify(page);
  await addExampleSourceNode(page);

  await page.locator('[data-catalog-property="mode"]').selectOption('WEBHOOK');
  await page.locator('[data-catalog-property="callbackUrl"]').fill('https://example.test/callback');

  const deliveryGuarantee = page.locator('[data-catalog-property="deliveryGuarantee"]');
  await expect(deliveryGuarantee).toBeVisible();
  await expect(deliveryGuarantee).toHaveAttribute('required', '');
  await expect(deliveryGuarantee).toHaveValue('');

  await page.locator('#node-editor input[name="id"]').fill('conditional-node-2');
  await page.locator('#node-editor button[type="submit"]').click();

  // Native constraint validation must refuse the submit before the JS submit handler ever runs — the
  // same discriminator adapter-binding-unconfigured.spec.js uses for an ordinary required-and-missing
  // property: the editor stays on the CREATE form (the id input is not yet readonly) and the control
  // itself reports invalid.
  await expect(page.locator('#node-editor input[name="id"]')).not.toHaveAttribute('readonly', '');
  await expect(deliveryGuarantee).toHaveJSProperty('validity.valid', false);
  await expect(deliveryGuarantee).toHaveJSProperty('validity.valueMissing', true);

  // Once the author actually decides, the same submit succeeds.
  await deliveryGuarantee.selectOption('EXACTLY_ONCE');
  await page.locator('#node-editor button[type="submit"]').click();

  await page.waitForSelector('#node-editor');
  await expect(page.locator('#node-editor input[name="id"]')).toHaveValue('conditional-node-2');
  await expect(page.locator('#node-editor input[name="id"]')).toHaveAttribute('readonly', '');
});

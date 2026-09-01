import { expect, test } from '@playwright/test';

// ── THE EDGE INSPECTOR PROPOSES OUTCOMES THE SOURCE NODE CAN PRODUCE ───────────────────────────
//
// `test/node-outcomes.test.js` proves the resolution and `test/edge-outcome-suggestions.test.js`
// proves the wiring against app.js's real source in a jsdom document. Neither can prove the last
// link: that a REAL BROWSER, handed the catalog JSON the server actually publishes, associates the
// Outcome field with the datalist and offers those values. A `<datalist>` is inert unless the input's
// `list` attribute resolves to it in a live document, and that resolution is exactly the kind of thing
// jsdom will happily let pass. `input.list` below is the browser's own answer, not the markup's.
//
// The catalog is stubbed on `**/v1/node-types`, matching `multi-selection-inspector.spec.js`, and its
// shape mirrors `RavenrootServer#nodeTypeJson`, including the `outcomes` array
// of `{ name, fromProperty, description }`. The two behaviors here are deliberately the real
// `cel-decision` shape and a fixed-outcome shape, because those are the two cases that differ.

const property = (name, type, options = {}) => ({
  name, displayName: options.displayName || name, type, required: false,
  description: options.description || '',
  defaultValue: Object.hasOwn(options, 'defaultValue') ? options.defaultValue : '',
  allowedValues: options.allowedValues || [], adapterBinding: false,
  visibleWhen: null, requiredWhen: null,
});

const CATALOG = [
  {
    behavior: 'outcome.decision',
    displayName: 'Decision',
    category: 'Tests',
    visualType: 'flow',
    agentic: false,
    capabilities: [],
    commands: [],
    properties: [
      property('trueOutcome', 'STRING', { displayName: 'True outcome', defaultValue: 'true' }),
      property('falseOutcome', 'STRING', { displayName: 'False outcome', defaultValue: 'false' }),
    ],
    outcomes: [
      { name: '', fromProperty: 'trueOutcome', description: 'The expression evaluated to true.' },
      { name: '', fromProperty: 'falseOutcome', description: 'The expression evaluated otherwise.' },
    ],
  },
  {
    behavior: 'outcome.fixed',
    displayName: 'Fixed',
    category: 'Tests',
    visualType: 'actor',
    agentic: false,
    capabilities: [],
    commands: [],
    properties: [],
    outcomes: [{ name: 'continue', fromProperty: '', description: 'Finished.' }],
  },
];

async function open(page) {
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json; charset=utf-8', body: JSON.stringify(CATALOG),
  }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
  await expect(page.locator('#node-catalog')).toContainText('Decision');
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
}

/** Gives `start` a behavior and the author's own outcome names, the way the node inspector would. */
async function configureSource(page, behavior, properties) {
  await page.evaluate(({ behavior: name, properties: values }) => {
    const document_ = window.ravenroot.activeDocument();
    const node = document_.graph.nodes.find(candidate => candidate.id === 'start');
    node.behavior = name;
    node.properties = values;
  }, { behavior, properties });
}

/** The option values the browser itself associates with the Outcome input, via its `list` property. */
function suggestedOutcomes(page) {
  return page.evaluate(() => {
    const input = document.querySelector('#edge-editor input[name="outcome"]');
    // `input.list` is the browser's own resolution of the `list` attribute. Reading the datalist by id
    // instead would pass even if the two were never associated, which is the failure this guards.
    return input.list ? [...input.list.options].map(option => option.value) : null;
  });
}

test('proposes the source node\'s own outcome names in the edge inspector', async ({ page }) => {
  await open(page);
  await configureSource(page, 'outcome.decision',
    { trueOutcome: 'approved', falseOutcome: 'rejected' });

  await page.locator('#btn-add-edge').click();
  await page.locator('#edge-editor select[name="source"]').selectOption('start');

  await expect.poll(() => suggestedOutcomes(page)).toEqual(['approved', 'rejected']);
  await expect(page.locator('#edge-outcome-hint')).toContainText('trueOutcome');
});

test('falls back to the declared defaults when the source configures neither property', async ({ page }) => {
  await open(page);
  await configureSource(page, 'outcome.decision', {});

  await page.locator('#btn-add-edge').click();
  await page.locator('#edge-editor select[name="source"]').selectOption('start');

  await expect.poll(() => suggestedOutcomes(page)).toEqual(['true', 'false']);
});

test('rebuilds the suggestions when the edge is pointed at a different source', async ({ page }) => {
  await open(page);
  await configureSource(page, 'outcome.decision', { trueOutcome: 'approved', falseOutcome: 'rejected' });
  await page.evaluate(() => {
    const document_ = window.ravenroot.activeDocument();
    const node = document_.graph.nodes.find(candidate => candidate.id === 'dosomething');
    node.behavior = 'outcome.fixed';
    node.properties = {};
  });

  await page.locator('#btn-add-edge').click();
  await page.locator('#edge-editor select[name="source"]').selectOption('start');
  await expect.poll(() => suggestedOutcomes(page)).toEqual(['approved', 'rejected']);

  // The outcome belongs to the source because the source is what produces it. Reading the target
  // instead is the plausible-looking mistake this asserts against.
  await page.locator('#edge-editor select[name="source"]').selectOption('dosomething');
  await expect.poll(() => suggestedOutcomes(page)).toEqual(['continue']);
});

test('leaves the Outcome field free text, so a value absent from the list still saves', async ({ page }) => {
  await open(page);
  await configureSource(page, 'outcome.decision', { trueOutcome: 'approved', falseOutcome: 'rejected' });

  await page.locator('#btn-add-edge').click();
  await page.locator('#edge-editor input[name="id"]').fill('edge-freetext');
  await page.locator('#edge-editor select[name="source"]').selectOption('start');
  await page.locator('#edge-editor select[name="target"]').selectOption('end');
  // Deliberately not one of the suggestions. The runner retries an unmatched outcome with 'continue',
  // and an outcome property can be edited after the edge is drawn, so "not suggested" must never mean
  // "not allowed" — a datalist that had become a closed choice would fail here.
  await page.locator('#edge-editor input[name="outcome"]').fill('escalated');
  const submit = page.locator('#edge-editor button[type="submit"]');
  await expect(submit).toBeEnabled();
  await submit.click();

  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().graph.edges
    .filter(edge => edge.id === 'edge-freetext').map(edge => edge.outcome))).toEqual(['escalated']);
});

import { expect, test } from '@playwright/test';

// ── THE EDGE INSPECTOR WARNS WHEN THE SOURCE CANNOT EMIT THE OUTCOME ───────────────────────────
//
// `test/node-outcomes.test.js` proves the predicate and `test/edge-outcome-unreachable.test.js`
// proves the wiring against app.js's real source in a jsdom document. Neither can prove the final
// browser link: that a REAL BROWSER shows the warning, hides it again, and — the part that matters
// most — never blocks the save. `hidden` on an element
// inside a flex container is exactly the kind of thing jsdom reports correctly and a browser does
// not, because an author `display` rule beats the user-agent's `[hidden]`. `isVisible()` below is the
// browser's own answer, not the attribute's.
//
// The catalog is stubbed on `**/v1/node-types`, the shape `edge-outcome-suggestions.spec.js`
// uses, plus one behavior that declares NO outcomes. Seven of the nine extension modules ship that
// no-outcomes shape, which must suppress the warning rather than imply that no outcome is reachable.

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
  {
    // An extension as they are shipped today: no `outcomes` key at all.
    behavior: 'outcome.silent',
    displayName: 'Undeclared',
    category: 'Tests',
    visualType: 'actor',
    agentic: false,
    capabilities: [],
    commands: [],
    properties: [],
    outcomes: [],
  },
];

const WARNING = '#edge-outcome-warning';

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

/** Gives a node a behavior and the author's own outcome names, the way the node inspector would. */
async function configureNode(page, nodeId, behavior, properties) {
  await page.evaluate(({ id, behavior: name, properties: values }) => {
    const document_ = window.ravenroot.activeDocument();
    const node = document_.graph.nodes.find(candidate => candidate.id === id);
    node.behavior = name;
    node.properties = values;
  }, { id: nodeId, behavior, properties });
}

async function openEdgeEditorFrom(page, sourceId) {
  await page.locator('#btn-add-edge').click();
  await page.locator('#edge-editor select[name="source"]').selectOption(sourceId);
}

const outcomeField = page => page.locator('#edge-editor input[name="outcome"]');

test('warns in the browser when the outcome is one the source cannot emit', async ({ page }) => {
  await open(page);
  await configureNode(page, 'start', 'outcome.decision',
    { trueOutcome: 'approved', falseOutcome: 'rejected' });
  await openEdgeEditorFrom(page, 'start');

  await outcomeField(page).fill('escalated');
  await expect(page.locator(WARNING)).toBeVisible();
  // The consequence, which is the part the author cannot read off the hint above it.
  await expect(page.locator(WARNING)).toContainText('never be taken');
});

test('hides the warning again as soon as the outcome becomes one the source emits', async ({ page }) => {
  await open(page);
  await configureNode(page, 'start', 'outcome.decision',
    { trueOutcome: 'approved', falseOutcome: 'rejected' });
  await openEdgeEditorFrom(page, 'start');

  await outcomeField(page).fill('escalated');
  await expect(page.locator(WARNING)).toBeVisible();

  await outcomeField(page).fill('approved');
  await expect(page.locator(WARNING)).toBeHidden();
});

/**
 * The `[hidden]` question a jsdom test cannot settle. `.editor-field` is `display: flex`, and an
 * author `display` on a child would beat the user-agent's `[hidden] { display: none }` — leaving an
 * empty amber box permanently on screen. This is the assertion that would have caught that.
 */
test('shows nothing at all before the author types an unreachable outcome', async ({ page }) => {
  await open(page);
  await configureNode(page, 'start', 'outcome.decision',
    { trueOutcome: 'approved', falseOutcome: 'rejected' });
  await openEdgeEditorFrom(page, 'start');

  await expect(page.locator(WARNING)).toBeHidden();
});

/**
 * The fallback. `GraphRunner` retries `nextEdges` with 'continue' when the produced outcome matched
 * nothing, so a 'continue' edge is reachable from outcomes no node declares. This source declares
 * neither 'continue' nor anything like it, which is what makes the case sharp.
 */
test("never warns on an edge wired to 'continue'", async ({ page }) => {
  await open(page);
  await configureNode(page, 'start', 'outcome.decision',
    { trueOutcome: 'approved', falseOutcome: 'rejected' });
  await openEdgeEditorFrom(page, 'start');

  await outcomeField(page).fill('continue');
  await expect(page.locator(WARNING)).toBeHidden();
});

/**
 * An extension that declares no outcomes has an unknown outcome set, not an empty one. Reading it as
 * 'emits nothing' would
 * put a warning on every edge leaving every mail, telegram, kafka and amqp node in every graph.
 */
test('says nothing about a source whose behavior declares no outcomes', async ({ page }) => {
  await open(page);
  await configureNode(page, 'start', 'outcome.silent', {});
  await openEdgeEditorFrom(page, 'start');

  await outcomeField(page).fill('delivered');
  await expect(page.locator(WARNING)).toBeHidden();
});

/**
 * Advisory, never a verdict — the acceptance constraint that a false positive costs more than
 * silence, taken to its conclusion. An author renaming a source node's `trueOutcome` after drawing
 * the edge passes through exactly this state on the way to a correct graph, so a blocked Save would
 * prevent the edit from being completed. The edge must still save with the value typed.
 */
test('warns without blocking the save, and the typed outcome is what lands', async ({ page }) => {
  await open(page);
  await configureNode(page, 'start', 'outcome.decision',
    { trueOutcome: 'approved', falseOutcome: 'rejected' });
  await openEdgeEditorFrom(page, 'start');

  await page.locator('#edge-editor input[name="id"]').fill('edge-unreachable');
  await page.locator('#edge-editor select[name="target"]').selectOption('end');
  await outcomeField(page).fill('escalated');
  await expect(page.locator(WARNING)).toBeVisible();

  const submit = page.locator('#edge-editor button[type="submit"]');
  await expect(submit).toBeEnabled();
  await submit.click();

  await expect.poll(() => page.evaluate(() => {
    const edge = window.ravenroot.activeDocument().graph.edges
      .find(candidate => candidate.id === 'edge-unreachable');
    return edge ? edge.outcome : null;
  })).toBe('escalated');
});

/**
 * While the edge is a declared failure route the Outcome field is readonly and parked at
 * the default: it carries the node's FAILURE, not one of its outcomes. A warning here would
 * contradict the panel directly above it, which has just said this edge selects on no outcome.
 */
test('says nothing while the edge is a declared failure route', async ({ page }) => {
  await open(page);
  await configureNode(page, 'start', 'outcome.decision',
    { trueOutcome: 'approved', falseOutcome: 'rejected' });
  await openEdgeEditorFrom(page, 'start');

  await outcomeField(page).fill('escalated');
  await expect(page.locator(WARNING)).toBeVisible();

  await page.locator('#edge-editor input[name="failureRoute"]').check();
  await expect(outcomeField(page)).toHaveAttribute('readonly', '');
  await expect(page.locator(WARNING)).toBeHidden();
});

/**
 * The live region must be WRITTEN once, not merely say the same thing repeatedly. `revalidateEdgeForm`
 * runs on every keystroke, and a polite region re-announces on every write — so without the
 * assign-only-if-changed guard in `setOutcomeWarning` this counts one mutation per character, and
 * whether the author hears one sentence or eight is left to the screen reader's deduplication.
 * Counted with a MutationObserver because the DOM is the only place the difference is visible: the
 * rendered text is identical either way, so no assertion on `textContent` can see this.
 */
test('does not rewrite the live region while the sentence is unchanged', async ({ page }) => {
  await open(page);
  await configureNode(page, 'start', 'outcome.decision',
    { trueOutcome: 'approved', falseOutcome: 'rejected' });
  await openEdgeEditorFrom(page, 'start');

  // Observe from the ALREADY-WARNING state. The transition into it legitimately costs two mutations
  // (the text write plus dropping `hidden`); what must cost nothing is every keystroke after it,
  // which is where the one-write-per-character defect lived.
  await outcomeField(page).fill('escalated');
  await expect(page.locator(WARNING)).toBeVisible();

  await page.evaluate(() => {
    window.__warningMutations = 0;
    const target = document.querySelector('#edge-outcome-warning');
    window.__warningObserver = new MutationObserver(records => {
      window.__warningMutations += records.length;
    });
    window.__warningObserver.observe(target, {
      childList: true, characterData: true, subtree: true, attributes: true,
    });
  });

  // Every one of these keystrokes leaves the outcome unreachable, so the sentence stays byte-identical
  // and the correct number of writes is zero. Without the guard this is six.
  await outcomeField(page).pressSequentially('-again', { delay: 10 });
  await expect(page.locator(WARNING)).toBeVisible();

  const mutations = await page.evaluate(() => {
    window.__warningObserver.disconnect();
    return window.__warningMutations;
  });
  expect(mutations).toBe(0);
});

/**
 * Amber, not red, and visibly a different object from `#edge-validation`, which is the one that gates
 * the save. Asserted as a COMPUTED colour because that is the property a reader actually sees; if the
 * two ever converge, an advisory reads as a refusal and refusals start being dismissed.
 */
test('is drawn as a warning and not as the save-blocking error', async ({ page }) => {
  await open(page);
  await configureNode(page, 'start', 'outcome.decision',
    { trueOutcome: 'approved', falseOutcome: 'rejected' });
  await openEdgeEditorFrom(page, 'start');
  await outcomeField(page).fill('escalated');
  await expect(page.locator(WARNING)).toBeVisible();

  const colours = await page.evaluate(() => {
    const read = selector => {
      const element = document.querySelector(selector);
      return element ? getComputedStyle(element).color : null;
    };
    return { warning: read('#edge-outcome-warning'), validation: read('#edge-validation') };
  });
  expect(colours.warning).not.toBeNull();
  expect(colours.warning).not.toBe(colours.validation);
});

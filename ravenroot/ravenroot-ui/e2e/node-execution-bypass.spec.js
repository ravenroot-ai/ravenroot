import { expect, test } from '@playwright/test';

// The editor half of `execution.bypass` has to prove two things no unit test can:
//
// 1. The INSPECTOR half. `/v1/catalog` (`/v1/node-types` on the wire — see runtime-client.js
// #nodeTypes) publishes `bypassProperty` per descriptor, beside `natureProperty`, and the editor
// DERIVES the key it writes from that field. A hardcoded `execution.bypass` passes every other
// test in this file and fails "the property name comes from the catalog" at the bottom, the only
// place that catalog-driven behavior is observable from a browser.
// 2. The CANVAS half. A graph with a bypassed node that looks normal is a trap. A `<canvas>` reaches
// no accessibility tree, so this is asserted the only way it can
// be — off the live renderer, in both themes, on all three carriers.
//
// The harness follows join-arrival-inspector.spec.js: hand-built GraphML fixtures through
// `replaceActiveDocumentFromText`, `window.cy` selection to open the Inspector, and a routed catalog
// stub shaped exactly like `RavenrootServer#nodeTypeJson`'s output. Note there is deliberately no
// `allowedBypassValues` in that shape — the two legal values are fixed by the platform for every node
// type, and a per-descriptor field would invite a consumer to believe a behavior could narrow them.

const catalogEntry = (behavior, bypassProperty = 'execution.bypass') => ({
  behavior, displayName: behavior, category: 'Bypass fixtures',
  description: `Fixture for ${behavior}.`, visualType: 'actor', agentic: false,
  capabilities: [], commands: [], properties: [], outcomes: [],
  defaultNature: 'WORKER', allowedNatures: ['WORKER'], natureProperty: 'runtime.nature',
  bypassProperty,
  // the twin platform-owned property, published exactly as `RavenrootServer#nodeTypeJson` does.
  // It is in this fixture because the Inspector's exclusion set is a UNION of both features' keys,
  // and a fixture that published only one of them could not tell a dropped half from a kept one.
  maxConcurrencyProperty: 'runtime.maxConcurrency',
  defaultMaxConcurrency: 1, maxConcurrencyCeiling: 8,
});

// `example.provisionable` is catalogued; `nobody.registered.this` deliberately is not — that is the
// case exists for (a node the deployment cannot provision) and the one place this control's
// rules differ from `runtime.nature`'s, which requires a catalogued behavior.
const CATALOG = [catalogEntry('example.provisionable')];

const HEADER = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
  '<key id="kkind" for="node" attr.name="kind" attr.type="string"/>',
  '<key id="kname" for="node" attr.name="name" attr.type="string"/>',
  '<key id="kbeh" for="node" attr.name="behavior" attr.type="string"/>',
  '<key id="kbyp" for="node" attr.name="execution.bypass" attr.type="string"/>',
  '<key id="kskip" for="node" attr.name="execution.skip" attr.type="string"/>',
  '<key id="kmax" for="node" attr.name="runtime.maxConcurrency" attr.type="string"/>',
  '<key id="koutcome" for="edge" attr.name="outcome" attr.type="string"/>',
  '<graph id="g" edgedefault="directed">',
].join('');

const FOOTER = '</graph></graphml>';

const behaviorNode = (id, {
  behavior = 'example.provisionable', bypass = null, skip = null, maxConcurrency = null,
} = {}) =>
  `<node id="${id}"><data key="kkind">BEHAVIOR</data><data key="kname">${id}</data>`
  + `<data key="kbeh">${behavior}</data>`
  + (bypass === null ? '' : `<data key="kbyp">${bypass}</data>`)
  + (skip === null ? '' : `<data key="kskip">${skip}</data>`)
  + (maxConcurrency === null ? '' : `<data key="kmax">${maxConcurrency}</data>`)
  + '</node>';

// One switched-off node, one ordinary one, and the terminals:
// `start -> a -> b -> c -> end` with `b` switched off.
const BASE_FIXTURE = HEADER
  + '<node id="start"><data key="kkind">START</data><data key="kname">start</data></node>'
  + behaviorNode('a')
  + behaviorNode('b', { bypass: 'true', maxConcurrency: '4' })
  + behaviorNode('c')
  + '<node id="end"><data key="kkind">END</data><data key="kname">end</data></node>'
  + '<node id="error"><data key="kkind">ERROR</data><data key="kname">error</data></node>'
  + '<edge id="e1" source="start" target="a"><data key="koutcome">continue</data></edge>'
  + '<edge id="e2" source="a" target="b"><data key="koutcome">continue</data></edge>'
  + '<edge id="e3" source="b" target="c"><data key="koutcome">continue</data></edge>'
  + '<edge id="e4" source="c" target="end"><data key="koutcome">continue</data></edge>'
  + FOOTER;

// A decision node with two named branches and one default. Switching it off makes the two named ones
// unreachable, which is the routing consequence the contract requires to be legible in the Inspector.
const DECISION_FIXTURE = HEADER
  + '<node id="start"><data key="kkind">START</data><data key="kname">start</data></node>'
  + behaviorNode('decide')
  + behaviorNode('plain')
  + '<node id="end"><data key="kkind">END</data><data key="kname">end</data></node>'
  + '<edge id="d0" source="start" target="decide"><data key="koutcome">continue</data></edge>'
  + '<edge id="d1" source="decide" target="plain"><data key="koutcome">approved</data></edge>'
  + '<edge id="d2" source="decide" target="end"><data key="koutcome">rejected</data></edge>'
  + '<edge id="d3" source="decide" target="end"><data key="koutcome">continue</data></edge>'
  + '<edge id="d4" source="plain" target="end"><data key="koutcome">continue</data></edge>'
  + FOOTER;

// A value that is neither `true` nor `false`. `NodeBypassValidator` refuses the WHOLE GRAPH for it
// rather than reading it as "not switched off", so the editor must not show a quiet unticked box.
const UNREADABLE_FIXTURE = HEADER
  + '<node id="start"><data key="kkind">START</data><data key="kname">start</data></node>'
  + behaviorNode('a', { bypass: 'yes' })
  + FOOTER;

// The key on a terminal, where the runtime refuses it even set to `false`.
const MISPLACED_FIXTURE = HEADER
  + '<node id="start"><data key="kkind">START</data><data key="kname">start</data></node>'
  + '<node id="end"><data key="kkind">END</data><data key="kname">end</data>'
  + '<data key="kbyp">false</data></node>'
  + FOOTER;

// An uncatalogued behavior — the motivating case — carrying the flag.
const UNCATALOGUED_FIXTURE = HEADER
  + '<node id="start"><data key="kkind">START</data><data key="kname">start</data></node>'
  + behaviorNode('unprovisionable', { behavior: 'nobody.registered.this', bypass: 'true' })
  + FOOTER;

// The catalog renamed the property. Everything must follow it.
const RENAMED_FIXTURE = HEADER
  + '<node id="start"><data key="kkind">START</data><data key="kname">start</data></node>'
  + behaviorNode('a', { skip: 'true' })
  + FOOTER;

async function open(page, catalog = CATALOG) {
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json; charset=utf-8', body: JSON.stringify(catalog),
  }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
  await expect(page.locator('#node-catalog')).toContainText(catalog[0].behavior);
}

async function loadFixture(page, xml) {
  await page.evaluate(text =>
    window.ravenroot.replaceActiveDocumentFromText(text, 'bypass-fixture.graphml'), xml);
  const button = page.locator('#btn-modify');
  if ((await button.getAttribute('aria-pressed')) !== 'true') await button.click();
  await expect(button).toHaveAttribute('aria-pressed', 'true');
}

async function selectNode(page, id) {
  await page.evaluate(nodeId => {
    window.cy.elements(':selected').unselect();
    window.cy.getElementById(nodeId).select();
  }, id);
  await page.waitForSelector('#node-editor');
}

/** What the canvas actually draws for one node — the three carriers, read off the live renderer. */
function canvasNode(page, id) {
  return page.evaluate(nodeId => {
    const node = window.cy.getElementById(nodeId);
    return {
      exists: node.nonempty(),
      label: node.data('label'),
      bypassed: node.data('bypassed'),
      borderStyle: node.style('border-style'),
      borderColor: node.style('border-color'),
    };
  }, id);
}

function nodeProperties(page, id) {
  return page.evaluate(nodeId => window.cy.getElementById(nodeId).data('properties'), id);
}

/** The DOCUMENT's own properties for a node, not the renderer's copy — what an autosave writes. */
function documentProperties(page, id) {
  return page.evaluate(nodeId =>
    structuredClone(window.ravenroot.activeDocument().graph.nodeMap[nodeId].properties), id);
}

const historyDepth = page => page.evaluate(() =>
  window.ravenroot.activeDocument().history.depth());

test.describe('the Inspector control', () => {
  test('reads a switched-off node from the document, and says so in text as well as by the tick', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);

    await selectNode(page, 'b');
    await expect(page.locator('#node-bypass-flag')).toBeChecked();
    await expect(page.locator('.node-bypass [data-bypass-state]'))
      .toHaveText('Switched off. This node does not execute.');

    await selectNode(page, 'a');
    await expect(page.locator('#node-bypass-flag')).not.toBeChecked();
    await expect(page.locator('.node-bypass [data-bypass-state]')).toHaveText('Executing normally.');
  });

  test('keeps dynamic consequence associated and moves stable guidance to accessible contextual help', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    await selectNode(page, 'a');

    const flag = page.locator('#node-bypass-flag');
    await expect(flag).toHaveAttribute('aria-describedby', /bypass-consequence/);
    const help = page.getByRole('button', { name: 'Help: Execution bypass' });
    await help.click();
    await expect(page.locator('#contextual-help-popover')).toContainText(/does not run its behaviour/i);
    await expect(page.locator('#contextual-help-popover')).toContainText(/continues past it/i);
    await expect(page.locator('#bypass-consequence')).toHaveAttribute('role', 'status');
    await expect(page.locator('#bypass-consequence')).toHaveAttribute('aria-live', 'polite');
  });

  test('flipping the switch updates the text state without a full re-render', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    await selectNode(page, 'a');

    const state = page.locator('.node-bypass [data-bypass-state]');
    await page.locator('#node-bypass-flag').check();
    await expect(state).toHaveText('Switched off. This node does not execute.');
    await page.locator('#node-bypass-flag').uncheck();
    await expect(state).toHaveText('Executing normally.');
    // Focus stays on the control across the update, the same way the nature control's live badge
    // update does — a re-render here would drop it.
    await expect(page.locator('#node-bypass-flag')).toBeFocused();
  });

  test('the flag round-trips through a save, under the name the catalog published', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    await selectNode(page, 'a');

    await page.locator('#node-bypass-flag').check();
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    await expect(page.locator('#node-bypass-flag')).toBeChecked();
    expect((await nodeProperties(page, 'a'))['execution.bypass']).toBe('true');
  });

  test('unticking and saving drops the property back to absent, not to a stored "false"', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    await selectNode(page, 'b');
    await expect(page.locator('#node-bypass-flag')).toBeChecked();

    await page.locator('#node-bypass-flag').uncheck();
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    await expect(page.locator('#node-bypass-flag')).not.toBeChecked();
    expect(Object.hasOwn(await nodeProperties(page, 'b'), 'execution.bypass')).toBe(false);
  });

  test('never leaks the key into the generic Additional properties editor as a duplicate control', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    await selectNode(page, 'b');

    // Two controls writing the key that decides whether a node runs, with no way for the author to
    // know which one wins, is the failure this exclusion exists to prevent.
    //
    // Node `b` declares BOTH platform-owned keys, and this asserts the count is zero rather than
    // "no row named execution.bypass", because the Inspector's exclusion set is a UNION of three
    // features' keys and dropping any one of them is invisible except as a row appearing here. That
    // is not hypothetical: merging with put both features' exclusion lists in one conflict
    // hunk, where taking either side whole would have silently readmitted the other's key.
    await expect(page.locator('#node-properties [data-property-name]')).toHaveCount(0);
    await expect(page.locator('#node-bypass-flag')).toBeChecked();
    // And each key still reaches its OWN dedicated control, so the exclusion removed a duplicate
    // rather than the property.
    await expect(page.locator('#node-max-concurrency-section')).toContainText(/./);
  });

  test('offers the switch on an UNCATALOGUED behavior — the case the flag exists for', async ({ page }) => {
    await open(page);
    await loadFixture(page, UNCATALOGUED_FIXTURE);
    await selectNode(page, 'unprovisionable');

    // Where this parts company with `runtime.nature`, which refuses an uncatalogued behavior: a
    // bypass subtracts execution rather than granting a privilege, and the motivating case is
    // precisely a node the deployment cannot provision.
    await expect(page.locator('#node-bypass-flag')).toBeVisible();
    await expect(page.locator('#node-bypass-flag')).toBeChecked();
    await expect(page.locator('#node-nature-select')).toHaveCount(0);
  });

  test('does not offer the switch on any node kind the runtime refuses the key on', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);

    // `NodeBypassValidator` refuses the KEY on these, for `false` as well as `true` — there is no
    // behaviour to skip. A disabled-but-present checkbox is not good enough: switching it back off
    // would itself be the refusal. It must not exist.
    for (const id of ['start', 'end', 'error']) {
      await selectNode(page, id);
      await expect(page.locator('#node-bypass-flag'), `node ${id}`).toHaveCount(0);
      await expect(page.locator('.bypass-refused'), `node ${id}`).toHaveCount(0);
    }
  });

  test('changing Kind away from BEHAVIOR withdraws the switch and drops the key on save', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    await selectNode(page, 'b');
    await expect(page.locator('#node-bypass-flag')).toBeChecked();

    await page.locator('#node-editor select[name="kind"]').selectOption('PASSTHROUGH');
    await expect(page.locator('#node-bypass-flag')).toHaveCount(0);

    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');
    // A checkbox surviving the switch would let a save write a graph the runtime refuses to load.
    expect(Object.hasOwn(await nodeProperties(page, 'b'), 'execution.bypass')).toBe(false);
  });
});

// Autosave defaults ON, so for most authors the Save button is never pressed and the
// flag reaches the document through `readNodeEditorPatch` — which is also what `inspectNodeDraft`
// diffs against the baseline to decide whether anything changed at all. That makes the placement of
// `readBypassEditor` load-bearing rather than stylistic: read it only at the submit site and the flag
// is missing from BOTH the baseline and the comparison, so `nodePatchChanged` answers "not changed"
// for a node whose only edit was switching it off, `commitNodeDraft` returns early, and nothing is
// written. Every other test in this file still passes in that state, because they all click Save.
// These do not.
test.describe('the flag reaches the document through autosave, not only through Save', () => {
  test('ticking the box writes the property with no Save pressed, and records one undo step', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    await expect(page.locator('#btn-autosave')).toHaveAttribute('aria-pressed', 'true');
    await selectNode(page, 'a');
    const before = await historyDepth(page);

    await page.locator('#node-bypass-flag').check();

    // A `change` on the form commits immediately rather than on the 180 ms debounce, but poll
    // anyway: what is asserted is that the write HAPPENS, not when.
    await expect.poll(async () => (await documentProperties(page, 'a'))['execution.bypass'])
      .toBe('true');
    expect(await historyDepth(page)).toBe(before + 1);
    // The canvas follows the autosaved document, through `syncAutosavedNodeRenderer`.
    expect((await canvasNode(page, 'a')).bypassed).toBe(true);
  });

  // On the autosave path, the label can update while the BORDER does not. `applyN8nNodeStyle` — which
  // runs for the default `cyto` visual style, not only for the n8n ones — must not write
  // `border-style` inline: an inline value beats the `node[?bypassed]` stylesheet rule and goes stale
  // because autosave updates node data without rerunning a visual-style pass. The default renderer
  // must distinguish a switched-off node from an executing one in both transition directions.
  test('the canvas ring follows an autosaved flip in BOTH directions, with no re-render', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);

    await selectNode(page, 'a');
    await page.locator('#node-bypass-flag').check();
    await expect.poll(async () => (await canvasNode(page, 'a')).borderStyle).toBe('dashed');
    expect((await canvasNode(page, 'a')).label).toContain('· bypassed');
    const off = await canvasNode(page, 'a');

    await page.locator('#node-bypass-flag').uncheck();
    await expect.poll(async () => (await canvasNode(page, 'a')).borderStyle).toBe('solid');
    const on = await canvasNode(page, 'a');
    expect(on.label).not.toContain('bypassed');
    // The colour carrier has to come back too, not just the dash — it is written inline by the
    // default style and is the half that needs `refreshBypassBorder` rather than the stylesheet.
    expect(on.borderColor).not.toBe(off.borderColor);
    expect(on.borderColor).toBe((await canvasNode(page, 'c')).borderColor);
  });

  test('unticking removes the property through the same path', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    await selectNode(page, 'b');
    await expect(page.locator('#node-bypass-flag')).toBeChecked();

    await page.locator('#node-bypass-flag').uncheck();

    await expect.poll(async () =>
      Object.hasOwn(await documentProperties(page, 'b'), 'execution.bypass')).toBe(false);
    expect((await canvasNode(page, 'b')).bypassed).toBe(false);
  });

  test('opening a switched-off node and touching nothing is not treated as an edit', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    const before = await historyDepth(page);

    // The other half of the baseline: the flag has to be in the captured baseline too, or every
    // switched-off node would read as dirty the moment it was opened and autosave would write a
    // no-op undo step over it.
    await selectNode(page, 'b');
    await selectNode(page, 'a');
    await selectNode(page, 'b');

    expect(await historyDepth(page)).toBe(before);
    expect((await documentProperties(page, 'b'))['execution.bypass']).toBe('true');
  });

  test('withdrawing the control by Kind removes the key without a Save, and in the right order', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    await selectNode(page, 'b');
    await expect(page.locator('#node-bypass-flag')).toBeChecked();

    // Two listeners see this one event: the Kind select's own, which re-renders the section and
    // removes the checkbox, and the form's, which autosaves. The DOM runs target-phase before
    // bubble-phase, so the section is already gone when the patch is read — which is why the
    // autosave writes a node with no key rather than one the runtime would refuse to load. If that
    // order ever inverted, this test is where it would show.
    await page.locator('#node-editor select[name="kind"]').selectOption('PASSTHROUGH');

    await expect(page.locator('#node-bypass-flag')).toHaveCount(0);
    await expect.poll(() =>
      page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.b.kind))
      .toBe('PASSTHROUGH');
    expect(Object.hasOwn(await documentProperties(page, 'b'), 'execution.bypass')).toBe(false);
  });
});

test.describe('the routing consequence, stated where the author is switching the node off', () => {
  test('names the branches a switched-off decision node stops taking, and only while it is off', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECISION_FIXTURE);
    await selectNode(page, 'decide');

    const consequence = page.locator('#bypass-consequence');
    await expect(consequence).toBeHidden();

    await page.locator('#node-bypass-flag').check();

    await expect(consequence).toBeVisible();
    // By NAME. "Some branches may not fire" would leave the author to go and count edges to find
    // out which, which is the thing this box exists to save them from.
    await expect(consequence).toContainText('“approved”');
    await expect(consequence).toContainText('“rejected”');
    await expect(consequence).toContainText(/continue branch/);
    await expect(consequence).toContainText(/join/);

    await page.locator('#node-bypass-flag').uncheck();
    await expect(consequence).toBeHidden();
  });

  test('says nothing extra for a node that loses no branch by being switched off', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECISION_FIXTURE);
    await selectNode(page, 'plain');

    // `plain`'s only outgoing edge is `continue`, so it routes identically switched off. Showing the
    // warning anyway would teach an author to skim the box on the node where it does matter.
    await page.locator('#node-bypass-flag').check();
    await expect(page.locator('#bypass-consequence')).toBeHidden();
  });
});

test.describe('documents the runtime would refuse are not shown as fine', () => {
  test('an unreadable value is reported, not quietly rendered as "not switched off"', async ({ page }) => {
    await open(page);
    await loadFixture(page, UNREADABLE_FIXTURE);
    await selectNode(page, 'a');

    const refusal = page.locator('.bypass-refused');
    await expect(refusal).toBeVisible();
    await expect(refusal).toContainText('execution.bypass');
    await expect(refusal).toContainText('yes');
    await expect(refusal).toContainText(/refuses to load/i);
    await expect(page.locator('#node-bypass-flag')).not.toBeChecked();
    // And the canvas draws it as executing, matching what the runtime would do with it.
    expect((await canvasNode(page, 'a')).bypassed).toBe(false);
  });

  test('the key on a terminal is reported rather than silently repaired on save', async ({ page }) => {
    await open(page);
    await loadFixture(page, MISPLACED_FIXTURE);
    await selectNode(page, 'end');

    const refusal = page.locator('.bypass-refused');
    await expect(refusal).toBeVisible();
    await expect(refusal).toContainText('execution.bypass');
    await expect(refusal).toContainText('END');
    // A silent repair of an invalid document is, to the author, indistinguishable from the document
    // having been fine — so the removal is announced before it happens.
    await expect(refusal).toContainText(/removes the property/i);
    await expect(page.locator('#node-bypass-flag')).toHaveCount(0);
  });
});

test.describe('the canvas affordance', () => {
  test('says a node is switched off with no Inspector open, on three carriers', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);

    const off = await canvasNode(page, 'b');
    const on = await canvasNode(page, 'a');
    expect(off.exists).toBe(true);
    expect(off.bypassed).toBe(true);
    // The text carrier is the one that matters most: a `<canvas>` reaches no accessibility tree, so
    // "not colour alone" here has to mean a mark a reader can actually read — one that survives a
    // screenshot in a bug report and a graph printed in greyscale.
    expect(off.label).toContain('· bypassed');
    expect(off.borderStyle).toBe('dashed');

    expect(on.bypassed).toBe(false);
    expect(on.label).not.toContain('bypassed');
    expect(on.borderStyle).toBe('solid');
    // The colour carrier alone would also be ambiguous: `nodeType="system"` is already grey.
    expect(off.borderColor).not.toBe(on.borderColor);
  });

  test('never marks a terminal that merely carries the key, which the runtime refuses anyway', async ({ page }) => {
    await open(page);
    await loadFixture(page, MISPLACED_FIXTURE);

    // Drawing it switched off would announce a behaviour it will never get to have.
    expect((await canvasNode(page, 'end')).bypassed).toBe(false);
  });

  test('holds in both themes, on shape and text, not on colour alone', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);

    for (const theme of ['dark', 'light']) {
      await page.evaluate(value => window.ravenroot.setApplicationTheme(value), theme);
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);

      const off = await canvasNode(page, 'b');
      const on = await canvasNode(page, 'a');
      expect(off.borderStyle, `switched-off node in ${theme}`).toBe('dashed');
      expect(off.label, `switched-off node in ${theme}`).toContain('· bypassed');
      expect(on.borderStyle, `ordinary node in ${theme}`).toBe('solid');
      expect(off.borderColor, `border colour in ${theme}`).not.toBe(on.borderColor);
    }
  });

  test('follows the flag when the author flips it, without reloading the document', async ({ page }) => {
    await open(page);
    await loadFixture(page, BASE_FIXTURE);
    await selectNode(page, 'a');
    expect((await canvasNode(page, 'a')).bypassed).toBe(false);

    await page.locator('#node-bypass-flag').check();
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    const drawn = await canvasNode(page, 'a');
    expect(drawn.bypassed).toBe(true);
    expect(drawn.label).toContain('· bypassed');
    expect(drawn.borderStyle).toBe('dashed');
  });
});

test('the property NAME comes from the catalog — a renamed one is read and written, not execution.bypass', async ({ page }) => {
  await open(page, [catalogEntry('example.provisionable', 'execution.skip')]);
  await loadFixture(page, RENAMED_FIXTURE);
  await selectNode(page, 'a');

  // Read under the published name.
  await expect(page.locator('#node-bypass-flag'))
    .toHaveAttribute('data-bypass-property', 'execution.skip');
  await expect(page.locator('#node-bypass-flag')).toBeChecked();
  expect((await canvasNode(page, 'a')).bypassed).toBe(true);
  // Excluded from the generic list under the published name too — otherwise a renamed key would come
  // back as a free-form row and give the author two controls over the same property.
  await expect(page.locator('#node-properties [data-property-name]')).toHaveCount(0);

  // And written back under it. A hardcoded editor passes every other test in this file and fails
  // here — it would write a key the runtime no longer reads, and the node would execute anyway.
  await page.locator('#node-editor button[type="submit"]').click();
  await page.waitForSelector('#node-editor');
  const properties = await nodeProperties(page, 'a');
  expect(properties['execution.skip']).toBe('true');
  expect(Object.hasOwn(properties, 'execution.bypass')).toBe(false);
});

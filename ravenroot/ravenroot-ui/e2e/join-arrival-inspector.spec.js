import { expect, test } from '@playwright/test';

// A node with several incoming edges is a synchronisation point only where the author declared
// one -- but until this wave nothing in the editor let them declare, see or migrate one. This
// spec drives the real Inspector DOM against small hand-built GraphML fixtures, same shape as
// node-runtime-nature.spec.js for the nature control: no catalog server is needed because every
// fixture node here is a plain PASSTHROUGH/ERROR/END, never a BEHAVIOR.

const LEGACY_FIXTURE = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
  '<key id="kkind" for="node" attr.name="kind" attr.type="string"/>',
  '<key id="kname" for="node" attr.name="name" attr.type="string"/>',
  '<graph id="g" edgedefault="directed">',
  '<node id="start"><data key="kkind">START</data><data key="kname">Start</data></node>',
  '<node id="a"><data key="kkind">PASSTHROUGH</data><data key="kname">a</data></node>',
  '<node id="b"><data key="kkind">PASSTHROUGH</data><data key="kname">b</data></node>',
  '<node id="merge"><data key="kkind">PASSTHROUGH</data><data key="kname">merge</data></node>',
  '<node id="end"><data key="kkind">END</data><data key="kname">End</data></node>',
  '<node id="error"><data key="kkind">ERROR</data><data key="kname">Error</data></node>',
  '<edge id="e-start-a" source="start" target="a"/>',
  '<edge id="e-start-b" source="start" target="b"/>',
  '<edge id="e-a-merge" source="a" target="merge"/>',
  '<edge id="e-b-merge" source="b" target="merge"/>',
  '<edge id="e-merge-end" source="merge" target="end"/>',
  '<edge id="e-a-error" source="a" target="error"/>',
  '<edge id="e-b-error" source="b" target="error"/>',
  '</graph></graphml>',
].join('');

// Same topology, but already declaring join.semantics=declared -- the reading the control and the
// END warning are actually about.
const DECLARED_FIXTURE = LEGACY_FIXTURE.replace(
  '<key id="kname"',
  '<key id="kjoin" for="graph" attr.name="join.semantics" attr.type="string"/><key id="kname"',
).replace('<graph id="g" edgedefault="directed">', '<graph id="g" edgedefault="directed"><data key="kjoin">declared</data>');

// `merge` declares `joinPolicy=ALL_OF`, which this control's four-option vocabulary does not define.
const UNRECOGNIZED_FIXTURE = DECLARED_FIXTURE
  .replace('<key id="kname"', '<key id="kjp" for="node" attr.name="joinPolicy" attr.type="string"/><key id="kname"')
  .replace('<node id="merge"><data key="kkind">PASSTHROUGH</data><data key="kname">merge</data></node>',
    '<node id="merge"><data key="kkind">PASSTHROUGH</data><data key="kjp">ALL_OF</data><data key="kname">merge</data></node>');

// `start` carries no canonical `kind` -- only the legacy boolean --
// which is what graph-parsers.js flags `_legacyKind` and, through that, what makes serializeGraphML
// treat the whole document as a state machine and stamp `joinPolicy=each` onto `merge` at save,
// regardless of the marker. No join.semantics marker here on purpose: this is the un-migrated shape
// the collision was measured against.
const STATE_MACHINE_FIXTURE = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
  '<key id="dstart" for="node" attr.name="start" attr.type="boolean"/>',
  '<key id="kkind" for="node" attr.name="kind" attr.type="string"/>',
  '<key id="kname" for="node" attr.name="name" attr.type="string"/>',
  '<graph id="g" edgedefault="directed">',
  '<node id="start"><data key="dstart">true</data><data key="kname">Start</data></node>',
  '<node id="a"><data key="kkind">PASSTHROUGH</data><data key="kname">a</data></node>',
  '<node id="b"><data key="kkind">PASSTHROUGH</data><data key="kname">b</data></node>',
  '<node id="merge"><data key="kkind">PASSTHROUGH</data><data key="kname">merge</data></node>',
  '<edge id="e-start-a" source="start" target="a"/>',
  '<edge id="e-start-b" source="start" target="b"/>',
  '<edge id="e-a-merge" source="a" target="merge"/>',
  '<edge id="e-b-merge" source="b" target="merge"/>',
  '</graph></graphml>',
].join('');

async function open(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
}

async function loadFixture(page, xml) {
  await page.evaluate(text => window.ravenroot.replaceActiveDocumentFromText(text, 'join-fixture.graphml'), xml);
  // Modify is an app-level toggle, not per-document -- a second fixture load in the same test must
  // not flip it back OFF just because it is already ON from the first.
  const button = page.locator('#btn-modify');
  if ((await button.getAttribute('aria-pressed')) !== 'true') await button.click();
  await expect(button).toHaveAttribute('aria-pressed', 'true');
}

async function selectNode(page, id) {
  await page.evaluate(nodeId => {
    window.cy.elements(':selected').unselect();
    window.cy.getElementById(nodeId).select();
  }, id);
  await page.waitForSelector('#node-join-kind, .node-join-fixed');
}

test.describe('Kind of arrival inspector control', () => {
  test('a node with fewer than two branches, and START, show a fixed non-applicable readout, not a reachable control', async ({ page }) => {
    await open(page);
    await loadFixture(page, LEGACY_FIXTURE);

    await selectNode(page, 'start');
    await expect(page.locator('#node-join-kind')).toHaveCount(0);
    await expect(page.locator('.node-join-fixed')).toContainText('Not a join · START receives externally');

    await selectNode(page, 'a');
    await expect(page.locator('#node-join-kind')).toHaveCount(0);
    await expect(page.locator('.node-join-fixed')).toContainText('Not a join · 1 incoming edge; 2 required');
  });

  test('an undeclared fan-in defaults to "No join" as the selection, but reports the true legacy-inferred effective behaviour', async ({ page }) => {
    await open(page);
    await loadFixture(page, LEGACY_FIXTURE);

    await selectNode(page, 'merge');
    await expect(page.locator('#node-join-kind')).toHaveValue('none');
    await expect(page.locator('[data-join-state]')).toHaveText(/Effective now: waits for all 2 branches/);
  });

  test('the same undeclared fan-in under join.semantics=declared reports no coordination in effect', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);

    await selectNode(page, 'merge');
    await expect(page.locator('#node-join-kind')).toHaveValue('none');
    await expect(page.locator('[data-join-state]'))
      .toHaveText(/Effective now: no join — each arrival runs independently/);
  });

  test('the ERROR terminal keeps its implicit quorum of one under the marker even with nothing declared', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);

    await selectNode(page, 'error');
    await expect(page.locator('[data-join-state]')).toHaveText(/first arrival wins/);
  });

  test('selecting "Wait for all branches" writes exactly joinPolicy=all to the document', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);
    await selectNode(page, 'merge');

    await page.locator('#node-join-kind').selectOption('all');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    const properties = await page.evaluate(() =>
      window.ravenroot.activeDocument().graph.nodeMap.merge.properties);
    expect(properties).toMatchObject({ joinPolicy: 'all' });
    expect(properties.joinQuorum).toBeUndefined();
    expect(properties.joinTimeout).toBeUndefined();
  });

  test('choosing K of N and a quorum writes exactly joinQuorum, never joinPolicy', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);
    await selectNode(page, 'merge');

    await page.locator('#node-join-kind').selectOption('quorum');
    await expect(page.locator('.join-quorum-field')).toBeVisible();
    await page.locator('#node-join-quorum').fill('2');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    const properties = await page.evaluate(() =>
      window.ravenroot.activeDocument().graph.nodeMap.merge.properties);
    expect(properties).toMatchObject({ joinQuorum: '2' });
    expect(properties.joinPolicy).toBeUndefined();
  });

  test('reverting a declared join back to "No join" and saving drops it back to absent -- writes nothing, not joinPolicy=each', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);
    await selectNode(page, 'merge');
    await page.locator('#node-join-kind').selectOption('first');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');
    await expect(page.locator('#node-join-kind')).toHaveValue('first');

    await page.locator('#node-join-kind').selectOption('none');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    const properties = await page.evaluate(() =>
      window.ravenroot.activeDocument().graph.nodeMap.merge.properties);
    expect(Object.hasOwn(properties, 'joinPolicy')).toBe(false);
    expect(Object.hasOwn(properties, 'joinQuorum')).toBe(false);
    expect(Object.hasOwn(properties, 'joinTimeout')).toBe(false);
  });

  test('a declared join never leaks into the generic Additional properties editor as a duplicate control', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);
    await selectNode(page, 'merge');
    await page.locator('#node-join-kind').selectOption('all');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    await expect(page.locator('#node-properties [data-property-name]')).toHaveCount(0);
  });

  test('the END terminal warns about the varying result payload only while its fan-in is undeclared, and the warning clears once a kind is chosen', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);

    // `end` is not itself a fan-in in this fixture (only `merge` feeds it) -- rewire it as one so the
    // warning's own precondition (an undeclared multi-predecessor END) is genuinely met.
    await page.evaluate(() => {
      const graph = window.ravenroot.activeDocument().graph;
      graph.edges.push({ id: 'e-a-end', source: 'a', target: 'end', outcome: 'continue' });
      window.cy.add({ group: 'edges', data: { id: 'e-a-end', source: 'a', target: 'end' } });
    });

    await selectNode(page, 'end');
    await expect(page.locator('.join-end-warning')).toBeVisible();
    await expect(page.locator('.join-end-warning')).toContainText(/varies between runs/);

    await page.locator('#node-join-kind').selectOption('all');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    await expect(page.locator('.join-end-warning')).toHaveCount(0);
  });

  // The control must PRESERVE a declaration it does not represent, not read it as "No join" and
  // erase it on the next unrelated save.
  test('a joinPolicy this control does not recognize is shown as such, not read as "No join" -- and survives an unrelated edit', async ({ page }) => {
    await open(page);
    await loadFixture(page, UNRECOGNIZED_FIXTURE);
    await selectNode(page, 'merge');

    const select = page.locator('#node-join-kind');
    await expect(select).toHaveValue('unrecognized');
    await expect(select.locator('option:checked')).toContainText('ALL_OF');

    // Renaming the node touches nothing about its join declaration -- `properties` is a whole
    // replace on submit (app.js's own comment on the node-editor submit handler), so this is exactly
    // the edit that used to turn an unrecognized declaration into `{}`.
    await page.locator('#node-editor input[name="name"]').fill('merge-renamed');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    const properties = await page.evaluate(() =>
      window.ravenroot.activeDocument().graph.nodeMap.merge.properties);
    expect(properties.joinPolicy).toBe('ALL_OF');
  });

  // `declaredJoinKind` deliberately folds an explicit legacy `joinPolicy=each`
  // into the same 'none' option an undeclared node shows -- correct for display, but submitting that
  // same 'none' used to write `{}` and erase the declaration on any unrelated edit.
  test('an explicit legacy joinPolicy=each is shown as "No join" and survives an unrelated edit through the same form', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);
    await page.evaluate(() => {
      window.ravenroot.activeDocument().graph.nodeMap.merge.properties.joinPolicy = 'each';
    });
    await selectNode(page, 'merge');
    await expect(page.locator('#node-join-kind')).toHaveValue('none');

    await page.locator('#node-editor input[name="name"]').fill('merge-renamed');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    const properties = await page.evaluate(() =>
      window.ravenroot.activeDocument().graph.nodeMap.merge.properties);
    expect(properties.joinPolicy).toBe('each');
  });

  // `each` must not be excluded from the warning. JoinSpec#defaultQuorum's Javadoc records the
  // measured behavior: WITHOUT a declared join an END fan-in's two branches
  // both land, 200/200; WITH `each`, exactly one lands and which one varies -- a different-shaped
  // version of the same nondeterministic hazard. The warning fires for `each` as it does for
  // `undeclared`.
  test('the END terminal warns when its fan-in is an explicit legacy joinPolicy=each', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);
    await page.evaluate(() => {
      const graph = window.ravenroot.activeDocument().graph;
      graph.edges.push({ id: 'e-a-end', source: 'a', target: 'end', outcome: 'continue' });
      window.cy.add({ group: 'edges', data: { id: 'e-a-end', source: 'a', target: 'end' } });
      graph.nodeMap.end.properties.joinPolicy = 'each';
    });

    await selectNode(page, 'end');
    await expect(page.locator('#node-join-kind')).toHaveValue('none');
    await expect(page.locator('.join-end-warning')).toBeVisible();
    await expect(page.locator('.join-end-warning')).toContainText(/varies between runs/);
  });

  // `state.kind === 'none'` is too broad: `declaredJoinKind` also resolves to 'none' for a bare
  // `joinTimeout` (no policy/quorum) -- a real declaration the editor preserves rather than erases, and one
  // the core accepts as a deterministic join (quorum defaults to N of N). The warning must not claim
  // the result varies between runs for a document the engine treats as fully coordinated.
  test('the END terminal does NOT warn on a bare joinTimeout with no declared policy -- the engine treats it as a deterministic join', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);
    await page.evaluate(() => {
      const graph = window.ravenroot.activeDocument().graph;
      graph.edges.push({ id: 'e-a-end', source: 'a', target: 'end', outcome: 'continue' });
      window.cy.add({ group: 'edges', data: { id: 'e-a-end', source: 'a', target: 'end' } });
      graph.nodeMap.end.properties.joinTimeout = 'PT30S';
    });

    await selectNode(page, 'end');
    await expect(page.locator('.join-end-warning')).toHaveCount(0);
  });

  // The other measured false positive is an unrecognized `joinPolicy`, which is also a real
  // declaration (the fifth dropdown option shown, selected)
  // -- and the core refuses to load it at all, which "varies between runs" does not describe either.
  test('the END terminal does NOT warn on an unrecognized joinPolicy -- it is a declaration this control cannot name, not an absent one', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);
    await page.evaluate(() => {
      const graph = window.ravenroot.activeDocument().graph;
      graph.edges.push({ id: 'e-a-end', source: 'a', target: 'end', outcome: 'continue' });
      window.cy.add({ group: 'edges', data: { id: 'e-a-end', source: 'a', target: 'end' } });
      graph.nodeMap.end.properties.joinPolicy = 'ALL_OF';
    });

    await selectNode(page, 'end');
    await expect(page.locator('#node-join-kind')).toHaveValue('unrecognized');
    await expect(page.locator('.join-end-warning')).toHaveCount(0);
  });

  // A bare joinTimeout with no policy/quorum, and an emptied joinQuorum next to a joinTimeout, both
  // read as kind 'none' and are indistinguishable from a genuinely undeclared node. Both must
  // survive an unrelated edit through this form.
  test('a bare joinTimeout with no declared policy survives an unrelated edit', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);
    await page.evaluate(() => {
      window.ravenroot.activeDocument().graph.nodeMap.merge.properties.joinTimeout = 'PT30S';
    });
    await selectNode(page, 'merge');
    await expect(page.locator('#node-join-kind')).toHaveValue('none');

    await page.locator('#node-editor input[name="name"]').fill('merge-renamed');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    const properties = await page.evaluate(() =>
      window.ravenroot.activeDocument().graph.nodeMap.merge.properties);
    expect(properties.joinTimeout).toBe('PT30S');
  });

  test('an emptied joinQuorum next to a joinTimeout survives an unrelated edit', async ({ page }) => {
    await open(page);
    await loadFixture(page, DECLARED_FIXTURE);
    await page.evaluate(() => {
      const properties = window.ravenroot.activeDocument().graph.nodeMap.merge.properties;
      properties.joinQuorum = '';
      properties.joinTimeout = 'PT30S';
    });
    await selectNode(page, 'merge');
    await expect(page.locator('#node-join-kind')).toHaveValue('none');

    await page.locator('#node-editor input[name="name"]').fill('merge-renamed');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    const properties = await page.evaluate(() =>
      window.ravenroot.activeDocument().graph.nodeMap.merge.properties);
    expect(properties.joinTimeout).toBe('PT30S');
  });

  // 'K of N' is the one Kind-of-arrival choice that writes joinQuorum with no joinPolicy, and a
  // legacy state-machine fan-in gets joinPolicy=each stamped onto it at save regardless -- a
  // combination the engine refuses to load. The control refuses to offer it here rather than let the
  // author produce a document that will not open.
  test('"K of N" is disabled on a legacy state-machine fan-in, with an explanation, and cannot be written even via a stale selection', async ({ page }) => {
    await open(page);
    await loadFixture(page, STATE_MACHINE_FIXTURE);
    await selectNode(page, 'merge');

    const quorumOption = page.locator('#node-join-kind option[value="quorum"]');
    await expect(quorumOption).toBeDisabled();
    await expect(page.locator('.join-quorum-collision-note')).toBeVisible();
    await expect(page.locator('.join-quorum-collision-note')).toContainText(/joinPolicy=each/);

    // Force the value through regardless of the disabled option, simulating a stale DOM -- the data
    // layer (readJoinEditor) must refuse this independently of the UI's own disabled attribute.
    await page.evaluate(() => {
      const select = document.getElementById('node-join-kind');
      select.value = 'quorum';
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.locator('#node-join-quorum').fill('2');
    await page.locator('#node-editor button[type="submit"]').click();
    await page.waitForSelector('#node-editor');

    const properties = await page.evaluate(() =>
      window.ravenroot.activeDocument().graph.nodeMap.merge.properties);
    expect(Object.hasOwn(properties, 'joinQuorum')).toBe(false);
    expect(Object.hasOwn(properties, 'joinPolicy')).toBe(false);
  });
});

test.describe('Migrate Join Semantics action', () => {
  async function openEditMenu(page) {
    await page.locator('#menu-edit').click();
    await expect(page.locator('#application-menu')).toBeVisible();
  }

  test('is offered on a legacy document and withdrawn once the document already declares the marker', async ({ page }) => {
    await open(page);
    await loadFixture(page, LEGACY_FIXTURE);
    await openEditMenu(page);
    await expect(page.locator('[data-command-id="edit.migrateJoinSemantics"]')).toHaveAttribute('aria-disabled', 'false');
    await page.keyboard.press('Escape');

    await loadFixture(page, DECLARED_FIXTURE);
    await openEditMenu(page);
    await expect(page.locator('[data-command-id="edit.migrateJoinSemantics"]')).toHaveAttribute('aria-disabled', 'true');
  });

  test('states the plan, then stamps the marker and materialises the inferred policy in one undoable step', async ({ page }) => {
    await open(page);
    await loadFixture(page, LEGACY_FIXTURE);

    page.once('dialog', dialog => {
      expect(dialog.message()).toContain('merge: joinPolicy=all');
      expect(dialog.message()).toContain('error: joinQuorum=1');
      dialog.accept();
    });
    await openEditMenu(page);
    await page.locator('[data-command-id="edit.migrateJoinSemantics"]').click();

    const state = await page.evaluate(() => {
      const document_ = window.ravenroot.activeDocument();
      return { graph: document_.graph, canUndo: document_.history.canUndo() };
    });
    expect(state.graph.graphProperties['join.semantics']).toBe('declared');
    expect(state.graph.nodeMap.merge.properties.joinPolicy).toBe('all');
    expect(state.graph.nodeMap.error.properties.joinQuorum).toBe('1');
    // One composite command, so migrating is one undo step -- not one step per touched node.
    expect(state.canUndo).toBe(true);

    await page.evaluate(() => window.ravenroot.activeDocument().history.undo(window.ravenroot.activeDocument().graph));
    const afterUndo = await page.evaluate(() => window.ravenroot.activeDocument().graph);
    expect(afterUndo.graphProperties['join.semantics']).toBeUndefined();
    expect(afterUndo.nodeMap.merge.properties.joinPolicy).toBeUndefined();
  });

  test('cancelling the confirmation leaves the document untouched', async ({ page }) => {
    await open(page);
    await loadFixture(page, LEGACY_FIXTURE);

    page.once('dialog', dialog => dialog.dismiss());
    await openEditMenu(page);
    await page.locator('[data-command-id="edit.migrateJoinSemantics"]').click();

    const graph = await page.evaluate(() => window.ravenroot.activeDocument().graph);
    expect(graph.graphProperties['join.semantics']).toBeUndefined();
    expect(graph.nodeMap.merge.properties.joinPolicy).toBeUndefined();
  });
});

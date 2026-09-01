import { expect, test } from '@playwright/test';

// Browser coverage for declaring a failure route from the edge inspector and for keeping a failure
// route visually distinct from an outcome edge named `failed`.
//
// The engine routes a node's failure down a failure edge, as pinned on the Java side by
// `GraphRunnerFailureRouteTest`. The editor must provide a way to declare that route and a way
// to SEE which edges were which: the inspector offered `Outcome` and `Command`, so an author
// connecting a node to `Error` wrote `outcome=failed` — and an exception never produces an outcome,
// so that edge never fired, while looking exactly like error handling.
//
// An edge that names no outcome into an `ERROR` node IS a failure route, and an explicit outcome overrides
// that. So there are three states to tell apart, and the inspector names each one.

async function startEditableWorkflow(page) {
  await page.goto('/');
  await enableModify(page);
}

// Modify mode belongs to the active document, so importing a file lands in a fresh one that is back
// in Inspect — where the edge inspector renders read-only and has no `#edge-editor` at all.
async function enableModify(page) {
  if (await page.locator('#btn-modify').getAttribute('aria-pressed') !== 'true') {
    await page.locator('#btn-modify').click();
  }
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
}

// The template document from "New" contains dosomething ──> error, shipped with `outcome=failed`;
// this fixture exercises that ambiguous authored shape.
async function openEdgeInspector(page, targetId) {
  await page.evaluate(target => {
    window.cy.edges().filter(candidate => candidate.data('target') === target)[0].emit('tap');
  }, targetId);
  await expect(page.locator('#edge-editor')).toBeVisible();
}

async function exportedGraphMl(page) {
  const download = await Promise.all([
    page.waitForEvent('download'),
    page.locator('#btn-export').click(),
  ]).then(([event]) => event);
  const stream = await download.createReadStream();
  return new Promise(resolve => {
    let text = '';
    stream.on('data', chunk => { text += chunk; });
    stream.on('end', () => resolve(text));
  });
}

function edgeState(page, targetId) {
  return page.evaluate(target => {
    const edge = window.cy.edges().filter(candidate => candidate.data('target') === target)[0];
    return {
      outcome: edge.data('outcome'),
      edgeType: edge.data('edgeType'),
      failureRouteKind: edge.data('failureRouteKind'),
      label: edge.data('label'),
      declared: edge.data('properties')['failure.route'],
      lineColor: edge.style('line-color'),
      lineStyle: edge.style('line-style'),
      dashPattern: String(edge.style('line-dash-pattern')),
      arrow: edge.style('target-arrow-shape'),
    };
  }, targetId);
}

// The third kind, `failure-declared`, is stated by the next test — it needs a target that is not an
// Error node, which this one does not have.
test('against an Error target the inspector states outcome edge or implicit failure route',
  async ({ page }) => {
  await startEditableWorkflow(page);
  await openEdgeInspector(page, 'error');

  const kind = page.locator('#edge-kind-state');
  const outcome = page.locator('#edge-editor input[name="outcome"]');

  // 1. An outcome edge that merely happens to be spelled `failed`. The template ships this, and it
  // looks like error handling but never fires on an unhandled error.
  await expect(outcome).toHaveValue('failed');
  await expect(kind).toHaveAttribute('data-edge-kind', 'outcome');
  await expect(kind).toContainText('Outcome edge');
  await expect(kind).toContainText('not failure');
  await expect(page.getByRole('button', { name: 'Help: Edge routing' }))
    .toHaveAttribute('data-contextual-help', /Outcome edges match a value returned by the source node/);

  // 2. Clear the outcome back to the default; the Error target then makes the route implicit.
  await outcome.fill('continue');
  await expect(kind).toHaveAttribute('data-edge-kind', 'failure-implicit');
  await expect(kind).toContainText('Error target; an unnamed outcome carries failure');

  // The checkbox is not offered here: against an ERROR target the Outcome field already governs
  // which of the two this edge is, and a second control for the same fact could only disagree.
  await expect(page.locator('#edge-editor [data-failure-route-control]')).toBeHidden();

  await page.locator('#edge-editor button[type="submit"]').click();
  await expect.poll(() => edgeState(page, 'error')).toMatchObject({
    outcome: 'continue', edgeType: 'failure', failureRouteKind: 'implicit', label: 'failure',
  });
});

test('a failure route is declarable where the default cannot reach, and survives the round trip',
  async ({ page }) => {
    await startEditableWorkflow(page);
    // A target that is NOT an Error node cannot acquire an implicit failure route, so the explicit
    // control remains necessary.
    await openEdgeInspector(page, 'end');

    const control = page.locator('#edge-editor input[name="failureRoute"]');
    const outcome = page.locator('#edge-editor input[name="outcome"]');
    const kind = page.locator('#edge-kind-state');

    await expect(page.locator('#edge-editor [data-failure-route-control]')).toBeVisible();
    await expect(kind).toHaveAttribute('data-edge-kind', 'outcome');

    await outcome.fill('reviewed');
    await control.check();

    // The interface makes the forbidden combination unreachable rather than reporting it afterwards:
    // an edge is a failure route or an outcome edge, never both, and the engine refuses a graph
    // claiming both AT LOAD. The outcome goes back to the default and is held there.
    await expect(outcome).toHaveValue('continue');
    await expect(outcome).toHaveAttribute('readonly', '');
    await expect(kind).toHaveAttribute('data-edge-kind', 'failure-declared');

    // Reversible without losing the keystroke that was parked.
    await control.uncheck();
    await expect(outcome).toHaveValue('reviewed');
    await expect(outcome).not.toHaveAttribute('readonly', '');

    await control.check();
    await page.locator('#edge-editor button[type="submit"]').click();
    await expect.poll(() => edgeState(page, 'end')).toMatchObject({
      outcome: 'continue', edgeType: 'failure', failureRouteKind: 'declared', declared: 'true',
    });

    // The value reaches the document, and survives export and reimport.
    const xml = await exportedGraphMl(page);
    expect(xml).toContain('attr.name="failure.route"');

    await page.locator('#file-inp').setInputFiles({
      name: 'failure-route.graphml', mimeType: 'application/graphml+xml', buffer: Buffer.from(xml),
    });
    await expect.poll(() => edgeState(page, 'end')).toMatchObject({
      outcome: 'continue', edgeType: 'failure', failureRouteKind: 'declared', declared: 'true',
    });
  });

test('clearing the declaration hands the outcome back and removes the property', async ({ page }) => {
  await startEditableWorkflow(page);
  await openEdgeInspector(page, 'end');

  await page.locator('#edge-editor input[name="failureRoute"]').check();
  await page.locator('#edge-editor button[type="submit"]').click();
  await expect.poll(() => edgeState(page, 'end')).toMatchObject({ declared: 'true' });

  await openEdgeInspector(page, 'end');
  await expect(page.locator('#edge-editor input[name="failureRoute"]')).toBeChecked();
  await page.locator('#edge-editor input[name="failureRoute"]').uncheck();
  await page.locator('#edge-editor button[type="submit"]').click();

  await expect.poll(() => edgeState(page, 'end'))
    .toMatchObject({ declared: undefined, edgeType: 'continue', failureRouteKind: '' });
  // Cleared means gone from the document, not written as `false`: only the exact string `true`
  // declares anything, so a leftover would be a property that says nothing while occupying the file.
  const xml = await exportedGraphMl(page);
  expect(xml).not.toMatch(/<data key="[^"]*failure[^"]*">/);
});

test('a failure route and an outcome edge named failed are not drawn the same way',
  async ({ page }) => {
    await startEditableWorkflow(page);

    // The template's `failed` edge, untouched: the impostor. Captured first, because the comparison
    // asks for is between these two renderings and nothing else.
    const impostor = await edgeState(page, 'error');
    expect(impostor.edgeType).toBe('failed');

    // Clearing the same edge's outcome makes it an implicit failure route through its Error target.
    await openEdgeInspector(page, 'error');
    await page.locator('#edge-editor input[name="outcome"]').fill('continue');
    await page.locator('#edge-editor button[type="submit"]').click();
    await expect.poll(() => edgeState(page, 'error')).toMatchObject({ edgeType: 'failure' });
    const route = await edgeState(page, 'error');

    // Three carriers differ because the distinction must not rest on
    // colour alone. `failed` is itself dashed, so `line-style` could not have carried it either —
    // the dash PATTERN and the arrowhead are what separate them alongside the hue.
    expect(route.lineColor).not.toBe(impostor.lineColor);
    expect(route.dashPattern).not.toBe(impostor.dashPattern);
    expect(route.arrow).not.toBe(impostor.arrow);
    expect(route.arrow).toBe('triangle-tee');
    // And the words on the edge differ too, which survives being read rather than looked at.
    expect(route.label).toBe('failure');
    expect(impostor.label).toBe('failed');

    // The legend names both, and puts them side by side: two names that have to be told apart are
    // told apart most cheaply by being read next to each other.
    await expect(page.locator('#li-edge-failure')).toHaveAttribute('aria-label', /FAILURE ROUTE/);
    await expect(page.locator('#li-edge-failed')).toHaveAttribute('aria-label', /FAILED/);
    expect(await page.evaluate(() =>
      document.getElementById('li-edge-failed').nextElementSibling?.id)).toBe('li-edge-failure');
  });

test('overriding an Error target with an outcome drops the declaration instead of contradicting it',
  async ({ page }) => {
    await startEditableWorkflow(page);

    // The awkward case, and it has to be IMPORTED rather than drawn: an edge that both targets an
    // Error node and carries an explicit `failure.route`. The declaration is redundant but legal
    // until an outcome is named. The editor never writes this shape because retargeting onto an
    // Error node drops the redundant declaration, but imported or hand-written documents may carry it.
    await page.locator('#file-inp').setInputFiles({
      name: 'declared-and-implicit.graphml',
      mimeType: 'application/graphml+xml',
      buffer: Buffer.from(`<?xml version="1.0" encoding="UTF-8"?>
        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
          <key id="kind" for="node" attr.name="kind" attr.type="string"/>
          <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
          <key id="fr" for="edge" attr.name="failure.route" attr.type="string"/>
          <graph id="g" edgedefault="directed">
            <node id="start"><data key="kind">START</data></node>
            <node id="dosomething"><data key="kind">PASSTHROUGH</data></node>
            <node id="error"><data key="kind">ERROR</data></node>
            <node id="end"><data key="kind">END</data></node>
            <edge id="e1" source="start" target="dosomething"><data key="outcome">continue</data></edge>
            <edge id="e2" source="dosomething" target="end"><data key="outcome">continue</data></edge>
            <edge id="e3" source="dosomething" target="error"><data key="fr">true</data></edge>
          </graph>
        </graphml>`),
    });
    await expect.poll(() => edgeState(page, 'error'))
      .toMatchObject({ declared: 'true', failureRouteKind: 'declared' });
    await enableModify(page);

    // Now override it. Carrying the declaration past the override would hand the engine
    // `failure.route=true` together with an explicit outcome — refused AT LOAD, which would move
    // the error from the drawing to the run.
    await openEdgeInspector(page, 'error');
    await page.locator('#edge-editor input[name="outcome"]').fill('reviewed');
    await expect(page.locator('#edge-kind-state')).toHaveAttribute('data-edge-kind', 'outcome');
    await page.locator('#edge-editor button[type="submit"]').click();

    await expect.poll(() => edgeState(page, 'error'))
      .toMatchObject({ outcome: 'reviewed', declared: undefined, failureRouteKind: '' });
    const xml = await exportedGraphMl(page);
    expect(xml).not.toMatch(/<data key="[^"]*failure[^"]*">/);
  });

test('retargeting a declared route onto an Error node keeps it a failure route',
  async ({ page }) => {
    await startEditableWorkflow(page);
    await openEdgeInspector(page, 'end');

    const outcome = page.locator('#edge-editor input[name="outcome"]');
    await outcome.fill('reviewed');
    await page.locator('#edge-editor input[name="failureRoute"]').check();
    await expect(outcome).toHaveValue('continue');

    // The checkbox has no meaning against an ERROR target, so it is cleared — but the author asked
    // for a failure route and still has one, implicitly. Handing back the outcome parked a moment
    // ago would turn it into an outcome edge named `reviewed`, which is what they last did NOT ask
    // for.
    await page.locator('#edge-editor select[name="target"]').selectOption('error');
    await expect(page.locator('#edge-editor [data-failure-route-control]')).toBeHidden();
    await expect(outcome).toHaveValue('continue');
    await expect(page.locator('#edge-kind-state'))
      .toHaveAttribute('data-edge-kind', 'failure-implicit');

    await page.locator('#edge-editor button[type="submit"]').click();
    // Saved as an IMPLICIT route with no property written: the declaration would say exactly what
    // the target already says, and a document should not carry both statements of one fact.
    await expect.poll(() => page.evaluate(() => {
      const edge = window.cy.getElementById('edge-dosomething-end');
      return { kind: edge.data('failureRouteKind'), declared: edge.data('properties')['failure.route'] };
    })).toEqual({ kind: 'implicit', declared: undefined });
  });

// Outcome suggestions and failure-route declaration meet on the same field, so the combined state
// needs its own test: suggestion coverage never declares a failure route, while the route tests do
// not otherwise exercise suggestions.
test('a declared failure route withdraws the outcome suggestions instead of advertising them',
  async ({ page }) => {
    await startEditableWorkflow(page);
    await openEdgeInspector(page, 'end');

    const hint = page.locator('#edge-outcome-hint');
    const control = page.locator('#edge-editor input[name="failureRoute"]');

    // Whatever the catalog says about the source, the hint is speaking about a usable field here.
    const before = await hint.textContent();
    expect(before?.trim()).not.toBe('');

    await control.check();
    // The field is now readonly and parked at the default, so suggestions would falsely imply that
    // this failure-route edge selects on the source's outcomes.
    await expect(page.locator('#edge-editor input[name="outcome"]')).toHaveAttribute('readonly', '');
    await expect(hint).toContainText('not one of its outcomes');
    expect(await page.evaluate(() =>
      document.getElementById('edge-outcome-options').children.length)).toBe(0);

    // And they come back when the declaration does, rather than staying withdrawn for the session.
    await control.uncheck();
    await expect(hint).not.toContainText('not one of its outcomes');
    expect(await hint.textContent()).toBe(before);
  });

test('the classification follows the target, not the moment the document was parsed',
  async ({ page }) => {
    await startEditableWorkflow(page);
    // dosomething ──> end, which the template ships at the default outcome, retargeted onto the
    // Error node. NOTHING about the edge itself changes — not its outcome, not its properties, only
    // where it lands. What it MEANS changes, and the canvas has to say so without a reload.
    await openEdgeInspector(page, 'end');
    await expect(page.locator('#edge-editor input[name="outcome"]')).toHaveValue('continue');
    await page.locator('#edge-editor select[name="target"]').selectOption('error');
    await expect(page.locator('#edge-kind-state')).toHaveAttribute('data-edge-kind', 'failure-implicit');

    await page.locator('#edge-editor button[type="submit"]').click();
    await expect.poll(() => page.evaluate(() => {
      const edge = window.cy.getElementById('edge-dosomething-end');
      return { edgeType: edge.data('edgeType'), kind: edge.data('failureRouteKind') };
    })).toEqual({ edgeType: 'failure', kind: 'implicit' });
  });

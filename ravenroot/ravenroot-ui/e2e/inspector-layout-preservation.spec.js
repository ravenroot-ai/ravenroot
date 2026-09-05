import { expect, test } from '@playwright/test';

function graphMl() {
  const nodes = Array.from({ length: 12 }, (_, index) => {
    const kind = index === 0 ? 'START' : index === 11 ? 'END' : 'PASSTHROUGH';
    return `<node id="n${index}"><data key="name">Node ${index}</data><data key="kind">${kind}</data>`
      + `<data key="x">${100 + (index % 4) * 170}</data>`
      + `<data key="y">${100 + Math.floor(index / 4) * 140}</data>`
      + '<data key="w">96</data><data key="h">56</data></node>';
  }).join('');
  const edges = Array.from({ length: 11 }, (_, index) =>
    `<edge id="edge-${index}" source="n${index}" target="n${index + 1}">`
    + `<data key="outcome">step-${index}</data></edge>`).join('');
  return `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
    <key id="name" for="node" attr.name="name" attr.type="string"/>
    <key id="kind" for="node" attr.name="kind" attr.type="string"/>
    <key id="x" for="node" attr.name="layoutX" attr.type="double"/>
    <key id="y" for="node" attr.name="layoutY" attr.type="double"/>
    <key id="w" for="node" attr.name="layoutWidth" attr.type="double"/>
    <key id="h" for="node" attr.name="layoutHeight" attr.type="double"/>
    <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
    <graph id="inspector-layout" edgedefault="directed">${nodes}${edges}</graph></graphml>`;
}

async function replaceGraph(page, name = 'inspector-layout.graphml') {
  await page.evaluate(({ xml, filename }) =>
    window.ravenroot.replaceActiveDocumentFromText(xml, filename), { xml: graphMl(), filename: name });
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
}

async function arrange(page, label) {
  await page.locator('#menu-layout').click();
  await page.getByRole('menuitem', { name: label, exact: true }).click();
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', {
    timeout: 15_000,
  });
}

async function startEditing(page, { autosave = true } = {}) {
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
  if (!autosave) {
    await page.locator('#btn-autosave').click();
    await expect(page.locator('#btn-autosave')).toHaveAttribute('aria-pressed', 'false');
  }
}

async function selectOnly(page, id) {
  await page.evaluate(elementId => {
    window.cy.$(':selected').unselect();
    window.cy.getElementById(elementId).select();
  }, id);
}

const positions = page => page.evaluate(() => Object.fromEntries(
  window.cy.nodes().map(node => [node.id(), node.position()]),
));

const routeGeometry = page => page.evaluate(() => Object.fromEntries(window.cy.edges().map(edge => [edge.id(), {
  curveStyle: edge.style('curve-style'),
  controlPointWeights: edge.style('control-point-weights'),
  controlPointDistances: edge.style('control-point-distances'),
  segmentWeights: edge.style('segment-weights'),
  segmentDistances: edge.style('segment-distances'),
  sourceEndpoint: edge.style('source-endpoint'),
  targetEndpoint: edge.style('target-endpoint'),
}])));

const presentation = page => page.evaluate(() => {
  const owner = window.ravenroot.activeDocument();
  return {
    renderMode: owner.renderMode,
    layoutMode: owner.layoutMode,
    visualStyle: owner.visualStyle,
    positions: Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()])),
    viewport: { zoom: owner.cy.zoom(), pan: owner.cy.pan() },
    selection: owner.cy.$(':selected').map(element => element.id()).sort(),
    cursor: owner.cy.nodes('.graph-cursor').map(node => node.id()).sort(),
  };
});

async function markPresentation(page) {
  await page.evaluate(() => { window.__inspectorLayoutRenderer = window.cy; });
  return presentation(page);
}

const rendererWasRetained = page => page.evaluate(() => window.cy === window.__inspectorLayoutRenderer);

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await replaceGraph(page);
});

test('Save node with Autosave OFF retains a Hierarchical canvas through undo and redo', async ({ page }) => {
  await arrange(page, 'Arrange — Hierarchical');
  await startEditing(page, { autosave: false });
  await selectOnly(page, 'n5');
  await page.evaluate(() => {
    window.cy.zoom(0.73);
    window.cy.pan({ x: 137, y: 83 });
  });
  const before = await markPresentation(page);
  const routesBefore = await routeGeometry(page);

  await page.locator('#node-editor textarea[name="description"]').fill('Manually saved in place');
  await page.getByRole('button', { name: 'Save node' }).click();

  expect(await presentation(page)).toEqual(before);
  expect(await rendererWasRetained(page)).toBe(true);
  expect(await routeGeometry(page)).toEqual(routesBefore);
  expect(await page.evaluate(() =>
    window.ravenroot.activeDocument().graph.nodeMap.n5.description)).toBe('Manually saved in place');

  await page.locator('#btn-undo').click();
  expect(await presentation(page)).toEqual(before);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.n5.description)).toBe('');
  await page.locator('#btn-redo').click();
  expect(await presentation(page)).toEqual(before);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.n5.description))
    .toBe('Manually saved in place');
});

test('Save edge with Autosave OFF refreshes appearance and retains compatible routed geometry', async ({ page }) => {
  await arrange(page, 'Arrange — Hierarchical');
  await startEditing(page, { autosave: false });
  await selectOnly(page, 'edge-5');
  await page.evaluate(() => {
    window.cy.zoom(0.78);
    window.cy.pan({ x: 119, y: 91 });
  });
  const before = await markPresentation(page);
  const routesBefore = await routeGeometry(page);
  const lineBefore = await page.evaluate(() => window.cy.getElementById('edge-5').style('line-style'));

  await page.locator('#edge-editor input[name="outcome"]').fill('failed');
  await page.locator('#edge-editor input[name="parallel"]').check();
  await page.locator('#edge-editor textarea[name="description"]').fill('Retains its routed path');
  await page.getByRole('button', { name: 'Save edge' }).click();

  expect(await presentation(page)).toEqual(before);
  expect(await rendererWasRetained(page)).toBe(true);
  expect(await routeGeometry(page)).toEqual(routesBefore);
  expect(await page.evaluate(() => {
    const edge = window.cy.getElementById('edge-5');
    const model = window.ravenroot.activeDocument().graph.edges.find(item => item.id === 'edge-5');
    return {
      outcome: model.outcome, parallel: model.parallel, description: model.description,
      edgeType: edge.data('edgeType'), lineStyle: edge.style('line-style'),
    };
  })).toEqual({
    outcome: 'failed', parallel: true, description: 'Retains its routed path',
    edgeType: 'failed', lineStyle: 'dashed',
  });
  expect(lineBefore).not.toBe('dashed');
  await page.screenshot({ path: '/tmp/ravenroot-222-inspector-layout.png', fullPage: true });
});

test('edge autosave retains Hierarchical geometry while refreshing outcome appearance', async ({ page }) => {
  await arrange(page, 'Arrange — Hierarchical');
  await startEditing(page);
  await selectOnly(page, 'edge-5');
  const before = await markPresentation(page);
  const routesBefore = await routeGeometry(page);

  await page.locator('#edge-editor input[name="outcome"]').fill('failed');
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.activeDocument().graph.edges.find(edge => edge.id === 'edge-5').outcome)).toBe('failed');

  expect(await rendererWasRetained(page)).toBe(true);
  expect(await presentation(page)).toEqual(before);
  expect(await routeGeometry(page)).toEqual(routesBefore);
  expect(await page.evaluate(() => ({
    edgeType: window.cy.getElementById('edge-5').data('edgeType'),
    lineStyle: window.cy.getElementById('edge-5').style('line-style'),
  }))).toEqual({ edgeType: 'failed', lineStyle: 'dashed' });
});

for (const [label, expectedLayout] of [
  ['Arrange — Hierarchical', 'hierarchical'],
  ['Arrange — Flow (new)', 'flow-new'],
]) {
  test(`endpoint autosave reroutes inside retained ${expectedLayout} arrangement`, async ({ page }) => {
    await arrange(page, label);
    await startEditing(page);
    await selectOnly(page, 'edge-5');
    const before = await markPresentation(page);
    const edgeBefore = (await routeGeometry(page))['edge-5'];

    await page.locator('#edge-editor select[name="target"]').selectOption('n9');
    await expect.poll(() => page.evaluate(() => {
      const edge = window.ravenroot.activeDocument().graph.edges.find(item => item.id === 'edge-5');
      return { target: edge.target, renderedTarget: window.cy.getElementById('edge-5').target().id() };
    })).toEqual({ target: 'n9', renderedTarget: 'n9' });

    expect(await rendererWasRetained(page)).toBe(true);
    expect(await presentation(page)).toEqual(before);
    expect((await routeGeometry(page))['edge-5']).not.toEqual(edgeBefore);
  });
}

for (const label of [
  'Arrange — Flow', 'Arrange — Organic',
  'Arrange — Hierarchical (new)', 'Arrange — Flow (new)',
]) {
  test(`node autosave retains ${label.replace('Arrange — ', '')}`, async ({ page }) => {
    await arrange(page, label);
    await startEditing(page);
    await selectOnly(page, 'n5');
    await page.evaluate(() => {
      window.cy.zoom(0.81);
      window.cy.pan({ x: 101, y: 67 });
    });
    const before = await markPresentation(page);
    const routesBefore = await routeGeometry(page);

    await page.locator('#node-editor textarea[name="description"]').fill(`Autosaved after ${label}`);
    await expect.poll(() => page.evaluate(() =>
      window.ravenroot.activeDocument().graph.nodeMap.n5.description)).toBe(`Autosaved after ${label}`);

    expect(await rendererWasRetained(page)).toBe(true);
    expect(await presentation(page)).toEqual(before);
    expect(await routeGeometry(page)).toEqual(routesBefore);
  });
}

test('Keep positions retains its input canvas through node autosave', async ({ page }) => {
  await arrange(page, 'Arrange — Hierarchical');
  await arrange(page, 'Keep positions');
  await startEditing(page);
  await selectOnly(page, 'n5');
  const before = await markPresentation(page);
  const routesBefore = await routeGeometry(page);

  await page.locator('#node-editor textarea[name="description"]').fill('Autosaved after Keep positions');
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.activeDocument().graph.nodeMap.n5.description)).toBe('Autosaved after Keep positions');

  expect(await rendererWasRetained(page)).toBe(true);
  expect(await presentation(page)).toEqual(before);
  expect(await routeGeometry(page)).toEqual(routesBefore);
});

test('document activation retains hierarchical and layered routes after independent Inspector autosaves', async ({ page }) => {
  const first = await page.evaluate(() => window.ravenroot.workspace.activeId);
  await arrange(page, 'Arrange — Hierarchical');
  await startEditing(page);
  await selectOnly(page, 'n3');
  await page.locator('#node-editor textarea[name="description"]').fill('First document autosave');
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.activeDocument().graph.nodeMap.n3.description)).toBe('First document autosave');
  const firstRoutes = await routeGeometry(page);

  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'second.graphml' }));
  await replaceGraph(page, 'second.graphml');
  await arrange(page, 'Arrange — Flow (new)');
  const secondPositions = await positions(page);

  // Establish both view snapshots after the split exists. Adding a pane legitimately resizes and
  // reframes the original one; activation after that point must preserve each settled view exactly.
  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  await selectOnly(page, 'n3');
  await page.evaluate(() => { window.cy.zoom(0.71); window.cy.pan({ x: 131, y: 79 }); });
  const firstPresentation = await presentation(page);
  expect(firstPresentation.selection).toEqual(['n3']);
  expect(await routeGeometry(page)).toEqual(firstRoutes);
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);
  await startEditing(page);
  await selectOnly(page, 'n4');
  await page.locator('#node-editor textarea[name="description"]').fill('Second document autosave');
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.activeDocument().graph.nodeMap.n4.description)).toBe('Second document autosave');
  await page.evaluate(() => { window.cy.zoom(0.82); window.cy.pan({ x: 97, y: 61 }); });
  const secondPresentation = await presentation(page);
  const secondRoutes = await routeGeometry(page);
  expect(secondPresentation.selection).toEqual(['n4']);

  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().layoutMode))
    .toBe('hierarchical');
  // Document activation deliberately clears graph selection (the established workspace contract),
  // while the durable presentation remains owned by the document that was edited.
  const { selection: _firstSelection, cursor: _firstCursor, ...firstDurable } = firstPresentation;
  const firstRestored = await presentation(page);
  const { selection: firstSelection, cursor: _restoredFirstCursor, ...firstRestoredDurable } = firstRestored;
  expect(firstRestoredDurable).toEqual(firstDurable);
  expect(firstSelection).toEqual([]);
  expect(await routeGeometry(page)).toEqual(firstRoutes);

  await page.evaluate(id => window.ravenroot.activateDocument(id), second);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().layoutMode)).toBe('flow-new');
  const { selection: _secondSelection, cursor: _secondCursor, ...secondDurable } = secondPresentation;
  const secondRestored = await presentation(page);
  const { selection: secondSelection, cursor: _restoredSecondCursor, ...secondRestoredDurable } = secondRestored;
  expect(secondRestoredDurable).toEqual({ ...secondDurable, positions: secondPositions });
  expect(secondSelection).toEqual([]);
  expect(await routeGeometry(page)).toEqual(secondRoutes);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.n4.description))
    .toBe('Second document autosave');
});

test('a pending autosave commits before a layered arrangement publishes its final session', async ({ page }) => {
  await startEditing(page);
  await selectOnly(page, 'n5');
  await page.locator('#node-editor textarea[name="description"]').fill('Committed before layered layout');

  await arrange(page, 'Arrange — Flow (new)');

  await expect.poll(() => page.evaluate(() => ({
    description: window.ravenroot.activeDocument().graph.nodeMap.n5.description,
    renderMode: window.ravenroot.activeDocument().renderMode,
    layoutMode: window.ravenroot.activeDocument().layoutMode,
    busy: window.ravenroot.activeDocument().layoutBusy,
  }))).toEqual({
    description: 'Committed before layered layout', renderMode: 'design',
    layoutMode: 'flow-new', busy: false,
  });
});

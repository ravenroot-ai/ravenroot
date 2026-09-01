import { expect, test } from '@playwright/test';

function denseGraphMl() {
  const nodes = Array.from({ length: 40 }, (_, index) => {
    const kind = index === 0 ? 'START' : index === 39 ? 'END' : 'PASSTHROUGH';
    return `<node id="n${index}"><data key="name">Node ${index}</data><data key="kind">${kind}</data>`
      + `<data key="x">${100 + (index % 8) * 145}</data><data key="y">${100 + Math.floor(index / 8) * 110}</data>`
      + '<data key="w">96</data><data key="h">56</data></node>';
  }).join('');
  const chain = Array.from({ length: 39 }, (_, index) =>
    `<edge id="chain-${index}" source="n${index}" target="n${index + 1}">`
    + `<data key="outcome">step-${index}</data></edge>`).join('');
  const parallel = ['Approved after an extended manual review', 'Retry after the service window',
    'Continue through the audited fallback'].map((label, index) =>
    `<edge id="parallel-${index}" source="n10" target="n20"><data key="outcome">${label}</data>`
    + '<data key="parallel">true</data></edge>').join('');
  return `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
    <key id="name" for="node" attr.name="name" attr.type="string"/>
    <key id="kind" for="node" attr.name="kind" attr.type="string"/>
    <key id="x" for="node" attr.name="layoutX" attr.type="double"/>
    <key id="y" for="node" attr.name="layoutY" attr.type="double"/>
    <key id="w" for="node" attr.name="layoutWidth" attr.type="double"/>
    <key id="h" for="node" attr.name="layoutHeight" attr.type="double"/>
    <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
    <key id="parallel" for="edge" attr.name="parallel" attr.type="boolean"/>
    <graph id="dense" edgedefault="directed">${nodes}${chain}${parallel}</graph></graphml>`;
}

async function openLayoutMenu(page) {
  await page.locator('#menu-layout').click();
  await expect(page.locator('#application-menu')).toBeVisible();
}

async function arrange(page, label) {
  await openLayoutMenu(page);
  await page.getByRole('menuitem', { name: label, exact: true }).click();
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
}

const positions = page => page.evaluate(() => Object.fromEntries(
  window.cy.nodes().map(node => [node.id(), { x: node.position('x'), y: node.position('y') }]),
));

const parallelEdgeSnapshot = page => page.evaluate(() => ['parallel-0', 'parallel-1', 'parallel-2'].map(id => {
  const edge = window.cy.getElementById(id);
  window.cy.$(':selected').unselect();
  edge.select();
  return {
    id: edge.id(), selected: edge.selected(), label: edge.style('label'),
    curveStyle: edge.style('curve-style'),
    sourceEndpoint: edge.style('source-endpoint'), targetEndpoint: edge.style('target-endpoint'),
  };
}));

const visualEdgeSnapshot = page => page.evaluate(() => Object.fromEntries(window.cy.edges().map(edge => [edge.id(), {
  curveStyle: edge.style('curve-style'),
  segmentWeights: edge.style('segment-weights'),
  segmentDistances: edge.style('segment-distances'),
  sourceEndpoint: edge.style('source-endpoint'),
  targetEndpoint: edge.style('target-endpoint'),
  label: edge.style('label'),
  textRotation: edge.style('edge-text-rotation'),
  textBackgroundOpacity: edge.style('text-background-opacity'),
}])));

async function expectIndependentParallelEdges(page) {
  const snapshot = await parallelEdgeSnapshot(page);
  expect(snapshot).toEqual([
    expect.objectContaining({ id: 'parallel-0', selected: true, label: expect.stringContaining('Approved') }),
    expect.objectContaining({ id: 'parallel-1', selected: true, label: expect.stringContaining('Retry') }),
    expect.objectContaining({ id: 'parallel-2', selected: true, label: expect.stringContaining('Continue') }),
  ]);
  return snapshot;
}

test.describe('Design arrangements', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.evaluate(xml => window.ravenroot.replaceActiveDocumentFromText(xml, 'dense.graphml'), denseGraphMl());
  });

  test('keeps the flat semantic menu ordered and Design-only', async ({ page }) => {
    expect(await page.evaluate(() => ({
      n0: window.cy.getElementById('n0').position(),
      n9: window.cy.getElementById('n9').position(),
      historyDepth: window.ravenroot.activeDocument().history.depth(),
    }))).toEqual({ n0: { x: 100, y: 100 }, n9: { x: 245, y: 210 }, historyDepth: 0 });
    await openLayoutMenu(page);
    const labels = await page.locator('#application-menu .application-menu-item span:first-child').allTextContents();
    expect(labels.slice(-6)).toEqual([
      'Design', 'Monitoring', 'Arrange — Hierarchical', 'Arrange — Flow', 'Arrange — Organic', 'Keep positions',
    ]);
    await page.screenshot({ path: '/tmp/ravenroot-648-arrange-menu.png', fullPage: true });
    await page.getByRole('menuitemradio', { name: 'Monitoring' }).click();
    await openLayoutMenu(page);
    for (const label of ['Arrange — Hierarchical', 'Arrange — Flow', 'Arrange — Organic', 'Keep positions']) {
      await expect(page.getByRole('menuitem', { name: label, exact: true }))
        .toHaveAttribute('aria-disabled', 'true');
    }
  });

  test('records one winning arrangement, keeps every edge independent, and undoes the geometry', async ({ page }) => {
    const before = await positions(page);
    const edgeIds = await page.evaluate(() => window.cy.edges().map(edge => edge.id()).sort());

    await arrange(page, 'Arrange — Flow');
    await expect.poll(() => page.evaluate(() => ({
      depth: window.ravenroot.activeDocument().history.depth(),
      label: window.ravenroot.activeDocument().history.undoLabel(),
      dirty: window.ravenroot.activeDocument().history.isDirty(),
    }))).toEqual({ depth: 1, label: 'Arrange — Flow', dirty: true });
    expect(await positions(page)).not.toEqual(before);
    expect(await page.evaluate(() => window.cy.edges().map(edge => edge.id()).sort())).toEqual(edgeIds);
    const parallelEdges = await expectIndependentParallelEdges(page);
    expect(new Set(parallelEdges.map(edge => edge.sourceEndpoint)).size).toBe(3);
    expect(new Set(parallelEdges.map(edge => edge.targetEndpoint)).size).toBe(3);

    const download = page.waitForEvent('download');
    await page.locator('#btn-export').click();
    expect((await download).suggestedFilename()).toBe('dense.graphml');
    expect(await page.evaluate(() => window.ravenroot.activeDocument().history.isDirty())).toBe(false);

    await page.locator('#btn-undo').click();
    await expect.poll(() => positions(page)).toEqual(before);
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().history.depth())).toBe(0);
  });

  test('preserves coordinates for Keep positions and retains edge identity across every arrangement', async ({ page }) => {
    const edgeIds = await page.evaluate(() => window.cy.edges().map(edge => edge.id()).sort());
    const before = await positions(page);
    await arrange(page, 'Keep positions');
    expect(await positions(page)).toEqual(before);
    expect(await page.evaluate(() => window.ravenroot.activeDocument().history.depth())).toBe(0);
    await expectIndependentParallelEdges(page);

    for (const label of ['Arrange — Hierarchical', 'Arrange — Organic']) {
      await arrange(page, label);
      expect(await page.evaluate(() => window.cy.edges().map(edge => edge.id()).sort())).toEqual(edgeIds);
      const parallel = await expectIndependentParallelEdges(page);
      if (label === 'Arrange — Hierarchical') {
        expect(new Set(parallel.map(edge => edge.curveStyle))).toEqual(new Set(['round-segments']));
        expect(parallel.every(edge => !edge.sourceEndpoint.startsWith('-'))).toBe(true);
        expect(parallel.every(edge => edge.targetEndpoint.startsWith('-'))).toBe(true);
        expect(new Set(parallel.map(edge => edge.sourceEndpoint)).size).toBe(3);
        expect(new Set(parallel.map(edge => edge.targetEndpoint)).size).toBe(3);
        const keepInvariant = await page.evaluate(() => ({
          positions: Object.fromEntries(window.cy.nodes().map(node => [node.id(), node.position()])),
          layoutMode: window.ravenroot.activeDocument().layoutMode,
          renderMode: window.ravenroot.activeDocument().renderMode,
          graph: JSON.stringify(window.ravenroot.activeDocument().graph),
          depth: window.ravenroot.activeDocument().history.depth(),
          dirty: window.ravenroot.activeDocument().history.isDirty(),
        }));
        const keepRoutes = await visualEdgeSnapshot(page);
        await arrange(page, 'Keep positions');
        expect(await page.evaluate(() => ({
          positions: Object.fromEntries(window.cy.nodes().map(node => [node.id(), node.position()])),
          layoutMode: window.ravenroot.activeDocument().layoutMode,
          renderMode: window.ravenroot.activeDocument().renderMode,
          graph: JSON.stringify(window.ravenroot.activeDocument().graph),
          depth: window.ravenroot.activeDocument().history.depth(),
          dirty: window.ravenroot.activeDocument().history.isDirty(),
        }))).toEqual(keepInvariant);
        expect(await visualEdgeSnapshot(page)).toEqual(keepRoutes);
        await page.screenshot({ path: '/tmp/ravenroot-648-dense-hierarchical.png', fullPage: true });
        await page.evaluate(() => {
          const focus = window.cy.collection()
            .union(window.cy.getElementById('n10'))
            .union(window.cy.getElementById('n20'))
            .union(window.cy.getElementById('parallel-0'))
            .union(window.cy.getElementById('parallel-1'))
            .union(window.cy.getElementById('parallel-2'));
          window.cy.fit(focus, 100);
          window.cy.zoom(0.65);
          window.cy.center(focus);
        });
        await page.screenshot({ path: '/tmp/ravenroot-648-parallel-edges.png', fullPage: true });
      }
    }
  });

  test('freezes the click-time canvas and retires an in-flight arrangement when keeping positions', async ({ page }) => {
    const frozen = await page.evaluate(() => {
      const run = id => {
        document.querySelector('#menu-layout').click();
        document.querySelector(`#application-menu [data-command-id="layout.arrange.${id}"]`).click();
      };
      run('organic');
      const snapshot = Object.fromEntries(
        window.cy.nodes().map(node => [node.id(), { x: node.position('x'), y: node.position('y') }]),
      );
      run('keep');
      return snapshot;
    });
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
    await page.waitForTimeout(1000);
    expect(await positions(page)).toEqual(frozen);
    expect(await page.evaluate(() => ({
      layoutMode: window.ravenroot.activeDocument().layoutMode,
      renderMode: window.ravenroot.activeDocument().renderMode,
      depth: window.ravenroot.activeDocument().history.depth(),
      dirty: window.ravenroot.activeDocument().history.isDirty(),
    }))).toEqual({ layoutMode: 'cose', renderMode: 'design', depth: 0, dirty: false });
    await expectIndependentParallelEdges(page);
  });

  test('lets only the winning rapid Arrange request publish positions and history', async ({ page }) => {
    await arrange(page, 'Arrange — Hierarchical');
    const hierarchicalPositions = await positions(page);
    await page.locator('#btn-undo').click();

    await page.evaluate(xml => window.ravenroot.replaceActiveDocumentFromText(xml, 'dense.graphml'), denseGraphMl());
    await page.evaluate(() => {
      const arrangeNow = id => {
        document.querySelector('#menu-layout').click();
        document.querySelector(`#application-menu [data-command-id="layout.arrange.${id}"]`).click();
      };
      arrangeNow('organic');
      arrangeNow('flow');
      arrangeNow('hierarchical');
    });
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
    await page.waitForTimeout(1000);
    expect(await page.evaluate(() => ({
      depth: window.ravenroot.activeDocument().history.depth(),
      undo: window.ravenroot.activeDocument().history.undoLabel(),
      layoutMode: window.ravenroot.activeDocument().layoutMode,
    }))).toEqual({ depth: 1, undo: 'Arrange — Hierarchical', layoutMode: 'hierarchical' });
    const raced = await positions(page);
    for (const [id, expected] of Object.entries(hierarchicalPositions)) {
      expect(Math.abs(raced[id].x - expected.x)).toBeLessThan(2);
      expect(Math.abs(raced[id].y - expected.y)).toBeLessThan(2);
    }
  });

  test('commits an autosaved Inspector draft before arranging and keeps both edits undoable', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.locator('#btn-modify').click();
    await page.evaluate(() => { window.cy.getElementById('n5').select(); });
    const name = page.locator('#node-editor input[name="name"]');
    await expect(name).toHaveValue('Node 5');
    await name.fill('Autosaved before arrangement');

    await arrange(page, 'Arrange — Hierarchical');
    await expect.poll(() => page.evaluate(() => ({
      name: window.ravenroot.activeDocument().graph.nodeMap.n5.name,
      depth: window.ravenroot.activeDocument().history.depth(),
      undo: window.ravenroot.activeDocument().history.undoLabel(),
    }))).toEqual({ name: 'Autosaved before arrangement', depth: 2, undo: 'Arrange — Hierarchical' });

    await page.locator('#btn-undo').click();
    expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.n5.name))
      .toBe('Autosaved before arrangement');
    await page.locator('#btn-undo').click();
    expect(await page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.n5.name)).toBe('Node 5');
  });
});

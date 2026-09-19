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

function unpositionedArrangedGraphMl(arrangement = 'flow', layout = 'dagre') {
  return `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
    <key id="name" for="node" attr.name="name" attr.type="string"/>
    <key id="kind" for="node" attr.name="kind" attr.type="string"/>
    <key id="render-mode" for="graph" attr.name="ravenroot.renderMode" attr.type="string"/>
    <key id="layout-mode" for="graph" attr.name="ravenroot.layoutMode" attr.type="string"/>
    <key id="arrangement" for="graph" attr.name="ravenroot.designArrangement" attr.type="string"/>
    <graph id="raw" edgedefault="directed">
      <data key="render-mode">design</data><data key="layout-mode">${layout}</data>
      <data key="arrangement">${arrangement}</data>
      <node id="raw-start"><data key="name">Start</data><data key="kind">START</data></node>
      <node id="raw-a"><data key="name">A</data><data key="kind">PASSTHROUGH</data></node>
      <node id="raw-b"><data key="name">B</data><data key="kind">PASSTHROUGH</data></node>
      <node id="raw-end"><data key="name">End</data><data key="kind">END</data></node>
      <edge id="raw-edge-1" source="raw-start" target="raw-a"/>
      <edge id="raw-edge-2" source="raw-a" target="raw-b"/>
      <edge id="raw-edge-3" source="raw-b" target="raw-end"/>
    </graph></graphml>`;
}

async function openLayoutMenu(page) {
  await page.locator('#menu-layout').click();
  await expect(page.locator('#application-menu')).toBeVisible();
}

async function arrange(page, label) {
  await openLayoutMenu(page);
  await page.locator(`[data-command-id="layout.arrange.${arrangementCommandIds[label]}"]`).click();
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
}

const positions = page => page.evaluate(() => Object.fromEntries(
  window.cy.nodes().map(node => [node.id(), { x: node.position('x'), y: node.position('y') }]),
));

const arrangementCommandIds = Object.freeze({
  'Arrange — Hierarchical': 'hierarchical',
  'Arrange — Flow': 'flow',
  'Arrange — Organic': 'organic',
  'Keep positions': 'keep',
  'Arrange — Hierarchical (new)': 'hierarchical-new',
  'Arrange — Layered (top-down)': 'layered-down',
});

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

async function startTimedOutInitialElk(page) {
  return page.evaluate(xml => {
    window.__lateInitialElkRuns = 0;
    window.__lateInitialElkStops = 0;
    window.__timeoutVisibleFrames = [];
    const collectionPrototype = Object.getPrototypeOf(window.cy.elements());
    const originalLayout = collectionPrototype.layout;
    const originalSetTimeout = window.setTimeout;
    window.setTimeout = function(callback, delay, ...args) {
      return originalSetTimeout.call(window, callback, delay === 15000 ? 40 : delay, ...args);
    };
    collectionPrototype.layout = function(options) {
      const controller = originalLayout.call(this, options);
      if (options.name !== 'elk' || this.cy().getElementById('raw-start').empty()) return controller;
      window.__lateInitialLayoutCy = this.cy();
      const run = controller.run.bind(controller);
      controller.one('layoutstop', () => { window.__lateInitialElkStops += 1; });
      controller.run = () => {
        originalSetTimeout.call(window, () => {
          window.__lateInitialElkRuns += 1;
          run();
        }, 300);
        return controller;
      };
      return controller;
    };
    const recordVisible = () => {
      if (window.cy?.getElementById('raw-start').nonempty()) {
        const owner = window.ravenroot.activeDocument();
        if (Number(getComputedStyle(owner.container).opacity) > 0) {
          window.__timeoutVisibleFrames.push(window.cy.nodes().map(node => node.position()));
        }
      }
      if (!window.__timeoutVisibleFrames.length) requestAnimationFrame(recordVisible);
    };
    requestAnimationFrame(recordVisible);
    try {
      window.ravenroot.replaceActiveDocumentFromText(xml, 'timed-out-elk.graphml');
      const owner = window.ravenroot.activeDocument();
      return {
        uniquePositions: new Set(window.cy.nodes().map(node => {
          const p = node.position(); return `${p.x}:${p.y}`;
        })).size,
        opacity: getComputedStyle(owner.container).opacity,
        inert: owner.container.inert,
        ariaHidden: owner.container.getAttribute('aria-hidden'),
        busy: owner.layoutBusy,
      };
    } finally {
      collectionPrototype.layout = originalLayout;
      window.setTimeout = originalSetTimeout;
    }
  }, unpositionedArrangedGraphMl('hierarchical', 'hierarchical'));
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
    expect(labels.slice(-9)).toEqual([
      'Design', 'Monitoring', 'Render', 'Arrange — Hierarchical', 'Arrange — Flow', 'Arrange — Organic', 'Keep positions',
      'Arrange — Hierarchical (new)', 'Arrange — Layered (top-down)',
    ]);
    await page.screenshot({ path: '/tmp/ravenroot-648-arrange-menu.png', fullPage: true });
    await page.getByRole('menuitemradio', { name: 'Monitoring' }).click();
    await openLayoutMenu(page);
    for (const label of ['Arrange — Hierarchical', 'Arrange — Flow', 'Arrange — Organic', 'Keep positions']) {
      await expect(page.locator(`[data-command-id="layout.arrange.${arrangementCommandIds[label]}"]`))
        .toHaveAttribute('aria-disabled', 'true');
    }
  });

  const initialArrangementCases = [
    ['hierarchical', 'hierarchical', 'elk'],
    ['flow', 'dagre', 'dagre'],
    ['organic', 'cose', 'cose'],
    ['hierarchical-new', 'hierarchical-new', 'rr-layered'],
    ['layered-down', 'layered-down', 'rr-layered'],
    ['keep', 'cose', 'cose'],
  ];

  for (const [arrangement, layout, engine] of initialArrangementCases) test(
    `withholds raw ${arrangement} until its only initial arrangement is paintable`, async ({ page }) => {
    await page.evaluate(({ xml }) => {
      window.__rawInitialLayouts = [];
      window.__rawPaintFrames = [];
      const collectionPrototype = Object.getPrototypeOf(window.cy.elements());
      const originalLayout = collectionPrototype.layout;
      collectionPrototype.layout = function(options) {
        if (this.cy().getElementById('raw-start').nonempty()) {
          window.__rawInitialLayouts.push(options.name);
        }
        return originalLayout.call(this, options);
      };
      const record = () => {
        if (window.cy?.getElementById('raw-start').nonempty()) {
          const canvas = window.ravenroot.activeDocument().container;
          const style = getComputedStyle(canvas);
          if (style.display !== 'none' && style.visibility === 'visible' && Number(style.opacity) > 0) {
            window.__rawPaintFrames.push(window.cy.nodes().map(node => node.position()));
          }
        }
        if (window.__rawPaintFrames.length < 3) requestAnimationFrame(record);
      };
      requestAnimationFrame(record);
      window.ravenroot.replaceActiveDocumentFromText(xml, 'raw-arranged.graphml');
    }, { xml: unpositionedArrangedGraphMl(arrangement, layout) });
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
    await expect.poll(() => page.evaluate(() => window.__rawPaintFrames.length)).toBe(3);
    expect(await page.evaluate(() => window.__rawInitialLayouts)).toEqual([engine]);
    expect(await page.evaluate(() => {
      const owner = window.ravenroot.activeDocument();
      return {
        arrangement: owner.designArrangement,
        layout: owner.layoutMode,
        historyDepth: owner.history.depth(),
        dirty: owner.history.isDirty(),
        paintable: getComputedStyle(owner.container).opacity === '1'
          && !owner.container.classList.contains('doc-canvas--initial-layout-pending'),
        everyPaintArranged: window.__rawPaintFrames.every(frame =>
          new Set(frame.map(position => `${position.x}:${position.y}`)).size === 4),
      };
    })).toEqual({ arrangement, layout, historyDepth: 0, dirty: false,
      paintable: true, everyPaintArranged: true });
  });

  test('keeps authored coordinates directly paintable without an initialization layout', async ({ page }) => {
    expect(await page.evaluate(xml => {
      const prototype = Object.getPrototypeOf(window.cy.elements());
      const originalLayout = prototype.layout;
      const layouts = [];
      prototype.layout = function(options) {
        if (this.cy().getElementById('n0').nonempty()) layouts.push(options.name);
        return originalLayout.call(this, options);
      };
      try {
        window.ravenroot.replaceActiveDocumentFromText(xml, 'authored.graphml');
        const owner = window.ravenroot.activeDocument();
        return {
          layouts,
          opacity: getComputedStyle(owner.container).opacity,
          concealed: owner.container.classList.contains('doc-canvas--initial-layout-pending'),
          inert: owner.container.inert,
          n0: window.cy.getElementById('n0').position(),
          historyDepth: owner.history.depth(),
          dirty: owner.history.isDirty(),
        };
      } finally {
        prototype.layout = originalLayout;
      }
    }, denseGraphMl())).toEqual({
      layouts: [], opacity: '1', concealed: false, inert: false,
      n0: { x: 100, y: 100 }, historyDepth: 0, dirty: false,
    });
  });

  test('reveals an initial canvas when its arrangement engine fails synchronously', async ({ page }) => {
    expect(await page.evaluate(xml => {
      const prototype = Object.getPrototypeOf(window.cy.elements());
      const originalLayout = prototype.layout;
      prototype.layout = function(options) {
        if (this.cy().getElementById('raw-start').nonempty()) throw new Error('synthetic layout failure');
        return originalLayout.call(this, options);
      };
      try {
        window.ravenroot.replaceActiveDocumentFromText(xml, 'failed-arrangement.graphml');
        const owner = window.ravenroot.activeDocument();
        return {
          opacity: getComputedStyle(owner.container).opacity,
          concealed: owner.container.classList.contains('doc-canvas--initial-layout-pending'),
          inert: owner.container.inert,
          ariaHidden: owner.container.getAttribute('aria-hidden'),
          busy: owner.layoutBusy,
          uniquePositions: new Set(window.cy.nodes().map(node => {
            const p = node.position(); return `${p.x}:${p.y}`;
          })).size,
          historyDepth: owner.history.depth(),
          dirty: owner.history.isDirty(),
        };
      } finally {
        prototype.layout = originalLayout;
      }
    }, unpositionedArrangedGraphMl())).toEqual({
      opacity: '1', concealed: false, inert: false, ariaHidden: null,
      busy: false, uniquePositions: 4, historyDepth: 0, dirty: false,
    });
  });

  test('releases a concealed initial canvas when its async arrangement is retired', async ({ page }) => {
    expect(await page.evaluate(({ raw, authored }) => {
      window.ravenroot.replaceActiveDocumentFromText(raw, 'retired-arrangement.graphml');
      const owner = window.ravenroot.activeDocument();
      const concealedBeforeRetirement = owner.container.classList
        .contains('doc-canvas--initial-layout-pending');
      window.ravenroot.replaceActiveDocumentFromText(authored, 'replacement.graphml');
      return {
        concealedBeforeRetirement,
        opacity: getComputedStyle(owner.container).opacity,
        concealed: owner.container.classList.contains('doc-canvas--initial-layout-pending'),
        inert: owner.container.inert,
        ariaHidden: owner.container.getAttribute('aria-hidden'),
        busy: owner.layoutBusy,
        n0: window.cy.getElementById('n0').position(),
      };
    }, { raw: unpositionedArrangedGraphMl('hierarchical', 'hierarchical'), authored: denseGraphMl() }))
      .toEqual({
        concealedBeforeRetirement: true, opacity: '1', concealed: false, inert: false,
        ariaHidden: null, busy: false, n0: { x: 100, y: 100 },
      });
  });

  for (const scenario of ['fallback', 'user edit', 'newer arrangement']) test(
    `timed-out ELK cannot publish after ${scenario}`, async ({ page }) => {
    expect(await startTimedOutInitialElk(page)).toEqual({
      uniquePositions: 1, opacity: '0', inert: true, ariaHidden: 'true', busy: true,
    });
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
    await expect.poll(() => page.evaluate(() => window.__timeoutVisibleFrames.length)).toBe(1);
    expect(await page.evaluate(() => {
      const owner = window.ravenroot.activeDocument();
      return {
        firstVisibleUnique: new Set(window.__timeoutVisibleFrames[0].map(p => `${p.x}:${p.y}`)).size,
        opacity: getComputedStyle(owner.container).opacity,
        inert: owner.container.inert,
        ariaHidden: owner.container.getAttribute('aria-hidden'),
        busy: owner.layoutBusy,
        isolatedDestroyed: window.__lateInitialLayoutCy.destroyed(),
        historyDepth: owner.history.depth(),
        dirty: owner.history.isDirty(),
      };
    })).toEqual({ firstVisibleUnique: 4, opacity: '1', inert: false, ariaHidden: null,
      busy: false, isolatedDestroyed: true, historyDepth: 0, dirty: false });

    if (scenario === 'user edit') {
      await page.evaluate(() => window.cy.getElementById('raw-a').position({ x: 777, y: 333 }));
    } else if (scenario === 'newer arrangement') {
      await arrange(page, 'Arrange — Flow');
    }
    const stable = await positions(page);
    await expect.poll(() => page.evaluate(() => window.__lateInitialElkRuns)).toBe(1);
    await page.waitForTimeout(500);
    expect(await positions(page)).toEqual(stable);
    expect(await page.evaluate(() => ({
      activeLayout: window.ravenroot.activeDocument().layoutMode,
      busy: window.ravenroot.activeDocument().layoutBusy,
      lateRuns: window.__lateInitialElkRuns,
    }))).toEqual({ activeLayout: scenario === 'newer arrangement' ? 'dagre' : 'hierarchical',
      busy: false, lateRuns: 1 });
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

  test('keeps arrangement selection independent and selecting it again clears future Render choice', async ({ page }) => {
    await arrange(page, 'Arrange — Flow');
    const arranged = await positions(page);
    await openLayoutMenu(page);
    await page.locator('[data-command-id="layout.arrange.flow"]').click();
    expect(await page.evaluate(() => ({
      choice: window.ravenroot.activeDocument().designArrangement,
      stored: window.ravenroot.activeDocument().graph.graphProperties['ravenroot.designArrangement'],
    }))).toEqual({ choice: null, stored: undefined });
    expect(await positions(page)).toEqual(arranged);
    await page.locator('#btn-render').click();
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
    expect(await positions(page)).not.toEqual(arranged);
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
          graph: JSON.stringify({
            nodes: window.ravenroot.activeDocument().graph.nodes,
            edges: window.ravenroot.activeDocument().graph.edges,
          }),
          depth: window.ravenroot.activeDocument().history.depth(),
          dirty: window.ravenroot.activeDocument().history.isDirty(),
        }));
        const keepRoutes = await visualEdgeSnapshot(page);
        await arrange(page, 'Keep positions');
        expect(await page.evaluate(() => ({
          positions: Object.fromEntries(window.cy.nodes().map(node => [node.id(), node.position()])),
          layoutMode: window.ravenroot.activeDocument().layoutMode,
          renderMode: window.ravenroot.activeDocument().renderMode,
          graph: JSON.stringify({
            nodes: window.ravenroot.activeDocument().graph.nodes,
            edges: window.ravenroot.activeDocument().graph.edges,
          }),
          depth: window.ravenroot.activeDocument().history.depth(),
          dirty: window.ravenroot.activeDocument().history.isDirty(),
        }))).toEqual(keepInvariant);
        expect(await page.evaluate(() => ({
          choice: window.ravenroot.activeDocument().designArrangement,
          stored: window.ravenroot.activeDocument().graph.graphProperties['ravenroot.designArrangement'],
        }))).toEqual({ choice: 'keep', stored: 'keep' });
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

  test('restores the exact Arrange choice across document, render, and reload lifecycles', async ({ page }) => {
    await page.route('**/v1/configuration', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024,
        workspace: { tenantId: 'arrangement-tenant' } }),
    }));
    await page.reload();
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().writable)).toBe(true);
    await page.evaluate(xml => window.ravenroot.replaceActiveDocumentFromText(xml, 'arranged.graphml'), denseGraphMl());

    await arrange(page, 'Arrange — Flow');
    const firstId = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const arranged = await positions(page);
    await openLayoutMenu(page);
    await expect(page.locator('[data-command-id="layout.arrange.flow"]'))
      .toHaveAttribute('aria-checked', 'true');
    await page.keyboard.press('Escape');

    const secondId = await page.evaluate(() => window.ravenroot.openDocument({ name: 'second.graphml' }));
    await page.evaluate(id => window.ravenroot.activateDocument(id), firstId);
    expect(await page.evaluate(() => {
      const owner = window.ravenroot.activeDocument();
      return [owner.designArrangement, owner.layoutMode];
    })).toEqual(['flow', 'dagre']);

    await page.locator('#btn-monitoring').click();
    await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
    await page.locator('#btn-design').click();
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', { timeout: 10_000 });
    expect(await page.evaluate(() => {
      const owner = window.ravenroot.activeDocument();
      return [owner.renderMode, owner.designArrangement, owner.layoutMode];
    })).toEqual(['design', 'flow', 'dagre']);

    const afterLifecycle = await positions(page);
    for (const [id, expected] of Object.entries(arranged)) {
      expect(Math.abs(afterLifecycle[id].x - expected.x)).toBeLessThan(2);
      expect(Math.abs(afterLifecycle[id].y - expected.y)).toBeLessThan(2);
    }
    await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
    await page.reload();
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().writable)).toBe(true);
    expect(await page.evaluate(() => ({
      active: window.ravenroot.activeDocument().id,
      documents: window.ravenroot.documents().map(owner => owner.id),
      choice: window.ravenroot.activeDocument().designArrangement,
      layout: window.ravenroot.activeDocument().layoutMode,
    }))).toEqual({ active: firstId, documents: [firstId, secondId], choice: 'flow', layout: 'dagre' });
    const restoredPositions = await positions(page);
    for (const [id, expected] of Object.entries(arranged)) {
      expect(restoredPositions[id].x).toBeCloseTo(expected.x, 3);
      expect(restoredPositions[id].y).toBeCloseTo(expected.y, 3);
    }
    await openLayoutMenu(page);
    await expect(page.locator('[data-command-id="layout.arrange.flow"]'))
      .toHaveAttribute('aria-checked', 'true');
    await page.screenshot({ path: test.info().outputPath('restored-design-arrangement.png'), fullPage: true });
  });
});

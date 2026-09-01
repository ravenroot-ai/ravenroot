import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const WIDE = { width: 1800, height: 900 };
const RESPONSIVE_FALLBACK = { width: 1280, height: 800 };
// Was `test/fixtures/edge-authoring.graphml` -- this file never asserts the loaded content's
// node identity, only that a replace/load succeeded, so it never needed the golden edge-authoring
// fixture (whose sole legitimate reader is `test/edge-fixture.test.js`, UI-02). Moved to the
// same lifecycle-only fixture `multi-document-lifecycle.spec.js` and `replace-active-workspace.spec.js`
// use, so a future edge-authoring change can't reach this file's layout-race tests either.
const graphMl = readFileSync(new URL('../test/fixtures/lifecycle-document.graphml', import.meta.url), 'utf8');
const incomingPositions = {
  archive: { x: 480, y: 220 },
  end: { x: 660, y: 220 },
  review: { x: 300, y: 220 },
  start: { x: 120, y: 220 },
};
const emptyHistory = {
  canUndo: false,
  canRedo: false,
  dirty: false,
  undoLabel: '',
  redoLabel: '',
  depth: 0,
};

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

async function settleActiveDocument(page) {
  await page.evaluate(() => {
    const instance = window.ravenroot.activeDocument().cy;
    window.__rrSettled = instance.scratch('_rrLayoutRunning')
      ? new Promise(resolve => instance.one('layoutstop', () => resolve()))
      : Promise.resolve();
  });
  await page.evaluate(() => window.__rrSettled);
}

async function openSettledDocument(page, name) {
  const id = await page.evaluate(documentName => window.ravenroot.openDocument({ name: documentName }), name);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  return id;
}

const paintedPixels = (page, documentId) => page.evaluate(id => {
  const pane = document.querySelector(`.doc-pane[data-document-id="${id}"]`);
  return [...pane.querySelectorAll('canvas')].reduce((total, canvas) => {
    if (!canvas.width || !canvas.height) return total;
    const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
    for (let index = 3; index < pixels.length; index += 4) if (pixels[index]) total += 1;
    return total;
  }, 0);
}, documentId);

const rendererSnapshot = (page, documentId) => page.evaluate(id => {
  const owner = window.ravenroot.workspace.find(id);
  return {
    generation: owner.renderer.token.generation,
    kind: owner.renderer.kind,
    positions: owner.cy.nodes().map(node => ({ id: node.id(), ...node.position() }))
      .sort((a, b) => a.id.localeCompare(b.id)),
    edges: owner.cy.edges().map(edge => ({
      id: edge.id(), width: edge.style('width'), opacity: edge.style('opacity'),
    })).sort((a, b) => a.id.localeCompare(b.id)),
  };
}, documentId);

async function prepareElasticPair(page) {
  const sibling = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const target = await openSettledDocument(page, 'elastic-target.graphml');
  await page.evaluate(id => window.ravenroot.activateDocument(id), sibling);
  await page.locator('#btn-design').click();
  await settleActiveDocument(page);
  await page.evaluate(id => window.ravenroot.activateDocument(id), target);
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => page.evaluate(id => {
    const renderer = window.ravenroot.workspace.find(id)?.renderer;
    return {
      kind: renderer?.kind,
      liveSimulation: Boolean(renderer?.simulation && renderer.simulation.alpha() > 0),
      connected: Boolean(renderer?.host.isConnected && renderer.svg.isConnected),
    };
  }, target)).toEqual({ kind: 'elastic', liveSimulation: true, connected: true });
  return { sibling, target };
}

async function preparePendingHiddenElastic(page) {
  await page.setViewportSize(RESPONSIVE_FALLBACK);
  const target = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const sibling = await openSettledDocument(page, 'pending-elastic-sibling.graphml');

  // Keep the whole interleaving in one browser task: Design owns the ELK slot, Monitoring replaces
  // it, then the responsive workspace hides its owner before the D3 renderer can be instantiated.
  await page.evaluate(([targetId, siblingId]) => {
    window.ravenroot.activateDocument(targetId);
    document.querySelector('#btn-design').click();
    document.querySelector('#btn-monitoring').click();
    window.ravenroot.activateDocument(siblingId);
  }, [target, sibling]);

  await expect.poll(() => page.evaluate(id => {
    const owner = window.ravenroot.workspace.find(id);
    return {
      layoutMode: owner?.layoutMode,
      rendererKind: owner?.renderer?.kind,
      shown: owner?.pane?.classList.contains('doc-pane--shown'),
      pendingGeneration: owner?.pendingElasticLayoutToken?.generation ?? null,
      hostCount: owner?.pane?.querySelectorAll('.doc-elastic-host').length ?? -1,
    };
  }, target)).toEqual({
    layoutMode: 'elastic',
    rendererKind: 'cytoscape',
    shown: false,
    pendingGeneration: expect.any(Number),
    hostCount: 0,
  });
  return { target, sibling };
}

test.describe('per-document layout ownership', () => {
  test.use({ viewport: WIDE });

  test.beforeEach(async ({ page }) => {
    await stubService(page);
    await page.goto('/');
    await settleActiveDocument(page);
  });

  test('the latest Design request owns the final positions', async ({ page }) => {
    const instanceId = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const snapshot = async () => page.evaluate(id => Object.fromEntries(
      window.ravenroot.workspace.find(id).cy.nodes().map(node => [node.id(), node.position()])
    ), instanceId);

    // Ground truth: dagre run alone, with nothing racing it, on whatever the current template
    // contains -- no hardcoded geometry or node count. The document opens on its authored preset
    // positions (createWorkflowDocument), not a dagre run, so this has to be measured, not assumed
    // from the initial state.
    await page.locator('#btn-design').click();
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
    const designPositions = await snapshot();

    // Now race an earlier request ('2', cose) against a later one ('1', dagre) on the same
    // document. If the earlier request's async completion won instead of the later one, the
    // raced positions diverge from the dagre-alone ground truth above; if the latest request
    // genuinely owns the final state, they coincide -- dagre is a deterministic layered layout,
    // not a relaxation seeded by whatever positions cose left behind.
    await page.locator('#btn-design').click();
    await page.locator('#btn-design').click();
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
    const positions = await snapshot();

    for (const [id, expected] of Object.entries(designPositions)) {
      expect(Math.abs(positions[id].x - expected.x)).toBeLessThan(2);
      expect(Math.abs(positions[id].y - expected.y)).toBeLessThan(2);
    }
  });

  test('a background owner completes without mutating the settled foreground document', async ({ page }) => {
    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await openSettledDocument(page, 'foreground.graphml');
    const evidence = await page.evaluate(async ([backgroundId, foregroundId]) => {
      const workspace = window.ravenroot.workspace;
      const background = workspace.find(backgroundId);
      const foreground = workspace.find(foregroundId);
      const foregroundBefore = foreground.cy.nodes().map(node => ({ id: node.id(), ...node.position() }));
      window.ravenroot.activateDocument(backgroundId);
      const stopped = new Promise(resolve => background.cy.one('layoutstop', () => resolve()));
      document.querySelector('#btn-design').click();
      window.ravenroot.activateDocument(foregroundId);
      await stopped;
      return {
        activeId: workspace.activeId,
        backgroundMode: background.layoutMode,
        backgroundRunning: Boolean(background.cy.scratch('_rrLayoutRunning')),
        foregroundBefore,
        foregroundAfter: foreground.cy.nodes().map(node => ({ id: node.id(), ...node.position() })),
      };
    }, [first, second]);

    expect(evidence.activeId).toBe(second);
    expect(evidence.backgroundMode).toBe('cyto');
    expect(evidence.backgroundRunning).toBe(false);
    expect(evidence.foregroundAfter).toEqual(evidence.foregroundBefore);
    expect(await paintedPixels(page, first)).toBeGreaterThan(0);
    expect(await paintedPixels(page, second)).toBeGreaterThan(0);
  });

  test('close and replace invalidate an ELK request before its deferred start', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const closing = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const survivor = await openSettledDocument(page, 'survivor.graphml');

    await page.evaluate(id => {
      window.ravenroot.activateDocument(id);
      document.querySelector('#btn-design').click();
      window.ravenroot.closeDocument(id);
    }, closing);
    await page.waitForTimeout(100);
    expect(await page.evaluate(() => window.ravenroot.workspace.activeId)).toBe(survivor);

    await page.evaluate(text => {
      document.querySelector('#btn-design').click();
      window.ravenroot.replaceActiveDocumentFromText(text, 'replacement.graphml');
    }, graphMl);
    await page.waitForTimeout(800);

    expect(errors).toEqual([]);
    expect(await page.evaluate(() => ({
      size: window.ravenroot.workspace.size,
      name: window.ravenroot.activeDocument().name,
      destroyed: window.ravenroot.activeDocument().cy.destroyed(),
    }))).toEqual({ size: 1, name: 'replacement.graphml', destroyed: false });
    expect(await paintedPixels(page, survivor)).toBeGreaterThan(0);
  });

  test('a hidden Monitoring handoff stays owner-local and resumes without stale publication', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const { target, sibling } = await preparePendingHiddenElastic(page);
    const siblingBefore = await rendererSnapshot(page, sibling);
    const targetBefore = await rendererSnapshot(page, target);
    const pendingGeneration = await page.evaluate(id =>
      window.ravenroot.workspace.find(id).pendingElasticLayoutToken.generation, target);

    // The same-task focus bounce may consume the pending handoff, but it must suspend the resulting
    // renderer inside its own hidden pane and never publish coordinates into either document.
    await page.evaluate(([targetId, siblingId]) => {
      window.ravenroot.activateDocument(targetId);
      window.ravenroot.activateDocument(siblingId);
    }, [target, sibling]);
    await expect.poll(() => page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      return {
        layoutMode: owner.layoutMode,
        rendererKind: owner.renderer.kind,
        shown: owner.pane.classList.contains('doc-pane--shown'),
        pending: owner.pendingElasticLayoutToken ?? null,
        hosts: owner.pane.querySelectorAll('.doc-elastic-host').length,
        hostOwned: owner.pane.contains(owner.renderer.host),
        hostConnected: owner.renderer.host.isConnected,
        svgOwner: owner.renderer.svg.dataset.documentId,
        suspended: owner.renderer.host.classList.contains('suspended'),
        requestGeneration: owner.renderer.layoutToken.generation,
      };
    }, target)).toEqual({
      layoutMode: 'elastic',
      rendererKind: 'elastic',
      shown: false,
      pending: null,
      hosts: 1,
      hostOwned: true,
      hostConnected: true,
      svgOwner: target,
      suspended: true,
      requestGeneration: expect.any(Number),
    });
    expect(await page.evaluate(id =>
      window.ravenroot.workspace.find(id).renderer.layoutToken.generation, target))
      .toBeGreaterThan(pendingGeneration);
    const resumedGeneration = await page.evaluate(id =>
      window.ravenroot.workspace.find(id).renderer.layoutToken.generation, target);
    const targetHidden = await rendererSnapshot(page, target);
    expect(targetHidden.positions).toEqual(targetBefore.positions);
    expect(targetHidden.edges).toEqual(targetBefore.edges);
    expect(await rendererSnapshot(page, sibling)).toEqual(siblingBefore);

    await page.evaluate(id => window.ravenroot.activateDocument(id), target);
    await expect.poll(() => page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      return {
        layoutMode: owner.layoutMode,
        rendererKind: owner.renderer.kind,
        shown: owner.pane.classList.contains('doc-pane--shown'),
        pending: owner.pendingElasticLayoutToken ?? null,
        hostOwned: owner.pane.contains(owner.renderer.host),
        hostConnected: owner.renderer.host.isConnected,
        svgConnected: owner.renderer.svg.isConnected,
        svgOwner: owner.renderer.svg.dataset.documentId,
        simulationLive: Boolean(owner.renderer.simulation && owner.renderer.simulation.alpha() > 0),
        requestGeneration: owner.renderer.layoutToken.generation,
      };
    }, target)).toEqual({
      layoutMode: 'elastic',
      rendererKind: 'elastic',
      shown: true,
      pending: null,
      hostOwned: true,
      hostConnected: true,
      svgConnected: true,
      svgOwner: target,
      simulationLive: true,
      requestGeneration: resumedGeneration,
    });
    const targetShown = await rendererSnapshot(page, target);
    expect(targetShown.positions).toEqual(targetBefore.positions);
    expect(targetShown.edges).toEqual(targetBefore.edges);
    expect(await rendererSnapshot(page, sibling)).toEqual(siblingBefore);
    expect(errors).toEqual([]);
  });

  test('closing a hidden owner invalidates its pending Elastic generation', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const { target, sibling } = await preparePendingHiddenElastic(page);
    const siblingBefore = await rendererSnapshot(page, sibling);

    await page.locator('#document-switcher').click();
    await page.locator(`[data-document-close="${target}"]`).click();
    await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id), target)).toBeNull();
    await page.waitForTimeout(750);

    expect(await page.evaluate(id => ({
      hosts: document.querySelectorAll(`.doc-elastic-host[data-document-id="${id}"]`).length,
      svgs: document.querySelectorAll(`.d3-elastic[data-document-id="${id}"]`).length,
    }), target)).toEqual({ hosts: 0, svgs: 0 });
    expect(await rendererSnapshot(page, sibling)).toEqual(siblingBefore);
    expect(errors).toEqual([]);
  });

  test('same-turn replace invalidates pending Elastic before resume can instantiate it', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const { target, sibling } = await preparePendingHiddenElastic(page);
    const siblingBefore = await rendererSnapshot(page, sibling);
    const retired = await page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      window.__pendingElasticCy = owner.cy;
      window.__pendingElasticRenderer = owner.renderer;
      return {
        layoutGeneration: owner.pendingElasticLayoutToken.generation,
        rendererGeneration: owner.renderer.token.generation,
      };
    }, target);

    await page.evaluate(([id, replacement]) => {
      window.ravenroot.activateDocument(id);
      window.ravenroot.replaceActiveDocumentFromText(replacement, 'pending-replacement.graphml');
    }, [target, graphMl]);
    await expect.poll(() => page.evaluate(([id, previous]) => {
      const owner = window.ravenroot.workspace.find(id);
      return {
        name: owner.name,
        renderMode: owner.renderMode,
        layoutMode: owner.layoutMode,
        visualStyle: owner.visualStyle,
        rendererKind: owner.renderer.kind,
        pending: owner.pendingElasticLayoutToken ?? null,
        oldCyDestroyed: window.__pendingElasticCy.destroyed(),
        oldRendererRetired: owner.renderer.token.generation !== previous.rendererGeneration,
        oldGenerationRetired: owner.layoutSessionToken?.generation !== previous.layoutGeneration,
        hosts: owner.pane.querySelectorAll('.doc-elastic-host').length,
        history: owner.history.state(),
        positions: Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()])),
        modelPositions: Object.fromEntries(owner.graph.nodes.map(node => [node.id, {
          x: node.ox, y: node.oy,
        }])),
      };
    }, [target, retired])).toEqual({
      name: 'pending-replacement.graphml',
      renderMode: 'design',
      layoutMode: 'cyto',
      visualStyle: 'cyto',
      rendererKind: 'cytoscape',
      pending: null,
      oldCyDestroyed: true,
      oldRendererRetired: true,
      oldGenerationRetired: true,
      hosts: 0,
      history: emptyHistory,
      positions: incomingPositions,
      modelPositions: incomingPositions,
    });
    const targetAfterReplace = await rendererSnapshot(page, target);
    expect(await rendererSnapshot(page, sibling)).toEqual(siblingBefore);
    await page.evaluate(id => window.ravenroot.activateDocument(id), sibling);
    await page.evaluate(id => window.ravenroot.activateDocument(id), target);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() =>
      requestAnimationFrame(() => requestAnimationFrame(resolve)))));
    expect(await rendererSnapshot(page, target)).toEqual(targetAfterReplace);
    expect(await rendererSnapshot(page, sibling)).toEqual(siblingBefore);
    expect(await page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      return {
        renderMode: owner.renderMode,
        layoutMode: owner.layoutMode,
        visualStyle: owner.visualStyle,
        rendererKind: owner.renderer.kind,
        hosts: owner.pane.querySelectorAll('.doc-elastic-host').length,
        history: owner.history.state(),
      };
    }, target)).toEqual({
      renderMode: 'design',
      layoutMode: 'cyto',
      visualStyle: 'cyto',
      rendererKind: 'cytoscape',
      hosts: 0,
      history: emptyHistory,
    });
    expect(errors).toEqual([]);
  });

  test('a newer layout invalidates pending Elastic before resume can instantiate it', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const { target, sibling } = await preparePendingHiddenElastic(page);
    const siblingBefore = await rendererSnapshot(page, sibling);
    const retired = await page.evaluate(id =>
      window.ravenroot.workspace.find(id).pendingElasticLayoutToken.generation, target);

    await page.evaluate(id => {
      window.ravenroot.activateDocument(id);
      document.querySelector('#btn-design').click();
    }, target);
    await page.waitForTimeout(750);

    expect(await page.evaluate(([id, generation]) => {
      const owner = window.ravenroot.workspace.find(id);
      return {
        layoutMode: owner.layoutMode,
        rendererKind: owner.renderer.kind,
        pending: owner.pendingElasticLayoutToken ?? null,
        generationAdvanced: owner.layoutSessionToken.generation > generation,
        hosts: owner.pane.querySelectorAll('.doc-elastic-host').length,
      };
    }, [target, retired])).toEqual({
      layoutMode: 'cyto',
      rendererKind: 'cytoscape',
      pending: null,
      generationAdvanced: true,
      hosts: 0,
    });
    expect(await rendererSnapshot(page, sibling)).toEqual(siblingBefore);
    expect(errors).toEqual([]);
  });

  // Routed through prepareElasticPair()'s keyboard shortcuts -- see the comment there.
  test('closing an active Elastic target retires only its D3 host, simulation, and callbacks', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const { sibling, target } = await prepareElasticPair(page);
    const siblingBefore = await rendererSnapshot(page, sibling);

    const retiredGeneration = await page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      const simulation = owner.renderer.simulation;
      const originalStop = simulation.stop;
      window.__retiredElastic = {
        renderer: owner.renderer,
        host: owner.renderer.host,
        svg: owner.renderer.svg,
        cy: owner.cy,
        tick: simulation.on('tick'),
        stopCalls: 0,
      };
      simulation.stop = function (...args) {
        window.__retiredElastic.stopCalls += 1;
        return originalStop.apply(this, args);
      };
      return owner.renderer.token.generation;
    }, target);

    // The same clean-close route a user takes from the document switcher.
    await page.locator('#document-switcher').click();
    await page.locator(`[data-document-close="${target}"]`).click();
    await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id), target)).toBeNull();
    await page.evaluate(() => window.__retiredElastic.tick());
    await page.waitForTimeout(750); // a stale D3 tick and queued callbacks have time to attempt publication

    expect(await page.evaluate(([id, generation]) => ({
      ownerAbsent: window.ravenroot.workspace.find(id) === null,
      targetHosts: document.querySelectorAll(`.doc-elastic-host[data-document-id="${id}"]`).length,
      targetSvgs: document.querySelectorAll(`.d3-elastic[data-document-id="${id}"]`).length,
      hostConnected: window.__retiredElastic.host.isConnected,
      svgConnected: window.__retiredElastic.svg.isConnected,
      simulationReleased: window.__retiredElastic.renderer.simulation === null,
      simulationStopCalls: window.__retiredElastic.stopCalls,
      cyDestroyed: window.__retiredElastic.cy.destroyed(),
      retiredGeneration: window.__retiredElastic.renderer.token.generation,
      expectedGeneration: generation,
    }), [target, retiredGeneration])).toEqual({
      ownerAbsent: true,
      targetHosts: 0,
      targetSvgs: 0,
      hostConnected: false,
      svgConnected: false,
      simulationReleased: true,
      simulationStopCalls: expect.any(Number),
      cyDestroyed: true,
      retiredGeneration,
      expectedGeneration: retiredGeneration,
    });
    expect(await page.evaluate(() => window.__retiredElastic.stopCalls)).toBeGreaterThanOrEqual(1);
    expect(await rendererSnapshot(page, sibling)).toEqual(siblingBefore);
    expect(errors).toEqual([]);
  });

  // Routed through prepareElasticPair()'s keyboard shortcuts -- see the comment there.
  test('replacing an active Elastic target cannot publish stale work into replacement or sibling', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const { sibling, target } = await prepareElasticPair(page);
    const siblingBefore = await rendererSnapshot(page, sibling);
    const retiredGeneration = await page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      const simulation = owner.renderer.simulation;
      const originalStop = simulation.stop;
      window.__retiredElastic = {
        renderer: owner.renderer,
        host: owner.renderer.host,
        svg: owner.renderer.svg,
        cy: owner.cy,
        tick: simulation.on('tick'),
        stopCalls: 0,
      };
      simulation.stop = function (...args) {
        window.__retiredElastic.stopCalls += 1;
        return originalStop.apply(this, args);
      };
      return owner.renderer.token.generation;
    }, target);

    // Exercise the real replacement file input rather than calling a test-only seam.
    await page.locator('#replace-file-inp').setInputFiles({
      name: 'replacement.graphml',
      mimeType: 'application/xml',
      buffer: Buffer.from(graphMl),
    });
    await expect.poll(() => page.evaluate(id =>
      window.ravenroot.workspace.find(id)?.name, target)).toBe('replacement.graphml');
    await page.waitForTimeout(350);
    const replacementSettled = await rendererSnapshot(page, target);
    await page.evaluate(() => window.__retiredElastic.tick());
    await page.waitForTimeout(750); // a stale D3 tick and queued callbacks cannot cross generations

    expect(await page.evaluate(([id, generation]) => {
      const replacement = window.ravenroot.workspace.find(id);
      return {
        oldHostConnected: window.__retiredElastic.host.isConnected,
        oldSvgConnected: window.__retiredElastic.svg.isConnected,
        oldSimulationReleased: window.__retiredElastic.renderer.simulation === null,
        oldSimulationStopCalls: window.__retiredElastic.stopCalls,
        oldCyDestroyed: window.__retiredElastic.cy.destroyed(),
        oldGeneration: window.__retiredElastic.renderer.token.generation,
        elasticHostsForTarget: replacement.pane.querySelectorAll('.doc-elastic-host').length,
        replacementKind: replacement.renderer.kind,
        replacementGeneration: replacement.renderer.token.generation,
        replacementHostOwned: replacement.pane.contains(replacement.renderer.host),
        replacementCyLive: !replacement.cy.destroyed(),
        generationChanged: replacement.renderer.token.generation !== generation,
      };
    }, [target, retiredGeneration])).toEqual({
      oldHostConnected: false,
      oldSvgConnected: false,
      oldSimulationReleased: true,
      oldSimulationStopCalls: expect.any(Number),
      oldCyDestroyed: true,
      oldGeneration: retiredGeneration,
      elasticHostsForTarget: 0,
      replacementKind: 'cytoscape',
      replacementGeneration: expect.any(Number),
      replacementHostOwned: true,
      replacementCyLive: true,
      generationChanged: true,
    });
    expect(await page.evaluate(() => window.__retiredElastic.stopCalls)).toBeGreaterThanOrEqual(1);
    expect(await rendererSnapshot(page, target)).toEqual(replacementSettled);
    expect(await rendererSnapshot(page, sibling)).toEqual(siblingBefore);
    expect(errors).toEqual([]);
  });

  test('a visible background Elastic pane keeps its renderer across focus, close, and replace elsewhere', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await openSettledDocument(page, 'elastic.graphml');

    await page.evaluate(([backgroundId, elasticId]) => {
      window.ravenroot.activateDocument(backgroundId);
      document.querySelector('#btn-design').click();
      window.ravenroot.activateDocument(elasticId);
      document.querySelector('#btn-monitoring').click();
    }, [first, second]);
    await page.waitForTimeout(350);

    const evidence = await page.evaluate(async ([backgroundId, elasticId]) => {
      const workspace = window.ravenroot.workspace;
      const background = workspace.find(backgroundId);
      const before = background.cy.nodes().map(node => ({ ...node.position() }));
      const elastic = workspace.find(elasticId);
      const generation = elastic.renderer.token.generation;
      window.ravenroot.activateDocument(backgroundId);
      await new Promise(resolve => requestAnimationFrame(resolve));
      return {
        backgroundWasRunning: Boolean(background.cy.scratch('_rrLayoutRunning')),
        backgroundMoved: background.cy.nodes().some((node, index) => {
          const position = node.position();
          return position.x !== before[index].x || position.y !== before[index].y;
        }),
        elasticShown: elastic.pane.classList.contains('doc-pane--shown'),
        elasticClass: elastic.container.classList.contains('doc-canvas--elastic'),
        generation,
        generationAfterFocus: elastic.renderer.token.generation,
        owner: elastic.pane.querySelector('.d3-elastic')?.dataset.documentId,
        svgNodes: elastic.pane.querySelectorAll('.d3-nodes circle').length,
        hostSize: [elastic.renderer.host.clientWidth, elastic.renderer.host.clientHeight],
      };
    }, [first, second]);

    expect(evidence.backgroundWasRunning).toBe(true);
    expect(evidence.elasticShown).toBe(true);
    expect(evidence.elasticClass).toBe(true);
    expect(evidence.generationAfterFocus).toBe(evidence.generation);
    expect(evidence.owner).toBe(second);
    expect(evidence.svgNodes).toBeGreaterThan(0);
    expect(evidence.hostSize.every(value => value > 0)).toBe(true);

    // Closing the active Cose document and replacing a later active sibling must not retire the
    // background Elastic token, host, or simulation.
    await settleActiveDocument(page);
    await page.evaluate(id => window.ravenroot.closeDocument(id), first);
    const replacementTarget = await openSettledDocument(page, 'replacement-target.graphml');
    await page.evaluate(([id, text]) => {
      window.ravenroot.activateDocument(id);
      window.ravenroot.replaceActiveDocumentFromText(text, 'replacement.graphml');
    }, [replacementTarget, graphMl]);
    await expect.poll(() => page.evaluate(([id, generation]) => {
      const owner = window.ravenroot.workspace.find(id);
      return {
        generation: owner.renderer.token.generation,
        hostConnected: owner.renderer.host.isConnected,
        active: owner.renderer.svg.classList.contains('active'),
      };
    }, [second, evidence.generation])).toEqual({
      generation: evidence.generation,
      hostConnected: true,
      active: true,
    });
    expect(errors).toEqual([]);
  });

  test('responsive fallback suspends only the hidden Elastic pane and resumes it without changing mode', async ({ page }) => {
    const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const second = await openSettledDocument(page, 'responsive.graphml');
    await page.setViewportSize({ width: 1280, height: 800 });
    await expect.poll(() => page.evaluate(() =>
      document.querySelectorAll('.doc-pane--shown').length)).toBe(1);

    await page.evaluate(id => {
      window.ravenroot.activateDocument(id);
      document.querySelector('#btn-monitoring').click();
    }, first);
    const generation = await page.evaluate(id => window.ravenroot.workspace.find(id).renderer.token.generation, first);
    // The template's own circle count, read from the renderer right after it comes up -- what
    // "resumes without changing mode" means is that this count survives the suspend/resume
    // cycle below unchanged, not that it equals any particular number.
    const expectedNodeCount = await page.evaluate(id =>
      window.ravenroot.workspace.find(id).renderer.svg.querySelectorAll('.d3-nodes circle').length, first);
    await page.evaluate(id => window.ravenroot.activateDocument(id), second);
    await expect.poll(() => page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      return {
        layout: owner.layoutMode,
        suspended: owner.renderer.host.classList.contains('suspended'),
        generation: owner.renderer.token.generation,
      };
    }, first)).toEqual({ layout: 'elastic', suspended: true, generation });
    await page.setViewportSize(WIDE);

    for (const mode of ['horizontal', 'vertical', 'grid']) {
      await page.evaluate(value => window.ravenroot.setWorkspaceLayout(value), mode);
      await expect.poll(() => page.evaluate(() =>
        [...document.querySelectorAll('.doc-pane--shown')].map(pane => pane.dataset.documentId)))
        .toEqual([first, second]);
      await expect.poll(() => paintedPixels(page, second)).toBeGreaterThan(0);
      await expect.poll(() => page.evaluate(id => {
        const owner = window.ravenroot.workspace.find(id);
        return {
          layout: owner.layoutMode,
          suspended: owner.renderer.host.classList.contains('suspended'),
          generation: owner.renderer.token.generation,
          nodes: owner.renderer.svg.querySelectorAll('.d3-nodes circle').length,
        };
      }, first)).toEqual({ layout: 'elastic', suspended: false, generation, nodes: expectedNodeCount });
    }
  });
});

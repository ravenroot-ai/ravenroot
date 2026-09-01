import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const LARGE = { width: 2400, height: 1800 };
// Was `test/fixtures/edge-authoring.graphml`, the golden fixture `test/edge-fixture.test.js`
// regenerates from the editor's own edge-authoring entry points (UI-02) -- an unrelated
// surface this suite doesn't exercise, only shared for "any loadable document". Growing that
// fixture (4 to 6 nodes) broke this suite's node-id assertions as a side effect. This file's
// own `lifecycle-document.graphml` is asserted freely because it belongs to this suite alone.
const graphMl = readFileSync(new URL('../test/fixtures/lifecycle-document.graphml', import.meta.url));
const rejectedGraphMl = readFileSync(new URL(
  '../../ravenroot-core/src/test/resources/graphml-corpus/rejected/dangling-edge.graphml',
  import.meta.url,
));

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

async function openGraphMl(page, name, buffer = graphMl) {
  await page.locator('#file-inp').setInputFiles({
    name,
    mimeType: 'application/xml',
    buffer,
  });
  await expect.poll(() => page.evaluate(expected => {
    const active = window.ravenroot.activeDocument();
    return active?.name === expected && !active.cy.scratch('_rrLayoutRunning');
  }, name)).toBe(true);
  return page.evaluate(() => window.ravenroot.activeDocument().id);
}

async function replaceFromFile(page, name = 'replacement.graphml', buffer = graphMl) {
  await page.locator('#replace-file-inp').setInputFiles({
    name,
    mimeType: 'application/xml',
    buffer,
  });
}

async function makeActiveDirty(page, name = 'Sibling-only edit') {
  if (await page.locator('#btn-modify').getAttribute('aria-pressed') !== 'true') {
    await page.locator('#btn-modify').click();
  }
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill(name);
  await page.locator('#node-editor button[type="submit"]').click();
  await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');
}

const paintedPixels = (page, documentId) => page.evaluate(id => {
  const pane = window.ravenroot.workspace.find(id).pane;
  return [...pane.querySelectorAll('canvas')].reduce((total, canvas) => {
    if (!canvas.width || !canvas.height) return total;
    const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
    for (let index = 3; index < pixels.length; index += 4) if (pixels[index]) total += 1;
    return total;
  }, 0);
}, documentId);

async function prepareWorkspace(page, mode = 'horizontal') {
  await page.goto('/');
  const left = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const target = await openGraphMl(page, 'target-old.graphml');
  const right = await openGraphMl(page, 'right.graphml');

  await page.evaluate(id => window.ravenroot.activateDocument(id), right);
  await makeActiveDirty(page);
  await page.locator('#btn-design').click();
  await expect.poll(() => page.evaluate(id =>
    Boolean(window.ravenroot.workspace.find(id).cy.scratch('_rrLayoutRunning')), right)).toBe(false);

  await page.evaluate(id => window.ravenroot.activateDocument(id), left);
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => page.evaluate(id => {
    const owner = window.ravenroot.workspace.find(id);
    return Boolean(owner.renderer?.kind === 'elastic' && owner.renderer.host.isConnected
      && owner.renderer.svg.isConnected && owner.renderer.simulation);
  }, left)).toBe(true);

  await page.evaluate(([leftId, targetId, rightId, layout]) => {
    const workspace = window.ravenroot.workspace;
    for (const [id, suffix] of [[leftId, 'left'], [targetId, 'target'], [rightId, 'right']]) {
      const owner = workspace.find(id);
      owner.execution.executionId = `execution-${suffix}`;
      owner.execution.graphVersion = `version-${suffix}`;
      owner.execution.finished.add(`finished-${suffix}`);
      owner.cy.zoom(0.82 + suffix.length / 100);
      owner.cy.pan({ x: 20 + suffix.length, y: 30 + suffix.length });
    }
    window.ravenroot.activateDocument(targetId);
    window.ravenroot.setWorkspaceLayout(layout);
  }, [left, target, right, mode]);
  await expect.poll(() => page.locator('#cy .doc-pane.doc-pane--shown').count()).toBe(3);

  const splitters = page.locator('#cy .workspace-splitter:not([hidden])');
  const splitterCount = await splitters.count();
  expect(splitterCount).toBeGreaterThan(0);
  for (let index = 0; index < splitterCount; index += 1) {
    const splitter = splitters.nth(index);
    const axis = await splitter.getAttribute('data-axis');
    await splitter.focus();
    await page.keyboard.press(axis === 'column' ? 'ArrowRight' : 'ArrowDown');
  }

  return { left, target, right };
}

async function captureBaseline(page) {
  await page.evaluate(() => {
    const documents = window.ravenroot.workspace.documents;
    window.__replaceBaseline = {
      activeId: window.ravenroot.workspace.activeId,
      order: documents.map(owner => owner.id),
      layout: window.ravenroot.workspaceLayout(),
      documents: Object.fromEntries(documents.map(owner => [owner.id, {
        record: owner,
        name: owner.name,
        displayName: owner.displayName,
        graph: owner.graph,
        graphJson: JSON.stringify(owner.graph),
        cy: owner.cy,
        renderer: owner.renderer,
        rendererToken: owner.renderer.token,
        rendererHost: owner.renderer.host,
        rendererNodes: owner.renderer.nodes ?? null,
        layoutMode: owner.layoutMode,
        zoom: owner.cy.zoom(),
        pan: owner.cy.pan(),
        history: owner.history,
        dirty: owner.history.isDirty(),
        execution: owner.execution,
        executionId: owner.execution.executionId,
        graphVersion: owner.execution.graphVersion,
        finished: [...owner.execution.finished],
        pane: owner.pane,
        container: owner.container,
        slot: [owner.pane.dataset.workspaceColumn, owner.pane.dataset.workspaceRow],
      }])),
    };
  });
}

async function siblingIdentityEvidence(page, siblingIds) {
  return page.evaluate(ids => {
    const baseline = window.__replaceBaseline;
    return ids.map(id => {
      const owner = window.ravenroot.workspace.find(id);
      const before = baseline.documents[id];
      return {
        id,
        record: owner === before.record,
        name: owner.name === before.name && owner.displayName === before.displayName,
        graph: owner.graph === before.graph && JSON.stringify(owner.graph) === before.graphJson,
        cy: owner.cy === before.cy && !owner.cy.destroyed(),
        renderer: owner.renderer === before.renderer && owner.renderer.token === before.rendererToken,
        host: owner.renderer.host === before.rendererHost && before.rendererHost.isConnected
          && owner.pane.contains(before.rendererHost),
        rendererNodes: (owner.renderer.nodes ?? null) === before.rendererNodes,
        layout: owner.layoutMode === before.layoutMode,
        viewport: owner.cy.zoom() === before.zoom && JSON.stringify(owner.cy.pan()) === JSON.stringify(before.pan),
        history: owner.history === before.history && owner.history.isDirty() === before.dirty,
        execution: owner.execution === before.execution
          && owner.execution.executionId === before.executionId
          && owner.execution.graphVersion === before.graphVersion
          && JSON.stringify([...owner.execution.finished]) === JSON.stringify(before.finished),
        pane: owner.pane === before.pane && owner.container === before.container
          && JSON.stringify([owner.pane.dataset.workspaceColumn, owner.pane.dataset.workspaceRow])
            === JSON.stringify(before.slot),
      };
    });
  }, siblingIds);
}

async function expectSiblingIdentity(page, siblingIds) {
  const evidence = await siblingIdentityEvidence(page, siblingIds);
  for (const sibling of evidence) {
    expect(sibling).toEqual({
      id: sibling.id,
      record: true,
      name: true,
      graph: true,
      cy: true,
      renderer: true,
      host: true,
      rendererNodes: true,
      layout: true,
      viewport: true,
      history: true,
      execution: true,
      pane: true,
    });
  }
}

async function workspaceIdentityEvidence(page) {
  return page.evaluate(() => {
    const baseline = window.__replaceBaseline;
    const documents = window.ravenroot.workspace.documents;
    return {
      activeId: window.ravenroot.workspace.activeId,
      order: documents.map(owner => owner.id),
      size: documents.length,
      documents: documents.map(owner => {
        const before = baseline.documents[owner.id];
        return before && owner === before.record && owner.name === before.name
          && owner.graph === before.graph && owner.cy === before.cy && owner.renderer === before.renderer
          && owner.history === before.history && owner.execution === before.execution
          && owner.pane === before.pane && owner.container === before.container;
      }),
    };
  });
}

test.describe('Replace active in a multi-document workspace', () => {
  test.use({ viewport: LARGE });

  test.beforeEach(async ({ page }) => {
    await stubService(page);
  });

  for (const mode of ['horizontal', 'vertical', 'grid']) {
    test(`replaces only the central target in ${mode}`, async ({ page }) => {
      const errors = [];
      page.on('pageerror', error => errors.push(String(error)));
      const { left, target, right } = await prepareWorkspace(page, mode);
      await captureBaseline(page);

      await replaceFromFile(page);
      await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id)?.name, target))
        .toBe('replacement.graphml');

      expect(await page.evaluate(id => {
        const owner = window.ravenroot.workspace.find(id);
        const baseline = window.__replaceBaseline;
        const before = baseline.documents[id];
        const layout = window.ravenroot.workspaceLayout();
        return {
          size: window.ravenroot.workspace.size,
          order: window.ravenroot.workspace.documents.map(item => item.id),
          activeId: window.ravenroot.workspace.activeId,
          record: owner === before.record,
          pane: owner.pane === before.pane && owner.container === before.container,
          slot: [owner.pane.dataset.workspaceColumn, owner.pane.dataset.workspaceRow],
          oldSlot: before.slot,
          shares: [layout.columnShares, layout.rowShares],
          oldShares: [baseline.layout.columnShares, baseline.layout.rowShares],
          newCy: owner.cy !== before.cy && !owner.cy.destroyed(),
          oldCyDestroyed: before.cy.destroyed(),
          newRenderer: owner.renderer !== before.renderer && owner.pane.contains(owner.renderer.host),
          oldRendererCurrent: owner.renderer.token === before.rendererToken,
          name: owner.name,
          nodes: owner.cy.nodes().map(node => node.id()).sort(),
          history: owner.history !== before.history && !owner.history.isDirty(),
          executionDetached: owner.execution === before.execution
            && owner.execution.executionId === null
            && owner.execution.graphVersion === null
            && owner.execution.finished.size === 0,
        };
      }, target)).toEqual({
        size: 3,
        order: [left, target, right],
        activeId: target,
        record: true,
        pane: true,
        slot: expect.any(Array),
        oldSlot: expect.any(Array),
        shares: expect.any(Array),
        oldShares: expect.any(Array),
        newCy: true,
        oldCyDestroyed: true,
        newRenderer: true,
        oldRendererCurrent: false,
        name: 'replacement.graphml',
        nodes: ['archive', 'end', 'review', 'start'],
        history: true,
        executionDetached: true,
      });
      expect(await page.evaluate(() => {
        const baseline = window.__replaceBaseline;
        const owner = window.ravenroot.workspace.active;
        const layout = window.ravenroot.workspaceLayout();
        return {
          slot: [owner.pane.dataset.workspaceColumn, owner.pane.dataset.workspaceRow],
          oldSlot: baseline.documents[owner.id].slot,
          shares: [layout.columnShares, layout.rowShares],
          oldShares: [baseline.layout.columnShares, baseline.layout.rowShares],
        };
      })).toEqual(expect.objectContaining({
        slot: expect.any(Array), oldSlot: expect.any(Array), shares: expect.any(Array), oldShares: expect.any(Array),
      }));
      expect(await page.evaluate(() => {
        const baseline = window.__replaceBaseline;
        const owner = window.ravenroot.workspace.active;
        const layout = window.ravenroot.workspaceLayout();
        return JSON.stringify([owner.pane.dataset.workspaceColumn, owner.pane.dataset.workspaceRow])
            === JSON.stringify(baseline.documents[owner.id].slot)
          && JSON.stringify([layout.columnShares, layout.rowShares])
            === JSON.stringify([baseline.layout.columnShares, baseline.layout.rowShares]);
      })).toBe(true);
      await expectSiblingIdentity(page, [left, right]);

      await page.locator('#document-switcher').click();
      await expect(page.locator('[data-document-row]')).toHaveCount(3);
      expect(await page.locator('[data-document-activate]').evaluateAll(items =>
        items.map(item => item.getAttribute('data-document-activate')))).toEqual([left, target, right]);
      await page.keyboard.press('Escape');

      await expect.poll(() => paintedPixels(page, target), { timeout: 15_000 }).toBeGreaterThan(0);
      await expect.poll(() => paintedPixels(page, right), { timeout: 15_000 }).toBeGreaterThan(0);
      expect(await page.locator(`.doc-pane[data-document-id="${left}"] .d3-nodes circle`).count()).toBeGreaterThan(0);
      expect(errors).toEqual([]);
    });
  }

  test('retires only an Elastic target renderer and cannot disturb its Elastic sibling', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const { left, target, right } = await prepareWorkspace(page, 'horizontal');
    await page.locator('#btn-monitoring').click();
    await expect.poll(() => page.evaluate(id =>
      window.ravenroot.workspace.find(id).renderer.kind, target)).toBe('elastic');
    await captureBaseline(page);
    await page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      const simulation = owner.renderer.simulation;
      const originalStop = simulation.stop;
      window.__replaceRetired = { renderer: owner.renderer, cy: owner.cy, tick: simulation.on('tick'), stopCalls: 0 };
      simulation.stop = function (...args) {
        window.__replaceRetired.stopCalls += 1;
        return originalStop.apply(this, args);
      };
    }, target);

    await replaceFromFile(page, 'elastic-replacement.graphml');
    await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id).name, target))
      .toBe('elastic-replacement.graphml');
    await page.evaluate(() => window.__replaceRetired.tick());
    await page.waitForTimeout(750);

    expect(await page.evaluate(id => ({
      hostConnected: window.__replaceRetired.renderer.host.isConnected,
      simulationReleased: window.__replaceRetired.renderer.simulation === null,
      stopCalls: window.__replaceRetired.stopCalls,
      cyDestroyed: window.__replaceRetired.cy.destroyed(),
      targetHosts: window.ravenroot.workspace.find(id).pane.querySelectorAll('.doc-elastic-host').length,
    }), target)).toEqual({
      hostConnected: false,
      simulationReleased: true,
      stopCalls: expect.any(Number),
      cyDestroyed: true,
      targetHosts: 0,
    });
    expect(await page.evaluate(() => window.__replaceRetired.stopCalls)).toBeGreaterThanOrEqual(1);
    await expectSiblingIdentity(page, [left, right]);
    expect(errors).toEqual([]);
  });

  test('dirty cancel and target drift are atomic, then confirm replaces the bound target', async ({ page }) => {
    const { left, target, right } = await prepareWorkspace(page, 'grid');
    await makeActiveDirty(page, 'Target edit');
    await captureBaseline(page);

    await replaceFromFile(page, 'dirty-replacement.graphml');
    await expect(page.locator('#unsaved-document-dialog')).toHaveAttribute('open', '');
    await page.locator('[data-unsaved-action="cancel"]').click();
    expect(await workspaceIdentityEvidence(page)).toEqual({
      activeId: target, order: [left, target, right], size: 3, documents: [true, true, true],
    });

    await replaceFromFile(page, 'must-not-replace-sibling.graphml');
    await expect(page.locator('#unsaved-document-dialog')).toHaveAttribute('open', '');
    await page.evaluate(id => window.ravenroot.activateDocument(id), right);
    await page.locator('[data-unsaved-action="discard"]').click();
    await page.waitForTimeout(100);
    expect(await page.evaluate(([targetId, siblingId]) => ({
      activeId: window.ravenroot.workspace.activeId,
      targetName: window.ravenroot.workspace.find(targetId).name,
      targetDirty: window.ravenroot.workspace.find(targetId).history.isDirty(),
      siblingName: window.ravenroot.workspace.find(siblingId).name,
    }), [target, right])).toEqual({
      activeId: right,
      targetName: 'target-old.graphml',
      targetDirty: true,
      siblingName: 'right.graphml',
    });
    await expectSiblingIdentity(page, [left, right]);

    await page.evaluate(id => window.ravenroot.activateDocument(id), target);
    await replaceFromFile(page, 'dirty-replacement.graphml');
    await page.locator('[data-unsaved-action="discard"]').click();
    await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id).name, target))
      .toBe('dirty-replacement.graphml');
    expect(await page.evaluate(() => window.ravenroot.workspace.size)).toBe(3);
    await expectSiblingIdentity(page, [left, right]);
  });

  test('Save and Replace is an atomic no-op when the bound target becomes background', async ({ page }) => {
    const downloads = [];
    page.on('download', download => downloads.push(download.suggestedFilename()));
    const { left, target, right } = await prepareWorkspace(page, 'grid');
    await makeActiveDirty(page, 'Target edit before focus drift');
    await captureBaseline(page);

    await replaceFromFile(page, 'must-not-save-or-replace.graphml');
    await expect(page.locator('#unsaved-document-dialog')).toHaveAttribute('open', '');
    await page.evaluate(id => window.ravenroot.activateDocument(id), right);
    await page.locator('[data-unsaved-action="save"]').click();
    await expect(page.locator('#unsaved-document-dialog')).not.toHaveAttribute('open', '');
    await page.waitForTimeout(250);

    expect(downloads).toEqual([]);
    expect(await workspaceIdentityEvidence(page)).toEqual({
      activeId: right, order: [left, target, right], size: 3, documents: [true, true, true],
    });
    await expectSiblingIdentity(page, [left, target, right]);
    expect(await page.evaluate(id => window.ravenroot.workspace.find(id).history.isDirty(), target)).toBe(true);
    expect(await page.evaluate(() => {
      const baseline = window.__replaceBaseline;
      const layout = window.ravenroot.workspaceLayout();
      return JSON.stringify([layout.columnShares, layout.rowShares])
        === JSON.stringify([baseline.layout.columnShares, baseline.layout.rowShares]);
    })).toBe(true);
  });

  test('Save and Replace downloads once and replaces only the still-active target', async ({ page }) => {
    const downloads = [];
    page.on('download', download => downloads.push(download.suggestedFilename()));
    const { left, target, right } = await prepareWorkspace(page, 'horizontal');
    await makeActiveDirty(page, 'Target edit to save');
    await captureBaseline(page);

    await replaceFromFile(page, 'saved-replacement.graphml');
    await expect(page.locator('#unsaved-document-dialog')).toHaveAttribute('open', '');
    const downloadPromise = page.waitForEvent('download');
    await page.locator('[data-unsaved-action="save"]').click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('target-old.graphml');
    await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id)?.name, target))
      .toBe('saved-replacement.graphml');
    await page.waitForTimeout(100);

    expect(downloads).toEqual(['target-old.graphml']);
    expect(await page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      const before = window.__replaceBaseline.documents[id];
      const layout = window.ravenroot.workspaceLayout();
      return {
        size: window.ravenroot.workspace.size,
        order: window.ravenroot.workspace.documents.map(document_ => document_.id),
        activeId: window.ravenroot.workspace.activeId,
        record: owner === before.record,
        pane: owner.pane === before.pane && owner.container === before.container,
        slot: [owner.pane.dataset.workspaceColumn, owner.pane.dataset.workspaceRow],
        oldSlot: before.slot,
        shares: [layout.columnShares, layout.rowShares],
        oldShares: [window.__replaceBaseline.layout.columnShares, window.__replaceBaseline.layout.rowShares],
        oldCyDestroyed: before.cy.destroyed(),
        newCy: owner.cy !== before.cy && !owner.cy.destroyed(),
        newHistory: owner.history !== before.history && !owner.history.isDirty(),
      };
    }, target)).toEqual({
      size: 3,
      order: [left, target, right],
      activeId: target,
      record: true,
      pane: true,
      slot: expect.any(Array),
      oldSlot: expect.any(Array),
      shares: expect.any(Array),
      oldShares: expect.any(Array),
      oldCyDestroyed: true,
      newCy: true,
      newHistory: true,
    });
    expect(await page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      const baseline = window.__replaceBaseline;
      const layout = window.ravenroot.workspaceLayout();
      return JSON.stringify([owner.pane.dataset.workspaceColumn, owner.pane.dataset.workspaceRow])
          === JSON.stringify(baseline.documents[id].slot)
        && JSON.stringify([layout.columnShares, layout.rowShares])
          === JSON.stringify([baseline.layout.columnShares, baseline.layout.rowShares]);
    }, target)).toBe(true);
    await expectSiblingIdentity(page, [left, right]);
  });

  test('Save and Replace safely resolves without stale mutation after the bound target closes', async ({ page }) => {
    const downloads = [];
    page.on('download', download => downloads.push(download.suggestedFilename()));
    const { left, target, right } = await prepareWorkspace(page, 'vertical');
    await makeActiveDirty(page, 'Target edit before close');

    await replaceFromFile(page, 'must-not-replace-closed.graphml');
    await expect(page.locator('#unsaved-document-dialog')).toHaveAttribute('open', '');
    await page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      window.__closedReplaceTarget = {
        owner,
        name: owner.name,
        graph: owner.graph,
        graphJson: JSON.stringify(owner.graph),
        history: owner.history,
      };
      window.ravenroot.closeDocument(id);
      const layout = window.ravenroot.workspaceLayout();
      window.__workspaceAfterTargetClose = {
        activeId: window.ravenroot.workspace.activeId,
        documents: window.ravenroot.workspace.documents.map(document_ => ({
          owner: document_, name: document_.name, graph: document_.graph, cy: document_.cy,
          renderer: document_.renderer, history: document_.history, execution: document_.execution,
          pane: document_.pane, container: document_.container,
        })),
        layout: JSON.stringify([layout.mode, layout.columnShares, layout.rowShares]),
      };
    }, target);

    await page.locator('[data-unsaved-action="save"]').click();
    await expect(page.locator('#unsaved-document-dialog')).not.toHaveAttribute('open', '');
    await page.waitForTimeout(250);

    expect(downloads).toEqual([]);
    expect(await page.evaluate(() => {
      const stale = window.__closedReplaceTarget;
      const afterClose = window.__workspaceAfterTargetClose;
      const layout = window.ravenroot.workspaceLayout();
      return {
        staleName: stale.owner.name === stale.name,
        staleGraph: stale.owner.graph === stale.graph && JSON.stringify(stale.owner.graph) === stale.graphJson,
        staleHistory: stale.owner.history === stale.history && stale.owner.history.isDirty(),
        activeStable: window.ravenroot.workspace.activeId === afterClose.activeId,
        remaining: window.ravenroot.workspace.documents.map((document_, index) => {
          const before = afterClose.documents[index];
          return document_ === before.owner && document_.name === before.name
            && document_.graph === before.graph && document_.cy === before.cy
            && document_.renderer === before.renderer && document_.history === before.history
            && document_.execution === before.execution && document_.pane === before.pane
            && document_.container === before.container;
        }),
        layout: JSON.stringify([layout.mode, layout.columnShares, layout.rowShares]) === afterClose.layout,
      };
    })).toEqual({
      staleName: true,
      staleGraph: true,
      staleHistory: true,
      activeStable: true,
      remaining: [true, true],
      layout: true,
    });
    expect(await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id)))
      .toEqual([left, right]);
  });

  test('malformed and semantically rejected files are whole-workspace no-ops', async ({ page }) => {
    const { left, target, right } = await prepareWorkspace(page, 'vertical');
    await captureBaseline(page);

    for (const candidate of [
      { name: 'malformed.graphml', buffer: Buffer.from('<graphml><broken>') },
      { name: 'dangling-edge.graphml', buffer: rejectedGraphMl },
    ]) {
      const alert = page.waitForEvent('dialog');
      await replaceFromFile(page, candidate.name, candidate.buffer);
      const dialog = await alert;
      expect(dialog.message()).toContain('Error:');
      await dialog.accept();
      await expect(page.locator('#unsaved-document-dialog')).not.toHaveAttribute('open', '');
      expect(await workspaceIdentityEvidence(page)).toEqual({
        activeId: target, order: [left, target, right], size: 3, documents: [true, true, true],
      });
      await expectSiblingIdentity(page, [left, right]);
    }
  });

  test('Open after replace adds N+1 and close, focus, and layout remain operational', async ({ page }) => {
    const { left, target, right } = await prepareWorkspace(page, 'horizontal');
    await captureBaseline(page);
    await replaceFromFile(page, 'lifecycle-replacement.graphml');
    await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id).name, target))
      .toBe('lifecycle-replacement.graphml');
    await expectSiblingIdentity(page, [left, right]);

    const fourth = await openGraphMl(page, 'opened-after-replace.graphml');
    expect(await page.evaluate(() => window.ravenroot.workspace.size)).toBe(4);
    await page.evaluate(id => window.ravenroot.activateDocument(id), target);
    await page.locator('#btn-design').click();
    await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id).layoutMode, target)).toBe('cyto');
    await page.evaluate(id => window.ravenroot.activateDocument(id), left);
    await page.locator('#document-switcher').click();
    await page.locator(`[data-document-close="${target}"]`).click();
    await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id), target)).toBeNull();

    expect(await page.evaluate(() => ({
      size: window.ravenroot.workspace.size,
      activeId: window.ravenroot.workspace.activeId,
      order: window.ravenroot.workspace.documents.map(owner => owner.id),
    }))).toEqual({ size: 3, activeId: left, order: [left, right, fourth] });
    expect(await page.evaluate(id => window.ravenroot.workspace.find(id).renderer.kind, left)).toBe('elastic');
  });
});

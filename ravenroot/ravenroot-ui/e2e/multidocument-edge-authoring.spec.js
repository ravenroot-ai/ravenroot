import { readFileSync } from 'node:fs';

import { expect, test } from '@playwright/test';

const WIDE = { width: 1800, height: 1000 };
const replacementGraphMl = readFileSync(
  new URL('../test/fixtures/edge-authoring.graphml', import.meta.url), 'utf8');

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

async function openTwinDocuments(page, layout = 'horizontal') {
  await stubService(page);
  await page.goto('/');
  await page.waitForFunction(() => window.ravenroot?.activeDocument);
  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'second.graphml' }));
  await page.evaluate(mode => window.ravenroot.setWorkspaceLayout(mode), layout);
  await page.waitForTimeout(800);
  await page.evaluate(ids => {
    ids.forEach(id => {
      const owner = window.ravenroot.workspace.find(id);
      owner.cy.stop();
      owner.cy.getElementById('start').position({ x: 80, y: 100 });
      owner.cy.getElementById('dosomething').position({ x: 310, y: 100 });
      owner.cy.getElementById('end').position({ x: 540, y: 55 });
      owner.cy.getElementById('error').position({ x: 540, y: 180 });
      owner.cy.fit(undefined, 55);
    });
  }, [first, second]);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  return { first, second };
}

async function activateAndStyle(page, documentId) {
  await page.evaluate(id => window.ravenroot.activateDocument(id), documentId);
  await page.locator('#btn-design').click();
  // A named renderer owns its ELK positioning pass. Pointer preconditions begin only after that
  // document's atomic layout-and-routing transaction releases its canvas.
  await expect(page.locator(`.doc-pane[data-document-id="${documentId}"]`))
    .not.toHaveAttribute('aria-busy', 'true');
}

async function enableEditing(page) {
  if (await page.locator('#btn-modify').getAttribute('aria-pressed') !== 'true') {
    await page.locator('#btn-modify').click();
  }
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
}

async function nodeCentre(page, documentId, nodeId) {
  return page.evaluate(({ documentId: id, nodeId: key }) => {
    const owner = window.ravenroot.workspace.find(id);
    const rect = owner.cy.container().getBoundingClientRect();
    const point = owner.cy.getElementById(key).renderedPosition();
    return { x: rect.left + point.x, y: rect.top + point.y };
  }, { documentId, nodeId });
}

async function stagePoint(page, documentId, modelPosition) {
  return page.evaluate(({ documentId: id, position }) => {
    const owner = window.ravenroot.workspace.find(id);
    const rect = owner.cy.container().getBoundingClientRect();
    const pan = owner.cy.pan();
    const zoom = owner.cy.zoom();
    return {
      x: rect.left + position.x * zoom + pan.x,
      y: rect.top + position.y * zoom + pan.y,
    };
  }, { documentId, position: modelPosition });
}

async function beginDrag(page, documentId, sourceId = 'start', targetId = 'end') {
  const source = await nodeCentre(page, documentId, sourceId);
  const target = await nodeCentre(page, documentId, targetId);
  await page.mouse.move(source.x, source.y);
  await page.mouse.down();
  await page.mouse.move(source.x + (target.x - source.x) * 0.35,
    source.y + (target.y - source.y) * 0.35, { steps: 4 });
  return { source, target };
}

async function finishDrag(page, target) {
  await page.mouse.move(target.x, target.y, { steps: 5 });
  await page.mouse.up();
}

const snapshot = (page, documentId) => page.evaluate(id => {
  if (window.ravenroot.workspace.activeId === id) window.ravenroot.activeDocument();
  const owner = window.ravenroot.workspace.find(id);
  return {
    graphBytes: JSON.stringify(owner.graph),
    edges: owner.graph.edges.map(edge => `${edge.id}:${edge.source}>${edge.target}`).sort(),
    positions: Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()])),
    selected: owner.cy.$(':selected').map(element => element.id()).sort(),
    depth: owner.history.depth(),
    undoLabel: owner.history.undoLabel(),
    renderer: owner.visualStyle,
  };
}, documentId);

const feedback = (page, documentId) => page.evaluate(id => {
  const owner = window.ravenroot.workspace.find(id);
  return {
    source: owner.cy.nodes('.connect-source').map(node => node.id()),
    valid: owner.cy.nodes('.connect-valid').map(node => node.id()),
    invalid: owner.cy.nodes('.connect-invalid').map(node => node.id()),
    reconnecting: owner.cy.edges('.edge-reconnecting').map(edge => edge.id()),
    ghostOn: owner.container.querySelector('.edge-ghost')?.classList.contains('on') ?? false,
    ghostOwner: owner.container.querySelector('.edge-ghost')?.dataset.documentId ?? null,
  };
}, documentId);

test.describe('multidocument edge gesture ownership', () => {
  test.use({ viewport: WIDE });

  test('overlapping ids mutate, preview, inspect, and undo only on the pointer-down owner', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const { first, second } = await openTwinDocuments(page, 'horizontal');
    expect(await page.evaluate(() => window.ravenroot.workspaceLayout().mode)).toBe('horizontal');
    await activateAndStyle(page, first);
    await activateAndStyle(page, second);
    await enableEditing(page);

    const beforeFirst = await snapshot(page, first);
    const beforeSecond = await snapshot(page, second);
    expect(beforeFirst.renderer).toBe('cyto');
    expect(beforeSecond.renderer).toBe('cyto');

    // Panel 2 is active already, but the session must still be rooted from its physical canvas.
    const secondGesture = await beginDrag(page, second);
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-document', second);
    expect((await feedback(page, second)).source).toEqual(['start']);
    expect((await feedback(page, second)).ghostOn).toBe(true);
    expect(await feedback(page, first)).toEqual({
      source: [], valid: [], invalid: [], reconnecting: [], ghostOn: false, ghostOwner: null,
    });
    await finishDrag(page, secondGesture.target);

    const afterSecond = await snapshot(page, second);
    expect(afterSecond.edges).toHaveLength(beforeSecond.edges.length + 1);
    expect(afterSecond.depth).toBe(beforeSecond.depth + 1);
    expect((await snapshot(page, first)).graphBytes).toBe(beforeFirst.graphBytes);
    expect((await snapshot(page, first)).depth).toBe(beforeFirst.depth);
    await expect(page.locator('#info-body')).toContainText('From');
    await expect(page.locator('#activity-log')).toContainText('Connected');

    // The symmetric gesture begins in a background pane. Native capture must activate panel 1
    // before Cytoscape resolves tapstart; no bubbling-listener ordering is allowed to choose doc 2.
    const firstGesture = await beginDrag(page, first);
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-document', first);
    expect(await page.evaluate(() => window.ravenroot.activeDocument().id)).toBe(first);
    expect((await feedback(page, first)).source).toEqual(['start']);
    expect((await feedback(page, first)).ghostOwner).toBe(first);
    expect((await feedback(page, second)).source).toEqual([]);
    await finishDrag(page, firstGesture.target);

    const afterFirst = await snapshot(page, first);
    expect(afterFirst.edges).toHaveLength(beforeFirst.edges.length + 1);
    expect(afterFirst.depth).toBe(beforeFirst.depth + 1);
    expect((await snapshot(page, second)).graphBytes).toBe(afterSecond.graphBytes);
    expect(await page.evaluate(ids => {
      const left = window.ravenroot.workspace.find(ids.first);
      const right = window.ravenroot.workspace.find(ids.second);
      return left.history !== right.history;
    }, { first, second })).toBe(true);

    // Merely activating doc 1 cannot inherit doc 2's undo. Returning to doc 2 and undoing removes
    // only its edge; doc 1 retains both its bytes and its own independent undo step.
    await page.evaluate(id => window.ravenroot.activateDocument(id), second);
    await page.locator('#btn-undo').click();
    expect((await snapshot(page, second)).edges).toEqual(beforeSecond.edges);
    expect((await snapshot(page, second)).depth).toBe(beforeSecond.depth);
    expect((await snapshot(page, first)).graphBytes).toBe(afterFirst.graphBytes);
    expect((await snapshot(page, first)).depth).toBe(afterFirst.depth);
    expect(errors).toEqual([]);
  });

  test('overlapping selected ids move only in their Cyto or N8N document', async ({ page }) => {
    const { first, second } = await openTwinDocuments(page, 'horizontal');
    await activateAndStyle(page, first);
    await activateAndStyle(page, second);
    await enableEditing(page);

    const beforeFirst = await snapshot(page, first);
    const beforeSecond = await snapshot(page, second);
    const secondStart = await nodeCentre(page, second, 'start');
    await page.mouse.click(secondStart.x, secondStart.y);
    await expect.poll(async () => (await snapshot(page, second)).selected).toEqual(['start']);
    await page.mouse.move(secondStart.x, secondStart.y);
    await page.mouse.down();
    await page.mouse.move(secondStart.x + 65, secondStart.y + 40, { steps: 10 });
    await page.mouse.up();

    const movedSecond = await snapshot(page, second);
    expect(movedSecond.positions.start).not.toEqual(beforeSecond.positions.start);
    expect(movedSecond.positions.dosomething).toEqual(beforeSecond.positions.dosomething);
    expect(movedSecond.edges).toEqual(beforeSecond.edges);
    expect(movedSecond.depth).toBe(beforeSecond.depth + 1);
    expect((await snapshot(page, first)).graphBytes).toBe(beforeFirst.graphBytes);
    expect((await snapshot(page, first)).depth).toBe(beforeFirst.depth);

    await page.evaluate(id => window.ravenroot.activateDocument(id), first);
    await enableEditing(page);
    const firstStart = await nodeCentre(page, first, 'start');
    await page.mouse.click(firstStart.x, firstStart.y);
    await expect.poll(async () => (await snapshot(page, first)).selected).toEqual(['start']);
    const secondAfterItsMove = await snapshot(page, second);
    await page.mouse.move(firstStart.x, firstStart.y);
    await page.mouse.down();
    await page.mouse.move(firstStart.x + 55, firstStart.y + 35, { steps: 10 });
    await page.mouse.up();

    const movedFirst = await snapshot(page, first);
    expect(movedFirst.positions.start).not.toEqual(beforeFirst.positions.start);
    expect(movedFirst.positions.dosomething).toEqual(beforeFirst.positions.dosomething);
    expect(movedFirst.edges).toEqual(beforeFirst.edges);
    expect(movedFirst.depth).toBe(beforeFirst.depth + 1);
    expect((await snapshot(page, second)).graphBytes).toBe(secondAfterItsMove.graphBytes);
    expect((await snapshot(page, second)).depth).toBe(secondAfterItsMove.depth);
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
  });

  test('Cyto and N8N stage precedence and coordinates remain document-owned', async ({ page }) => {
    const { first, second } = await openTwinDocuments(page, 'horizontal');
    await activateAndStyle(page, first);
    await activateAndStyle(page, second);
    await enableEditing(page);
    const beforeFirst = await snapshot(page, first);
    const beforeSecond = await snapshot(page, second);
    const secondStart = await nodeCentre(page, second, 'start');
    await page.mouse.click(secondStart.x, secondStart.y);
    await expect.poll(async () => (await snapshot(page, second)).selected).toEqual(['start']);

    const clearModelPosition = { x: 180, y: 300 };
    const clearPoint = await stagePoint(page, second, clearModelPosition);
    await page.mouse.click(clearPoint.x, clearPoint.y);
    expect(await snapshot(page, second)).toMatchObject({
      selected: [], depth: beforeSecond.depth,
    });
    expect((await snapshot(page, second)).graphBytes).toBe(beforeSecond.graphBytes);
    expect((await snapshot(page, first)).graphBytes).toBe(beforeFirst.graphBytes);

    const insertModelPosition = { x: 240, y: 300 };
    const insertPoint = await stagePoint(page, second, insertModelPosition);
    await page.mouse.click(insertPoint.x, insertPoint.y);
    await expect.poll(async () => (await snapshot(page, second)).depth).toBe(beforeSecond.depth + 1);
    const inserted = await page.evaluate(id => window.ravenroot.workspace.find(id).cy.nodes().last().position(), second);
    expect(Math.abs(inserted.x - insertModelPosition.x)).toBeLessThan(2);
    expect(Math.abs(inserted.y - insertModelPosition.y)).toBeLessThan(2);
    expect((await snapshot(page, first)).graphBytes).toBe(beforeFirst.graphBytes);
  });

  for (const direction of [{ name: 'Design background owner' }]) {
    for (const selection of ['node', 'edge', 'multiple nodes']) {
      test(`${direction.name} preserves its ${selection} at physical stage-press precedence`, async ({ page }) => {
        const { first: target, second: sibling } = await openTwinDocuments(page, 'horizontal');
        await activateAndStyle(page, target);
        await enableEditing(page);
        if (selection === 'edge') {
          // The stage press below is physical-pointer driven. Establish the edge precondition via
          // the renderer because a one-pixel edge hit is intentionally renderer-style-dependent;
          // the requirement here is that an already-selected edge survives background ownership
          // until that physical stage press is classified.
          await page.evaluate(id => {
            const owner = window.ravenroot.workspace.find(id);
            owner.cy.getElementById('edge-start-dosomething').select();
          }, target);
          await expect.poll(async () => (await snapshot(page, target)).selected)
            .toEqual(['edge-start-dosomething']);
        } else {
          const start = await nodeCentre(page, target, 'start');
          await page.mouse.click(start.x, start.y);
          if (selection === 'multiple nodes') {
            const next = await nodeCentre(page, target, 'dosomething');
            await page.keyboard.down(process.platform === 'darwin' ? 'Meta' : 'Control');
            await page.mouse.click(next.x, next.y);
            await page.keyboard.up(process.platform === 'darwin' ? 'Meta' : 'Control');
            await expect.poll(async () => (await snapshot(page, target)).selected)
              .toEqual(['dosomething', 'start']);
          } else {
            await expect.poll(async () => (await snapshot(page, target)).selected).toEqual(['start']);
          }
        }

        // Make the sibling active by a real pointer interaction. The target retains its own
        // selection in the background; this is the destructive activation boundary under test.
        await activateAndStyle(page, sibling);
        const siblingStart = await nodeCentre(page, sibling, 'start');
        await page.mouse.click(siblingStart.x, siblingStart.y);
        await expect.poll(async () => (await snapshot(page, sibling)).selected).toEqual(['start']);
        const beforeTarget = await snapshot(page, target);
        const beforeSibling = await snapshot(page, sibling);

        const clearPoint = await stagePoint(page, target, { x: 180, y: 300 });
        await page.mouse.click(clearPoint.x, clearPoint.y);
        const afterClear = await snapshot(page, target);
        expect(afterClear).toMatchObject({ selected: [], depth: beforeTarget.depth });
        expect(afterClear.graphBytes).toBe(beforeTarget.graphBytes);
        expect(await page.evaluate(() => window.ravenroot.activeDocument().id)).toBe(target);
        expect(await snapshot(page, sibling)).toMatchObject({
          graphBytes: beforeSibling.graphBytes, depth: beforeSibling.depth, selected: beforeSibling.selected,
        });

        const insertModelPosition = { x: 240, y: 300 };
        const insertPoint = await stagePoint(page, target, insertModelPosition);
        const nodeCount = await page.evaluate(id => window.ravenroot.workspace.find(id).cy.nodes().length, target);
        await page.mouse.click(insertPoint.x, insertPoint.y);
        await expect.poll(async () => (await snapshot(page, target)).depth).toBe(beforeTarget.depth + 1);
        expect(await page.evaluate(id => window.ravenroot.workspace.find(id).cy.nodes().length, target)).toBe(nodeCount + 1);
        const inserted = await page.evaluate(id => window.ravenroot.workspace.find(id).cy.nodes().last().position(), target);
        expect(Math.abs(inserted.x - insertModelPosition.x)).toBeLessThan(2);
        expect(Math.abs(inserted.y - insertModelPosition.y)).toBeLessThan(2);
      });
    }

    test(`${direction.name} inserts on a genuinely empty background owner`, async ({ page }) => {
      const { first: target, second: sibling } = await openTwinDocuments(page, 'horizontal');
      await activateAndStyle(page, target);
      await enableEditing(page);
      await activateAndStyle(page, sibling);
      const siblingStart = await nodeCentre(page, sibling, 'start');
      await page.mouse.click(siblingStart.x, siblingStart.y);
      const beforeTarget = await snapshot(page, target);
      const position = { x: 240, y: 300 };
      const point = await stagePoint(page, target, position);
      await page.mouse.click(point.x, point.y);
      await expect.poll(async () => (await snapshot(page, target)).depth).toBe(beforeTarget.depth + 1);
      const inserted = await page.evaluate(id => window.ravenroot.workspace.find(id).cy.nodes().last().position(), target);
      expect(Math.abs(inserted.x - position.x)).toBeLessThan(2);
      expect(Math.abs(inserted.y - position.y)).toBeLessThan(2);
    });
  }

  for (const layout of ['vertical', 'grid']) {
    test(`${layout} panes preserve Design click selection without preview or mutation`, async ({ page }) => {
      const { first, second } = await openTwinDocuments(page, layout);
      expect(await page.evaluate(() => window.ravenroot.workspaceLayout().mode)).toBe(layout);
      await activateAndStyle(page, second);
      await enableEditing(page);
      const beforeFirst = await snapshot(page, first);
      const beforeSecond = await snapshot(page, second);
      const source = await nodeCentre(page, second, 'start');

      await page.mouse.click(source.x, source.y);

      expect((await snapshot(page, first)).graphBytes).toBe(beforeFirst.graphBytes);
      expect((await snapshot(page, second)).graphBytes).toBe(beforeSecond.graphBytes);
      expect((await snapshot(page, second)).depth).toBe(beforeSecond.depth);
      expect(await feedback(page, first)).toMatchObject({ source: [], valid: [], invalid: [], ghostOn: false });
      expect(await feedback(page, second)).toMatchObject({ source: [], valid: [], invalid: [], ghostOn: false });
      await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
    });
  }

  test('switch, replace, hide/invalidate, close, Escape, and invalid drop cancel atomically', async ({ page }) => {
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    let { first, second } = await openTwinDocuments(page, 'horizontal');
    await enableEditing(page);

    // Switch during an intentional drag.
    let before = await snapshot(page, second);
    await beginDrag(page, second);
    await page.evaluate(id => window.ravenroot.activateDocument(id), first);
    await page.mouse.up();
    expect((await snapshot(page, second)).graphBytes).toBe(before.graphBytes);
    expect((await snapshot(page, second)).depth).toBe(before.depth);
    expect(await feedback(page, second)).toMatchObject({ source: [], valid: [], invalid: [], ghostOn: false });

    // Replace retires the captured model/history/renderer as one root. The pointer release cannot
    // append its candidate edge to the incoming GraphML document.
    await page.evaluate(id => window.ravenroot.activateDocument(id), second);
    await enableEditing(page);
    await beginDrag(page, second);
    await page.evaluate(({ xml }) => window.ravenroot.replaceActiveDocumentFromText(
      xml, 'replacement.graphml'), { xml: replacementGraphMl });
    await page.mouse.up();
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
    expect((await feedback(page, second)).ghostOn).toBe(false);
    expect((await snapshot(page, second)).depth).toBe(0);

    // Elastic hides and invalidates the Cytoscape authoring renderer. Editing becomes unavailable,
    // and the old renderer's classes/ghost are cleaned before it is suspended.
    await page.locator('#btn-design').click();
    await page.waitForTimeout(500);
    await enableEditing(page);
    before = await snapshot(page, second);
    await beginDrag(page, second);
    await page.locator('#btn-monitoring').click();
    await page.mouse.up();
    expect((await snapshot(page, second)).graphBytes).toBe(before.graphBytes);
    expect((await snapshot(page, second)).depth).toBe(before.depth);
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');

    // Return to an editable renderer and prove Escape and a semantically invalid drop both clean up.
    await page.locator('#btn-design').click();
    await page.waitForTimeout(500);
    await enableEditing(page);
    before = await snapshot(page, second);
    await beginDrag(page, second);
    await page.keyboard.press('Escape');
    await page.mouse.up();
    expect((await snapshot(page, second)).graphBytes).toBe(before.graphBytes);
    expect(await feedback(page, second)).toMatchObject({ source: [], valid: [], invalid: [], ghostOn: false });

    const invalid = await beginDrag(page, second, 'dosomething', 'start');
    await finishDrag(page, invalid.target);
    expect((await snapshot(page, second)).graphBytes).toBe(before.graphBytes);
    expect((await snapshot(page, second)).depth).toBe(before.depth);
    expect(await feedback(page, second)).toMatchObject({ source: [], valid: [], invalid: [], ghostOn: false });

    // Closing the owner while the button is held removes that root and cannot touch its sibling.
    const firstBeforeClose = await snapshot(page, first);
    await beginDrag(page, second);
    await page.evaluate(id => window.ravenroot.closeDocument(id), second);
    await page.mouse.up();
    expect(await page.evaluate(id => window.ravenroot.workspace.find(id), second)).toBeNull();
    expect((await snapshot(page, first)).graphBytes).toBe(firstBeforeClose.graphBytes);
    await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
    expect(errors).toEqual([]);
  });
});

import { expect, test } from '@playwright/test';

const BASE_NODES = ['dosomething', 'end', 'error', 'start'];
const BASE_EDGES = ['edge-dosomething-end', 'edge-dosomething-error', 'edge-start-dosomething'];

async function open(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
  await page.locator('#btn-modify').click();
  await page.evaluate(() => {
    window.cy.stop(true);
    const positions = {
      start: { x: 120, y: 120 }, dosomething: { x: 400, y: 120 },
      end: { x: 680, y: 80 }, error: { x: 680, y: 330 },
    };
    Object.entries(positions).forEach(([id, position]) =>
      window.cy.getElementById(id).position(position));
    window.cy.fit(undefined, 100);
  });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

const graphState = page => page.evaluate(() => ({
  nodes: window.cy.nodes().map(node => node.id()).sort(),
  edges: window.cy.edges().map(edge => edge.id()).sort(),
  selected: window.cy.$(':selected').map(element => element.id()).sort(),
  historyDepth: window.ravenroot.activeDocument().history.depth(),
}));

async function elementPoint(page, group, id) {
  return page.evaluate(({ targetGroup, targetId }) => {
    const element = window.cy.getElementById(targetId);
    const point = targetGroup === 'node' ? element.renderedPosition() : element.renderedMidpoint();
    const canvas = window.cy.container().getBoundingClientRect();
    return { x: canvas.left + point.x, y: canvas.top + point.y };
  }, { targetGroup: group, targetId: id });
}

async function completedClick(page, group, id, { modifier = null } = {}) {
  const point = await elementPoint(page, group, id);
  if (modifier) await page.keyboard.down(modifier);
  await page.mouse.move(point.x, point.y);
  await page.mouse.down();
  await page.mouse.up();
  if (modifier) await page.keyboard.up(modifier);
}

async function expectSelection(page, selected, badge, inspector) {
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: BASE_NODES, edges: BASE_EDGES, selected: [...selected].sort(), historyDepth: 0,
  });
  await expect(page.locator('#b-sel')).toHaveText(badge);
  await expect(page.locator('#info-title')).toContainText(inspector);
  if (selected.length === 0) {
    await expect(page.locator('#info-body')).toContainText('Select a node or edge');
  }
  await expect(page.locator('.graph-node-action-menu:visible')).toHaveCount(0);
}

test('Ctrl-click adds and toggles a node using the completed pointer modifier lifecycle', async ({ page }) => {
  await open(page);
  await completedClick(page, 'edge', 'edge-start-dosomething');
  await expectSelection(page, ['edge-start-dosomething'], 'Start → Do something', 'Start → Do something');

  await completedClick(page, 'node', 'end', { modifier: 'Control' });
  await expectSelection(page, ['edge-start-dosomething', 'end'], '2 elements', '2 elements selected');
  await expect(page.locator('.graph-selection-box[data-node-id="end"]')).toBeVisible();

  await completedClick(page, 'node', 'end', { modifier: 'Control' });
  await expectSelection(page, ['edge-start-dosomething'], 'Start → Do something', 'Start → Do something');
  await expect(page.locator('.graph-selection-box[data-node-id="end"]')).toHaveCount(0);

  // Control was released after the completed toggle; the next ordinary click replaces selection.
  await completedClick(page, 'node', 'error');
  await expectSelection(page, ['error'], 'Error', 'Error');
  await completedClick(page, 'node', 'error', { modifier: 'Control' });
  await expectSelection(page, [], '—', 'Inspector');
});

test('Ctrl-click adds and toggles an edge without stage, context-menu or click-through effects', async ({ page }) => {
  await open(page);
  await completedClick(page, 'node', 'start');
  await expectSelection(page, ['start'], 'Start', 'Start');

  await completedClick(page, 'edge', 'edge-dosomething-end', { modifier: 'Meta' });
  await expectSelection(page, ['edge-dosomething-end', 'start'], '2 elements', '2 elements selected');

  await completedClick(page, 'edge', 'edge-dosomething-end', { modifier: 'Meta' });
  await expectSelection(page, ['start'], 'Start', 'Start');

  await completedClick(page, 'edge', 'edge-dosomething-error');
  await expectSelection(page, ['edge-dosomething-error'], 'Do something → Error', 'Do something → Error');
  await completedClick(page, 'edge', 'edge-dosomething-error', { modifier: 'Control' });
  await expectSelection(page, [], '—', 'Inspector');
});

const activeChromeSnapshot = page => page.evaluate(() => {
  const owner = window.ravenroot.activeDocument();
  const overlay = [...owner.container.children]
    .find(child => child.classList?.contains('graph-selection-overlay'));
  return JSON.stringify({
    documentId: owner.id,
    selected: owner.cy.$(':selected').map(element => element.id()).sort(),
    badge: document.getElementById('b-sel').outerHTML,
    inspectorTitle: document.getElementById('info-title').outerHTML,
    inspectorBody: document.getElementById('info-body').innerHTML,
    overlay: overlay?.outerHTML || '',
  });
});

async function settleSelection(page) {
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.waitForTimeout(300);
}

test('a stale completed tap cannot mutate another document or a replacement renderer', async ({ page }) => {
  await open(page);
  const { first, second } = await page.evaluate(() => {
    const firstOwner = window.ravenroot.activeDocument();
    const staleNode = firstOwner.cy.getElementById('start');
    staleNode.select();
    staleNode.emit({ type: 'tapstart', originalEvent: { ctrlKey: true, metaKey: false } });

    const secondId = window.ravenroot.openDocument({ name: 'second.graphml' });
    const secondOwner = window.ravenroot.workspace.find(secondId);
    secondOwner.cy.elements().unselect();
    secondOwner.cy.getElementById('end').select();
    return { first: firstOwner.id, second: secondId };
  });
  await expectSelection(page, ['end'], 'End', 'End');
  await settleSelection(page);
  const beforeBackgroundCompletion = await activeChromeSnapshot(page);

  await page.evaluate(firstId => {
    const owner = window.ravenroot.workspace.find(firstId);
    owner.cy.getElementById('start').emit({
      type: 'tap', originalEvent: { ctrlKey: true, metaKey: false },
    });
  }, first);
  await settleSelection(page);
  expect(await activeChromeSnapshot(page)).toBe(beforeBackgroundCompletion);

  await page.evaluate(secondId => {
    const owner = window.ravenroot.workspace.find(secondId);
    const staleCy = owner.cy;
    const staleNode = staleCy.getElementById('end');
    staleNode.emit({ type: 'tapstart', originalEvent: { ctrlKey: true, metaKey: false } });
    window.__staleModifierSelection = { cy: staleCy, node: staleNode };
    window.ravenroot.replaceActiveDocumentFromText(JSON.stringify({
      nodes: [{ id: 'replacement', label: 'Replacement', type: 'file' }], edges: [],
    }), 'replacement.json');
    window.cy.getElementById('replacement').select();
  }, second);
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: ['replacement'], edges: [], selected: ['replacement'], historyDepth: 0,
  });
  await expect(page.locator('#b-sel')).toHaveText('Replacement');
  await expect(page.locator('#info-title')).toContainText('Replacement');
  await settleSelection(page);
  const beforeDestroyedCompletion = await activeChromeSnapshot(page);

  await page.evaluate(() => {
    window.__staleModifierSelection.node.emit({
      type: 'tap', originalEvent: { ctrlKey: true, metaKey: false },
    });
    delete window.__staleModifierSelection;
  });
  await settleSelection(page);
  expect(await activeChromeSnapshot(page)).toBe(beforeDestroyedCompletion);
});

test('drag, context-menu and cancellation retire a modifier snapshot before the next tap', async ({ page }) => {
  await open(page);
  const exerciseCancellation = async cancellation => {
    await page.evaluate(cancelKind => {
      const target = window.cy.getElementById('end');
      window.cy.elements().unselect();
      window.cy.getElementById('start').select();
      target.emit({ type: 'tapstart', originalEvent: { ctrlKey: true, metaKey: false } });
      if (cancelKind === 'drag') target.emit({ type: 'tapdrag' });
      else if (cancelKind === 'contextmenu') {
        target.emit({
          type: 'cxttap', renderedPosition: target.renderedPosition(),
          originalEvent: { preventDefault() {} },
        });
      } else {
        document.dispatchEvent(new PointerEvent('pointercancel', { bubbles: true }));
      }
    }, cancellation);
    if (cancellation === 'contextmenu') {
      await expect(page.locator('.graph-node-action-menu:visible')).toHaveCount(1);
      await page.keyboard.press('Escape');
    }
    // A new physical press replaces any retired gesture state. The modifier has been released, so
    // this ordinary completed click must replace rather than add to the selection.
    await completedClick(page, 'node', 'end');
    await expectSelection(page, ['end'], 'End', 'End');
  };

  await exerciseCancellation('drag');
  await exerciseCancellation('contextmenu');
  await exerciseCancellation('pointercancel');
});

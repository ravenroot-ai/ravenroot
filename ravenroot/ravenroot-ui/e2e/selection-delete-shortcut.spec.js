import { expect, test } from '@playwright/test';

const BASE_NODES = ['dosomething', 'end', 'error', 'start'];
const BASE_EDGES = ['edge-dosomething-end', 'edge-dosomething-error', 'edge-start-dosomething'];

async function open(page, { editing = true } = {}) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
  if (editing) {
    await page.locator('#btn-modify').click();
    await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
  }
  await pinGraph(page);
}

async function pinGraph(page) {
  await page.evaluate(() => {
    window.cy.stop(true);
    const positions = {
      start: { x: 120, y: 120 }, dosomething: { x: 400, y: 120 },
      end: { x: 680, y: 80 }, error: { x: 680, y: 330 },
    };
    Object.entries(positions).forEach(([id, position]) => window.cy.getElementById(id).position(position));
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

const selectElements = (page, ids) => page.evaluate(elementIds => {
  window.cy.$(':selected').unselect();
  elementIds.forEach(id => { window.cy.getElementById(id).select(); });
}, ids);

async function nodePoint(page, id) {
  return page.evaluate(nodeId => {
    const point = window.cy.getElementById(nodeId).renderedPosition();
    const canvas = window.cy.container().getBoundingClientRect();
    return { x: canvas.left + point.x, y: canvas.top + point.y };
  }, id);
}

async function edgePoint(page, id) {
  return page.evaluate(edgeId => {
    const point = window.cy.getElementById(edgeId).renderedMidpoint();
    const canvas = window.cy.container().getBoundingClientRect();
    return { x: canvas.left + point.x, y: canvas.top + point.y };
  }, id);
}

async function clickEdge(page, id) {
  const point = await edgePoint(page, id);
  await page.mouse.move(point.x, point.y);
  await page.mouse.down();
  await page.mouse.up();
  await expect.poll(() => graphState(page)).toMatchObject({ selected: [id] });
}

test('a completed edge click routes Delete and Backspace after pointer release', async ({ page }) => {
  await open(page);

  for (const { key, focus } of [
    { key: 'Delete', focus: '#cy-wrap' },
    { key: 'Backspace', focus: 'body' },
  ]) {
    await clickEdge(page, 'edge-start-dosomething');
    const before = await graphState(page);
    await page.locator(focus).focus();
    await page.locator(focus).press(key);
    await expect.poll(() => graphState(page)).toMatchObject({
      nodes: BASE_NODES,
      edges: ['edge-dosomething-end', 'edge-dosomething-error'],
      selected: [],
      historyDepth: before.historyDepth + 1,
    });
    await expect(page.locator('#info-title')).toHaveText('Inspector');
    await expect(page.locator('#info-body')).toContainText('Select a node or edge');
    await page.locator('#btn-undo').click();
    await expect.poll(() => graphState(page)).toMatchObject({ nodes: BASE_NODES, edges: BASE_EDGES });
    await pinGraph(page);
  }
});

for (const key of ['Delete', 'Backspace']) {
  test(`a non-editable focused control cannot swallow released-edge ${key}`, async ({ page }) => {
    await open(page);
    await page.evaluate(() => {
      const control = document.createElement('button');
      control.id = 'delete-shortcut-non-editable';
      control.textContent = 'Non-editable control';
      control.style.cssText = 'position:fixed;right:0;bottom:0';
      control.addEventListener('keydown', event => event.stopPropagation());
      document.body.append(control);
    });

    await clickEdge(page, 'edge-start-dosomething');
    await page.locator('#delete-shortcut-non-editable').focus();
    await page.locator('#delete-shortcut-non-editable').press(key);
    await expect.poll(() => graphState(page)).toMatchObject({
      nodes: BASE_NODES,
      edges: ['edge-dosomething-end', 'edge-dosomething-error'],
      selected: [],
    });
  });
}

test('Delete and macOS-like Backspace remove the current mixed selection regardless of hover or mouse-down', async ({ page }) => {
  await open(page);

  await selectElements(page, ['start', 'edge-dosomething-end']);
  const beforeDelete = await graphState(page);
  const error = await nodePoint(page, 'error');
  await page.mouse.move(error.x, error.y);
  await page.locator('body').press('Delete');
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: ['dosomething', 'end', 'error'],
    edges: ['edge-dosomething-error'],
    selected: [],
    historyDepth: beforeDelete.historyDepth + 1,
  });

  // Nodes, the explicitly selected edge and the incident edge are one reversible delete command.
  await page.locator('#btn-undo').click();
  await expect.poll(() => graphState(page)).toMatchObject({ nodes: BASE_NODES, edges: BASE_EDGES });

  await selectElements(page, ['end', 'edge-dosomething-error']);
  const beforeBackspace = await graphState(page);
  const start = await nodePoint(page, 'start');
  await page.mouse.move(start.x, start.y);
  await page.mouse.down();
  await page.keyboard.press('Backspace');
  // Assert before pointer-up: deletion must not depend on completion of the pointer gesture.
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: ['dosomething', 'error', 'start'],
    edges: ['edge-start-dosomething'],
    selected: [],
    historyDepth: beforeBackspace.historyDepth + 1,
  });
  await page.mouse.up();
  await expect.poll(() => graphState(page)).toMatchObject({ selected: ['start'] });

  await page.locator('#btn-undo').click();
  await expect.poll(() => graphState(page)).toMatchObject({ nodes: BASE_NODES, edges: BASE_EDGES });
});

test('Delete shortcuts preserve graph selection while focus edits native and contenteditable controls', async ({ page }) => {
  await open(page);
  await selectElements(page, ['start']);
  await expect(page.locator('#node-editor')).toBeVisible();

  const controls = [
    { locator: page.locator('#node-editor input[name="name"]'), key: 'Backspace' },
    { locator: page.locator('#assistant-draft'), key: 'Delete' },
    { locator: page.locator('#node-editor select[name="kind"]'), key: 'Backspace' },
  ];
  for (const { locator, key } of controls) {
    await locator.focus();
    await locator.press(key);
    await expect.poll(() => graphState(page)).toMatchObject({
      nodes: BASE_NODES, edges: BASE_EDGES, selected: ['start'],
    });
  }

  await page.evaluate(() => {
    const editable = document.createElement('div');
    editable.id = 'delete-shortcut-contenteditable';
    editable.contentEditable = 'true';
    editable.textContent = 'editable';
    document.body.append(editable);
  });
  const editable = page.locator('#delete-shortcut-contenteditable');
  await editable.focus();
  await editable.press('Backspace');
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: BASE_NODES, edges: BASE_EDGES, selected: ['start'],
  });

  await page.evaluate(() => {
    const editable = document.getElementById('delete-shortcut-contenteditable');
    editable.innerHTML = '<span id="delete-shortcut-nested-editable" tabindex="0">nested</span>';
  });
  const nestedEditable = page.locator('#delete-shortcut-nested-editable');
  await nestedEditable.focus();
  await nestedEditable.press('Delete');
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: BASE_NODES, edges: BASE_EDGES, selected: ['start'],
  });

  await page.evaluate(() => {
    const dialog = document.createElement('dialog');
    dialog.id = 'delete-shortcut-dialog';
    dialog.innerHTML = '<button id="delete-shortcut-dialog-control">Dialog control</button>';
    document.body.append(dialog);
    dialog.showModal();
  });
  const dialogControl = page.locator('#delete-shortcut-dialog-control');
  await dialogControl.focus();
  await dialogControl.press('Backspace');
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: BASE_NODES, edges: BASE_EDGES, selected: ['start'],
  });
});

test('Delete remains unavailable for Graphify and Elastic read-only presentations', async ({ page }) => {
  await open(page, { editing: false });
  await page.evaluate(() => {
    window.ravenroot.replaceActiveDocumentFromText(JSON.stringify({
      nodes: [{ id: 'graphify-node', label: 'Graphify node', type: 'file' }], edges: [],
    }), 'catalog.json');
    window.cy.getElementById('graphify-node').select();
  });
  await page.locator('body').press('Delete');
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: ['graphify-node'], edges: [], selected: ['graphify-node'], historyDepth: 0,
  });
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-disabled', 'true');

  await page.reload();
  await page.locator('#btn-monitoring').click();
  await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
  await page.evaluate(() => { window.cy.getElementById('start').select(); });
  await page.locator('body').press('Backspace');
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: BASE_NODES, edges: BASE_EDGES, selected: ['start'], historyDepth: 0,
  });
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-disabled', 'true');
});

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

const documentGraphState = (page, documentId) => page.evaluate(id => {
  const owner = window.ravenroot.workspace.find(id);
  return {
    nodes: owner.cy.nodes().map(node => node.id()).sort(),
    edges: owner.cy.edges().map(edge => edge.id()).sort(),
    selected: owner.cy.$(':selected').map(element => element.id()).sort(),
    historyDepth: owner.history.depth(),
    active: window.ravenroot.workspace.activeId === id,
  };
}, documentId);

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

async function documentNodePoint(page, documentId, nodeId) {
  return page.evaluate(({ documentId: id, nodeId: key }) => {
    const owner = window.ravenroot.workspace.find(id);
    const point = owner.cy.getElementById(key).renderedPosition();
    const canvas = owner.cy.container().getBoundingClientRect();
    return { x: canvas.left + point.x, y: canvas.top + point.y };
  }, { documentId, nodeId });
}

async function clickElementNaturally(page, type, id, { additive = false, additiveKey = 'Control' } = {}) {
  const point = await (type === 'node' ? nodePoint(page, id) : edgePoint(page, id));
  if (additive) await page.keyboard.down(additiveKey);
  await page.mouse.click(point.x, point.y);
  if (additive) await page.keyboard.up(additiveKey);
}

for (const { type, id, key } of [
  { type: 'node', id: 'dosomething', key: 'Delete' },
  { type: 'edge', id: 'edge-start-dosomething', key: 'Backspace' },
]) {
  test(`a natural ${type} click routes immediate ${key} without focus transfer or selection polling`, async ({ page }) => {
    await open(page);
    const before = await graphState(page);

    // Begin in a real editor field so the completed canvas gesture, rather than a test-only focus
    // helper, must hand the immediately following key to the graph.
    await clickElementNaturally(page, type, id);
    await page.locator(type === 'node'
      ? '#node-editor input[name="name"]'
      : '#edge-editor input[name="edgeName"]').click();
    await clickElementNaturally(page, type, id);
    await page.keyboard.press(key);

    await expect.poll(() => graphState(page)).toMatchObject(type === 'node' ? {
      nodes: ['end', 'error', 'start'], edges: [], selected: [],
      historyDepth: before.historyDepth + 1,
    } : {
      nodes: BASE_NODES, edges: ['edge-dosomething-end', 'edge-dosomething-error'], selected: [],
      historyDepth: before.historyDepth + 1,
    });
    await page.locator('#btn-undo').click();
    await expect.poll(() => graphState(page)).toMatchObject({ nodes: BASE_NODES, edges: BASE_EDGES });
  });
}

test('natural additive node selection routes immediate Backspace as one command', async ({ page }) => {
  await open(page);
  const before = await graphState(page);

  await clickElementNaturally(page, 'node', 'dosomething');
  await clickElementNaturally(page, 'node', 'error', { additive: true });
  await page.keyboard.press('Backspace');

  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: ['end', 'start'], edges: [], selected: [], historyDepth: before.historyDepth + 1,
  });
  await page.locator('#btn-undo').click();
  await expect.poll(() => graphState(page)).toMatchObject({ nodes: BASE_NODES, edges: BASE_EDGES });
});

test('natural additive mixed selection routes immediate Delete as one command', async ({ page }) => {
  await open(page);
  const before = await graphState(page);

  await clickElementNaturally(page, 'node', 'start');
  await clickElementNaturally(page, 'edge', 'edge-dosomething-end', { additive: true, additiveKey: 'Meta' });
  await page.keyboard.press('Delete');

  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: ['dosomething', 'end', 'error'], edges: ['edge-dosomething-error'], selected: [],
    historyDepth: before.historyDepth + 1,
  });
  await page.locator('#btn-undo').click();
  await expect.poll(() => graphState(page)).toMatchObject({ nodes: BASE_NODES, edges: BASE_EDGES });
});

test('natural Delete waits for the dirty Inspector decision and Cancel preserves selection', async ({ page }) => {
  await open(page);
  await page.locator('#btn-autosave').click();
  await expect(page.locator('#btn-autosave')).toHaveAttribute('aria-pressed', 'false');
  await clickElementNaturally(page, 'node', 'dosomething');
  const name = page.locator('#node-editor input[name="name"]');
  await name.fill('Uncommitted delete draft');
  const before = await graphState(page);

  await clickElementNaturally(page, 'node', 'dosomething');
  await page.keyboard.press('Delete');

  const dialog = page.locator('#inspector-unsaved-dialog');
  await expect(dialog).toBeVisible();
  expect(await graphState(page)).toEqual(before);
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(name).toHaveValue('Uncommitted delete draft');
  expect(await graphState(page)).toEqual(before);

  await clickElementNaturally(page, 'node', 'dosomething');
  await page.keyboard.press('Backspace');
  await expect(dialog).toBeVisible();
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
  await expect.poll(() => graphState(page)).toMatchObject({
    nodes: ['end', 'error', 'start'], edges: [], selected: [],
    historyDepth: before.historyDepth + 1,
  });
});

test('a background document pointer selection becomes the only Delete target', async ({ page }) => {
  await page.setViewportSize({ width: 1800, height: 1000 });
  await open(page);
  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'second.graphml' }));
  await page.evaluate(() => window.ravenroot.setWorkspaceLayout('horizontal'));
  await expect(page.locator(`.doc-pane[data-document-id="${first}"]`))
    .not.toHaveAttribute('aria-busy', 'true');
  await expect(page.locator(`.doc-pane[data-document-id="${second}"]`))
    .not.toHaveAttribute('aria-busy', 'true');
  await page.evaluate(ids => {
    for (const id of ids) {
      const owner = window.ravenroot.workspace.find(id);
      owner.cy.stop(true);
      owner.cy.getElementById('start').position({ x: 120, y: 120 });
      owner.cy.getElementById('dosomething').position({ x: 400, y: 120 });
      owner.cy.getElementById('end').position({ x: 680, y: 80 });
      owner.cy.getElementById('error').position({ x: 680, y: 330 });
      owner.cy.fit(undefined, 70);
    }
  }, [first, second]);
  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  if (await page.locator('#btn-modify').getAttribute('aria-pressed') !== 'true') {
    await page.locator('#btn-modify').click();
  }
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);
  const beforeFirst = await documentGraphState(page, first);
  const beforeSecond = await documentGraphState(page, second);

  const point = await documentNodePoint(page, first, 'dosomething');
  await page.mouse.click(point.x, point.y);
  await page.keyboard.press('Delete');

  await expect.poll(() => documentGraphState(page, first)).toMatchObject({
    nodes: ['end', 'error', 'start'], edges: [], selected: [],
    historyDepth: beforeFirst.historyDepth + 1, active: true,
  });
  expect(await documentGraphState(page, second)).toEqual({ ...beforeSecond, active: false });
});

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

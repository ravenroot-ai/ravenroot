import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';

const importedGroups = readFileSync(new URL('../../ravenroot-core/src/test/resources/graphml-corpus/accepted/visual-groups.graphml', import.meta.url));

async function editable(page) {
  await page.goto('/');
  await page.waitForFunction(() => window.cy && window.ravenroot?.workspace.active);
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
}

async function select(page, ids) {
  await page.evaluate(ids => {
    window.cy.elements().unselect();
    ids.forEach(id => window.cy.getElementById(id).select());
  }, ids);
}

async function groupSelection(page, name = 'Processing') {
  await page.keyboard.press('ControlOrMeta+g');
  const dialog = page.getByRole('dialog', { name: 'Group selection', exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('textbox', { name: 'Group name' }).fill(name);
  await dialog.getByRole('button', { name: 'Create group', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.cy.nodes('[rrVisualRole="summary"]:visible').length)).toBe(1);
  await page.waitForFunction(() => !window.ravenroot.workspace.active.visualGroupsRenderer.isAnimating);
}

const groupData = page => page.evaluate(() => JSON.parse(window.ravenroot.workspace.active.graph.graphProperties['ravenroot.ui.visualGroups']));
const canonical = page => page.evaluate(() => ({
  nodes: window.ravenroot.workspace.active.graph.nodes.map(node => node.id),
  edges: window.ravenroot.workspace.active.graph.edges.map(edge => [edge.id, edge.source, edge.target]),
}));

test('keyboard create, expand, rename, summary Delete and undo preserve canonical elements', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await editable(page);
  const before = await canonical(page);
  await select(page, ['dosomething', 'end']);
  await groupSelection(page);
  await expect(page.locator('#info-body')).toContainText('Visual group');
  await expect(page.locator('#info-body')).toContainText('2 members');
  expect(await canonical(page)).toEqual(before);
  await page.getByRole('button', { name: 'Expand', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('dosomething').visible())).toBe(true);
  await page.waitForFunction(() => !window.ravenroot.workspace.active.visualGroupsRenderer.isAnimating);
  await select(page, await page.evaluate(() => window.cy.nodes('[rrVisualRole="header"]').map(node => node.id())));
  await page.getByRole('button', { name: 'Rename group', exact: true }).click();
  await page.getByRole('dialog', { name: 'Rename group' }).getByRole('textbox').fill('Renamed');
  await page.getByRole('dialog', { name: 'Rename group' }).getByRole('button', { name: 'Save', exact: true }).click();
  expect((await groupData(page)).groups[0].name).toBe('Renamed');
  await page.getByRole('button', { name: 'Collapse', exact: true }).click();
  await page.waitForFunction(() => !window.ravenroot.workspace.active.visualGroupsRenderer.isAnimating);
  await select(page, await page.evaluate(() => window.cy.nodes('[rrVisualRole="summary"]').map(node => node.id())));
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('Delete');
  await expect.poll(async () => (await groupData(page)).groups.length).toBe(0);
  expect(await canonical(page)).toEqual(before);
  await page.locator('#btn-undo').click();
  expect((await groupData(page)).groups[0].name).toBe('Renamed');
  expect(await canonical(page)).toEqual(before);
  expect(errors).toEqual([]);
});

test('minibar retains selected nodes captured before its pointer gesture', async ({ page }) => {
  await editable(page);
  await select(page, ['dosomething', 'end']);
  // Hover a different real node: the minibar must group the retained explicit multi-selection.
  const point = await page.evaluate(() => {
    const node = window.cy.getElementById('error');
    const box = window.cy.container().getBoundingClientRect(); const p = node.renderedPosition();
    return { x: box.left + p.x, y: box.top + p.y };
  });
  await page.mouse.move(point.x, point.y);
  const button = page.locator('.graph-node-action[data-node-action="group"]:visible');
  await expect(button).toBeVisible(); await button.click();
  await page.getByRole('dialog', { name: 'Group selection' }).getByRole('button', { name: 'Create group' }).click();
  await expect.poll(async () => (await groupData(page)).groups[0]?.memberNodeIds).toEqual(['dosomething', 'end']);
});

test('read-only presentation toggles do not mutate pinned graph or add history', async ({ page }) => {
  await editable(page); await select(page, ['dosomething', 'end']); await groupSelection(page);
  await page.evaluate(() => {
    const source = window.ravenroot.workspace.active;
    window.ravenroot.openDocument({ name: 'pinned.graphml', mode: 'test', graph: structuredClone(source.graph) });
    const owner = window.ravenroot.workspace.active;
    owner._testPinnedGraph = JSON.stringify(owner.graph);
  });
  expect(await page.evaluate(() => window.cy.nodes('[rrVisualRole="summary"]').first().grabbable())).toBe(false);
  await page.evaluate(() => { window.cy.nodes('[rrVisualRole="summary"]').select(); });
  await expect(page.locator('#btn-modify')).toBeDisabled();
  await page.getByRole('button', { name: 'Expand', exact: true }).click();
  await page.waitForFunction(() => !window.ravenroot.workspace.active.visualGroupsRenderer.isAnimating);
  expect(await page.evaluate(() => {
    const owner = window.ravenroot.workspace.active;
    return { same: JSON.stringify(owner.graph) === owner._testPinnedGraph, dirty: owner.history.isDirty(),
      mode: owner.mode, collapsed: Object.values(owner.visualGroupState)[0].collapsed };
  })).toEqual({ same: true, dirty: false, mode: 'test', collapsed: false });
});

test('toggle-only changes on an imported baseline use close and replace save guards', async ({ page }) => {
  await page.goto('/');
  await page.locator('#replace-file-inp').setInputFiles({ name: 'groups.graphml', mimeType: 'application/xml', buffer: importedGroups });
  await page.waitForFunction(() => window.ravenroot.workspace.active.graph.nodeMap.work);
  expect(await page.evaluate(() => window.ravenroot.workspace.active.history.isDirty())).toBe(false);
  await select(page, await page.evaluate(() => window.cy.nodes('[rrVisualRole="summary"]').map(node => node.id())));
  await page.getByRole('button', { name: 'Expand', exact: true }).click();
  await page.waitForFunction(() => !window.ravenroot.workspace.active.visualGroupsRenderer.isAnimating);
  expect(await page.evaluate(() => window.ravenroot.workspace.active.history.isDirty())).toBe(false);
  await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');
  await page.getByRole('menuitem', { name: 'File', exact: true }).click();
  await page.getByRole('menuitem', { name: 'Close Document', exact: true }).click();
  await expect(page.locator('#unsaved-document-dialog')).toHaveAttribute('open', '');
  await page.locator('[data-unsaved-action="cancel"]').click();
  await page.locator('#replace-file-inp').setInputFiles({ name: 'replacement.graphml', mimeType: 'application/xml', buffer: importedGroups });
  await expect(page.locator('#unsaved-document-dialog')).toHaveAttribute('open', '');
  await page.locator('[data-unsaved-action="cancel"]').click();
  expect(await page.evaluate(() => window.ravenroot.workspace.active.name)).toBe('groups.graphml');
});

test('a physical summary drag is one rigid move and member deletion reconciles in one undo', async ({ page }) => {
  await editable(page); await select(page, ['dosomething', 'end', 'error']); await groupSelection(page);
  const originalMembers = (await groupData(page)).groups[0].memberNodeIds;
  const before = await page.evaluate(() => Object.fromEntries(window.ravenroot.workspace.active.graph.nodes.map(node => [node.id, { x: node.ox, y: node.oy }])));
  const point = await page.evaluate(() => {
    const node = window.cy.nodes('[rrVisualRole="summary"]').first();
    const box = window.cy.container().getBoundingClientRect(); const p = node.renderedPosition();
    return { x: box.left + p.x, y: box.top + p.y };
  });
  await page.mouse.move(point.x, point.y); await page.mouse.down();
  await page.mouse.move(point.x + 42, point.y + 28, { steps: 8 }); await page.mouse.up();
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', 'Undo Move visual group');
  const moved = await page.evaluate(() => Object.fromEntries(window.ravenroot.workspace.active.graph.nodes.map(node => [node.id, { x: node.ox, y: node.oy }])));
  const delta = { x: moved.end.x - before.end.x, y: moved.end.y - before.end.y };
  expect(Math.abs(delta.x)).toBeGreaterThan(1);
  for (const id of ['dosomething', 'error']) {
    expect(moved[id].x - before[id].x).toBeCloseTo(delta.x, 6);
    expect(moved[id].y - before[id].y).toBeCloseTo(delta.y, 6);
  }
  expect(moved.start).toEqual(before.start);
  await page.locator('#btn-undo').click();
  expect(await page.evaluate(() => Object.fromEntries(window.ravenroot.workspace.active.graph.nodes.map(node => [node.id, { x: node.ox, y: node.oy }])))).toEqual(before);
  await select(page, await page.evaluate(() => [...window.cy.nodes('[rrVisualRole="summary"]').map(node => node.id()), 'start']));
  await page.locator('#cy-wrap').focus(); await page.keyboard.press('Delete');
  expect((await canonical(page)).nodes).toHaveLength(4);
  expect((await groupData(page)).groups).toHaveLength(1);
  await select(page, await page.evaluate(() => window.cy.nodes('[rrVisualRole="summary"]').map(node => node.id())));
  await page.getByRole('button', { name: 'Expand', exact: true }).click();
  await page.waitForFunction(() => !window.ravenroot.workspace.active.visualGroupsRenderer.isAnimating);
  await select(page, ['error']); await page.locator('#cy-wrap').focus(); await page.keyboard.press('Delete');
  expect((await groupData(page)).groups[0].memberNodeIds).toEqual(['dosomething', 'end']);
  await page.locator('#btn-undo').click();
  expect((await groupData(page)).groups[0].memberNodeIds).toEqual(originalMembers);
  expect((await canonical(page)).nodes).toHaveLength(4);
});

test('workspace reload retains document-owned group view, viewport, same-name separation and fork independence', async ({ page }) => {
  let tenant = 'group-tenant-a';
  await page.route('**/v1/configuration', route => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 10485760, workspace: { tenantId: tenant } }) }));
  await page.route('**/v1/node-types', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events**', route => route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  const ready = () => expect.poll(() => page.evaluate(() => window.ravenroot?.workspacePersistence().writable)).toBe(true);
  await page.goto('/'); await ready();
  await page.locator('#replace-file-inp').setInputFiles({ name: 'same.graphml', mimeType: 'application/xml', buffer: importedGroups });
  await page.waitForFunction(() => window.ravenroot.workspace.active.graph.nodeMap.work);
  await select(page, await page.evaluate(() => window.cy.nodes('[rrVisualRole="summary"]').map(node => node.id())));
  await page.getByRole('button', { name: 'Expand', exact: true }).click();
  await page.waitForFunction(() => !window.ravenroot.workspace.active.visualGroupsRenderer.isAnimating);
  const ids = await page.evaluate(() => {
    const first = window.ravenroot.workspace.active;
    const second = window.ravenroot.openDocument({ name: 'same.graphml', mode: 'test', graph: structuredClone(first.graph) });
    window.ravenroot.activateDocument(first.id);
    window.cy.viewport({ zoom: 0.73, pan: { x: 72, y: 49 } });
    return [first.id, second];
  });
  await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
  expect(await page.evaluate(() => window.cy.zoom())).toBeCloseTo(0.73, 6);
  await page.reload(); await ready();
  const restored = await page.evaluate(() => ({
    ids: window.ravenroot.workspace.documents.map(owner => owner.id),
    states: window.ravenroot.workspace.documents.map(owner => owner.visualGroupState['group-work'].collapsed),
    zoom: window.cy.zoom(), pan: window.cy.pan(),
  }));
  expect(restored.ids).toEqual(ids); expect(restored.states).toEqual([false, true]);
  expect(restored.zoom).toBeCloseTo(0.73, 6); expect(restored.pan).toEqual({ x: 72, y: 49 });
  await page.evaluate(id => { window.ravenroot.activateDocument(id); window.ravenroot.forkDocument(); }, ids[1]);
  const fork = await page.evaluate(() => window.ravenroot.workspace.active.id);
  expect(fork).not.toBe(ids[1]);
  await select(page, await page.evaluate(() => window.cy.nodes('[rrVisualRole="summary"]').map(node => node.id())));
  await page.getByRole('button', { name: 'Expand', exact: true }).click();
  expect(await page.evaluate(id => window.ravenroot.workspace.find(id).visualGroupState['group-work'].collapsed, ids[1])).toBe(true);
  await page.locator('#btn-monitoring').click();
  await page.waitForFunction(() => window.ravenroot.workspace.active.renderer?.elasticMount?.visualGroupProjection);
  const header = page.getByRole('button', { name: 'Collapse visual group Processing, 2 members', exact: true });
  await header.focus();
  for (const collapsed of [true, false]) {
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => !window.ravenroot.workspace.active.renderer.elasticMount.visualGroupAnimating);
    await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
    await page.reload(); await ready();
    await page.waitForFunction(() => window.ravenroot.workspace.active.renderer?.elasticMount?.visualGroupProjection);
    const badge = page.getByRole('button', { name: `${collapsed ? 'Expand' : 'Collapse'} visual group Processing, 2 members`, exact: true });
    await expect(badge).toHaveAttribute('data-selected', 'true');
    await expect(badge).toBeFocused();
    expect(await page.evaluate(() => window.ravenroot.workspace.active.visualGroupState['group-work'].collapsed)).toBe(collapsed);
  }
  await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
  tenant = 'group-tenant-b'; await page.reload(); await ready();
  expect(await page.evaluate(() => window.ravenroot.workspace.documents.some(owner => Object.keys(owner.visualGroupState).length))).toBe(false);
  tenant = 'group-tenant-a'; await page.reload(); await ready();
  expect(await page.evaluate(() => window.ravenroot.workspace.documents.map(owner => owner.id))).toEqual([...ids, fork]);
});

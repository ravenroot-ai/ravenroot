import { expect, test } from '@playwright/test';

const baseNodes = ['dosomething', 'end', 'error', 'start'];
const baseEdges = ['edge-dosomething-end', 'edge-dosomething-error', 'edge-start-dosomething'];

const state = page => page.evaluate(() => ({
  nodes: window.cy.nodes('[rrVisualRole != "summary"][rrVisualRole != "header"]')
    .map(node => node.id()).sort(),
  edges: window.cy.edges('[rrVisualProjected != "true"]').map(edge => edge.id()).sort(),
  selected: window.cy.$(':selected').map(element => element.id()).sort(),
}));

async function select(page, ids) {
  await page.evaluate(elementIds => {
    window.cy.elements().unselect();
    elementIds.forEach(id => window.cy.getElementById(id).select());
  }, ids);
}

async function selectByReleasedPointer(page, ids) {
  const additiveKey = process.platform === 'darwin' ? 'Meta' : 'Control';
  for (const [index, id] of ids.entries()) {
    const point = await page.evaluate(elementId => {
      const element = window.cy.getElementById(elementId);
      const rendered = element.isNode() ? element.renderedPosition() : element.renderedMidpoint();
      const canvas = window.cy.container().getBoundingClientRect();
      return { x: canvas.left + rendered.x, y: canvas.top + rendered.y };
    }, id);
    if (index) await page.keyboard.down(additiveKey);
    await page.mouse.click(point.x, point.y);
    if (index) await page.keyboard.up(additiveKey);
  }
  await expect.poll(() => state(page).then(value => value.selected)).toEqual([...ids].sort());
}

async function deleteAndUndo(page, ids, key, expected) {
  await selectByReleasedPointer(page, ids);
  await page.keyboard.press(key);
  await expect.poll(() => state(page)).toMatchObject(expected);
  await page.locator('#btn-undo').click();
  await expect.poll(() => state(page)).toMatchObject({ nodes: baseNodes, edges: baseEdges });
}

test('stable selection shortcuts are identical through the packaged launch profile', async ({ page }) => {
  await page.goto('/');
  await page.waitForFunction(() => window.cy && window.ravenroot?.workspace.active);
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');

  await deleteAndUndo(page, ['dosomething'], 'Delete', {
    nodes: ['end', 'error', 'start'], edges: [], selected: [],
  });
  await deleteAndUndo(page, ['edge-start-dosomething'], 'Backspace', {
    nodes: baseNodes, edges: ['edge-dosomething-end', 'edge-dosomething-error'], selected: [],
  });
  await deleteAndUndo(page, ['start', 'edge-dosomething-end'], 'Delete', {
    nodes: ['dosomething', 'end', 'error'], edges: ['edge-dosomething-error'], selected: [],
  });

  await select(page, ['dosomething', 'end']);
  await page.keyboard.press('ControlOrMeta+g');
  const groupDialog = page.getByRole('dialog', { name: 'Group selection', exact: true });
  await groupDialog.getByRole('textbox', { name: 'Group name' }).fill('Launch profile group');
  await groupDialog.getByRole('button', { name: 'Create group', exact: true }).click();
  await page.waitForFunction(() => !window.ravenroot.workspace.active.visualGroupsRenderer.isAnimating);
  await select(page, await page.evaluate(() => window.cy.nodes('[rrVisualRole="summary"]').map(node => node.id())));
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('Backspace');
  await expect.poll(() => page.evaluate(() => JSON.parse(
    window.ravenroot.activeDocument().graph.graphProperties['ravenroot.ui.visualGroups'],
  ).groups.length)).toBe(0);
  await page.locator('#btn-undo').click();
  await expect.poll(() => page.evaluate(() => JSON.parse(
    window.ravenroot.activeDocument().graph.graphProperties['ravenroot.ui.visualGroups'],
  ).groups.length)).toBe(1);

  await select(page, ['start']);
  const name = page.locator('#node-editor input[name="name"]');
  await name.focus();
  await name.press('Backspace');
  await expect.poll(() => state(page)).toMatchObject({ nodes: baseNodes, selected: ['start'] });

  await page.locator('#btn-autosave').click();
  await name.fill('Unsaved launch-profile draft');
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('Delete');
  const guard = page.locator('#inspector-unsaved-dialog');
  await expect(guard).toBeVisible();
  await guard.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(name).toHaveValue('Unsaved launch-profile draft');
  await expect.poll(() => state(page)).toMatchObject({ nodes: baseNodes, selected: ['start'] });
});

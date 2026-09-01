import { expect, test } from '@playwright/test';

async function openEditable(page) {
  await page.goto('/');
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
}

async function selectOnly(page, id) {
  await page.evaluate(nodeId => {
    window.cy.$(':selected').unselect();
    if (nodeId) window.cy.getElementById(nodeId).select();
  }, id);
}

const selected = page => page.evaluate(() =>
  window.cy.$(':selected').map(element => element.id()).sort());

async function duplicateFromEditMenu(page) {
  await page.locator('#menu-edit').click();
  await page.getByRole('menuitem', { name: 'Duplicate Node' }).click();
}

const graphState = page => page.evaluate(() => ({
  nodes: window.cy.nodes().length,
  selected: window.cy.$(':selected').map(element => element.id()).sort(),
  depth: window.ravenroot.activeDocument().history.depth(),
}));

const nodePoint = (page, id) => page.evaluate(nodeId => {
  const point = window.cy.getElementById(nodeId).renderedPosition();
  const rect = window.cy.container().getBoundingClientRect();
  return { x: rect.left + point.x, y: rect.top + point.y };
}, id);

test('Autosave defaults ON, persists OFF across reload, and remains an accessible responsive command', async ({ page }) => {
  await page.goto('/');
  const toggle = page.locator('#btn-autosave');
  await expect(toggle).toHaveText('Autosave');
  await expect(toggle).toHaveAttribute('aria-pressed', 'true');
  await expect(toggle).toHaveAttribute('title', 'Save valid Inspector changes automatically');

  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-pressed', 'false');
  await page.reload();
  await expect(toggle).toHaveAttribute('aria-pressed', 'false');
  expect(await page.evaluate(() => localStorage.getItem('ravenroot.inspector.autosave.v1'))).toBe('false');

  await page.setViewportSize({ width: 800, height: 720 });
  // The compact breakpoint intentionally moves editor mirrors into the application menu.
  await page.locator('#menu-edit').click();
  const menuToggle = page.locator('#application-menu [data-command-id="edit.autosave"]');
  await expect(menuToggle).toBeVisible();
  await expect(menuToggle).toHaveAttribute('role', 'menuitemcheckbox');
  await expect(menuToggle).toHaveAttribute('aria-checked', 'false');
  const box = await menuToggle.boundingBox();
  expect(box.x + box.width).toBeLessThanOrEqual(800);
});

test('valid text autosaves as one focus-session undo and undo/redo refresh the Inspector', async ({ page }) => {
  await openEditable(page);
  await selectOnly(page, 'dosomething');
  const name = page.locator('#node-editor input[name="name"]');
  const original = await name.inputValue();
  await name.fill('');
  await name.pressSequentially('Automatically saved', { delay: 15 });

  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.activeDocument().graph.nodeMap.dosomething.name)).toBe('Automatically saved');
  await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');
  expect(await page.evaluate(() => window.ravenroot.activeDocument().history.depth())).toBe(1);

  await page.getByRole('button', { name: 'Save node' }).click();
  expect(await page.evaluate(() => window.ravenroot.activeDocument().history.depth())).toBe(1);

  await page.locator('#btn-undo').click();
  await expect(page.locator('#node-editor input[name="name"]')).toHaveValue(original);
  await expect(page.evaluate(() => window.cy.getElementById('dosomething').data('name'))).resolves.toBe(original);
  await page.locator('#btn-redo').click();
  await expect(page.locator('#node-editor input[name="name"]')).toHaveValue('Automatically saved');
  await expect(page.evaluate(() => window.cy.getElementById('dosomething').data('name')))
    .resolves.toBe('Automatically saved');
});

test('selection change flushes pending valid text and select/property removal commit immediately without duplicate listeners', async ({ page }) => {
  await openEditable(page);
  await selectOnly(page, 'dosomething');
  const documentState = () => page.evaluate(() => ({
    depth: window.ravenroot.activeDocument().history.depth(),
    node: structuredClone(window.ravenroot.activeDocument().graph.nodeMap.dosomething),
  }));

  // Move between the same node several times. This exercises the rerender/listener guard before
  // measuring the one real change below.
  await selectOnly(page, 'dosomething');
  await selectOnly(page, 'dosomething');
  const before = await documentState();
  await page.locator('#node-editor select[name="kind"]').selectOption('END');
  await expect.poll(async () => (await documentState()).node.kind).toBe('END');
  expect((await documentState()).depth).toBe(before.depth + 1);

  await page.locator('#btn-undo').click();
  await expect(page.locator('#node-editor select[name="kind"]')).toHaveValue(before.node.kind);

  await page.locator('[data-add-property="node-properties"]').click();
  const row = page.locator('#node-properties .property-row').last();
  await row.locator('[data-property-name]').fill('temporary');
  await row.locator('[data-property-value]').fill('value');
  await expect.poll(async () => (await documentState()).node.properties.temporary).toBe('value');
  await row.locator('[data-remove-property]').click();
  await expect.poll(async () => Object.hasOwn((await documentState()).node.properties, 'temporary')).toBe(false);

  const name = page.locator('#node-editor input[name="name"]');
  await name.fill('Selection flush');
  // The 180 ms debounce has not been awaited: leaving the node must synchronously finalise it.
  await selectOnly(page, 'end');
  await expect.poll(() => selected(page)).toEqual(['end']);
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.dosomething.name))
    .resolves.toBe('Selection flush');
});

test('Autosave OFF guards node changes with Cancel, Discard, Save, same-node, and deselect outcomes', async ({ page }) => {
  await openEditable(page);
  await page.locator('#btn-autosave').click();
  await expect(page.locator('#btn-autosave')).toHaveAttribute('aria-pressed', 'false');
  await selectOnly(page, 'start');
  const name = page.locator('#node-editor input[name="name"]');
  const original = await name.inputValue();
  await name.fill('Draft name');

  await selectOnly(page, 'end');
  const dialog = page.locator('#inspector-unsaved-dialog');
  await expect(dialog).toBeVisible();
  await expect.poll(() => selected(page)).toEqual(['start']);
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.start.name))
    .resolves.toBe(original);
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(name).toHaveValue('Draft name');
  await expect(name).toBeFocused();
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.start.name))
    .resolves.toBe(original);

  // Re-selecting the same node is not a transition and must not prompt.
  await selectOnly(page, 'start');
  await expect(dialog).not.toBeVisible();
  await expect(name).toHaveValue('Draft name');
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.start.name))
    .resolves.toBe(original);

  await selectOnly(page, 'end');
  await expect(dialog).toBeVisible();
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
  await expect.poll(() => selected(page)).toEqual(['end']);
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.start.name))
    .resolves.toBe(original);

  await selectOnly(page, 'start');
  await page.locator('#node-editor input[name="name"]').fill('Explicitly saved');
  await selectOnly(page, 'end');
  await dialog.locator('[data-inspector-unsaved-action="save"]').click();
  await expect.poll(() => selected(page)).toEqual(['end']);
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.start.name))
    .resolves.toBe('Explicitly saved');

  await selectOnly(page, 'end');
  await page.locator('#node-editor input[name="name"]').fill('Discard on deselect');
  await selectOnly(page, null);
  await expect(dialog).toBeVisible();
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
  await expect.poll(() => selected(page)).toEqual([]);
  await expect(page.locator('#info-body')).toContainText('Select a node or edge');
});

test('invalid drafts are retained with an explicit Discard path in Autosave ON and OFF', async ({ page }) => {
  await openEditable(page);
  for (const autosave of [true, false]) {
    if ((await page.locator('#btn-autosave').getAttribute('aria-pressed')) !== String(autosave)) {
      await page.locator('#btn-autosave').click();
    }
    await selectOnly(page, 'start');
    const name = page.locator('#node-editor input[name="name"]');
    await name.fill('');
    await selectOnly(page, 'end');
    const dialog = page.locator('#inspector-unsaved-dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('#inspector-unsaved-description')).toContainText('invalid Inspector changes');
    await expect.poll(() => selected(page)).toEqual(['start']);
    await dialog.locator('[data-inspector-unsaved-action="save"]').click();
    await expect(dialog).toBeVisible();
    await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
    await expect(name).toHaveValue('');
    await selectOnly(page, 'end');
    await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
    await expect.poll(() => selected(page)).toEqual(['end']);
  }
});

test('Duplicate defers mutation until Save or Discard and Cancel preserves the exact draft', async ({ page }) => {
  await openEditable(page);
  await page.locator('#btn-autosave').click();
  await selectOnly(page, 'dosomething');
  const name = page.locator('#node-editor input[name="name"]');
  const original = await name.inputValue();
  const before = await graphState(page);
  await name.fill('Duplicate draft');

  await duplicateFromEditMenu(page);
  const dialog = page.locator('#inspector-unsaved-dialog');
  await expect(dialog).toBeVisible();
  expect(await graphState(page)).toEqual(before);
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(name).toHaveValue('Duplicate draft');
  await expect(name).toBeFocused();
  expect(await graphState(page)).toEqual(before);

  await duplicateFromEditMenu(page);
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
  await expect.poll(async () => (await graphState(page)).nodes).toBe(before.nodes + 1);
  expect((await graphState(page)).selected).not.toEqual(before.selected);
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.dosomething.name))
    .resolves.toBe(original);

  await selectOnly(page, 'dosomething');
  await page.locator('#node-editor input[name="name"]').fill('Duplicate saved');
  const beforeSave = await graphState(page);
  await duplicateFromEditMenu(page);
  await dialog.locator('[data-inspector-unsaved-action="save"]').click();
  await expect.poll(async () => (await graphState(page)).nodes).toBe(beforeSave.nodes + 1);
  expect((await graphState(page)).depth).toBe(beforeSave.depth + 2);
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.dosomething.name))
    .resolves.toBe('Duplicate saved');
});

test('invalid Duplicate stays guarded in Autosave ON and OFF until explicit Discard', async ({ page }) => {
  await openEditable(page);
  for (const autosave of [true, false]) {
    if ((await page.locator('#btn-autosave').getAttribute('aria-pressed')) !== String(autosave)) {
      await page.locator('#btn-autosave').click();
    }
    await selectOnly(page, 'dosomething');
    const name = page.locator('#node-editor input[name="name"]');
    await name.fill('');
    const before = await graphState(page);
    await duplicateFromEditMenu(page);
    const dialog = page.locator('#inspector-unsaved-dialog');
    await expect(dialog).toBeVisible();
    expect(await graphState(page)).toEqual(before);
    await dialog.locator('[data-inspector-unsaved-action="save"]').click();
    await expect(dialog).toBeVisible();
    expect(await graphState(page)).toEqual(before);
    await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
    await expect(name).toHaveValue('');
    await duplicateFromEditMenu(page);
    await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
    await expect.poll(async () => (await graphState(page)).nodes).toBe(before.nodes + 1);
  }
});

test('other direct Inspector replacements share the draft guard before acting', async ({ page }) => {
  await openEditable(page);
  await page.locator('#btn-autosave').click();
  await selectOnly(page, 'dosomething');
  const name = page.locator('#node-editor input[name="name"]');
  await name.fill('Guard add form');
  const before = await graphState(page);
  const dialog = page.locator('#inspector-unsaved-dialog');

  await page.locator('#btn-modify').click();
  await expect(dialog).toBeVisible();
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
  await expect(name).toHaveValue('Guard add form');
  await expect(name).toBeFocused();

  await page.locator('#btn-add-node').click();
  await expect(dialog).toBeVisible();
  expect(await graphState(page)).toEqual(before);
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(name).toHaveValue('Guard add form');
  await expect(name).toBeFocused();

  await page.locator('#btn-add-node').click();
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
  await expect(page.locator('#node-editor input[name="id"]')).toHaveValue(/node-/);
  expect((await graphState(page)).nodes).toBe(before.nodes);
});

test('Connect defers exact Save, Discard and Cancel outcomes before arming or replacing the Inspector', async ({ page }) => {
  await openEditable(page);
  await page.locator('#btn-autosave').click();
  await selectOnly(page, 'dosomething');
  const name = page.locator('#node-editor input[name="name"]');
  const original = await name.inputValue();
  const before = await graphState(page);
  const dialog = page.locator('#inspector-unsaved-dialog');
  await name.fill('Connect draft');

  await page.locator('#btn-connect').click();
  await expect(dialog).toBeVisible();
  await expect(page.locator('#btn-connect')).toHaveAttribute('aria-pressed', 'false');
  expect(await graphState(page)).toEqual(before);
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(name).toHaveValue('Connect draft');
  await expect(name).toBeFocused();
  await expect(page.locator('#btn-connect')).toHaveAttribute('aria-pressed', 'false');

  await page.locator('#btn-connect').click();
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
  await expect(page.locator('#btn-connect')).toHaveAttribute('aria-pressed', 'true');
  expect((await graphState(page)).depth).toBe(before.depth);
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.dosomething.name))
    .resolves.toBe(original);

  await page.locator('#btn-connect').click();
  await selectOnly(page, 'dosomething');
  await page.locator('#node-editor input[name="name"]').fill('Connect saved');
  const beforeSave = await graphState(page);
  await page.locator('#btn-connect').click();
  await dialog.locator('[data-inspector-unsaved-action="save"]').click();
  await expect(page.locator('#btn-connect')).toHaveAttribute('aria-pressed', 'true');
  expect((await graphState(page)).depth).toBe(beforeSave.depth + 1);
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.dosomething.name))
    .resolves.toBe('Connect saved');
});

test('Connect keeps an invalid draft through Save and only Discard may arm it', async ({ page }) => {
  await openEditable(page);
  await page.locator('#btn-autosave').click();
  await selectOnly(page, 'dosomething');
  const name = page.locator('#node-editor input[name="name"]');
  await name.fill('');
  const before = await graphState(page);
  await page.locator('#btn-connect').click();
  const dialog = page.locator('#inspector-unsaved-dialog');
  await dialog.locator('[data-inspector-unsaved-action="save"]').click();
  await expect(dialog).toBeVisible();
  await expect(page.locator('#btn-connect')).toHaveAttribute('aria-pressed', 'false');
  expect(await graphState(page)).toEqual(before);
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(name).toHaveValue('');
  await expect(name).toBeFocused();
  await page.locator('#btn-connect').click();
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
  await expect(page.locator('#btn-connect')).toHaveAttribute('aria-pressed', 'true');
  expect((await graphState(page)).depth).toBe(before.depth);
});

test('Monitoring defers mode and Editing changes for Cancel, Discard, Save and invalid Save', async ({ page }) => {
  const prepare = async value => {
    await page.goto('/');
    await page.locator('#btn-modify').click();
    if ((await page.locator('#btn-autosave').getAttribute('aria-pressed')) !== 'false') {
      await page.locator('#btn-autosave').click();
    }
    await selectOnly(page, 'dosomething');
    const field = page.locator('#node-editor input[name="name"]');
    const original = await field.inputValue();
    await field.fill(value);
    return { field, original, before: await graphState(page) };
  };
  const dialog = page.locator('#inspector-unsaved-dialog');

  let state = await prepare('Monitoring draft');
  await page.locator('#btn-monitoring').click();
  await expect(dialog).toBeVisible();
  await expect(page.locator('#btn-design')).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
  expect(await graphState(page)).toEqual(state.before);
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(state.field).toHaveValue('Monitoring draft');
  await expect(state.field).toBeFocused();
  await expect(page.locator('#btn-design')).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');

  await page.locator('#btn-monitoring').click();
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
  await expect(page.locator('#btn-monitoring')).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'false');
  expect((await graphState(page)).depth).toBe(state.before.depth);
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.dosomething.name))
    .resolves.toBe(state.original);

  state = await prepare('Monitoring saved');
  await page.locator('#btn-monitoring').click();
  await dialog.locator('[data-inspector-unsaved-action="save"]').click();
  await expect(page.locator('#btn-monitoring')).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'false');
  expect((await graphState(page)).depth).toBe(state.before.depth + 1);
  await expect(page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.dosomething.name))
    .resolves.toBe('Monitoring saved');

  state = await prepare('');
  await page.locator('#btn-monitoring').click();
  await dialog.locator('[data-inspector-unsaved-action="save"]').click();
  await expect(dialog).toBeVisible();
  await expect(page.locator('#btn-design')).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
  expect(await graphState(page)).toEqual(state.before);
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
  await expect(page.locator('#btn-monitoring')).toHaveAttribute('aria-checked', 'true');
});

test('keyboard refusal and pointer edge start cannot replace a dirty draft before a decision', async ({ page }) => {
  await openEditable(page);
  await page.locator('#btn-autosave').click();
  await selectOnly(page, 'dosomething');
  const name = page.locator('#node-editor input[name="name"]');
  const before = await graphState(page);
  const dialog = page.locator('#inspector-unsaved-dialog');
  await name.fill('Edge draft');

  await page.locator('#cy-wrap').focus();
  await page.locator('#cy-wrap').press('e');
  await expect(dialog).toBeVisible();
  await expect(page.locator('#cy-wrap')).not.toHaveAttribute(
    'data-edge-gesture-state', /pressed|dragging|composing|target-/,
  );
  expect(await graphState(page)).toEqual(before);
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(name).toHaveValue('Edge draft');
  await expect(name).toBeFocused();

  await page.locator('#cy-wrap').focus();
  for (const direction of ['ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowUp']) {
    await page.locator('#cy-wrap').press(direction);
    const cursor = await page.evaluate(() => window.cy.nodes('.graph-cursor').first()?.id());
    if (cursor && cursor !== 'dosomething') break;
  }
  await expect.poll(() => page.evaluate(() => window.cy.nodes('.graph-cursor').first()?.id()))
    .not.toBe('dosomething');
  await page.locator('#cy-wrap').press('e');
  await expect(dialog).toBeVisible();
  await expect(page.locator('#cy-wrap')).not.toHaveAttribute(
    'data-edge-gesture-state', /pressed|dragging|composing|target-/,
  );
  expect(await graphState(page)).toEqual(before);
  await dialog.locator('[data-inspector-unsaved-action="cancel"]').click();
  await expect(name).toHaveValue('Edge draft');
  await expect(name).toBeFocused();

  const source = await nodePoint(page, 'start');
  await page.mouse.move(source.x, source.y);
  await page.mouse.down();
  await expect(dialog).toBeVisible();
  await expect(page.locator('#cy-wrap')).not.toHaveAttribute(
    'data-edge-gesture-state', /pressed|dragging|composing|target-/,
  );
  expect(await graphState(page)).toEqual(before);
  await page.mouse.up();
  await dialog.locator('[data-inspector-unsaved-action="discard"]').click();
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'composing');
  expect((await graphState(page)).depth).toBe(before.depth);
  await page.keyboard.press('Escape');
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
});

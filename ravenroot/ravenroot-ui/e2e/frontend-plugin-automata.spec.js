import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';

const packageDirectory = fileURLToPath(new URL('../public/examples/frontend-plugins/textbook-automata/', import.meta.url));
const fixture = readFileSync(new URL('../public/examples/frontend-plugins/textbook-automata/dfa-even-ones-sanitized.graphml', import.meta.url), 'utf8');
const mapping = readFileSync(new URL('../public/examples/frontend-plugins/textbook-automata/dfa-even-ones-presentation.json', import.meta.url), 'utf8');

async function openDrawingModelOptions(page) {
  const actions = page.locator('.drawing-model-actions');
  await actions.evaluate(element => { element.open = true; });
  await expect(actions.locator('.drawing-model-actions-menu')).toBeVisible();
}

test('keeps drawing options inside the established desktop command-bar height', async ({ page }) => {
  for (const viewport of [{ width: 1280, height: 800 }, { width: 1440, height: 900 }]) {
    await page.setViewportSize(viewport);
    await page.goto('/');
    const geometry = await page.evaluate(() => {
      const box = selector => {
        const value = document.querySelector(selector).getBoundingClientRect();
        return { x: value.x, y: value.y, width: value.width, height: value.height };
      };
      const options = box('#drawing-model-options');
      return { bar: box('#workflowbar'), save: box('#btn-export'), options,
        hit: document.elementFromPoint(options.x + options.width / 2, options.y + options.height / 2)?.id };
    });
    expect(geometry.bar.height).toBeLessThanOrEqual(75);
    expect(geometry.save.x + geometry.save.width).toBeLessThan(geometry.options.x);
    expect(geometry.hit).toBe('drawing-model-options');
    await page.locator('#drawing-model-options').click();
    await expect(page.locator('#drawing-model-select')).toBeVisible();
  }
});

test('installs and authors the textbook automata drawing model while keeping real evidence accessible', async ({ page }) => {
  await page.goto('/');
  await page.locator('#drawing-model-package-input').setInputFiles(packageDirectory);
  await expect(page.locator('#drawing-model-select option')).toContainText(['Integrated Design', 'Textbook DFA/NFA']);

  await page.evaluate(xml => window.ravenroot.replaceActiveDocumentFromText(xml, 'dfa-even-ones.graphml'), fixture);
  await openDrawingModelOptions(page);
  await page.locator('#drawing-model-mapping').click();
  await page.locator('#drawing-model-mapping-json').fill(mapping);
  await page.locator('#drawing-model-dialog button[type="submit"]').click();
  await page.locator('#drawing-model-select').selectOption('model|ai.ravenroot.examples.textbook-automata|textbook-automata');

  const scene = page.locator('.drawing-model-scene');
  await expect(scene).toBeVisible();
  await expect(scene.locator('circle[role="button"]')).toHaveCount(2);
  await expect(scene.getByText('qEven')).toBeVisible();
  await expect(scene.getByText('qOdd')).toBeVisible();
  await expect(page.locator('.automata-accepting')).toHaveCount(1);
  await expect(page.locator('[data-evidence-role="transition"]')).toHaveCount(4);

  const geometry = await scene.evaluate(svg => {
    const byEvidence = id => svg.querySelector(`[data-evidence-id="${id}"]`);
    const box = id => {
      const value = byEvidence(id).getBBox();
      return { x: value.x, y: value.y, width: value.width, height: value.height };
    };
    const state = id => ({ cx: byEvidence(id).getAttribute('cx'), cy: byEvidence(id).getAttribute('cy'),
      r: byEvidence(id).getAttribute('r') });
    const accepting = svg.querySelector('.automata-accepting');
    return { qEven: state('qEven'), qOdd: state('qOdd'), accepting: {
      cx: accepting.getAttribute('cx'), cy: accepting.getAttribute('cy'), r: accepting.getAttribute('r'),
    }, evenToOdd: box('qEven-1'), oddToEven: box('qOdd-1') };
  });
  expect(geometry.qEven).toEqual({ cx: '220', cy: '250', r: '46' });
  expect(geometry.qOdd).toEqual({ cx: '570', cy: '250', r: '46' });
  expect(geometry.accepting).toEqual({ cx: '220', cy: '250', r: '38' });
  expect(geometry.evenToOdd.y).toBeGreaterThanOrEqual(249);
  expect(geometry.oddToEven.y + geometry.oddToEven.height).toBeLessThanOrEqual(251);
  expect(geometry.evenToOdd.height).toBeGreaterThan(40);
  expect(geometry.oddToEven.height).toBeGreaterThan(40);

  await page.locator('[data-evidence-id="qEven"]').focus();
  await page.locator('[data-evidence-id="qEven"]').press('Enter');
  expect(await page.evaluate(() => window.cy.nodes(':selected').map(node => node.id())))
    .toEqual(['state-q-even']);

  await page.locator('[data-evidence-id="qEven-1"]').focus();
  await page.locator('[data-evidence-id="qEven-1"]').press('Enter');
  expect(await page.evaluate(() => window.cy.nodes(':selected').map(node => node.id()))).toEqual([]);
  expect(await page.evaluate(() => window.cy.edges(':selected').map(edge => edge.id())))
    .toEqual(['e10', 'e11', 'e13', 'e14', 'e15', 'e16', 'e18', 'e20', 'e22']);

  const exported = await page.evaluate(() => window.ravenroot.serializeGraphML(window.ravenroot.activeDocument().graph));
  expect(exported).toContain('ravenroot.frontendPresentation.v1');
  expect(exported).toContain('ravenroot.frontendDrawingModel.v1');

  await page.locator('#drawing-model-full-flow').click();
  await expect(scene).toBeHidden();
  await expect(page.locator('.doc-canvas canvas').first()).toBeVisible();
});

test('manages layout-only and renderer-only composition packages independently', async ({ page }, testInfo) => {
  const layoutDirectory = testInfo.outputPath('layout-package');
  const rendererDirectory = testInfo.outputPath('renderer-package');
  mkdirSync(layoutDirectory, { recursive: true }); mkdirSync(rendererDirectory, { recursive: true });
  writeFileSync(`${layoutDirectory}/ravenroot-frontend-plugin.json`, JSON.stringify({
    schema: 'ravenroot.frontend-plugin/v1', id: 'test.layout-only', name: 'Layout only', version: '1.0.0',
    apiVersion: '1.0', permissions: [], layouts: [{ id: 'layout', name: 'Test layout', entry: 'plugin.js',
      capabilities: ['directed-edges'] }], renderers: [], drawingModels: [], integrity: {},
  }));
  writeFileSync(`${layoutDirectory}/plugin.js`, `export default { layout(snapshot) { return {
    schema: 'ravenroot.layout-result/v1', positions: Object.fromEntries(snapshot.states.map((state, index) =>
      [state.id, { x: 180 + index * 300, y: 220 }])) }; } };`);
  writeFileSync(`${rendererDirectory}/ravenroot-frontend-plugin.json`, JSON.stringify({
    schema: 'ravenroot.frontend-plugin/v1', id: 'test.renderer-only', name: 'Renderer only', version: '1.0.0',
    apiVersion: '1.0', permissions: [], layouts: [], renderers: [{ id: 'renderer', name: 'Test renderer',
      entry: 'plugin.js', requires: ['directed-edges'] }], drawingModels: [], integrity: {},
  }));
  writeFileSync(`${rendererDirectory}/plugin.js`, `export default { render({ snapshot, layout }) { return {
    schema: 'ravenroot.scene/v1', width: 700, height: 440, elements: snapshot.states.map(state => ({
      type: 'circle', id: state.id, role: 'state', label: state.label, x: layout.positions[state.id].x,
      y: layout.positions[state.id].y, r: 35, className: 'automata-state' })) }; } };`);

  await page.goto('/');
  await page.locator('#drawing-model-package-input').setInputFiles(layoutDirectory);
  await page.locator('#drawing-model-package-input').setInputFiles(rendererDirectory);
  await openDrawingModelOptions(page);
  await page.locator('#drawing-model-manage').click();
  for (const name of ['Layout only', 'Renderer only']) {
    await page.getByRole('button', { name: `Disable ${name}` }).click();
    await expect(page.getByRole('button', { name: `Enable ${name}` })).toBeVisible();
    await page.getByRole('button', { name: `Enable ${name}` }).click();
    await expect(page.getByRole('button', { name: `Disable ${name}` })).toBeVisible();
  }
  await page.locator('#drawing-model-package-dialog').getByRole('button', { name: 'Close', exact: true }).click();
  await page.evaluate(xml => window.ravenroot.replaceActiveDocumentFromText(xml, 'dfa-even-ones.graphml'), fixture);
  await openDrawingModelOptions(page);
  await page.locator('#drawing-model-mapping').click();
  await page.locator('#drawing-model-mapping-json').fill(mapping);
  await page.locator('#drawing-model-dialog button[type="submit"]').click();
  await page.locator('#drawing-model-select')
    .selectOption('compose|test.layout-only|layout|test.renderer-only|renderer');
  await expect(page.locator('.drawing-model-scene circle[role="button"]')).toHaveCount(2);

  await openDrawingModelOptions(page);
  await page.locator('#drawing-model-manage').click();
  await page.getByRole('button', { name: 'Remove Layout only' }).click();
  await expect(page.getByText(/Layout only 1\.0\.0/)).toHaveCount(0);
  await page.getByRole('button', { name: 'Remove Renderer only' }).click();
  await expect(page.getByText('No frontend packages are installed.')).toBeVisible();
});

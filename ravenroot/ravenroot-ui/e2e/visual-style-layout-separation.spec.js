import { expect, test } from '@playwright/test';

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

const state = page => page.evaluate(() => {
  const owner = window.ravenroot.activeDocument();
  return {
    id: owner.id,
    renderMode: owner.renderMode,
    layoutMode: owner.layoutMode,
    visualStyle: owner.visualStyle,
    renderer: owner.renderer.kind,
    history: owner.history.state(),
    positions: Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()])),
    routed: owner.cy.edges().toArray()
      .every(edge => edge.style('curve-style') === 'unbundled-bezier'),
  };
});

async function chooseDesign(page) {
  await page.locator('#btn-design').click();
  await expect(page.locator('.doc-pane--active')).toHaveAttribute('aria-busy', 'true');
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', {
    timeout: 10_000,
  });
}

test.beforeEach(async ({ page }) => {
  await stubService(page);
  await page.goto('/');
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', {
    timeout: 10_000,
  });
});

test('Design is one complete render mode that relayouts and routes without editing history', async ({ page }) => {
  const before = await state(page);
  await page.evaluate(() => {
    window.cy.nodes().forEach((node, index) =>
      node.position({ x: -700 + index * 211, y: 900 - index * 173 }));
  });
  const scrambled = (await state(page)).positions;
  await chooseDesign(page);
  const after = await state(page);

  expect(after).toMatchObject({
    renderMode: 'design', layoutMode: 'cyto', visualStyle: 'cyto',
    renderer: 'cytoscape', history: before.history, routed: true,
  });
  expect(after.positions).not.toEqual(scrambled);
});

test('Monitoring and Design remain isolated per document as semantic choices', async ({ page }) => {
  const first = (await state(page)).id;
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => state(page)).toMatchObject({
    id: first, renderMode: 'monitoring', layoutMode: 'elastic',
    visualStyle: 'cyto', renderer: 'elastic',
  });

  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'design.graphml' }));
  await expect.poll(() => state(page)).toMatchObject({
    id: second, renderMode: 'design', visualStyle: 'cyto', renderer: 'cytoscape',
  });
  await chooseDesign(page);
  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  await expect.poll(() => state(page)).toMatchObject({
    id: first, renderMode: 'monitoring', layoutMode: 'elastic', renderer: 'elastic',
  });
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);
  await expect.poll(() => state(page)).toMatchObject({
    id: second, renderMode: 'design', layoutMode: 'cyto', renderer: 'cytoscape',
  });
});

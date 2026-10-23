import { expect, test } from '@playwright/test';

async function connect(page) {
  if (!process.env.RAVENROOT_SAGA_ORIGIN) throw new Error('Run through SagaAuthoringBrowserTest');
  page.on('dialog', dialog => dialog.accept(dialog.type() === 'prompt' ? dialog.defaultValue() || 'Reusable Log' : undefined));
  await page.goto(process.env.RAVENROOT_SAGA_ORIGIN);
  await expect(page.locator('#runtime-connection')).toHaveClass(/connected/, { timeout: 30_000 });
}
async function inspect(page) {
  return page.evaluate(async () => {
    const graph = window.ravenroot.activeDocument().graph;
    const xml = window.ravenroot.serializeGraphML(graph);
    const response = await fetch('/v1/graphs/inspect?purpose=EXECUTION', {
      method: 'POST', headers: { 'Content-Type': 'application/graphml+xml' }, body: xml,
    });
    return { status: response.status, summary: await response.json(), xml };
  });
}
async function run(page, button) {
  const started = page.waitForResponse(response => response.request().method() === 'POST'
    && (button === '#btn-run' ? new URL(response.url()).pathname.endsWith('/initial-start')
      : new URL(response.url()).pathname === '/v1/executions'));
  await page.locator(button).click();
  expect((await started).status()).toBe(202);
}
async function importGraph(page, xml, name) {
  await page.locator('#file-inp').setInputFiles({ name, mimeType: 'application/xml', buffer: Buffer.from(xml) });
  await expect(page.locator('#btn-run')).toBeEnabled();
}
function graph(logDeclarations = '', participant = false) {
  return `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
    <key id="kind" for="node" attr.name="kind" attr.type="string"/>
    <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
    ${['scope', 'step', 'participant', 'role', 'irreversible', 'businessCompletionRequired'].map(name =>
      `<key id="${name}" for="node" attr.name="saga.${name}" attr.type="string"/>`).join('')}
    <graph id="sanitized" edgedefault="directed">
      <node id="start"><data key="kind">START</data></node>
      <node id="log"><data key="kind">BEHAVIOR</data><data key="behavior">log</data>${logDeclarations}</node>
      <node id="transform"><data key="kind">BEHAVIOR</data><data key="behavior">test.saga.pure</data></node>
      ${participant ? '<node id="reserve"><data key="kind">BEHAVIOR</data><data key="behavior">test.saga.pure</data><data key="scope">order</data><data key="step">reserve</data><data key="participant">pure</data></node>' : ''}
      <node id="end"><data key="kind">END</data></node>
      <edge source="start" target="${participant ? 'reserve' : 'log'}"/>
      ${participant ? '<edge source="reserve" target="log"/>' : ''}
      <edge source="log" target="transform"/><edge source="transform" target="end"/>
    </graph></graphml>`;
}

test('New, default Log, edits, autosave, save, Test and Run preserve saga absence', async ({ page }) => {
  await connect(page);
  await page.locator('#btn-new').click();
  await page.locator('#btn-modify').click();
  await page.locator('#btn-add-node').click();
  await page.locator('[name="catalogBehavior"]').selectOption('log');
  await expect(page.locator('[data-saga-enabled]')).toHaveCount(0);
  const id = await page.locator('[name="id"]').inputValue();
  await page.locator('#node-editor button[type="submit"]').click();
  await expect.poll(() => page.evaluate(nodeId => Boolean(window.ravenroot.activeDocument().graph.nodeMap[nodeId]), id)).toBe(true);
  let report = await inspect(page);
  expect(report.summary.valid, JSON.stringify(report.summary)).toBe(true);
  expect(report.xml).not.toContain('saga.');
  await page.locator('[data-catalog-property="message"]').fill('sanitized {{payload}}');
  await page.locator('#node-editor [name="name"]').focus();
  await expect.poll(() => page.evaluate(nodeId => window.ravenroot.activeDocument().graph.nodeMap[nodeId].properties.message, id))
    .toBe('sanitized {{payload}}');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.locator('#node-palettes .palette-create input').fill('Ordinary nodes');
  await page.locator('#node-palettes .palette-create button').click();
  const palette = page.locator('#node-palettes .node-palette');
  await expect(palette).toHaveCount(1);
  await palette.locator('summary').click();
  await page.evaluate(nodeId => { const cy = window.ravenroot.activeDocument().cy; cy.elements().unselect(); cy.getElementById(nodeId).select(); }, id);
  await palette.locator('.palette-actions button').first().click();
  await expect(palette.locator('.node-template-insert')).toHaveCount(1);
  await palette.locator('.node-template-insert').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().graph.nodes.filter(node => node.behavior === 'log').length)).toBe(2);
  expect((await inspect(page)).xml).not.toContain('saga.');
  await run(page, '#btn-play');
  await expect(page.locator('#btn-run')).toBeEnabled();
  await run(page, '#btn-run');
  report = await inspect(page);
  expect(report.summary.valid).toBe(true);
  expect(report.xml).not.toContain('saga.');
  await importGraph(page, report.xml, 'ordinary-reopened.graphml');
  expect((await inspect(page)).xml).not.toContain('saga.');
});

test('scope authoring selects one eligible node while Log remains ordinary', async ({ page }) => {
  await connect(page);
  await importGraph(page, graph(), 'mixed-ordinary.graphml');
  await page.locator('#btn-modify').click();
  await page.locator('#btn-saga-scopes').click();
  await expect(page.locator('#saga-scope-editor')).toBeVisible();
  expect(await page.locator('#saga-scope-editor select option').allTextContents()).not.toContain('log');
  await page.locator('#saga-scope-editor [name="scope"]').fill('order');
  await page.locator('#saga-scope-editor [name="node"]').selectOption('transform');
  await page.locator('#saga-scope-editor button').click();
  await expect(page.locator('[data-saga-enabled]')).toBeChecked();
  await page.locator('[data-catalog-property="saga.step"]').fill('reserve');
  await page.locator('[data-catalog-property="saga.participant"]').selectOption('pure');
  await page.locator('#node-editor button[type="submit"]').click();
  const report = await inspect(page);
  expect(report.summary.valid, JSON.stringify(report.summary)).toBe(true);
  const nodes = await page.evaluate(() => window.ravenroot.activeDocument().graph.nodes.map(node => ({ id: node.id, properties: node.properties })));
  expect(Object.keys(nodes.find(node => node.id === 'log').properties).filter(key => key.startsWith('saga.'))).toEqual([]);
  expect(nodes.find(node => node.id === 'transform').properties['saga.scope']).toBe('order');
  await run(page, '#btn-run');
});

test('mixed sagas survive import/export and forged or incomplete declarations have node/property findings', async ({ page }) => {
  await connect(page);
  await importGraph(page, graph('', true), 'explicit-mixed.graphml');
  const accepted = await inspect(page);
  expect(accepted.summary.valid, JSON.stringify(accepted.summary)).toBe(true);
  await importGraph(page, accepted.xml, 'explicit-reopened.graphml');
  expect((await inspect(page)).summary.valid).toBe(true);
  await run(page, '#btn-run');
  await importGraph(page, graph('<data key="scope">order</data><data key="step">fake</data><data key="participant">pure</data>'), 'forged-log.graphml');
  const forged = await inspect(page);
  expect(forged.summary.valid).toBe(false);
  expect(forged.summary.findings[0]).toMatchObject({ nodeId: 'log', propertyName: 'saga.participant', reason: 'SAGA_PARTICIPANT_UNSUPPORTED' });
  await importGraph(page, graph('<data key="role">forward</data>'), 'incomplete.graphml');
  expect((await inspect(page)).summary.findings[0]).toMatchObject({ nodeId: 'log', propertyName: 'saga.scope', reason: 'SAGA_CONTRACT_INVALID' });
  await importGraph(page, graph('<data key="role">forward</data><data key="irreversible">false</data><data key="businessCompletionRequired">false</data>'), 'legacy-default-log.graphml');
  const migrated = await inspect(page);
  expect(migrated.summary.valid).toBe(true);
  expect(migrated.xml).not.toContain('<data key="role">');
});


test('Log before a real source remains ordinary for Test and source-session Run', async ({ page }) => {
  await connect(page);
  const xml = graph().replace('<node id="end">', '<node id="source"><data key="kind">BEHAVIOR</data><data key="behavior">test.saga.source</data></node><node id="end">')
    .replace('<edge source="log" target="transform"/>', '<edge source="log" target="source"/><edge source="source" target="transform"/>');
  await importGraph(page, xml, 'log-before-source.graphml');
  expect((await inspect(page)).summary.valid).toBe(true);
  await run(page, '#btn-play');
  await expect(page.locator('#btn-run')).toBeEnabled();
  const mutations = [];
  page.on('request', request => { if (request.method() === 'POST') mutations.push(new URL(request.url()).pathname); });
  const start = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/initial-start'));
  await page.locator('#btn-run').click();
  expect((await start).status()).toBe(202);
  expect(mutations.filter(path => path.endsWith('/initial-start'))).toHaveLength(1);
  expect(mutations).not.toContain('/v1/source-sessions');
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.phase)).toBe('READY');
  const binding = await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun);
  expect(binding.processInstanceId).toBeTruthy(); expect(binding.traversalId).toBeTruthy();
  expect((await inspect(page)).xml).not.toContain('<data key="scope">');
});

test('catalog and kind transitions preserve correlation controls and explicit saga opt-in independently', async ({ page }) => {
  await connect(page); await importGraph(page, graph(), 'combined-authoring.graphml');
  await page.locator('#btn-modify').click();
  await page.evaluate(() => { const cy = window.ravenroot.activeDocument().cy; cy.elements().unselect(); cy.getElementById('transform').select(); });
  await expect(page.locator('#node-editor [name="id"]')).toHaveValue('transform');
  await expect(page.locator('[data-saga-enabled]')).not.toBeChecked();
  await page.locator('[data-saga-enabled]').check();
  await page.locator('[data-catalog-property="saga.scope"]').fill('order');
  await page.locator('[data-catalog-property="saga.step"]').fill('reserve');
  await page.locator('[data-catalog-property="saga.participant"]').selectOption('pure');
  await page.locator('#node-editor [name="kind"]').selectOption('START');
  await expect(page.locator('[data-saga-enabled]')).toHaveCount(0);
  await page.locator('[data-catalog-property="correlation.mode"]').selectOption('ACCEPT_OR_CREATE');
  await page.locator('[data-catalog-property="correlation.selector"]').fill('/businessId');
  await page.locator('#node-editor [name="catalogBehavior"]').selectOption('timer');
  await expect(page.locator('[data-catalog-property="correlation.selector"]')).toHaveValue('/businessId');
  await expect(page.locator('[data-catalog-property="correlation.mode"]')).toHaveCount(1);
  await expect(page.locator('[data-saga-enabled]')).toHaveCount(0);
  await page.locator('#node-editor [name="catalogBehavior"]').selectOption('http-request');
  await expect(page.locator('[data-saga-enabled]')).not.toBeChecked();
  await expect(page.locator('[data-catalog-property="saga.scope"]')).toBeDisabled();
  await expect(page.locator('[data-catalog-property="saga.scope"]')).toHaveCount(1);
  await page.locator('[data-saga-enabled]').check();
  await expect(page.locator('[data-catalog-property="saga.scope"]')).toBeEnabled();
  await page.locator('[data-saga-enabled]').uncheck();
  await expect(page.locator('[data-catalog-property="saga.scope"]')).toBeDisabled();
});

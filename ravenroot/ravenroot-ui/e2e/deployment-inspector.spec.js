import { createHash } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { deferred, deploymentService } from './deployment-run-fixture.mjs';
import { keepAuthenticationInMemory } from './memory-only-auth.mjs';

const deploymentId = 'pinned-configuration';
const incarnationId = 'runtime-1';
const reference = 'rrc_0123456789abcdef0123456789abcdef';
const credentialLabel = 'Test adapter credential';
const expression = 'payload.count > 0 && payload.ready';
const settings = '\n{\n  "nested": { "enabled": false, "count": 0 }\n}\n';
const custom = '{\n  "route": "original",\n  "limit": 0\n}';

const property = (name, displayName, type, options = {}) => ({
  name, displayName, type, description: `Configuration for ${displayName}.`,
  required: false, allowedValues: [], defaultValue: '', ...options,
});

const descriptor = {
  behavior: 'external.consume', displayName: 'Configurable source', category: 'Sources',
  visualType: 'actor', defaultNature: 'SOURCE', allowedNatures: ['SOURCE'],
  properties: [
    property('mode', 'Mode', 'STRING', { allowedValues: ['basic', 'advanced'] }),
    property('enabled', 'Enabled', 'BOOLEAN', { defaultValue: 'true' }),
    property('count', 'Count', 'INTEGER', { defaultValue: '3' }),
    property('settings', 'Settings', 'TEXT'),
    property('expression', 'Expression', 'CEL_EXPRESSION'),
    property('credentialRef', 'Credential', 'SECRET_REFERENCE'),
    property('optional', 'Advanced-only field', 'STRING', {
      visibleWhen: { contract: 'ravenroot.property-condition/1', property: 'mode', operator: 'EQUALS', values: ['advanced'] },
    }),
  ],
};

const deployedGraph = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
  <key id="name" for="node" attr.name="name" attr.type="string"/>
  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
  <key id="mode" for="node" attr.name="mode" attr.type="string"/>
  <key id="enabled" for="node" attr.name="enabled" attr.type="boolean"/>
  <key id="count" for="node" attr.name="count" attr.type="int"/>
  <key id="settings" for="node" attr.name="settings" attr.type="string"/>
  <key id="expression" for="node" attr.name="expression" attr.type="string"/>
  <key id="credential" for="node" attr.name="credentialRef" attr.type="string"/>
  <key id="optional" for="node" attr.name="optional" attr.type="string"/>
  <key id="custom" for="node" attr.name="custom.routing" attr.type="string"/>
  <graph id="configured" edgedefault="directed">
    <node id="start"><data key="kind">START</data></node>
    <node id="source"><data key="kind">BEHAVIOR</data><data key="name">Deployed source</data>
      <data key="behavior">external.consume</data><data key="mode">basic</data>
      <data key="enabled">false</data><data key="count">0</data><data key="settings">${settings}</data>
      <data key="expression">${expression.replaceAll('&', '&amp;')}</data><data key="credential">${reference}</data>
      <data key="optional">retained but irrelevant</data><data key="custom">${custom}</data>
    </node>
    <node id="end"><data key="kind">END</data></node>
    <edge id="first" source="start" target="source"/>
    <edge id="second" source="source" target="end"/>
  </graph>
</graphml>`;

const graphVersion = createHash('sha256').update(deployedGraph).digest('hex');
const binding = { deploymentId, incarnationId, graphVersion };

async function setup(page, {
  denied = false, wrongVersion = false, sourceGraph = deployedGraph, catalog = [descriptor],
} = {}) {
  const backend = await deploymentService(page);
  const graphVersion = createHash('sha256').update(sourceGraph).digest('hex');
  const binding = { deploymentId, incarnationId, graphVersion };
  const status = {
    deploymentId, tenantId: 'tenant', graphVersion, state: 'READY', sourceCount: 1,
    runtimeIncarnationId: incarnationId, registrationIdentity: 'registered-configuration',
    scope: 'LOCAL_PROCESS', diagnostic: null, continuity: 'DURABLE',
    deploymentGeneration: 1, deploymentRevision: 1, desiredState: 'READY', observedState: 'READY', recoveryFailure: null,
  };
  backend.held.set(deploymentId, status);
  await page.route('**/v1/deployments', route => route.fulfill({ json: { deployments: [status] } }));
  await page.route(`**/v1/deployments/${deploymentId}/view`, route => route.fulfill({ json: {
    viewerSourceVersion: '1', source: { kind: 'deployment', ...binding }, lifecycle: 'READY',
    projection: {
      viewerContractVersion: '1.0',
      nodes: [
        { id: 'start', kind: 'START', label: 'Start', layout: { x: 90, y: 160 } },
        { id: 'source', kind: 'BEHAVIOR', label: 'Source drawing', layout: { x: 300, y: 160 } },
        { id: 'end', kind: 'END', label: 'End', layout: { x: 510, y: 160 } },
      ],
      edges: [{ id: 'first', source: 'start', target: 'source' }, { id: 'second', source: 'source', target: 'end' }],
    },
  } }));
  const sourceReads = [];
  await page.route(`**/v1/deployments/${deploymentId}/source?**`, route => {
    const request = route.request();
    sourceReads.push({ method: request.method(), query: Object.fromEntries(new URL(request.url()).searchParams) });
    if (denied) return route.fulfill({ status: 403, json: { code: 'FORBIDDEN' } });
    return route.fulfill({ json: {
      ...binding, graphVersion: wrongVersion ? 'unrelated-version' : graphVersion,
      graphMlBase64: Buffer.from(sourceGraph).toString('base64'),
    } });
  });
  await page.route('**/v1/node-types', route => route.fulfill({ json: catalog }));
  await page.route('**/v1/credentials', route => route.fulfill({ json: { credentials: [{
    reference, label: credentialLabel, scheme: 'api-key', username: '', createdAt: '2026-10-25T12:00:00Z',
  }] } }));
  await keepAuthenticationInMemory(page);
  await page.goto('/');
  await expect.poll(() => page.evaluate(() => window.ravenroot?.workspacePersistence().authority.tenantId)).toBe('tenant');
  await expect(page.locator('[data-catalog-add="external.consume"]')).toBeVisible();
  return { sourceReads, calls: backend.calls };
}

async function openReadOnlyView(page) {
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Deployments…' }).click();
  await page.locator(`[data-deployment-view="${deploymentId}"]`).click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().graph.format)).toBe('deployment');
}

const selectSource = page => page.evaluate(() => {
  window.cy.getElementById('source').select();
});
const formHeadings = panel => panel.locator('label:visible, .editor-section-title:visible')
  .evaluateAll(elements => elements.map(element => {
    const copy = element.cloneNode(true);
    copy.querySelectorAll('button').forEach(button => button.remove());
    return copy.textContent.replace(/\s+/g, ' ').trim();
  }));
const field = (panel, name) => panel.locator(`[data-catalog-property="${name}"]`);

test('deployed Inspector matches editor controls and retains values across draft edits', async ({ page }, testInfo) => {
  const { sourceReads, calls } = await setup(page);
  await page.locator('#file-inp').setInputFiles({
    name: 'configured.graphml', mimeType: 'application/xml', buffer: Buffer.from(deployedGraph),
  });
  await page.locator('#btn-modify').click();
  await selectSource(page);
  const editor = page.locator('#node-editor');
  await expect(field(editor, 'credentialRef').locator('option:checked')).toHaveText(credentialLabel);
  const headings = await formHeadings(editor);
  await page.screenshot({ path: testInfo.outputPath('editor.png') });

  const editorName = editor.locator('input[name="name"]');
  await editorName.fill('Edited draft source');
  await editorName.press('Tab');
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().graph.nodeMap.source.name))
    .toBe('Edited draft source');
  const draftId = await page.evaluate(() => window.ravenroot.activeDocument().id);

  await openReadOnlyView(page);
  expect(sourceReads).toEqual([]);
  await selectSource(page);
  const inspector = page.locator('section[data-deployment-inspector-node="source"]');
  await expect(inspector).toBeVisible();
  expect(await formHeadings(inspector)).toEqual(headings);
  await expect(inspector.locator('input[name="name"]')).toHaveValue('Deployed source');
  await expect(field(inspector, 'enabled')).toHaveValue('false');
  await expect(field(inspector, 'count')).toHaveValue('0');
  await expect(field(inspector, 'count')).toHaveAttribute('type', 'number');
  await expect(field(inspector, 'settings')).toHaveValue(settings);
  await expect(field(inspector, 'expression')).toHaveValue(expression);
  await expect(field(inspector, 'optional')).toBeHidden();
  await expect(field(inspector, 'credentialRef')).toHaveValue(reference);
  await expect(field(inspector, 'credentialRef').locator('option:checked')).toHaveText(credentialLabel);
  await expect(inspector.locator('textarea[data-property-value]')).toHaveValue(custom);
  expect(sourceReads).toEqual([{ method: 'GET', query: { incarnationId, graphVersion } }]);
  expect(await inspector.locator('input, select, textarea').evaluateAll(controls =>
    controls.every(control => control.disabled || control.readOnly))).toBe(true);
  await expect(inspector.locator('button:not([data-contextual-help])')).toHaveCount(0);
  await expect(page.locator('#btn-modify')).toBeDisabled();
  await page.screenshot({ path: testInfo.outputPath('deployed.png') });

  await inspector.getByRole('button', { name: 'Help: Settings' }).click();
  await expect(page.locator('#contextual-help-popover')).toContainText('Configuration for Settings.');
  await page.keyboard.press('Escape');
  const longValue = field(inspector, 'settings');
  await longValue.press('End');
  await longValue.press('x');
  await longValue.press('Enter');
  await expect(longValue).toHaveValue(settings);
  await page.screenshot({ path: testInfo.outputPath('deployed-settings.png') });

  await page.evaluate(() => { document.activeElement.blur(); window.cy.getElementById('source').emit('dbltap'); });
  await page.keyboard.press('Delete');
  await page.keyboard.press('ControlOrMeta+z');
  await page.keyboard.press('ControlOrMeta+s');
  await expect(inspector.locator('input[name="name"]')).toHaveValue('Deployed source');
  expect(await page.evaluate(id => {
    const { graph, history } = window.ravenroot.activeDocument();
    return { properties: graph.nodeMap.source.properties, nodes: graph.nodes.length, history: history.depth(),
      draftName: window.ravenroot.workspace.find(id).graph.nodeMap.source.name };
  }, draftId)).toEqual({ properties: {}, nodes: 3, history: 0, draftName: 'Edited draft source' });
  expect(calls.every(call => call.method === 'GET')).toBe(true);
});

test('deployed defaults drive catalog visibility and preserve explicit empty and false overrides', async ({ page }) => {
  const sourceGraph = deployedGraph
    .replace('<key id="mode" for="node" attr.name="mode" attr.type="string"/>',
      '<key id="mode" for="all" attr.name="mode" attr.type="string"><default>advanced</default></key>')
    .replace('<key id="enabled" for="node" attr.name="enabled" attr.type="boolean"/>',
      '<key id="enabled" for="node" attr.name="enabled" attr.type="boolean"><default>true</default></key>')
    .replace('<key id="count" for="node" attr.name="count" attr.type="int"/>',
      '<key id="count" for="node" attr.name="count" attr.type="int"><default>0</default></key>')
    .replace('<key id="custom" for="node" attr.name="custom.routing" attr.type="string"/>',
      `<key id="custom" for="all" attr.name="custom.routing" attr.type="string"><default>${custom}</default></key>
       <key id="emptyChoice" for="node" attr.name="emptyChoice" attr.type="string"><default>graph</default></key>`)
    .replace('<data key="mode">basic</data>', '<data key="emptyChoice"/>')
    .replace('<data key="count">0</data>', '')
    .replace(`<data key="custom">${custom}</data>`, '');
  const properties = [...descriptor.properties,
    property('emptyChoice', 'Optional choice', 'STRING', { allowedValues: ['graph', 'catalog'], defaultValue: 'catalog' })];
  await setup(page, { sourceGraph, catalog: [{ ...descriptor, properties }] });
  await openReadOnlyView(page);
  await selectSource(page);
  const inspector = page.locator('section[data-deployment-inspector-node="source"]');
  await expect(field(inspector, 'mode')).toHaveValue('advanced');
  await expect(field(inspector, 'optional')).toBeVisible();
  await expect(field(inspector, 'optional')).toHaveValue('retained but irrelevant');
  await expect(field(inspector, 'count')).toHaveValue('0');
  await expect(field(inspector, 'enabled')).toHaveValue('false');
  await expect(field(inspector, 'emptyChoice')).toHaveValue('');
  await expect(inspector.locator('textarea[data-property-value]')).toHaveValue(custom);
});

test('deployed catalog retains multiline strings and an exact signed integer', async ({ page }, testInfo) => {
  const multiline = '\nfirst line\nsecond line\n';
  const sourceGraph = deployedGraph
    .replace('<key id="optional"', '<key id="multiline" for="node" attr.name="multiline" attr.type="string"/>\n  <key id="optional"')
    .replace('<data key="optional">', `<data key="multiline">${multiline}</data><data key="optional">`)
    .replace('<data key="count">0</data>', '<data key="count">+7</data>');
  const properties = [...descriptor.properties, property('multiline', 'Multiline string', 'STRING')];
  await setup(page, { sourceGraph, catalog: [{ ...descriptor, properties }] });
  await openReadOnlyView(page);
  await selectSource(page);
  const inspector = page.locator('section[data-deployment-inspector-node="source"]');
  const text = field(inspector, 'multiline');
  await expect(text).toHaveValue(multiline);
  await expect(text).toHaveAccessibleName('Multiline string');
  await expect(text).toHaveAttribute('readonly', '');
  await expect(field(inspector, 'count')).toHaveValue('+7');
  await expect(field(inspector, 'count')).toHaveAttribute('readonly', '');
  await expect(field(inspector, 'credentialRef')).toBeDisabled();
  await text.focus();
  await page.screenshot({ path: testInfo.outputPath('deployed-multiline-and-signed.png') });
});

test('deployed governed program retains its multiline test payload', async ({ page }) => {
  const payload = '{\n  "approved": true\n}';
  const sourceGraph = deployedGraph
    .replace('<key id="optional"', '<key id="testPayload" for="node" attr.name="testPayload" attr.type="string"/>\n  <key id="optional"')
    .replace('<data key="optional">', `<data key="testPayload">${payload}</data><data key="optional">`)
    .replace('<data key="behavior">external.consume</data>', '<data key="behavior">program</data>');
  const program = { ...descriptor, behavior: 'program', displayName: 'Program', properties: [] };
  await setup(page, { sourceGraph, catalog: [descriptor, program] });
  await openReadOnlyView(page);
  await selectSource(page);
  const inspector = page.locator('section[data-deployment-inspector-node="source"]');
  await expect(field(inspector, 'testPayload')).toHaveValue(payload);
  await expect(field(inspector, 'testPayload')).toHaveAttribute('readonly', '');
  await expect(inspector.locator('[data-program-operation]')).toHaveCount(0);
});

for (const result of ['denied', 'wrongVersion']) {
  test(`deployed Inspector keeps the drawing when the source is ${result}`, async ({ page }, testInfo) => {
    const { sourceReads } = await setup(page, { [result]: true });
    await openReadOnlyView(page);
    await selectSource(page);
    await expect(page.locator('#info-body')).toContainText('The deployed node configuration could not be loaded.');
    await expect(page.locator('#info-title')).toHaveText('Source drawing');
    await expect(page.locator('#info-body [data-catalog-property]')).toHaveCount(0);
    expect(sourceReads).toEqual([{ method: 'GET', query: { incarnationId, graphVersion } }]);
    expect(await page.evaluate(() => window.cy.nodes().map(node => node.id()))).toEqual(['start', 'source', 'end']);
    await page.screenshot({ path: testInfo.outputPath('restricted-drawing.png') });
  });
}

test('deployment invalidation retires displayed source configuration', async ({ page }) => {
  const { sourceReads } = await setup(page);
  const invalidate = deferred();
  await page.route(`**/v1/deployments/${deploymentId}/events?**`, async route => {
    await invalidate.promise;
    await route.fulfill({ contentType: 'text/event-stream', body:
      `event: source-invalidated\ndata: ${JSON.stringify({ ...binding, reason: 'UNDEPLOYED', cursor: 'c'.repeat(43) })}\n\n`,
    });
  });
  await openReadOnlyView(page);
  await selectSource(page);
  await expect(page.locator('#info-title')).toHaveText('Deployed source');
  invalidate.resolve();
  await expect(page.locator('#info-body')).toContainText('The deployed node configuration could not be loaded.');
  await expect(page.locator('#info-title')).toHaveText('Source drawing');
  await expect(page.locator('#info-body [data-catalog-property]')).toHaveCount(0);
  expect(sourceReads).toHaveLength(1);
});

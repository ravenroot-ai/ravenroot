import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';

const property = (name, type, defaultValue = '') => ({ name, displayName: name, type, defaultValue,
  required: false, description: `Governed ${name}`, allowedValues: [], adapterBinding: false });
const catalog = [
  { behavior: 'workspace', displayName: 'Workspace', category: 'Resources', visualType: 'workspace',
    properties: [property('workspaceProfile', 'STRING', 'development')], capabilities: [] },
  { behavior: 'agent', displayName: 'Agent', category: 'AI', visualType: 'agent', agentic: true,
    properties: [property('workspaceRef', 'WORKSPACE_REFERENCE'), property('agentDefinition', 'STRING'),
      property('agentVersion', 'INTEGER', '1')], capabilities: ['ai', 'agentic'] },
];

test('named Agents select only same-document Workspaces and preserve their typed references through GraphML', async ({ page }) => {
  await page.route('**/v1/node-types', route => route.fulfill({ json: catalog }));
  await page.route('**/v1/runner-plane/catalog', route => route.fulfill({ json: { items:
    ['polaris', 'betelgeuse', 'antares'].map(name => ({ kind: 'AGENT_DEFINITION', name, version: 1, approved: true })) } }));
  await page.route('**/v1/events', route => route.fulfill({ status: 204, body: '' }));
  await page.goto('/');
  const graph = await readFile(new URL('../../../docs/examples/governed-runner/three-agents.graphml', import.meta.url), 'utf8');
  await page.locator('#file-inp').setInputFiles({ name: 'other.graphml', mimeType: 'application/xml',
    buffer: Buffer.from(graph.replaceAll('repository', 'foreign-workspace')) });
  await page.locator('#file-inp').setInputFiles({ name: 'team.graphml', mimeType: 'application/xml', buffer: Buffer.from(graph) });
  const palette = page.locator('#node-catalog');
  await expect(palette.locator('[data-catalog-add="workspace"]')).toBeVisible();
  await expect(palette.locator('[data-catalog-add="workspace-agent"]')).toHaveCount(0);
  for (const name of ['polaris', 'betelgeuse', 'antares'])
    await expect(palette.locator(`[data-catalog-add="agent:${name}:1"]`)).toBeVisible();
  await palette.locator('[data-catalog-add="agent:antares:1"]').click();
  const reference = page.locator('#node-editor [data-catalog-property="workspaceRef"]');
  await expect(reference).toHaveJSProperty('tagName', 'SELECT');
  expect(await reference.locator('option').evaluateAll(options => options.map(option => option.value))).toEqual(['', 'repository']);
  await reference.selectOption('repository');
  await page.locator('#node-editor input[name="id"]').fill('another-review');
  await page.locator('#node-editor button[type="submit"]').click();
  const saved = await page.evaluate(() => window.ravenroot.serializeGraphML(window.ravenroot.activeDocument().graph));
  await page.locator('#file-inp').setInputFiles({ name: 'roundtrip.graphml', mimeType: 'application/xml', buffer: Buffer.from(saved) });
  const identities = await page.evaluate(() => window.ravenroot.activeDocument().graph.nodes
    .filter(node => node.behavior === 'agent').map(node => ({ id: node.id,
      workspace: node.properties.workspaceRef, definition: node.properties.agentDefinition })));
  expect(identities).toEqual(expect.arrayContaining([
    { id: 'polaris', workspace: 'repository', definition: 'polaris' },
    { id: 'betelgeuse', workspace: 'repository', definition: 'betelgeuse' },
    { id: 'antares', workspace: 'repository', definition: 'antares' },
    { id: 'another-review', workspace: 'repository', definition: 'antares' },
  ]));
});

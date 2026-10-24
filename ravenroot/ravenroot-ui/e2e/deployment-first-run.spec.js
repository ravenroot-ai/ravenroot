import { expect, test } from '@playwright/test';
import { keepAuthenticationInMemory } from './memory-only-auth.mjs';
import { PID, deferred, deploymentService as service } from './deployment-run-fixture.mjs';
const graph = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns"><key id="kind" for="node" attr.name="kind" attr.type="string"/><key id="b" for="node" attr.name="behavior" attr.type="string"/><graph id="mixed" edgedefault="directed"><node id="start"><data key="kind">START</data></node><node id="source"><data key="kind">BEHAVIOR</data><data key="b">external.consume</data></node><node id="end"><data key="kind">END</data></node><node id="error"><data key="kind">ERROR</data></node><edge source="start" target="source"/><edge source="source" target="end"/></graph></graphml>`;
async function boot(page) {
  page.on('dialog', dialog => dialog.accept()); await page.goto('/');
  await expect.poll(() => page.evaluate(() => window.ravenroot?.workspacePersistence().authority.tenantId)).toBe('tenant');
  await page.locator('#file-inp').setInputFiles({ name: 'X.graphml', mimeType: 'application/xml', buffer: Buffer.from(graph) });
  await expect(page.locator('#btn-run')).toBeEnabled(); return page.evaluate(() => window.ravenroot.activeDocument().id);
}
test.beforeEach(async ({ page }) => keepAuthenticationInMemory(page));
test('saves origin before network and binds X after focus moves to Y', async ({ page }) => {
  const gate = deferred(), backend = await service(page, { gate }), origin = await boot(page);
  await page.locator('#execution-payload').fill('original-X'); await page.locator('#btn-run').click(); const id = await backend.registered;
  const other = await page.evaluate(() => window.ravenroot.openDocument({ name: 'Y.graphml' })); gate.resolve();
  const pane = page.locator(`.doc-pane[data-document-id="${origin}"]`); await expect(pane).toHaveAttribute('data-deployment-run-phase', 'READY');
  await expect.poll(() => backend.calls.filter(c => c.action === 'initial-start').length).toBe(1);
  expect(JSON.parse(backend.calls.find(c => c.action === 'initial-start').body).input).toBe('original-X');
  expect(await page.evaluate(() => window.ravenroot.activeDocument().id)).toBe(other); expect(await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun)).toBeNull();
  await expect(pane.locator('.doc-run-binding')).toContainText(id);
});
for (const lose of ['registration', 'activation']) test(`lost ${lose} response reuses saved identity`, async ({ page }) => {
  const backend = await service(page, { lose }); await boot(page); await page.locator('#btn-run').click(); await backend.registered;
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.phase)).toBe('RECONCILE');
  const id = await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun.deploymentId);
  await page.locator('#btn-run').click(); await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.processInstanceId)).toBe(PID);
  expect(backend.calls.filter(c => c.method === 'POST' && !c.action)).toHaveLength(1); expect(backend.calls.filter(c => c.action === 'initial-start')).toHaveLength(1);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun.deploymentId)).toBe(id);
});
test('reload restores association without activation and later Run starts no second traversal', async ({ page }) => {
  const backend = await service(page); await boot(page); await page.locator('#btn-run').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.processInstanceId)).toBe(PID);
  await page.evaluate(() => window.ravenroot.flushWorkspacePersistence()); const before = backend.calls.filter(c => c.method === 'POST').length;
  const id = await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun.deploymentId);
  await page.reload(); await expect.poll(() => page.evaluate(() => window.ravenroot?.activeDocument()?.deploymentRun?.deploymentId)).toBe(id);
  expect(backend.calls.filter(c => c.method === 'POST')).toHaveLength(before); await page.locator('#btn-run').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun.phase)).toBe('READY'); expect(backend.calls.filter(c => c.action === 'initial-start')).toHaveLength(1);
});
test('quota failure refuses all deployment mutations with a visible reason', async ({ page }) => {
  const backend = await service(page); await boot(page); await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
  await page.evaluate(() => { const original = IDBDatabase.prototype.transaction;
    IDBDatabase.prototype.transaction = function (names, mode, ...rest) { if (mode === 'readwrite') throw new DOMException('quota fixture', 'QuotaExceededError'); return original.call(this, names, mode, ...rest); };
  });
  await page.locator('#btn-run').click(); await expect(page.locator('#activity-log')).toContainText('Run needs a saved workspace association'); expect(backend.calls.filter(c => c.method === 'POST')).toHaveLength(0);
});

import { expect, test } from '@playwright/test';
import { existsSync, writeFileSync, watch } from 'node:fs';
import { join } from 'node:path';
const ORIGIN = process.env.RAVENROOT_DEPLOYMENT_PERSISTENCE_ORIGIN;
const OUTPUT = process.env.RAVENROOT_FIRST_RUN_RESTART_DIR;
const graph = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns"><key id="kind" for="node" attr.name="kind" attr.type="string"/><graph id="first" edgedefault="directed"><node id="start"><data key="kind">START</data></node><node id="end"><data key="kind">END</data></node><edge source="start" target="end"/></graph></graphml>`;
async function restartReady(round) {
  const expected = `ready-${round + 1}`;
  if (existsSync(join(OUTPUT, expected))) return;
  await new Promise((resolve, reject) => {
    const watcher = watch(OUTPUT, (_, file) => { if (file === expected && existsSync(join(OUTPUT, expected))) { clearTimeout(timeout); watcher.close(); resolve(); } });
    const timeout = setTimeout(() => { watcher.close(); reject(new Error('JVM restart timed out')); }, 55_000);
    if (existsSync(join(OUTPUT, expected))) { clearTimeout(timeout); watcher.close(); resolve(); }
  });
}
test('saved admission, never admitted and lost response rebind safely across real JVM crashes', async ({ page }) => {
  test.setTimeout(180_000);
  if (!ORIGIN || !OUTPUT) throw new Error('requires JVM restart harness');
  page.on('dialog', dialog => dialog.accept());
  await page.goto(ORIGIN);
  await expect(page.locator('#runtime-connection')).toHaveClass(/connected/, { timeout: 30_000 });
  for (const [round, mode] of ['saved', 'unadmitted', 'response-lost'].entries()) {
    await page.locator('#file-inp').setInputFiles({ name: `restart-${mode}.graphml`, mimeType: 'application/xml', buffer: Buffer.from(graph) });
    await page.locator('#execution-payload').fill(`original-${mode}`);
    let accepted = null;
    const capture = async route => {
      if (mode === 'response-lost') { const response = await route.fetch(); expect(response.status()).toBe(202); accepted = await response.json(); }
      await route.abort('failed');
    };
    if (mode !== 'saved') await page.route('**/initial-start', capture);
    await page.locator('#btn-run').click();
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.phase)).toBe(mode === 'saved' ? 'READY' : 'RECONCILE');
    if (mode === 'saved') await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.processInstanceId)).toBeTruthy();
    const before = await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun);
    expect(before.registrationIdentity).toBeTruthy(); expect(before.incarnationId).toBeTruthy();
    if (mode === 'saved') expect(before.processInstanceId).toBeTruthy(); else expect(before.processInstanceId).toBeNull();
    if (mode !== 'saved') await page.unroute('**/initial-start', capture);
    await page.evaluate(() => window.ravenroot.flushWorkspacePersistence());
    writeFileSync(join(OUTPUT, `restart-${round}`), JSON.stringify(before));
    await restartReady(round);
    const mutations = [], observations = [];
    const record = request => { if (request.url().includes('/v1/deployments/') && request.method() === 'POST') mutations.push(request.url());
      if (request.url().includes('/initial-start?')) observations.push(request.url()); };
    page.on('request', record);
    await page.reload();
    await expect.poll(() => page.evaluate(() => window.ravenroot?.activeDocument()?.deploymentRun?.deploymentId)).toBe(before.deploymentId);
    expect(mutations).toHaveLength(0);
    const absent = await page.request.get(`${ORIGIN}/v1/deployments/${before.deploymentId}`); expect(absent.status()).toBe(404);
    await page.locator('#btn-run').click();
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.phase)).toBe('READY');
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.processInstanceId)).toBeTruthy();
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().deploymentRun?.incarnationId)).not.toBe(before.incarnationId);
    const after = await page.evaluate(() => window.ravenroot.activeDocument().deploymentRun);
    expect(after.registrationIdentity).toBe(before.registrationIdentity); expect(after.incarnationId).not.toBe(before.incarnationId);
    expect(after.deploymentId).toBe(before.deploymentId); expect(after.initialStartRequestId).toBe(before.initialStartRequestId); expect(after.inputJson).toBe(before.inputJson);
    if (mode === 'saved') { expect(after.processInstanceId).toBe(before.processInstanceId); expect(after.traversalId).toBe(before.traversalId); expect(mutations.filter(url => url.endsWith('/restore'))).toHaveLength(1); expect(mutations.filter(url => url.endsWith('/initial-start'))).toHaveLength(0); expect(observations).toHaveLength(1); }
    else { expect(mutations.filter(url => url.endsWith('/initial-start'))).toHaveLength(1); if (accepted) { expect(after.processInstanceId).toBe(accepted.processInstanceId); expect(after.traversalId).toBe(accepted.traversalId); } }
    // Distinct request identities remain one initial traversal in the actual reopened SQL store.
    const duplicate = await page.request.post(`${ORIGIN}/v1/deployments/${after.deploymentId}/initial-start`, { data: { incarnationId: after.incarnationId, graphVersion: after.graphVersion, requestId: `another-${round}`, input: 'replacement' } });
    expect(duplicate.status()).toBe(200); expect(await duplicate.json()).toMatchObject({ outcome: 'DUPLICATE', processInstanceId: after.processInstanceId, traversalId: after.traversalId });
    page.off('request', record);
  }
  await page.screenshot({ path: join(OUTPUT, 'first-run-restarted-association.png'), fullPage: true });
});

import { expect, test } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createServer, request as httpRequest } from 'node:http';
let replayProxy = null, cutReplayStream = null;

const origin = process.env.RAVENROOT_DEPLOYMENT_PERSISTENCE_ORIGIN;
const control = process.env.RAVENROOT_OBSERVATION_CONTROL_ORIGIN;
const output = process.env.RAVENROOT_OBSERVATION_EVIDENCE_DIR;
const graph = '<graphml xmlns="http://graphml.graphdrawing.org/xmlns"><key id="k" for="node" attr.name="kind" attr.type="string"/><graph id="observed" edgedefault="directed"><node id="start"><data key="k">START</data></node><node id="end"><data key="k">END</data></node><edge source="start" target="end"/></graph></graphml>';
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };

// Each scenario owns an ephemeral deployment. Release it after evidence capture rather than
// exhausting the real runtime's unchanged activation quota across twelve independent cases.
test.afterEach(async ({ request }) => {
  if (replayProxy) { replayProxy.closeAllConnections(); await new Promise(resolve => replayProxy.close(resolve)); replayProxy = null; }
  const response = await request.get(`${origin}/v1/deployments`);
  expect(response.status()).toBe(200);
  for (const deployment of (await response.json()).deployments) {
    const removed = await request.delete(`${origin}/v1/deployments/${encodeURIComponent(deployment.deploymentId)}`);
    expect([200, 404]).toContain(removed.status());
  }
});

for (const mode of ['normal', 'slow', 'first-failure', 'drawing-unavailable', 'retry', 'background', 'stop', 'retire', 'denied', 'invalid-binding', 'replay', 'revoked']) {
  test(`first traversal observation: ${mode}`, async ({ page, request }) => {
    if (!origin || !control || !output) throw new Error('requires actual observation-admission harness');
    await request.post(`${control}/drawing?${mode === 'drawing-unavailable' ? 'unavailable' : 'available'}`);
    await request.post(`${control}/observation?${mode === 'denied' ? 'denied' : 'allowed'}`);
    page.on('dialog', dialog => dialog.accept());
    // The deployment channel must supply every observed execution event in this regression.
    await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
    let replayCut = false;
    if (mode === 'replay') {
      // Cut the genuine HTTP response after exactly its first frame. No synthetic event or store
      // response can make this case pass: the runtime must replay its completed traversal.
      replayProxy = createServer((incoming, outgoing) => {
        const destination = new URL(incoming.url, origin);
        const forwarded = httpRequest(destination, { headers: { ...incoming.headers, host: destination.host } }, upstream => {
          outgoing.writeHead(upstream.statusCode, upstream.headers);
          let bytes = Buffer.alloc(0);
          upstream.on('data', chunk => {
            bytes = Buffer.concat([bytes, chunk]); const boundary = bytes.indexOf('\n\n');
            if (boundary < 0) return;
            outgoing.write(bytes.subarray(0, boundary + 2));
            // Keep confirmation live through admission, then drop before forwarding any event.
            cutReplayStream = () => { replayCut = true; outgoing.end(); upstream.destroy(); };
            upstream.removeAllListeners('data');
            upstream.on('data', () => {});
          });
          upstream.on('error', () => { if (!outgoing.writableEnded) outgoing.end(); });
        });
        forwarded.on('error', () => outgoing.destroy()); forwarded.end();
      });
      await new Promise(resolve => replayProxy.listen(0, '127.0.0.1', resolve));
    }
    const timeline = [], commands = [], gate = deferred();
    const held = ['slow', 'retry', 'background', 'stop', 'retire'].includes(mode);
    if (!held) gate.resolve();
    let attempts = 0, initial = null, admitted = deferred();
    page.on('request', request => {
      const path = new URL(request.url()).pathname;
      if (request.method() === 'POST' && path.startsWith('/v1/deployments')) {
        commands.push({ path, body: request.postData() });
        if (mode === 'replay' && path.endsWith('/initial-start')) {
          expect(cutReplayStream).toEqual(expect.any(Function)); cutReplayStream(); cutReplayStream = null;
        }
      }
    });
    page.on('response', async response => {
      if (new URL(response.url()).pathname.endsWith('/initial-start')) {
        initial = { status: response.status(), body: await response.json() };
        timeline.push({ event: 'initial response', at: Date.now() }); admitted.resolve();
      }
    });
    // Delay or fail only the genuine first observation request, never synthesize store/events.
    await page.route('**/v1/deployments/*/events?**', async route => {
      attempts++;
      timeline.push({ event: 'observation requested', at: Date.now(), attempt: attempts });
      if (mode === 'first-failure' && attempts === 1) { await route.abort('failed'); return; }
      await gate.promise;
      const headers = { ...route.request().headers() };
      if (mode === 'invalid-binding') headers['x-ravenroot-deployment-incarnation'] = 'foreign-runtime';
      timeline.push({ event: 'observation forwarded', at: Date.now(), attempt: attempts,
        cursor: headers['last-event-id'] || null });
      const url = new URL(route.request().url());
      const destination = mode === 'replay' && attempts === 1
        ? `http://127.0.0.1:${replayProxy.address().port}${url.pathname}${url.search}` : route.request().url();
      try { await route.continue({ url: destination, headers }); } catch (error) {
        if (!['stop', 'retire', 'retry'].includes(mode)) throw error;
      }
    });
    await page.goto(origin);
    await expect.poll(() => page.evaluate(() => window.ravenroot?.workspacePersistence().authority.state)).toBe('ready');
    await page.locator('#file-inp').setInputFiles({ name: `${mode}.graphml`, mimeType: 'application/xml', buffer: Buffer.from(graph) });
    await page.locator('#btn-run').click();
    await expect.poll(() => attempts).toBeGreaterThan(0);
    const ownerId = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const binding = await page.evaluate(() => ({ ...window.ravenroot.activeDocument().deploymentRun }));
    const owner = () => page.evaluate(id => {
      const document = window.ravenroot.workspace.find(id);
      return document ? { binding: document.deploymentRun, status: document.deploymentRunStatus,
        events: document.execution.events.map(event => event.type),
        nodes: document.cy.nodes().map(node => ({ id: node.id(), state: node.data('runtimeState'), observed: node.data('runtimeObserved') })) } : null;
    }, ownerId);
    if (mode === 'slow') {
      await page.waitForTimeout(4200); // Deliberate regression window beyond the old false-success timer.
      expect(commands.filter(command => command.path.endsWith('/initial-start'))).toHaveLength(0);
      gate.resolve();
    }
    if (mode === 'background') { await page.locator('#btn-new').click(); gate.resolve(); }
    if (mode === 'retry') {
      await expect.poll(async () => (await owner()).binding.phase, { timeout: 15000 }).toBe('RECONCILE');
      expect((await owner()).status).toBe('READY'); expect(initial).toBeNull();
      await expect(page.locator('#btn-run')).toBeEnabled();
      gate.resolve(); await page.locator('#btn-run').click();
    }
    if (mode === 'stop' || mode === 'retire') {
      if (mode === 'stop') {
        await page.locator('#btn-stop').click();
        await expect.poll(async () => (await owner()).status).toBe('STOPPED');
      } else {
        await page.locator('#menu-file').click();
        await page.locator('#application-menu [data-command-id="file.close"]').click();
        await page.locator('[data-active-deployment-action="close"]').click();
        await expect.poll(owner).toBeNull();
      }
      gate.resolve(); await page.waitForTimeout(300);
      expect(commands.filter(command => command.path.endsWith('/initial-start'))).toHaveLength(0);
      writeFileSync(join(output, `${mode}.json`), JSON.stringify({ binding, timeline, commands, owner: await owner() }, null, 2));
      return;
    }
    if (mode === 'denied' || mode === 'invalid-binding') {
      await expect.poll(async () => (await owner()).binding.phase, { timeout: 15000 }).toBe('RECONCILE');
      expect(initial).toBeNull(); expect(commands.filter(command => command.path.endsWith('/initial-start'))).toHaveLength(0);
      await expect(page.locator('#btn-run')).toBeEnabled();
      writeFileSync(join(output, `${mode}.json`), JSON.stringify({ binding, timeline, commands, owner: await owner() }, null, 2));
      return;
    }
    await expect.poll(() => initial, { timeout: 15000 }).not.toBeNull();
    expect(initial.status).toBe(202);
    await expect.poll(async () => (await owner()).events.includes('EXECUTION_COMPLETED')).toBe(true);
    const final = await owner();
    expect(final.events).toHaveLength(7);
    expect(final.nodes).toEqual([{ id: 'start', state: 'completed', observed: true }, { id: 'end', state: 'completed', observed: true }]);
    expect(final.binding).toMatchObject({ deploymentId: binding.deploymentId, graphVersion: binding.graphVersion,
      initialStartRequestId: binding.initialStartRequestId, inputJson: binding.inputJson, graphMl: binding.graphMl,
      processInstanceId: initial.body.processInstanceId, traversalId: initial.body.traversalId });
    expect(commands.filter(command => !command.path.includes('/deployments/'))).toHaveLength(1);
    expect(commands.filter(command => command.path.endsWith('/initial-start'))).toHaveLength(1);
    const history = await request.get(`${origin}/v1/deployments/${binding.deploymentId}/history?graphVersion=${binding.graphVersion}&view=process-traversals&processInstanceId=${initial.body.processInstanceId}`,
      { headers: { 'X-Ravenroot-Deployment-Incarnation': final.binding.incarnationId } });
    expect(history.status()).toBe(200); const retained = await history.json();
    expect(retained.items).toHaveLength(1); expect(retained.items[0]).toMatchObject({ cause: 'INITIAL_START', status: 'COMPLETED', visitCount: 2 });
    if (mode === 'first-failure' || mode === 'replay') expect(attempts).toBeGreaterThanOrEqual(2);
    if (mode === 'replay') {
      expect(replayCut).toBe(true);
      expect(timeline.filter(row => row.event === 'observation forwarded')[1].cursor).toMatch(/^[A-Za-z0-9_-]{43}$/);
    }
    if (mode === 'revoked') {
      await request.post(`${control}/observation?denied`);
      await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id).originDeploymentView?.state.continuity, ownerId)).toBe('DETACHED');
      expect(commands.filter(command => command.path.endsWith('/initial-start'))).toHaveLength(1);
    }
    await page.screenshot({ path: join(output, `${mode}.png`) });
    writeFileSync(join(output, `${mode}.json`), JSON.stringify({ timeline, commands, owner: final, history: retained }, null, 2));
  });
}

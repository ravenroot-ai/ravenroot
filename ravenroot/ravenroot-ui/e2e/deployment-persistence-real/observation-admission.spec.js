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

for (const presentation of ['design', 'monitoring']) for (const ending of ['undeploy', 'stop', 'revoked', 'gap', 'error', 'authority', 'background', 'replacement']) {
  test(`terminal renderer reset: ${presentation}/${ending}`, async ({ page, request }) => {
    await request.post(`${control}/drawing?available`);
    await request.post(`${control}/observation?allowed`);
    page.on('dialog', dialog => dialog.accept());
    await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
    // All initial frames come from the real server. This owned transport cut permits a genuine
    // reconnect with a refused cursor, a denied request, or exhausted network retries afterwards.
    await page.addInitScript(() => {
      const fetch = window.fetch.bind(window);
      window.fetch = async (input, options) => {
        const response = await fetch(input, options);
        if (!new URL(String(input), location.href).pathname.endsWith('/events')
            || !response.ok || !response.body || !response.headers.get('content-type')?.includes('text/event-stream')) return response;
        const reader = response.body.getReader(); let ended = false;
        return new Response(new ReadableStream({
          async start(controller) {
            window.__cutObservationTransport = () => { ended = true; reader.cancel(); controller.close(); };
            options.signal.addEventListener('abort', () => { ended = true; reader.cancel(); }, { once: true });
            try {
              while (!ended) {
                const next = await reader.read();
                if (ended) return;
                if (next.done) { controller.close(); return; }
                controller.enqueue(next.value);
              }
            } catch (error) { if (!ended) controller.error(error); }
          },
          cancel() { ended = true; return reader.cancel(); },
        }), { status: response.status, headers: response.headers });
      };
    });
    const commands = []; let observations = 0;
    page.on('request', value => {
      const path = new URL(value.url()).pathname;
      if (path.endsWith('/events')) observations++;
      if (value.method() === 'POST' && path.startsWith('/v1/deployments')) commands.push({ path, body: value.postData() });
    });
    await page.goto(origin);
    await expect.poll(() => page.evaluate(() => window.ravenroot?.workspacePersistence().authority.state)).toBe('ready');
    await page.locator('#file-inp').setInputFiles({ name: `${ending}.graphml`, mimeType: 'application/xml', buffer: Buffer.from(graph) });
    if (presentation === 'monitoring') await page.locator('#btn-monitoring').click();
    if (presentation === 'monitoring')
      await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().renderer?.kind)).toBe('elastic');
    const idle = await page.evaluate(() => {
      const owner = window.ravenroot.activeDocument(), renderer = owner.renderer;
      return { nodes: owner.cy.nodes().map(node => ({ id: node.id(), border: node.style('border-color') })),
        edges: owner.cy.edges().map(edge => ({ id: edge.id(), color: edge.style('line-color') })),
        elasticEdges: renderer?.kind === 'elastic' ? renderer.links.map(link => ({ id: link.id,
          color: renderer.edgeSelection.filter(value => value.id === link.id).attr('stroke') })) : null };
    });
    await page.locator('#btn-run').click();
    const id = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const read = () => page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id), observation = window.__endingObservation || owner.originDeploymentView;
      const renderer = owner.renderer?.kind === 'elastic' ? owner.renderer : null;
      return { binding: { ...owner.deploymentRun }, incarnation: owner.incarnation,
        continuity: observation?.state.continuity, lifecycle: observation?.state.lifecycle,
        active: observation?.barrier.active ?? false, canAdmit: observation?.barrier.canAdmit() ?? false,
        modelNodes: observation?.state.nodeStates.size ?? 0, modelEdges: observation?.state.edgeStates.size ?? 0,
        events: owner.execution.events.map(event => event.type),
        nodes: owner.cy.nodes().map(node => ({ id: node.id(), observed: node.data('runtimeObserved'),
          state: node.data('runtimeState'), border: node.style('border-color') })),
        edges: owner.cy.edges().map(edge => ({ id: edge.id(), active: edge.data('runtimeActive'),
          recent: edge.data('runtimeRecent'), count: edge.data('runtimeCount'), color: edge.style('line-color') })),
        elasticNodes: renderer?.nodes.map(node => ({ id: node.id, observed: node.runtimeObserved,
          state: node.runtimeState, stroke: renderer.nodeSelection.filter(value => value.id === node.id).attr('stroke') })),
        elasticEdges: renderer?.links.map(link => ({ id: link.id, count: link.flow?.count,
          active: renderer.edgeSelection.filter(value => value.id === link.id).classed('d3-edge--active'),
          color: renderer.edgeSelection.filter(value => value.id === link.id).attr('stroke'),
          dash: renderer.edgeSelection.filter(value => value.id === link.id).attr('stroke-dasharray') })),
      };
    }, id);
    await expect.poll(async () => (await read()).events.length).toBe(7);
    await expect.poll(async () => (await read()).nodes.every(node => node.observed && node.state === 'completed')).toBe(true);
    await expect.poll(async () => (await read()).edges.every(edge => edge.count === 1)).toBe(true);
    if (presentation === 'monitoring')
      await expect.poll(async () => (await read()).elasticNodes.every(node => node.observed && node.state === 'completed')).toBe(true);
    const geometry = await page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id), renderer = owner.renderer;
      window.__endingRenderer = renderer; window.__endingRestartCalls = 0;
      if (renderer.kind === 'elastic') {
        renderer.simulation.stop().alpha(0);
        const restart = renderer.simulation.restart;
        renderer.simulation.restart = function (...args) { window.__endingRestartCalls++; return restart.apply(this, args); };
      }
      return { zoom: owner.cy.zoom(), pan: owner.cy.pan(),
        positions: owner.cy.nodes().map(node => ({ id: node.id(), position: node.position() })),
        routes: owner.cy.edges().map(edge => ({ id: edge.id(), curve: edge.style('curve-style'),
          distances: edge.style('control-point-distances'), weights: edge.style('control-point-weights') })) };
    }, id);
    const before = await read();
    await page.evaluate(id => {
      const owner = window.ravenroot.workspace.find(id);
      window.__endingObservation = owner.originDeploymentView;
      const disconnect = owner.originDeploymentView.disconnect;
      owner.originDeploymentView.disconnect = () => {
        disconnect();
        window.__paintAtRetirement = { active: window.__endingObservation.barrier.active,
          nodes: owner.cy.nodes().map(node => ({ id: node.id(), observed: node.data('runtimeObserved'),
            state: node.data('runtimeState'), border: node.style('border-color') })) };
      };
    }, id);
    let sibling, siblingPaint;
    if (ending === 'background') {
      sibling = await page.evaluate(() => window.ravenroot.openDocument({ name: 'unrelated.graphml' }));
      siblingPaint = await page.evaluate(() => {
        const owner = window.ravenroot.activeDocument();
        window.__terminalSibling = owner; window.__terminalSiblingRenderer = owner.renderer;
        return owner.cy.nodes().map(node => ({ id: node.id(), observed: node.data('runtimeObserved'),
          state: node.data('runtimeState'), border: node.style('border-color') }));
      });
    }
    if (ending === 'replacement') {
      // Hold already queued live painting, rotate the actual document incarnation/canvas through
      // the normal replacement API, then release old callbacks and invoke late retirement.
      await page.evaluate(id => {
        window.__retiredObservation = window.ravenroot.workspace.find(id).originDeploymentView;
        window.__originalRaf = window.requestAnimationFrame;
        window.__heldRaf = [];
        window.requestAnimationFrame = callback => { window.__heldRaf.push(callback); return 0; };
        window.__cutObservationTransport();
      }, id);
      await expect.poll(() => observations).toBeGreaterThanOrEqual(2);
      await expect.poll(() => page.evaluate(() => window.__retiredObservation.live && window.__retiredObservation.barrier.canAdmit())).toBe(true);
      await expect.poll(() => page.evaluate(() => window.__heldRaf.length)).toBeGreaterThan(0);
      await page.evaluate(graph => {
        window.ravenroot.replaceActiveDocumentFromText(graph, 'replacement.graphml');
        const owner = window.ravenroot.activeDocument();
        owner.cy.nodes().data({ runtimeObserved: true, runtimeState: 'failed' });
        owner.cy.nodes().style('border-color', '#da3633');
        window.__replacementCanvas = owner.cy;
        window.__retiredObservation.disconnect();
        const held = window.__heldRaf.splice(0); window.__replacementHeldCount = held.length;
        window.requestAnimationFrame = window.__originalRaf;
        held.forEach(callback => callback(performance.now()));
      }, graph);
      const after = await read();
      expect(after.incarnation).not.toBe(before.incarnation);
      expect(after.nodes.every(node => node.observed && node.state === 'failed')).toBe(true);
      expect(after.binding).toEqual({});
      expect(commands.filter(command => command.path.endsWith('/initial-start'))).toHaveLength(1);
      writeFileSync(join(output, `terminal-${presentation}-${ending}.json`), JSON.stringify({ before, after, commands, observations,
        heldFrames: await page.evaluate(() => window.__replacementHeldCount) }, null, 2));
      return;
    }
    if (ending === 'stop') {
      await page.locator('#btn-stop').click();
      await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id).deploymentRunStatus, id)).toBe('STOPPED');
    } else if (ending === 'authority') {
      await page.locator('#access-token').fill('replacement-authority');
      await page.locator('#access-token').press('Enter');
    } else if (ending === 'revoked') {
      await page.evaluate(() => window.__cutObservationTransport());
      await request.post(`${control}/observation?denied`);
    } else if (ending === 'gap') {
      await page.route('**/v1/deployments/*/events?**', route => route.continue({
        headers: { ...route.request().headers(), 'last-event-id': 'unresolvable-owned-test-anchor' },
      }));
      await page.evaluate(() => window.__cutObservationTransport());
    } else if (ending === 'error') {
      await page.route('**/v1/deployments/*/events?**', route => route.abort('failed'));
      await page.evaluate(() => window.__cutObservationTransport());
    } else {
      const removed = await request.delete(`${origin}/v1/deployments/${before.binding.deploymentId}`);
      expect(removed.status()).toBe(200);
    }
    await expect.poll(async () => (await read()).nodes.every(node => !node.observed && node.state === 'idle'), { timeout: 15000 }).toBe(true);
    await expect.poll(async () => (await read()).edges.every(edge => !edge.active && !edge.recent && !edge.count)).toBe(true);
    if (presentation === 'monitoring') {
      await expect.poll(async () => (await read()).elasticNodes.every(node => !node.observed && node.state === 'idle')).toBe(true);
      await expect.poll(async () => (await read()).elasticEdges.map(({ active, count, dash }) => ({ active, count, dash })))
        .toEqual([{ active: false, count: 0, dash: null }]);
    }
    const after = await read();
    writeFileSync(join(output, `terminal-${presentation}-${ending}.json`), JSON.stringify({ idle, geometry, before, after, commands, observations }, null, 2));
    expect(after.active).toBe(false); expect(after.canAdmit).toBe(false);
    expect(after.modelNodes).toBe(0); expect(after.modelEdges).toBe(0);
    await expect.poll(async () => (await read()).nodes.map(({ id, border }) => ({ id, border }))).toEqual(idle.nodes);
    let retired = null, retainedGeometry = null;
    if (ending === 'authority') {
      retired = await page.evaluate(() => window.__paintAtRetirement);
      expect(retired.active).toBe(false);
      expect(retired.nodes.every(node => !node.observed && node.state === 'idle')).toBe(true);
      expect(retired.nodes.map(({ id, border }) => ({ id, border }))).toEqual(idle.nodes);
    }
    expect(after.edges.map(({ id, color }) => ({ id, color }))).toEqual(idle.edges);
    if (presentation === 'monitoring') {
      await expect.poll(async () => (await read()).elasticNodes.every(node =>
        !['#238636', 'rgb(35, 134, 54)', 'rgb(35,134,54)'].includes(node.stroke))).toBe(true);
      expect(after.elasticEdges.map(({ id, color }) => ({ id, color }))).toEqual(idle.elasticEdges);
    }
    if (ending !== 'authority') {
      retainedGeometry = await page.evaluate(id => {
        const owner = window.ravenroot.workspace.find(id), renderer = window.__endingRenderer;
        return { sameRenderer: owner.renderer === renderer, restartCalls: window.__endingRestartCalls,
          alpha: renderer.kind === 'elastic' ? renderer.simulation.alpha() : 0,
          zoom: owner.cy.zoom(), pan: owner.cy.pan(),
          positions: owner.cy.nodes().map(node => ({ id: node.id(), position: node.position() })),
          routes: owner.cy.edges().map(edge => ({ id: edge.id(), curve: edge.style('curve-style'),
            distances: edge.style('control-point-distances'), weights: edge.style('control-point-weights') })) };
      }, id);
      expect(retainedGeometry).toEqual({ sameRenderer: true, restartCalls: 0, alpha: 0, ...geometry });
    }
    expect(after.binding).toEqual(before.binding);
    expect(after.events).toEqual(before.events);
    expect(commands.filter(command => command.path.endsWith('/initial-start'))).toHaveLength(1);
    expect(commands.filter(command => !command.path.includes('/deployments/'))).toHaveLength(1);
    if (sibling) {
      expect(await page.evaluate(() => window.ravenroot.activeDocument() === window.__terminalSibling)).toBe(true);
      expect(await page.evaluate(id => window.ravenroot.workspace.find(id).execution.events.length, sibling)).toBe(0);
      expect(await page.evaluate(() => window.__terminalSibling.renderer === window.__terminalSiblingRenderer)).toBe(true);
      expect(await page.evaluate(() => window.__terminalSibling.cy.nodes().map(node => ({ id: node.id(),
        observed: node.data('runtimeObserved'), state: node.data('runtimeState'), border: node.style('border-color') })))).toEqual(siblingPaint);
    }
    await page.screenshot({ path: join(output, `terminal-${presentation}-${ending}.png`) });
    writeFileSync(join(output, `terminal-${presentation}-${ending}.json`), JSON.stringify({ idle, geometry, retainedGeometry, retired, before, after, commands, observations }, null, 2));
  });
}

import { createServer } from 'node:http';

import { expect, test } from '@playwright/test';

import { SERVICE_ORIGIN, SERVICE_PORT, UI_ORIGIN } from './ports.mjs';

// Process, traversal and invocation must be distinguishable in the UI and in
// events. This is the end-to-end proof: a REAL SSE stream, parsed by the real `runtime-client.js`
// (`event: execution` frames, exactly as `RavenrootServer#writeEvent` emits them), reaching the real
// `#activity-log` DOM. `test/activity-identifiers.test.js` unit-tests the rendering function itself
// in isolation (including the mutation criterion, in a form where any combination of identifiers can
// be constructed); this file proves the WHOLE chain — server wire format, `runtime-client.js`'s own
// SSE parser, `handleRuntimeEvent`, the DOM — carries all four through without flattening or dropping
// one, which a unit test calling the rendering function directly cannot demonstrate on its own.
//
// Real routing (`documentForRuntimeEvent`, workspace.js) binds an event to a document by its
// `executionId` (== traversalId) alone — a SEPARATE, pre-existing, deliberate mechanism. The "two
// traversals of one process, one open document" case requires a second traversalId and is outside
// this display-only proof. So
// this file exercises what today's chain actually delivers: one traversal, with an invocation and an
// attempt beneath it — proving the four identifiers are distinguishable, not merged, and not dropped,
// as required for their UI representation.

const PROCESS_ID = '11111111-aaaa-4aaa-8aaa-111111111111';
const TRAVERSAL_ID = '22222222-bbbb-4bbb-8bbb-222222222222';
const INVOCATION_ID = '44444444-dddd-4ddd-8ddd-444444444444';
const ATTEMPT_ID = '66666666-ffff-4fff-8fff-666666666666';

function executionEventFrame(sequence, overrides) {
  const event = {
    sequence,
    occurredAt: new Date().toISOString(),
    engineId: 'test-engine',
    graphVersion: 'v1',
    processInstanceId: PROCESS_ID,
    traversalId: TRAVERSAL_ID,
    executionId: TRAVERSAL_ID,
    invocationId: null,
    attemptId: null,
    type: 'NODE_STARTED',
    nodeId: null,
    activeInstances: 0,
    fallback: false,
    detail: '',
    processingDuration: null,
    ...overrides,
  };
  return `id: ${sequence}\nevent: execution\ndata: ${JSON.stringify(event)}\n\n`;
}

let service;
let pushEvent;
let holdSubmission;
let releaseSubmission;

function startService() {
  return new Promise((resolve, reject) => {
    service = createServer((request, response) => {
      // `runtime-client.js#start` POSTs with `Content-Type: application/graphml+xml`, not a "simple"
      // CORS content type, so the browser sends an OPTIONS preflight before the real request. Missing
      // omitting this response makes the POST fail with a plain "Failed to fetch" and no server-side
      // log, because the browser never gets past the preflight to send it.
      if (request.method === 'OPTIONS') {
        response.writeHead(204, {
          'Access-Control-Allow-Origin': UI_ORIGIN,
          'Access-Control-Allow-Methods': 'GET, POST',
          'Access-Control-Allow-Headers': 'content-type, authorization',
          Vary: 'Origin',
        });
        response.end();
        return;
      }
      const headers = {
        'Access-Control-Allow-Origin': UI_ORIGIN,
        Vary: 'Origin',
        'Content-Type': 'application/json; charset=utf-8',
      };
      if (request.url === '/v1/configuration') {
        response.writeHead(200, headers);
        response.end(JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024 }));
        return;
      }
      if (request.url === '/v1/node-types') {
        response.writeHead(200, headers);
        response.end('[]');
        return;
      }
      if (request.url?.startsWith('/v1/executions')) {
        const accept = () => {
          response.writeHead(202, headers);
          response.end(JSON.stringify({
          processInstanceId: PROCESS_ID, traversalId: TRAVERSAL_ID, executionId: TRAVERSAL_ID,
          graphVersion: 'v1', executionPolicy: 'TEST_PASSTHROUGH', payloadContract: '', payloadKind: 'NONE',
          payloadSchema: '', payloadSchemaVersion: '',
          }));
        };
        if (holdSubmission) releaseSubmission = accept;
        else accept();
        return;
      }
      if (request.url?.startsWith('/v1/events')) {
        response.writeHead(200, {
          'Access-Control-Allow-Origin': UI_ORIGIN,
          Vary: 'Origin',
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-store',
        });
        // Pushed on demand by the test, not immediately: the test connects first (via the page's own
        // boot), then asks for specific frames once it knows the document is bound and listening.
        pushEvent = frame => response.write(frame);
        return;
      }
      response.writeHead(404, headers).end('{}');
    });
    service.once('error', reject).listen(SERVICE_PORT, '127.0.0.1', resolve);
  });
}

async function connectAndCreateWorkflow(page) {
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await page.locator('#btn-new').click();
}

async function connectAndLoadWorkflow(page, graphml) {
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await page.locator('#file-inp').setInputFiles({
    name: 'exact-edge-identities.graphml', mimeType: 'application/xml', buffer: Buffer.from(graphml),
  });
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.activeDocument()?.graph.edges.map(edge => edge.id)))
    .toEqual(['edge', ' edge ', 'tail']);
}

// The SSE connection opens at page boot, independent of submitting anything — `pushEvent` can
// already be set before `#btn-play` is even clicked. Pushing a frame right after that poll races the
// in-flight `/v1/executions` POST: if the frame lands before `setDocumentExecution` runs, the "request
// accepted" activity message gets appended AFTER it, and `.activity-entry.last()` would pick that
// bare, identifier-less message instead of the event row under test. Waiting for the "accepted" text
// is a reliable sync point: it only appears once `document_.execution.executionId` is actually bound
// to TRAVERSAL_ID, which is also the moment `documentForRuntimeEvent`'s exact-match path (rather than
// the PENDING_EXECUTION fallback) is guaranteed to route the next frame correctly.
async function submitAndAwaitBinding(page) {
  await connectAndCreateWorkflow(page);
  await page.locator('#btn-play').click();
  await expect.poll(() => pushEvent !== null, { timeout: 10_000 }).toBe(true);
  await expect(page.locator('#activity-log')).toContainText('accepted');
}

test.beforeEach(async () => {
  pushEvent = null;
  holdSubmission = false;
  releaseSubmission = null;
  await startService();
});

test('preserves an authoritative traversal that arrives before POST acceptance', async ({ page }) => {
  await connectAndCreateWorkflow(page);
  const edgeId = await page.evaluate(() => window.ravenroot.activeDocument().graph.edges[0].id);
  holdSubmission = true;
  await page.locator('#btn-play').click();
  await expect.poll(() => pushEvent !== null && releaseSubmission !== null).toBe(true);
  pushEvent(executionEventFrame(81, {
    type: 'EDGE_TRAVERSED', nodeId: 'start', edgeId, publicReason: 'continue',
  }));
  await expect.poll(() => page.evaluate(id =>
    window.ravenroot.activeDocument().execution.monitoringFlow.edges.get(id)?.count ?? 0, edgeId)).toBe(1);
  releaseSubmission();
  await expect(page.locator('#activity-log')).toContainText('accepted');
  await expect.poll(() => page.evaluate(id =>
    window.ravenroot.activeDocument().execution.monitoringFlow.edges.get(id)?.count ?? 0, edgeId)).toBe(1);
  await page.locator('#btn-monitoring').click();
  const edge = page.locator('.doc-elastic-host.active .d3-edges path').first();
  await edge.dispatchEvent('mouseover', { offsetX: 80, offsetY: 40 });
  const tooltip = page.locator('.doc-elastic-host.active .d3-tooltip');
  await expect(tooltip).toContainText('Traversals: 1');
  pushEvent(executionEventFrame(81, {
    type: 'EDGE_TRAVERSED', nodeId: 'start', edgeId, publicReason: 'continue',
  }));
  await expect(tooltip).toContainText('Traversals: 1');
});

test('attributes raw whitespace-significant edge identities end to end', async ({ page }) => {
  const graphml = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
    <key id="kind" for="node" attr.name="kind" attr.type="string"/>
    <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
    <graph id="g" edgedefault="directed">
      <node id="start"><data key="kind">START</data></node>
      <node id="one"><data key="kind">PASSTHROUGH</data></node>
      <node id="two"><data key="kind">PASSTHROUGH</data></node>
      <node id="end"><data key="kind">END</data></node>
      <edge id="edge" source="start" target="one"><data key="outcome">continue</data></edge>
      <edge id=" edge " source="one" target="two"><data key="outcome">continue</data></edge>
      <edge id="tail" source="two" target="end"><data key="outcome">continue</data></edge>
    </graph>
  </graphml>`;
  await connectAndLoadWorkflow(page, graphml);
  await page.locator('#btn-play').click();
  await expect.poll(() => pushEvent !== null).toBe(true);
  await expect(page.locator('#activity-log')).toContainText('accepted');

  pushEvent(executionEventFrame(83, {
    type: 'EDGE_TRAVERSED', nodeId: 'one', edgeId: ' edge ', publicReason: 'continue',
  }));
  await expect.poll(() => page.evaluate(() => {
    const edges = window.ravenroot.activeDocument().execution.monitoringFlow.edges;
    return { exact: edges.get(' edge ')?.count ?? 0, unspaced: edges.get('edge')?.count ?? 0 };
  })).toEqual({ exact: 1, unspaced: 0 });
  pushEvent(executionEventFrame(83, {
    type: 'EDGE_TRAVERSED', nodeId: 'one', edgeId: ' edge ', publicReason: 'continue',
  }));
  await expect.poll(() => page.evaluate(() =>
    window.ravenroot.activeDocument().execution.monitoringFlow.edges.get(' edge ')?.count)).toBe(1);

  pushEvent(executionEventFrame(84, {
    type: 'EDGE_TRAVERSED', nodeId: 'start', edgeId: 'edge', publicReason: 'continue',
  }));
  await expect.poll(() => page.evaluate(() => {
    const edges = window.ravenroot.activeDocument().execution.monitoringFlow.edges;
    return { exact: edges.get(' edge ')?.count ?? 0, unspaced: edges.get('edge')?.count ?? 0 };
  })).toEqual({ exact: 1, unspaced: 1 });

  await page.locator('#btn-monitoring').click();
  const paths = page.locator('.doc-elastic-host.active .d3-edges path');
  await paths.nth(1).dispatchEvent('mouseover', { offsetX: 80, offsetY: 40 });
  await expect(page.locator('.doc-elastic-host.active .d3-tooltip')).toContainText('Traversals: 1');
  expect(await page.evaluate(() => window.ravenroot.activeDocument().renderer.links.map(link => link.id)))
    .toEqual(['edge', ' edge ', 'tail']);
});

test('rehydrates a suspended Monitoring document and its open tooltip on activation', async ({ page }) => {
  await submitAndAwaitBinding(page);
  const first = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.cy.zoom(1.1);
    owner.cy.pan({ x: 87, y: -24 });
    return { id: owner.id, edgeId: owner.graph.edges[0].id, transform: 'translate(87,-24) scale(1.1)' };
  });
  await page.locator('#btn-monitoring').click();
  const firstHost = page.locator(`.doc-elastic-host[data-document-id="${first.id}"]`);
  await expect(firstHost.locator('.d3-zoom-group')).toHaveAttribute('transform', first.transform);
  await firstHost.locator('.d3-edges path').first().dispatchEvent('mouseover', { offsetX: 80, offsetY: 40 });
  await expect(firstHost.locator('.d3-tooltip')).toContainText('Traversals: 0');

  await page.locator('#btn-new').click();
  const secondId = await page.evaluate(() => window.ravenroot.activeDocument().id);
  expect(secondId).not.toBe(first.id);
  await expect(firstHost).toHaveClass(/suspended/);
  pushEvent(executionEventFrame(82, {
    type: 'EDGE_TRAVERSED', nodeId: 'start', edgeId: first.edgeId, publicReason: 'continue',
  }));
  await expect.poll(() => page.evaluate(({ id, edgeId }) =>
    window.ravenroot.documents().find(document_ => document_.id === id)
      .execution.monitoringFlow.edges.get(edgeId)?.count ?? 0, first)).toBe(1);
  expect(await page.evaluate(id => {
    const state = window.ravenroot.documents().find(document_ => document_.id === id).execution.monitoringFlow;
    return state ? [...state.edges.values()].reduce((sum, edge) => sum + edge.count, 0) : 0;
  }, secondId)).toBe(0);

  await page.evaluate(id => window.ravenroot.activateDocument(id), first.id);
  await expect(firstHost).not.toHaveClass(/suspended/);
  await expect(firstHost.locator('.d3-zoom-group')).toHaveAttribute('transform', first.transform);
  await expect(firstHost.locator('.d3-tooltip')).toContainText('Traversals: 1');
  await expect.poll(async () => Number(await firstHost.locator('.d3-edges path').first()
    .getAttribute('stroke-width'))).toBeGreaterThan(1.8);
});

test.afterEach(async () => {
  // The SSE connection is deliberately left open across the test (frames are pushed on demand, not
  // sent-and-closed), so a plain `close()` — which waits for every open connection to end on its own
  // — would hang for the full test timeout instead of tearing down. `closeAllConnections()` destroys
  // the still-open SSE socket immediately, the same way `load-harness.sh`'s own cleanup insists on
  // reaching what a graceful stop would otherwise leave dangling.
  service.closeAllConnections();
  await new Promise(resolve => service.close(resolve));
});

test('Monitoring preserves the Design viewport and paints only authoritative edge flow', async ({ page }) => {
  await submitAndAwaitBinding(page);
  const before = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    const authoredX = [-300, -100, 100, 300];
    owner.cy.nodes().forEach((node, index) => node.position({ x: authoredX[index], y: index % 2 ? 40 : -40 }));
    owner.cy.zoom(1.25);
    owner.cy.pan({ x: 550, y: 130 });
    document.querySelector('#btn-monitoring').click();
    const circles = [...owner.renderer.svg.querySelectorAll('.d3-nodes circle')];
    return {
      edgeId: owner.graph.edges[0].id, zoom: 1.25, pan: { x: 550, y: 130 }, authoredX,
      firstX: circles.map(circle => Number(circle.getAttribute('cx'))),
      firstScreenCentroid: circles.reduce((sum, circle) =>
        sum + Number(circle.getAttribute('cx')) * 1.25 + 550, 0) / circles.length,
    };
  });
  const overlay = page.locator('.doc-elastic-host.active');
  await expect(overlay).toBeVisible();
  expect(before.firstX).toEqual(before.authoredX);
  expect(before.firstScreenCentroid).toBeCloseTo(550, 5);
  await expect(overlay.locator('.d3-zoom-group')).toHaveAttribute('transform',
    `translate(${before.pan.x},${before.pan.y}) scale(${before.zoom})`);
  const settledCentroid = await page.evaluate(() => {
    const renderer = window.ravenroot.activeDocument().renderer;
    renderer.simulation.stop().tick(400);
    renderer.paint();
    return renderer.nodes.reduce((sum, node) => sum + node.x * 1.25 + 550, 0) / renderer.nodes.length;
  });
  // The simulation may settle at a fractional device pixel. A sub-0.1px residual is below the
  // renderer's physical paint resolution while still rejecting the historical multi-hundred-pixel
  // recentering drift.
  expect(Math.abs(settledCentroid - 550)).toBeLessThan(0.1);

  await page.setViewportSize({ width: 1_100, height: 720 });
  await expect(overlay.locator('.d3-zoom-group')).toHaveAttribute('transform',
    `translate(${before.pan.x},${before.pan.y}) scale(${before.zoom})`);
  const resizedCentroid = await page.evaluate(() => {
    const renderer = window.ravenroot.activeDocument().renderer;
    return renderer.nodes.reduce((sum, node) => sum + node.x * 1.25 + 550, 0) / renderer.nodes.length;
  });
  expect(Math.abs(resizedCentroid - settledCentroid)).toBeLessThan(0.01);

  const edge = overlay.locator('.d3-edges path').first();
  await expect(edge).toHaveAttribute('stroke-width', '1.8');
  pushEvent(executionEventFrame(91, {
    type: 'EDGE_TRAVERSED', nodeId: 'start', edgeId: before.edgeId,
    publicReason: 'continue', activeInstances: 0, inFlightArrivals: 0,
  }));
  await expect.poll(async () => Number(await edge.getAttribute('stroke-width'))).toBeGreaterThan(1.8);
  await edge.dispatchEvent('mouseover', { offsetX: 80, offsetY: 40 });
  await expect(overlay.locator('.d3-tooltip')).toContainText('Recent activity: 1');
  await expect(overlay.locator('.d3-tooltip')).toContainText('Traversals: 1');
  await expect(overlay.locator('.d3-tooltip')).toContainText('Configured weight:');

  // A terminal node event is not a traversal and replaying the same stable sequence is idempotent.
  pushEvent(executionEventFrame(92, { type: 'NODE_COMPLETED', nodeId: 'start', publicReason: 'continue' }));
  pushEvent(executionEventFrame(91, {
    type: 'EDGE_TRAVERSED', nodeId: 'start', edgeId: before.edgeId, publicReason: 'continue',
  }));
  await expect(overlay.locator('.d3-tooltip')).toContainText('Traversals: 1');

  await page.locator('#btn-design').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().layoutBusy)).toBe(false);
  const repeated = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.cy.zoom(0.82);
    owner.cy.pan({ x: -31, y: 64 });
    return { zoom: owner.cy.zoom(), pan: owner.cy.pan() };
  });
  await page.locator('#btn-monitoring').click();
  await expect(page.locator('.doc-elastic-host.active .d3-zoom-group')).toHaveAttribute('transform',
    `translate(${repeated.pan.x},${repeated.pan.y}) scale(${repeated.zoom})`);
});

test('a real invocation event carries process, traversal, invocation and attempt distinctly into the activity log', async ({ page }) => {
  await submitAndAwaitBinding(page);

  // Bound: the document now holds TRAVERSAL_ID as its execution id, per the /v1/executions stub
  // above — so an event on the open SSE connection carrying that same traversalId will route to it
  // (documentForRuntimeEvent's exact-match path).
  pushEvent(executionEventFrame(1, {
    type: 'NODE_STARTED', nodeId: 'n1', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
  }));

  const entry = page.locator('.activity-entry').last();
  await expect(entry).toBeVisible();

  const shortProcess = PROCESS_ID.slice(0, 8);
  const shortTraversal = TRAVERSAL_ID.slice(0, 8);
  const shortInvocation = INVOCATION_ID.slice(0, 8);
  const shortAttempt = ATTEMPT_ID.slice(0, 8);

  // Four DISTINCT spans, each pinned to its OWN identifier — not one combined id, not three copies
  // of the same value. This is the "not flattened, not omitted" contract requirement read literally.
  await expect(entry.locator('[data-id-kind="process"]')).toContainText(shortProcess);
  await expect(entry.locator('[data-id-kind="traversal"]')).toContainText(shortTraversal);
  await expect(entry.locator('[data-id-kind="invocation"]')).toContainText(shortInvocation);
  await expect(entry.locator('[data-id-kind="attempt"]')).toContainText(shortAttempt);

  // Every value differs from every other — the strongest single check that nothing collapsed two
  // identifiers into the same displayed text by accident.
  const values = await Promise.all(
    ['process', 'traversal', 'invocation', 'attempt'].map(kind =>
      entry.locator(`[data-id-kind="${kind}"] code`).innerText()));
  expect(new Set(values).size).toBe(4);
});

test('an execution-level event with no invocation shows the placeholder, not a borrowed or blank identifier', async ({ page }) => {
  await submitAndAwaitBinding(page);

  pushEvent(executionEventFrame(1, { type: 'EXECUTION_STARTED', invocationId: null, attemptId: null }));

  const entry = page.locator('.activity-entry').last();
  await expect(entry.locator('[data-id-kind="invocation"] code')).toHaveText('—');
  await expect(entry.locator('[data-id-kind="attempt"] code')).toHaveText('—');
  // process/traversal are never absent on ExecutionEvent (constructor-enforced non-null), so they
  // must still read as real values, not the same placeholder.
  await expect(entry.locator('[data-id-kind="process"] code')).not.toHaveText('—');
  await expect(entry.locator('[data-id-kind="traversal"] code')).not.toHaveText('—');
});

test('Play identifies the test policy and projects bypass separately from fallback', async ({ page }) => {
  await submitAndAwaitBinding(page);

  await expect(page.locator('#btn-play')).toHaveText('▶ Test');
  await expect(page.locator('#btn-play')).toHaveAttribute('title', /pass-through/);
  await expect(page.locator('#activity-log')).toContainText('TEST_PASSTHROUGH');

  pushEvent(executionEventFrame(1, {
    type: 'NODE_BYPASSED', nodeId: 'start', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
    detail: 'command passthrough',
  }));

  const entry = page.locator('.activity-entry').last();
  await expect(entry).toContainText('NODE_BYPASSED · start');
  await expect(entry).toHaveClass(/bypassed/);
  await expect(entry).not.toHaveClass(/fallback/);
});

// Two different facts now share NODE_BYPASSED: "this whole run is a rehearsal" and "one node
// is switched off in the saved graph while the rest of the run is real". A reader deciding whether a
// result is trustworthy has to tell them apart. `test/execution-event-public-reason.test.js` proves
// the sentence selection in isolation; this proves the classifier survives the whole chain — server
// wire format, `runtime-client.js`'s SSE parser, `handleRuntimeEvent`, the DOM — which is the link
// that matters, because `detail` travels the same chain and is dropped at the HTTP boundary by
// design. A panel that read `detail` would pass a unit test and show nothing here.
test('the activity panel distinguishes an authored bypass from a traversal-wide one', async ({ page }) => {
  await submitAndAwaitBinding(page);

  pushEvent(executionEventFrame(1, {
    type: 'NODE_BYPASSED', nodeId: 'start', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
    publicReason: 'command.passthrough',
  }));
  await expect(page.locator('.activity-entry').last())
    .toContainText('the traversal was not executing node behaviours');

  pushEvent(executionEventFrame(2, {
    type: 'NODE_BYPASSED', nodeId: 'worker', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
    publicReason: 'authored',
  }));
  const authored = page.locator('.activity-entry').last();
  await expect(authored).toContainText('the graph author switched this node off');
  await expect(authored).not.toContainText('the traversal was not executing');
});

test('a bypass with no classifier keeps the bare legacy sentence, and never borrows the diagnostic channel', async ({ page }) => {
  await submitAndAwaitBinding(page);

  // Durable replay never captured a classifier, and a peer predating sends none. `detail` here
  // is the constant `ExecutionMonitor#nodeBypassed` used to publish; `RavenrootServer` does not
  // serialize it, and the panel must not read it even when a fixture supplies one.
  pushEvent(executionEventFrame(1, {
    type: 'NODE_BYPASSED', nodeId: 'start', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
    detail: 'incoming command=passthrough',
  }));

  const entry = page.locator('.activity-entry').last();
  await expect(entry).toContainText('Node was bypassed.');
  await expect(entry).not.toContainText('incoming command=passthrough');
});

test('the identifiers reach the accessible live region, not merely the DOM', async ({ page }) => {
  await submitAndAwaitBinding(page);

  const log = page.locator('#activity-log');
  await expect(log).toHaveAttribute('role', 'log');
  await expect(log).toHaveAttribute('aria-live', 'polite');

  pushEvent(executionEventFrame(1, {
    type: 'NODE_COMPLETED', nodeId: 'n1', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
  }));

  await expect(log).toContainText(TRAVERSAL_ID.slice(0, 8));
  await expect(log).toContainText(INVOCATION_ID.slice(0, 8));
});

test('structured output preserves newline meaning and wraps inside a realistic activity panel', async ({ page }) => {
  await submitAndAwaitBinding(page);
  await page.setViewportSize({ width: 800, height: 720 });

  const secret = 'activity-output-secret';
  const longToken = 'L'.repeat(900);
  pushEvent(executionEventFrame(1, {
    type: 'NODE_COMPLETED', nodeId: 'log-1', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
    output: {
      actual: 'alpha\nbeta',
      literal: String.raw`literal\nsequence`,
      long: longToken,
      password: secret,
      marker: '[ravenroot:truncated]',
    },
    outputRedacted: true,
    outputTruncated: true,
  }));

  const detail = page.locator('.activity-entry').last().locator('.activity-detail');
  await expect(detail).toContainText('[ravenroot:truncated]');
  await expect(detail).not.toContainText(secret);
  const text = await detail.textContent();
  expect(text).toContain('"actual": "alpha\nbeta"');
  expect(text).toContain('"literal": "literal\\\\nsequence"');

  const layout = await detail.evaluate((element, token) => {
    const content = element.firstChild;
    const textContent = content.textContent;
    const boxFor = (start, length = 1) => {
      const range = document.createRange();
      range.setStart(content, start);
      range.setEnd(content, start + length);
      return range.getBoundingClientRect();
    };
    const alpha = textContent.indexOf('alpha');
    const beta = textContent.indexOf('beta');
    const tokenStart = textContent.indexOf(token);
    const tokenEnd = tokenStart + token.length - 1;
    const alphaBox = boxFor(alpha, 5);
    const betaBox = boxFor(beta, 4);
    const tokenFirstBox = boxFor(tokenStart);
    const tokenLastBox = boxFor(tokenEnd);
    const style = getComputedStyle(element);
    return {
      whiteSpace: style.whiteSpace,
      overflowWrap: style.overflowWrap,
      wordBreak: style.wordBreak,
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
      newlineCreatesAnotherLine: betaBox.top > alphaBox.top,
      tokenWrapsAcrossLines: tokenLastBox.top > tokenFirstBox.top,
    };
  }, longToken);

  expect(layout.whiteSpace).toBe('pre-wrap');
  expect(layout.overflowWrap).toBe('anywhere');
  expect(layout.wordBreak).toBe('break-word');
  expect(layout.clientWidth).toBeGreaterThan(100);
  expect(layout.clientWidth).toBeLessThan(700);
  expect(layout.newlineCreatesAnotherLine).toBe(true);
  expect(layout.tokenWrapsAcrossLines).toBe(true);
  expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth + 1);
});

test('JSON and XML string outcomes are readable, inert and equivalent across live and replay', async ({ page }) => {
  await submitAndAwaitBinding(page);
  await page.setViewportSize({ width: 800, height: 720 });

  const secret = 'structured-display-secret';
  const prettyJson = JSON.stringify({
    root: {
      category: 'Electronic communications',
      children: [{ category: 'Service notifications', details: { password: secret } }],
    },
    actual: 'first line\nsecond line',
    literal: String.raw`first line\nsecond line`,
    escaped: 'quote " and path \\tmp',
  }, null, 2);
  const event = {
    type: 'NODE_COMPLETED', nodeId: 'json-live', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
    output: prettyJson,
  };
  pushEvent(executionEventFrame(1, event));

  const liveDetail = page.locator('.activity-entry').last().locator('.activity-detail');
  await expect(liveDetail).toContainText('"root": {');
  await expect(liveDetail).toContainText('"category": "Electronic communications"');
  await expect(liveDetail).toContainText('[ravenroot:redacted:credential]');
  await expect(liveDetail).not.toContainText(secret);
  const liveText = await liveDetail.textContent();
  expect(liveText).not.toContain('output="{');
  expect(liveText).not.toContain('\\"root\\"');
  expect(liveText).toContain('"actual": "first line\nsecond line"');
  expect(liveText).toContain('"literal": "first line\\\\nsecond line"');
  expect(liveText).toContain('"escaped": "quote \\" and path \\\\tmp"');

  pushEvent(`id: 2\nevent: execution\ndata: ${JSON.stringify({
    journalOffset: 2,
    streamSequence: 2,
    occurredAt: new Date().toISOString(),
    eventType: 'NODE_COMPLETED',
    graphVersion: 'v1',
    traversalId: TRAVERSAL_ID,
    processInstanceId: PROCESS_ID,
    nodeId: 'json-replay',
    invocationId: INVOCATION_ID,
    attemptId: ATTEMPT_ID,
    output: prettyJson,
  })}\n\n`);
  const replayDetail = page.locator('.activity-entry').last().locator('.activity-detail');
  await expect(replayDetail).toContainText('"category": "Electronic communications"');
  expect(await replayDetail.textContent()).toBe(liveText);

  const xml = '<?xml version="1.0"?><root xmlns="urn:test"><item password="hidden">visible</item>'
    + '<mixed>Hello <b>world</b><![CDATA[<literal>]]><!--note--></mixed>'
    + '<script>window.__runtimeMarkupExecuted=true</script></root>';
  pushEvent(executionEventFrame(3, {
    type: 'NODE_COMPLETED', nodeId: 'xml-live', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
    output: xml,
  }));
  const xmlDetail = page.locator('.activity-entry').last().locator('.activity-detail');
  await expect(xmlDetail).toContainText('<?xml version="1.0"?>');
  await expect(xmlDetail).toContainText('<mixed>Hello <b>world</b><![CDATA[<literal>]]><!--note--></mixed>');
  await expect(xmlDetail).toContainText('[ravenroot:redacted:credential]');
  await expect(xmlDetail.locator('script, b, root, item')).toHaveCount(0);
  expect(await page.evaluate(() => window.__runtimeMarkupExecuted)).toBeUndefined();
  const xmlLayout = await xmlDetail.evaluate(element => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
    whiteSpace: getComputedStyle(element).whiteSpace,
  }));
  expect(xmlLayout.whiteSpace).toBe('pre-wrap');
  expect(xmlLayout.scrollWidth).toBeLessThanOrEqual(xmlLayout.clientWidth + 1);
});

test('invalid structured-looking output and double-encoded JSON remain literal text', async ({ page }) => {
  await submitAndAwaitBinding(page);

  for (const [sequence, nodeId, output] of [
    [1, 'invalid-json', '{"root":{"open":true}'],
    [2, 'invalid-xml', '<root><open></root>'],
    [3, 'double-json', JSON.stringify(JSON.stringify({ nested: true }))],
    [4, 'literal-newline', String.raw`authored\nsequence`],
    [5, 'real-newline', 'authored\nsequence'],
  ]) {
    pushEvent(executionEventFrame(sequence, {
      type: 'NODE_COMPLETED', nodeId, invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID, output,
    }));
    const detail = page.locator('.activity-entry').last().locator('.activity-detail');
    await expect(detail).toContainText(output);
    if (nodeId === 'literal-newline') {
      expect(await detail.textContent()).toContain(String.raw`output=authored\nsequence`);
      expect(await detail.textContent()).not.toContain('output=authored\nsequence');
    }
    if (nodeId === 'real-newline') {
      expect(await detail.textContent()).toContain('output=authored\nsequence');
      expect(await detail.textContent()).not.toContain(String.raw`output=authored\nsequence`);
    }
  }
});

test('public descriptions remain literal and safe across live delivery and cursor replay', async ({ page }) => {
  await submitAndAwaitBinding(page);

  const secret = 'password=hunter2 /home/runner/private stack trace';
  const hostileDescription = '<img src=x onerror=alert(1)> visit https://example.invalid/path';
  pushEvent(executionEventFrame(1, {
    type: 'NODE_FAILED', nodeId: 'n1', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
    description: `\n${hostileDescription}\u0000`, detail: secret,
  }));

  const live = page.locator('.activity-entry').last();
  await expect(live.locator('.activity-detail')).toContainText(hostileDescription);
  await expect(live.locator('.activity-detail img')).toHaveCount(0);
  await expect(live.locator('.activity-detail a')).toHaveCount(0);
  await expect(page.locator('#activity-log')).not.toContainText(secret);
  await expect(live.locator('.activity-detail')).not.toHaveAttribute('title');

  pushEvent(executionEventFrame(2, {
    type: 'NODE_COMPLETED', nodeId: 'n1', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
    description: '🙂'.repeat(200), detail: secret,
  }));
  const longText = page.locator('.activity-entry').last().locator('.activity-detail');
  await expect(longText).toContainText('[truncated]');
  expect(new TextEncoder().encode(await longText.textContent()).byteLength).toBeLessThan(320);
  await expect(longText).toHaveCSS('overflow-wrap', 'anywhere');

  pushEvent(executionEventFrame(3, {
    type: 'FUTURE_EVENT', nodeId: 'n1', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
    description: undefined, detail: secret,
  }));
  await expect(page.locator('.activity-entry').last().locator('.activity-detail'))
    .toContainText('Execution activity was reported.');
  await expect(page.locator('#activity-log')).not.toContainText(secret);

  // The durable cursor/replay serializer uses eventType + traversalId rather than the live aliases.
  // Deliver that real wire shape through the production parser and UI, while server tests pin that
  // both initial backlog and later durable polls call the same serializer.
  pushEvent(`id: 4\nevent: execution\ndata: ${JSON.stringify({
    journalOffset: 4,
    streamSequence: 4,
    occurredAt: new Date().toISOString(),
    eventType: 'NODE_COMPLETED',
    graphVersion: 'v1',
    traversalId: TRAVERSAL_ID,
    processInstanceId: PROCESS_ID,
    nodeId: 'n1',
    description: 'Delivered from cursor replay.',
  })}\n\n`);
  await expect(page.locator('#activity-log')).toContainText('Delivered from cursor replay.', { timeout: 8_000 });
  await expect(page.locator('#activity-log')).not.toContainText(secret);
});

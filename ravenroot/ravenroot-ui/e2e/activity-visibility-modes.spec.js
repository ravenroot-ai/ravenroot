import { createServer } from 'node:http';

import { expect, test } from '@playwright/test';

import { SERVICE_ORIGIN, SERVICE_PORT, UI_ORIGIN } from './ports.mjs';

// The Activity panel's three observation levels (issue #458): Output, Nodes and Trace.
//
// This is the end-to-end proof, and it deliberately spans the whole chain — a REAL SSE stream, parsed
// by the real `runtime-client.js` (`event: execution` frames, exactly as `RavenrootServer#writeEvent`
// emits them), reaching the real `#activity-log` DOM. `test/activity-visibility.test.js` pins the
// classification matrix and the concise row model in isolation; only this file can show that the
// classification is actually consulted on the path that appends rows, that a hidden event costs no
// row, and that the level never reaches the monitoring projection that paints nodes and edges.
//
// A level is an OBSERVER preference. Nothing here proves an execution changed: there is no
// re-submission to prove it, and the tests assert that explicitly.
//
// Row counts are always expressed as a DELTA from the rows the UI itself produced while connecting
// and submitting ("Minimal Start → … workflow created", "Submitting…", "accepted"). Those are
// UI-local messages, which stay visible at every level by design, so counting them as a fixed number
// would make the suite agree with itself instead of with the behaviour under test.

const PROCESS_ID = '11111111-aaaa-4aaa-8aaa-111111111111';
const TRAVERSAL_ID = '22222222-bbbb-4bbb-8bbb-222222222222';
const INVOCATION_ID = '44444444-dddd-4ddd-8ddd-444444444444';
const ATTEMPT_ID = '66666666-ffff-4fff-8fff-666666666666';

const ACTIVITY_CAP = 400;

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
    inFlightArrivals: 0,
    fallback: false,
    processingDuration: null,
    ...overrides,
  };
  return `id: ${sequence}\nevent: execution\ndata: ${JSON.stringify(event)}\n\n`;
}

let service;
let pushEvent;
let submissions;

function startService() {
  return new Promise((resolve, reject) => {
    service = createServer((request, response) => {
      // `runtime-client.js#start` POSTs with `Content-Type: application/graphml+xml`, which is not a
      // "simple" CORS content type, so the browser preflights before the real request.
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
        if (request.method === 'GET') {
          // A terminal event makes the UI fetch the authoritative outcome. Answering with a real
          // terminal status keeps that path quiet, so a test asserting "no extra rows" is asserting
          // about the level and not about a reconciliation failure it accidentally provoked.
          response.writeHead(200, headers);
          response.end(JSON.stringify({ status: 'COMPLETED' }));
          return;
        }
        submissions += 1;
        response.writeHead(202, headers);
        response.end(JSON.stringify({
          processInstanceId: PROCESS_ID, traversalId: TRAVERSAL_ID, executionId: TRAVERSAL_ID,
          graphVersion: 'v1', executionPolicy: 'TEST_PASSTHROUGH', payloadContract: '', payloadKind: 'NONE',
          payloadSchema: '', payloadSchemaVersion: '',
        }));
        return;
      }
      if (request.url?.startsWith('/v1/events')) {
        response.writeHead(200, {
          'Access-Control-Allow-Origin': UI_ORIGIN,
          Vary: 'Origin',
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-store',
        });
        pushEvent = frame => {
          // A navigation can leave this handler pointing at a socket the page already abandoned.
          try { response.write(frame); } catch { /* the socket is gone; nothing left to deliver to. */ }
        };
        return;
      }
      response.writeHead(404, headers).end('{}');
    });
    service.once('error', reject).listen(SERVICE_PORT, '127.0.0.1', resolve);
  });
}

// Connect, create a workflow and submit it, then wait for the binding sync point: "accepted" only
// appears once the document's execution id is bound to TRAVERSAL_ID, which is also the moment
// `documentForRuntimeEvent` routes an arriving frame by exact match rather than by the
// PENDING_EXECUTION fallback.
async function connectAndBind(page) {
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await page.locator('#btn-new').click();
  await page.locator('#btn-play').click();
  await expect.poll(() => pushEvent !== null, { timeout: 10_000 }).toBe(true);
  await expect(page.locator('#activity-log')).toContainText('accepted');
}

const LEVELS = ['output', 'nodes', 'trace'];

const levelButton = (page, level) => page.locator(`.activity-modes [data-mode="${level}"]`);

async function selectLevel(page, level) {
  await levelButton(page, level).click();
  await expect(levelButton(page, level)).toHaveAttribute('aria-checked', 'true');
}

async function checkedLevel(page) {
  return page.evaluate(() => document.querySelector('.activity-modes [role="radio"][aria-checked="true"]')
    ?.dataset.mode ?? null);
}

// The concise rows only, so an assertion about progressive output cannot be satisfied by some other
// row on the panel (a UI-local message, a failure, a terminal state).
const outputValues = page => page.locator('#activity-log .activity-entry.output-value .activity-title');
const allTitles = page => page.locator('#activity-log .activity-entry .activity-title');
const rowCount = page => page.locator('#activity-log .activity-entry').count();

// A frame the browser has accepted is not yet a frame the app has processed. Only a state change
// proves it landed, so every "nothing was added" assertion first establishes a positive control:
// a visible row that could only exist if the stream were live.
async function proveStreamLive(page, value = 'control') {
  pushEvent(executionEventFrame(1, { type: 'NODE_COMPLETED', nodeId: 'log-pi', output: value }));
  await expect(page.locator('#activity-log .activity-entry.output-value .activity-title').last())
    .toHaveText(value);
}

test.beforeEach(async () => {
  pushEvent = null;
  submissions = 0;
  await startService();
});

test.afterEach(async () => {
  // The SSE connection is deliberately left open across the test (frames are pushed on demand, not
  // sent-and-closed), so a plain `close()` — which waits for every open connection to end on its own
  // — would hang for the full test timeout instead of tearing down. `closeAllConnections()` destroys
  // the still-open SSE socket immediately.
  service.closeAllConnections();
  await new Promise(resolve => service.close(resolve));
});

test('ships Output selected, as one tab stop, outside the live region', async ({ page }) => {
  await connectAndBind(page);

  expect(await checkedLevel(page)).toBe('output');
  await expect(levelButton(page, 'output')).toHaveAttribute('tabindex', '0');
  await expect(levelButton(page, 'nodes')).toHaveAttribute('tabindex', '-1');
  await expect(levelButton(page, 'trace')).toHaveAttribute('tabindex', '-1');
  // Exactly one tab stop for the whole group, so the panel does not spend three stops on a view.
  await expect(page.locator('.activity-modes [role="radio"][tabindex="0"]')).toHaveCount(1);

  // The control is a sibling of the live region, never a child: a mode change must not be able to
  // re-announce the entries already in it.
  await expect(page.locator('#activity-log .activity-modes')).toHaveCount(0);
  await expect(page.locator('#activity-log')).toHaveAttribute('role', 'log');
  await expect(page.locator('#activity-log')).toHaveAttribute('aria-live', 'polite');
  await expect(page.locator('.activity-modes')).toHaveAttribute('role', 'radiogroup');
  await expect(page.locator('.activity-modes')).toHaveAttribute('aria-label', 'Activity detail level');

  // A fresh load is Output even after a level was chosen: the choice is deliberately not persisted.
  await selectLevel(page, 'trace');
  await page.reload();
  await expect(page.locator('.activity-modes [data-mode="output"]')).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('.activity-modes [data-mode="trace"]')).toHaveAttribute('aria-checked', 'false');
});

test('presents progressive log values as the primary rows in Output mode', async ({ page }) => {
  await connectAndBind(page);

  for (const [sequence, value] of [[1, '3.1'], [2, '3.14'], [3, '3.141']]) {
    pushEvent(executionEventFrame(sequence, { type: 'NODE_COMPLETED', nodeId: 'log-pi', output: value }));
  }
  await expect(outputValues(page)).toHaveText(['3.1', '3.14', '3.141']);

  // The representation the issue removes from Output: no generic node-completion title, no node id,
  // no process/traversal/invocation/attempt identifier row, no instance counts.
  const last = page.locator('#activity-log .activity-entry.output-value').last();
  await expect(last).not.toContainText('NODE_COMPLETED');
  await expect(last).not.toContainText('log-pi');
  await expect(last).not.toContainText(/instances=|inFlight=/);
  await expect(last.locator('.activity-ids')).toHaveCount(0);
  await expect(last).toHaveClass(/completed/);

  // Node and technical activity is still available, one level up — the same events, same stream.
  await selectLevel(page, 'trace');
  pushEvent(executionEventFrame(4, { type: 'NODE_STARTED', nodeId: 'log-pi' }));
  await expect(page.locator('#activity-log .activity-entry', { hasText: 'NODE_STARTED · log-pi' }))
    .toHaveCount(1);
  pushEvent(executionEventFrame(5, { type: 'NODE_COMPLETED', nodeId: 'log-pi', output: '3.1415' }));
  await expect(page.locator('#activity-log .activity-entry', { hasText: 'NODE_COMPLETED · log-pi' }).last())
    .toContainText('output=3.1415');
});

test('keeps node lifecycle out of Output, adds it in Nodes, and never backfills hidden history', async ({ page }) => {
  await connectAndBind(page);
  await proveStreamLive(page);
  const baseline = await rowCount(page);

  pushEvent(executionEventFrame(2, { type: 'NODE_STARTED', nodeId: 'work' }));
  pushEvent(executionEventFrame(3, { type: 'NODE_COMPLETED', nodeId: 'work' }));
  pushEvent(executionEventFrame(4, { type: 'EDGE_TRAVERSED', nodeId: 'work', edgeId: 'edge-x' }));
  // A later visible emission is the proof that the three frames above were delivered and processed:
  // if they had not arrived yet, this one could not be last either.
  pushEvent(executionEventFrame(5, {
    type: 'NODE_COMPLETED', nodeId: 'log-pi', output: 'after-hidden',
  }));
  await expect(outputValues(page).last()).toHaveText('after-hidden');

  expect(await rowCount(page)).toBe(baseline + 1);
  await expect(page.locator('#activity-log')).not.toContainText('NODE_STARTED');
  await expect(page.locator('#activity-log')).not.toContainText('EDGE_TRAVERSED');

  // Switching up does NOT replay them. A level changes what arrives next; it is not a filter applied
  // retroactively to a hidden buffer, because such a buffer would have to live inside the panel's
  // bounded budget that the level exists to protect.
  await selectLevel(page, 'nodes');
  expect(await rowCount(page)).toBe(baseline + 1);

  // New arrivals appear at their own level: node lifecycle in Nodes, technical detail only in Trace.
  pushEvent(executionEventFrame(6, { type: 'NODE_STARTED', nodeId: 'work' }));
  pushEvent(executionEventFrame(7, { type: 'NODE_COMPLETED', nodeId: 'work' }));
  await expect.poll(() => rowCount(page)).toBe(baseline + 3);
  await expect(page.locator('#activity-log')).toContainText('NODE_STARTED · work');
  await expect(page.locator('#activity-log')).toContainText('NODE_COMPLETED · work');
  await expect(page.locator('#activity-log')).not.toContainText('EDGE_TRAVERSED');

  await selectLevel(page, 'trace');
  pushEvent(executionEventFrame(8, { type: 'EDGE_TRAVERSED', nodeId: 'work', edgeId: 'edge-x' }));
  await expect(page.locator('#activity-log')).toContainText('EDGE_TRAVERSED · work');

  // And a log emission is readable at the quietest level it appears at: the concise row is unchanged
  // by the levels above it.
  await selectLevel(page, 'output');
  pushEvent(executionEventFrame(9, { type: 'NODE_COMPLETED', nodeId: 'log-pi', output: '3.14159' }));
  await expect(outputValues(page)).toHaveText(['control', 'after-hidden', '3.14159']);
});

test('changes only what arrives next, and never resubmits the execution', async ({ page }) => {
  await connectAndBind(page);
  expect(submissions).toBe(1);

  const before = await page.evaluate(() => window.ravenroot.activeDocument().execution.executionId);

  for (const level of ['nodes', 'trace', 'output', 'trace', 'nodes', 'output']) {
    await selectLevel(page, level);
  }
  expect(await checkedLevel(page)).toBe('output');

  // Switching is a view change: no submission, no reconciled identity change, no new execution.
  expect(submissions).toBe(1);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().execution.executionId)).toBe(before);

  // Existing entries are neither rebuilt nor removed by a switch, which is precisely why a switch
  // cannot cause the live region to re-announce them en masse.
  await proveStreamLive(page, 'first');
  const titlesBefore = await allTitles(page).allTextContents();
  await selectLevel(page, 'trace');
  await selectLevel(page, 'output');
  expect(await allTitles(page).allTextContents()).toEqual(titlesBefore);
});

test('is keyboard operable with radio semantics and a roving tab stop', async ({ page }) => {
  await connectAndBind(page);

  await levelButton(page, 'output').focus();
  await page.keyboard.press('ArrowRight');
  expect(await checkedLevel(page)).toBe('nodes');
  await expect(levelButton(page, 'nodes')).toBeFocused();

  await page.keyboard.press('ArrowRight');
  expect(await checkedLevel(page)).toBe('trace');
  await page.keyboard.press('ArrowRight');
  // Wraps rather than dead-ends.
  expect(await checkedLevel(page)).toBe('output');
  await page.keyboard.press('ArrowLeft');
  expect(await checkedLevel(page)).toBe('trace');

  await page.keyboard.press('Home');
  expect(await checkedLevel(page)).toBe('output');
  await page.keyboard.press('End');
  expect(await checkedLevel(page)).toBe('trace');

  await expect(page.locator('.activity-modes [role="radio"][tabindex="0"]')).toHaveCount(1);
  await expect(levelButton(page, 'trace')).toHaveAttribute('tabindex', '0');
  for (const level of LEVELS) {
    await expect(levelButton(page, level)).toHaveAttribute('aria-checked',
      level === 'trace' ? 'true' : 'false');
  }
});

test('shows failures and the terminal state at every level', async ({ page }) => {
  for (const [index, level] of LEVELS.entries()) {
    await connectAndBind(page);
    await selectLevel(page, level);

    pushEvent(executionEventFrame(1, {
      type: 'NODE_FAILED', nodeId: 'work', invocationId: INVOCATION_ID, attemptId: ATTEMPT_ID,
      message: 'the node refused',
    }));
    pushEvent(executionEventFrame(2, { type: 'JOIN_FAILED', nodeId: 'join-1' }));
    pushEvent(executionEventFrame(3, { type: 'EXECUTION_FAILED' }));
    // A terminal event last: it settles the execution, so nothing is published after it. The two
    // terminals alternate across levels so both are covered at a level that is not Trace.
    pushEvent(executionEventFrame(4, {
      type: index === 1 ? 'EXECUTION_CANCELLED' : 'EXECUTION_COMPLETED',
    }));

    const log = page.locator('#activity-log');
    await expect(log, level).toContainText('NODE_FAILED · work');
    await expect(log, level).toContainText('JOIN_FAILED · join-1');
    await expect(log, level).toContainText('EXECUTION_FAILED');
    await expect(log, level).toContainText(index === 1 ? 'EXECUTION_CANCELLED' : 'EXECUTION_COMPLETED');
  }
});

test('does not let a high-volume hidden sequence consume the 400-row budget', async ({ page }) => {
  await connectAndBind(page);
  await proveStreamLive(page, 'first');
  const baseline = await rowCount(page);

  // Far more hidden events than the panel can hold...
  for (let sequence = 100; sequence < 100 + ACTIVITY_CAP + 60; sequence += 1) {
    pushEvent(executionEventFrame(sequence, { type: 'NODE_STARTED', nodeId: 'work' }));
  }
  // ...and not one of them became a row, so the earlier output is still there and is still the only
  // row either assertion knows about. The next emission is the positive control that they arrived.
  pushEvent(executionEventFrame(9_000, { type: 'NODE_COMPLETED', nodeId: 'log-pi', output: 'last' }));
  await expect(outputValues(page)).toHaveText(['first', 'last']);
  expect(await rowCount(page)).toBe(baseline + 1);

  // The same sequence at Trace does fill (and then hold) the cap, which is what makes the statement
  // above meaningful rather than a coincidence of a quiet stream.
  await selectLevel(page, 'trace');
  for (let sequence = 20_000; sequence < 20_000 + ACTIVITY_CAP + 60; sequence += 1) {
    pushEvent(executionEventFrame(sequence, { type: 'NODE_STARTED', nodeId: 'work' }));
  }
  await expect.poll(() => rowCount(page)).toBe(ACTIVITY_CAP);
});

test('keeps node and edge monitoring correct at every level', async ({ page }) => {
  await connectAndBind(page);
  const baseline = await rowCount(page);

  const { nodeId, edgeId } = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    return { nodeId: owner.graph.nodes[0].id, edgeId: owner.graph.edges[0].id };
  });

  const edgeCount = () => page.evaluate(id =>
    window.ravenroot.activeDocument().execution.monitoringFlow?.edges?.get(id)?.count ?? 0, edgeId);
  const nodeOpening = () => page.evaluate(id =>
    window.ravenroot.activeDocument().execution.monitoringFlow.nodes.get(id)?.opening ?? null, nodeId);

  // Hidden at Output: no row, but the projection that PAINTS must still be exact.
  pushEvent(executionEventFrame(1, {
    type: 'NODE_STARTED', nodeId, activeInstances: 1, inFlightArrivals: 0,
  }));
  pushEvent(executionEventFrame(2, { type: 'EDGE_TRAVERSED', nodeId, edgeId, publicReason: 'continue' }));
  await expect.poll(edgeCount).toBe(1);
  expect(await nodeOpening()).toBe(true);
  expect(await rowCount(page)).toBe(baseline);

  // The same holds while the panel is at its most verbose — the filter runs after the projection, so
  // the level can never be the reason a node or edge is painted wrong.
  await selectLevel(page, 'trace');
  pushEvent(executionEventFrame(3, { type: 'EDGE_TRAVERSED', nodeId, edgeId, publicReason: 'continue' }));
  await expect.poll(edgeCount).toBe(2);
  pushEvent(executionEventFrame(4, { type: 'NODE_COMPLETED', nodeId, publicReason: 'continue' }));
  await expect.poll(() => page.evaluate(id =>
    window.ravenroot.activeDocument().execution.monitoringFlow.nodes.get(id)?.settled ?? null, nodeId))
    .toBe('completed');
  // Exactly the two Trace-level events that were pushed after the switch — the NODE_STARTED and the
  // first EDGE_TRAVERSED were hidden at Output and stayed hidden, which is the same "no backfill"
  // rule the lifecycle test states as its own case.
  await expect.poll(() => rowCount(page)).toBe(baseline + 2);

  // A node the graph does not contain cannot be painted; the technical rows still say what arrived.
  pushEvent(executionEventFrame(5, { type: 'NODE_STARTED', nodeId: 'not-in-this-graph' }));
  await expect(page.locator('#activity-log')).toContainText('NODE_STARTED · not-in-this-graph');
  expect(await page.evaluate(() =>
    window.ravenroot.activeDocument().execution.monitoringFlow.nodes.has('not-in-this-graph'))).toBe(false);
});

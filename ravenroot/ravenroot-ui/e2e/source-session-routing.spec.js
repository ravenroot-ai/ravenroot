import { expect, test } from '@playwright/test';

const SOURCE_CATALOG = JSON.stringify([{
  behavior: 'external.consume', displayName: 'External consumer', category: 'Sources',
  description: 'Receives external events.', visualType: 'actor', agentic: false,
  capabilities: [], properties: [], defaultNature: 'SOURCE', allowedNatures: ['SOURCE'],
  natureProperty: 'runtime.nature',
}]);

const sourceGraph = behavior => `<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
  <graph id="ui-source" edgedefault="directed">
    <node id="error"><data key="kind">ERROR</data></node>
    <node id="start"><data key="kind">START</data></node>
    ${behavior ? `<node id="source"><data key="kind">BEHAVIOR</data><data key="behavior">${behavior}</data></node>` : ''}
    <node id="end"><data key="kind">END</data></node>
    ${behavior
      ? '<edge source="start" target="source"><data key="outcome">continue</data></edge><edge source="source" target="end"><data key="outcome">continue</data></edge>'
      : '<edge source="start" target="end"><data key="outcome">continue</data></edge>'}
  </graph>
</graphml>`;

// One SSE frame per admitted message, in the shape the ring publishes: a NEW traversal id every
// time, and the deployment the session runs under carried on every one of them.
function sourceEventStream(deploymentId, nodeIds, admissions) {
  const frames = [];
  let sequence = 0;
  for (let admission = 1; admission <= admissions; admission += 1) {
    const executionId = `00000000-0000-4000-8000-${String(admission).padStart(12, '0')}`;
    const processInstanceId = `00000000-0000-4000-9000-${String(admission).padStart(12, '0')}`;
    for (const nodeId of nodeIds) {
      for (const [type, arrivals] of [['NODE_STARTED', 1], ['NODE_COMPLETED', 0]]) {
        sequence += 1;
        frames.push(`id: ${sequence}\nevent: execution\ndata: ${JSON.stringify({
          schemaVersion: 1, source: 'RING', id: String(sequence), eventType: type,
          sequence, occurredAt: '2026-09-09T10:00:00Z', engineId: 'stub', graphVersion: 'v1',
          processInstanceId, traversalId: executionId, executionId, deploymentId,
          workloadId: executionId,
          type, nodeId, activeInstances: arrivals, inFlightArrivals: arrivals, fallback: false,
          description: 'stub', publicReason: null, message: null,
          messageRedacted: false, messageTruncated: false, processingDuration: null,
        })}\n\n`);
      }
    }
  }
  return frames.join('');
}

function durableHumanTaskReentryStream(deploymentId) {
  const firstProcess = '10000000-0000-4000-8000-000000000001';
  const firstTraversal = '20000000-0000-4000-8000-000000000001';
  const resumedTraversal = '20000000-0000-4000-8000-000000000002';
  const secondProcess = '10000000-0000-4000-8000-000000000002';
  const secondTraversal = '20000000-0000-4000-8000-000000000003';
  const frames = [
    { processInstanceId: firstProcess, executionId: firstTraversal, traversalId: firstTraversal,
      deploymentId, workloadId: 'source-workload-1', type: 'NODE_COMPLETED', nodeId: 'source',
      output: 'amqp-message-1' },
    { processInstanceId: firstProcess, executionId: resumedTraversal, traversalId: resumedTraversal,
      deploymentId: null, workloadId: 'source-workload-1', type: 'HANDLER_RESOLVED',
      nodeId: 'human-task' },
    { processInstanceId: firstProcess, executionId: resumedTraversal, traversalId: resumedTraversal,
      deploymentId: null, workloadId: 'source-workload-1', type: 'NODE_COMPLETED',
      nodeId: 'post-task-log', output: 'resolved-message-1' },
    { processInstanceId: firstProcess, executionId: resumedTraversal, traversalId: resumedTraversal,
      deploymentId: null, workloadId: 'source-workload-1', type: 'NODE_COMPLETED', nodeId: 'publish' },
    { processInstanceId: firstProcess, executionId: resumedTraversal, traversalId: resumedTraversal,
      deploymentId: null, workloadId: 'source-workload-1', type: 'EXECUTION_COMPLETED', nodeId: null },
    { processInstanceId: secondProcess, executionId: secondTraversal, traversalId: secondTraversal,
      deploymentId, workloadId: 'source-workload-2', type: 'NODE_COMPLETED', nodeId: 'source',
      output: 'amqp-message-2' },
  ];
  return frames.map((event, index) => `id: ${index + 1}\nevent: execution\ndata: ${JSON.stringify({
    schemaVersion: 1, source: 'RING', id: String(index + 1), eventType: event.type,
    sequence: index + 1, occurredAt: '2026-09-09T10:00:00Z', engineId: 'stub',
    graphVersion: 'v1', activeInstances: 0, inFlightArrivals: 0, fallback: false,
    description: 'stub', publicReason: null, message: null, messageRedacted: false,
    messageTruncated: false, processingDuration: null, ...event,
  })}\n\n`).join('');
}

async function stubRuntime(page, { sourceResponder, sourceTraffic, workspaceTenant = null,
  configurationResponder, eventResponder } = {}) {
  const sourceCalls = [];
  const executionCalls = [];
  const configurationCalls = [];
  const eventCalls = [];
  // The editor connects its stream at boot, before any session exists, so the frames cannot be
  // composed up front: their deployment id is the one the browser invents when Run is pressed. The
  // stream therefore waits for the start request and is composed from it, which is also the order
  // the real server publishes in.
  let announceSession = () => {};
  const startedSession = new Promise(resolve => { announceSession = resolve; });
  if (workspaceTenant || configurationResponder) {
    await page.route('**/v1/configuration', async route => {
      configurationCalls.push({ headers: route.request().headers() });
      if (configurationResponder) {
        await configurationResponder({ route, configurationCalls });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024,
          workspace: { tenantId: workspaceTenant } }),
      });
    });
  }
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json', body: SOURCE_CATALOG,
  }));
  await page.route('**/v1/events**', async route => {
    eventCalls.push({ headers: route.request().headers() });
    if (eventResponder) {
      await eventResponder({ route, eventCalls, startedSession });
      return;
    }
    if (!sourceTraffic) {
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    const deploymentId = await startedSession;
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sourceTraffic.body
        ? sourceTraffic.body(deploymentId)
        : sourceEventStream(deploymentId, sourceTraffic.nodeIds, sourceTraffic.admissions),
    });
  });
  await page.route('**/v1/source-sessions**', async route => {
    const request = route.request();
    sourceCalls.push({ method: request.method(), url: request.url(), body: request.postData() || '' });
    const sessionId = new URL(request.url()).searchParams.get('id')
      || decodeURIComponent(new URL(request.url()).pathname.split('/').at(-1));
    if (request.method() === 'POST') announceSession(sessionId);
    if (sourceResponder) {
      await sourceResponder({ route, request, sessionId, sourceCalls });
      return;
    }
    const state = request.method() === 'POST' ? 'STARTING'
      : request.method() === 'DELETE' ? 'STOPPED' : 'LISTENING';
    await route.fulfill({
      status: request.method() === 'POST' ? 202 : 200,
      contentType: 'application/json',
      body: JSON.stringify({ sessionId, deploymentId: sessionId, state, sourceCount: 1,
        scope: 'LOCAL_PROCESS', diagnostic: null }),
    });
  });
  await page.route('**/v1/executions**', async route => {
    executionCalls.push({
      method: route.request().method(), url: route.request().url(), body: route.request().postData() || '',
    });
    await route.fulfill({
      status: 202, contentType: 'application/json',
      body: JSON.stringify({ executionId: 'one-shot', graphVersion: 'v1', executionPolicy: 'STANDARD' }),
    });
  });
  return { sourceCalls, executionCalls, configurationCalls, eventCalls };
}

async function openGraph(page, xml, name) {
  await page.locator('#file-inp').setInputFiles({
    name, mimeType: 'application/xml', buffer: Buffer.from(xml),
  });
  await expect(page.locator('#btn-run')).toBeEnabled();
}

test('Run routes an effective SOURCE to an accessible local session and Test stays passthrough', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'source.graphml');
  await page.locator('#execution-payload').fill('must-not-be-an-initial-event');

  const confirmation = new Promise(resolve => page.once('dialog', async dialog => {
    resolve(dialog.message());
    await dialog.accept();
  }));
  await page.locator('#btn-run').click();
  await expect(confirmation).resolves.toMatch(/No initial payload or traversal/);
  await expect(page.locator('#source-session-status')).toContainText('Listening · 1 local source');
  await expect(page.locator('#source-session-status')).toHaveAttribute('role', 'status');
  await expect(page.locator('#btn-stop')).toBeEnabled();
  await expect(page.locator('#btn-pause')).toBeDisabled();
  await expect(page.locator('#btn-cancel')).toBeHidden();

  expect(calls.sourceCalls.filter(call => call.method === 'POST')).toHaveLength(1);
  expect(calls.sourceCalls[0].body).toContain('<graphml');
  expect(calls.sourceCalls[0].body).not.toContain('must-not-be-an-initial-event');
  expect(calls.executionCalls.filter(call => call.method === 'POST')).toHaveLength(0);

  await page.locator('#btn-stop').click();
  await expect(page.locator('#source-session-status')).toContainText('Stopped · 1 local source');
  expect(calls.sourceCalls.some(call => call.method === 'DELETE')).toBe(true);

  await page.locator('#btn-play').click();
  await expect.poll(() => calls.executionCalls.filter(call => call.method === 'POST').length).toBe(1);
  expect(calls.sourceCalls.filter(call => call.method === 'POST')).toHaveLength(1);
  expect(calls.executionCalls.find(call => call.method === 'POST').url).not.toContain('mode=run');
});

test('source Human Task re-entry and a second AMQP message stay in one listening timeline', async ({ page }) => {
  await stubRuntime(page, {
    sourceTraffic: { body: durableHumanTaskReentryStream }, workspaceTenant: 'tenant-authenticated',
  });
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'source-human-task.graphml');

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect(page.locator('#source-session-status')).toContainText('Listening');
  await expect(page.locator('#activity-log')).toContainText('resolved-message-1');
  await expect(page.locator('#activity-log')).toContainText('amqp-message-2');

  const timeline = await page.evaluate(() => {
    const document_ = window.ravenroot.activeDocument();
    return {
      tenantId: document_.tenantId,
      state: document_.sourceSession.state,
      processOwners: [...document_.sourceSession.processOwners.keys()],
      events: document_.execution.events.map(event => ({
        type: event.type, executionId: event.executionId,
        processInstanceId: event.processInstanceId, nodeId: event.nodeId,
      })),
    };
  });
  expect(timeline.tenantId).toBe('tenant-authenticated');
  expect(timeline.state).toBe('LISTENING');
  expect(timeline.processOwners).toEqual(expect.arrayContaining([
    '10000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000002',
  ]));
  expect(timeline.events).toEqual(expect.arrayContaining([
    expect.objectContaining({ type: 'HANDLER_RESOLVED',
      executionId: '20000000-0000-4000-8000-000000000002' }),
    expect.objectContaining({ type: 'NODE_COMPLETED', nodeId: 'post-task-log',
      executionId: '20000000-0000-4000-8000-000000000002' }),
    expect.objectContaining({ type: 'NODE_COMPLETED', nodeId: 'publish',
      executionId: '20000000-0000-4000-8000-000000000002' }),
    expect.objectContaining({ type: 'EXECUTION_COMPLETED',
      executionId: '20000000-0000-4000-8000-000000000002' }),
    expect.objectContaining({ type: 'NODE_COMPLETED', nodeId: 'source',
      executionId: '20000000-0000-4000-8000-000000000003' }),
  ]));
});

test('re-authentication holds a source frame until workspace authority can deliver it', async ({ page }) => {
  let releaseInitialTraffic;
  const initialTrafficGate = new Promise(resolve => { releaseInitialTraffic = resolve; });
  let releaseReauthentication;
  const reauthenticationGate = new Promise(resolve => { releaseReauthentication = resolve; });
  let announceReauthentication;
  const reauthenticationStarted = new Promise(resolve => { announceReauthentication = resolve; });
  const configurationBody = JSON.stringify({
    schemaVersion: 1,
    graphDocumentMaxBytes: 10 * 1024 * 1024,
    workspace: { tenantId: 'tenant-authenticated' },
  });
  let deploymentId;
  const calls = await stubRuntime(page, {
    configurationResponder: async ({ route, configurationCalls }) => {
      if (configurationCalls.length === 2) {
        announceReauthentication();
        await reauthenticationGate;
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: configurationBody });
    },
    eventResponder: async ({ route, eventCalls, startedSession }) => {
      if (eventCalls.length === 1) {
        deploymentId = await startedSession;
        await initialTrafficGate;
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: `retry: 30000\n${sourceEventStream(deploymentId, ['source'], 1)}`,
        });
        return;
      }
      if (eventCalls.length === 2) {
        const event = {
          schemaVersion: 1, source: 'RING', id: '7', eventType: 'NODE_COMPLETED', sequence: 7,
          occurredAt: '2026-09-09T10:00:00Z', engineId: 'stub', graphVersion: 'v1',
          processInstanceId: '30000000-0000-4000-8000-000000000001',
          traversalId: '40000000-0000-4000-8000-000000000001',
          executionId: '40000000-0000-4000-8000-000000000001', deploymentId,
          workloadId: 'source-workload-before-authority', type: 'NODE_COMPLETED', nodeId: 'source',
          activeInstances: 0, inFlightArrivals: 0, fallback: false, description: 'stub',
          publicReason: null, message: null, messageRedacted: false, messageTruncated: false,
          processingDuration: null, output: 'arrived-before-authority',
        };
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: `retry: 250\nid: 7\nevent: execution\ndata: ${JSON.stringify(event)}\n\n`,
        });
        return;
      }
      await route.fulfill({ status: 403, body: '' });
    },
  });
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'source-authority-race.graphml');
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect(page.locator('#source-session-status')).toContainText('Listening');
  releaseInitialTraffic();
  await expect.poll(() => page.evaluate(
    () => window.ravenroot.activeDocument().execution.events.length)).toBe(2);

  await page.locator('#access-token').fill('replacement-token');
  await page.locator('#access-token').press('Enter');
  await reauthenticationStarted;
  await expect.poll(() => calls.eventCalls.length).toBe(2);
  expect(calls.eventCalls[1].headers).not.toHaveProperty('last-event-id');

  // If the pending callback were treated as delivered, the 250 ms retry would already acknowledge
  // frame 7 here. The exact client authority is still blocked, so neither routing nor reconnect may
  // advance beyond this request.
  await page.waitForTimeout(500);
  expect(calls.eventCalls).toHaveLength(2);
  expect(await page.locator('#activity-log').textContent()).not.toContain('arrived-before-authority');

  releaseReauthentication();
  await expect(page.locator('#activity-log')).toContainText('arrived-before-authority');
  await expect.poll(() => calls.eventCalls.length).toBe(3);
  expect(calls.eventCalls[2].headers['last-event-id']).toBe('7');
  const timeline = await page.evaluate(() => {
    const document_ = window.ravenroot.activeDocument();
    return {
      processOwners: [...document_.sourceSession.processOwners.keys()],
      events: document_.execution.events,
    };
  });
  expect(timeline.processOwners).toContain('30000000-0000-4000-8000-000000000001');
  expect(timeline.events).toEqual(expect.arrayContaining([
    expect.objectContaining({ type: 'NODE_COMPLETED', nodeId: 'source',
      executionId: '40000000-0000-4000-8000-000000000001' }),
  ]));
});

test('Run preserves the one-shot route when the graph has no effective SOURCE', async ({ page }) => {
  const calls = await stubRuntime(page);
  await page.goto('/');
  await openGraph(page, sourceGraph(''), 'ordinary.graphml');

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();

  await expect.poll(() => calls.executionCalls.filter(call => call.method === 'POST').length).toBe(1);
  expect(calls.executionCalls.find(call => call.method === 'POST').url).toContain('mode=run');
  expect(calls.sourceCalls).toHaveLength(0);
});

test('a delayed failed start updates its owning background document and is recoverable', async ({ page }) => {
  let releaseStart;
  const startGate = new Promise(resolve => { releaseStart = resolve; });
  await stubRuntime(page, {
    sourceResponder: async ({ route, request }) => {
      if (request.method() === 'POST') {
        await startGate;
        await route.fulfill({
          status: 500, contentType: 'application/problem+json',
          body: JSON.stringify({ title: 'Source startup failed', status: 500 }),
        });
        return;
      }
      await route.fulfill({ status: 404, body: '' });
    },
  });
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'delayed.graphml');
  const ownerId = await page.evaluate(() => window.ravenroot.documents().find(
    document_ => document_.name === 'delayed.graphml').id);

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.documents().find(
    document_ => document_.name === 'delayed.graphml').sourceSession.state)).toBe('STARTING');
  await page.evaluate(() => window.ravenroot.openDocument({ name: 'other.graphml' }));
  releaseStart();

  await expect.poll(() => page.evaluate(id => window.ravenroot.documents().find(
    document_ => document_.id === id).sourceSession.state, ownerId)).toBe('FAILED');
  await page.evaluate(id => window.ravenroot.activateDocument(id), ownerId);
  await expect(page.locator('#source-session-status')).toContainText('Failed');
  await expect(page.locator('#btn-run')).toBeEnabled();
  await expect(page.locator('#btn-play')).toBeEnabled();
});

test('Stop requested while start is pending waits for registration and leaves no listener', async ({ page }) => {
  let releaseStart;
  const startGate = new Promise(resolve => { releaseStart = resolve; });
  let listenerPresent = false;
  let deleteCalls = 0;
  const calls = await stubRuntime(page, {
    sourceResponder: async ({ route, request, sessionId }) => {
      if (request.method() === 'POST') {
        await startGate;
        listenerPresent = true;
        await route.fulfill({
          status: 202, contentType: 'application/json',
          body: JSON.stringify({ sessionId, state: 'LISTENING', sourceCount: 1, scope: 'LOCAL_PROCESS' }),
        });
        return;
      }
      if (request.method() === 'DELETE') {
        deleteCalls += 1;
        listenerPresent = false;
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessionId, state: 'STOPPED', sourceCount: 1, scope: 'LOCAL_PROCESS' }),
        });
        return;
      }
      await route.fulfill({ status: 404, body: '' });
    },
  });
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'queued-stop.graphml');

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect.poll(() => calls.sourceCalls.filter(call => call.method === 'POST').length).toBe(1);
  await expect(page.locator('#btn-stop')).toBeEnabled();
  await page.locator('#btn-stop').click();
  await page.waitForTimeout(100);
  expect(deleteCalls).toBe(0);

  releaseStart();
  await expect(page.locator('#source-session-status')).toContainText('Stopped');
  expect(deleteCalls).toBe(1);
  expect(listenerPresent).toBe(false);
  await expect(page.locator('#btn-run')).toBeEnabled();
});

test('closing the owner cannot cancel a Stop already queued behind its pending start', async ({ page }) => {
  let releaseStart;
  const startGate = new Promise(resolve => { releaseStart = resolve; });
  let listenerPresent = false;
  let startedSessionId = null;
  const deletedSessionIds = [];
  const calls = await stubRuntime(page, {
    sourceResponder: async ({ route, request, sessionId }) => {
      if (request.method() === 'POST') {
        startedSessionId = sessionId;
        await startGate;
        listenerPresent = true;
        await route.fulfill({
          status: 202, contentType: 'application/json',
          body: JSON.stringify({ sessionId, state: 'LISTENING', sourceCount: 1, scope: 'LOCAL_PROCESS' }),
        });
        return;
      }
      if (request.method() === 'DELETE') {
        deletedSessionIds.push(sessionId);
        listenerPresent = false;
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessionId, state: 'STOPPED', sourceCount: 1, scope: 'LOCAL_PROCESS' }),
        });
        return;
      }
      await route.fulfill({ status: 404, body: '' });
    },
  });
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'closed-while-starting.graphml');
  const ownerId = await page.evaluate(() => window.ravenroot.workspace.activeId);

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect.poll(() => calls.sourceCalls.filter(call => call.method === 'POST').length).toBe(1);
  await page.locator('#btn-stop').click();
  await expect(page.locator('#source-session-status')).toContainText('Stopping');
  await page.evaluate(id => window.ravenroot.closeDocument(id), ownerId);
  await expect.poll(() => page.evaluate(id => window.ravenroot.workspace.find(id), ownerId)).toBeNull();
  expect(deletedSessionIds).toHaveLength(0);

  releaseStart();
  await expect.poll(() => deletedSessionIds).toHaveLength(1);
  expect(deletedSessionIds).toEqual([startedSessionId]);
  expect(listenerPresent).toBe(false);
  await page.waitForTimeout(100);
  expect(deletedSessionIds).toHaveLength(1);
});

test('an authoritative observation 404 recovers STARTING to stopped', async ({ page }) => {
  await stubRuntime(page, {
    sourceResponder: async ({ route, request, sessionId }) => {
      if (request.method() === 'POST') {
        await route.fulfill({
          status: 202, contentType: 'application/json',
          body: JSON.stringify({ sessionId, state: 'STARTING', sourceCount: 1, scope: 'LOCAL_PROCESS' }),
        });
        return;
      }
      await route.fulfill({ status: 404, body: '' });
    },
  });
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'lost-process.graphml');

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect(page.locator('#source-session-status')).toContainText('Stopped');
  await expect(page.locator('#btn-run')).toBeEnabled();
  await expect(page.locator('#btn-play')).toBeEnabled();
});

test('an authoritative stop 404 recovers STOPPING to stopped', async ({ page }) => {
  await stubRuntime(page, {
    sourceResponder: async ({ route, request, sessionId }) => {
      if (request.method() === 'DELETE') {
        await route.fulfill({ status: 404, body: '' });
        return;
      }
      await route.fulfill({
        status: request.method() === 'POST' ? 202 : 200,
        contentType: 'application/json',
        body: JSON.stringify({ sessionId, state: 'LISTENING', sourceCount: 1, scope: 'LOCAL_PROCESS' }),
      });
    },
  });
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'missing-on-stop.graphml');

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect(page.locator('#source-session-status')).toContainText('Listening');
  await page.locator('#btn-stop').click();
  await expect(page.locator('#source-session-status')).toContainText('Stopped');
  await expect(page.locator('#btn-run')).toBeEnabled();
  await expect(page.locator('#btn-play')).toBeEnabled();
});

test('an ambiguous stop restores the last honest state and keeps observing without allowing Run', async ({ page }) => {
  let releaseObservation;
  const observationGate = new Promise(resolve => { releaseObservation = resolve; });
  let stopAttempted = false;
  await stubRuntime(page, {
    sourceResponder: async ({ route, request, sessionId }) => {
      if (request.method() === 'DELETE') {
        stopAttempted = true;
        await route.abort('connectionfailed');
        return;
      }
      if (request.method() === 'GET' && stopAttempted) await observationGate;
      await route.fulfill({
        status: request.method() === 'POST' ? 202 : 200,
        contentType: 'application/json',
        body: JSON.stringify({ sessionId, state: 'LISTENING', sourceCount: 1, scope: 'LOCAL_PROCESS' }),
      });
    },
  });
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'uncertain-stop.graphml');

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect(page.locator('#source-session-status')).toContainText('Listening');
  await page.locator('#btn-stop').click();
  await expect(page.locator('#source-session-status')).toContainText('status unavailable, retrying');
  await expect(page.locator('#btn-stop')).toBeEnabled();
  await expect(page.locator('#btn-run')).toBeDisabled();
  await expect(page.locator('#btn-play')).toBeDisabled();

  releaseObservation();
  await expect(page.locator('#source-session-status')).toHaveText('Listening · 1 local source');
});

test('an ambiguous start becomes UNKNOWN until observation proves the listener state', async ({ page }) => {
  let releaseObservation;
  const observationGate = new Promise(resolve => { releaseObservation = resolve; });
  await stubRuntime(page, {
    sourceResponder: async ({ route, request, sessionId }) => {
      if (request.method() === 'POST') {
        await route.abort('connectionfailed');
        return;
      }
      await observationGate;
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ sessionId, state: 'LISTENING', sourceCount: 1, scope: 'LOCAL_PROCESS' }),
      });
    },
  });
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'uncertain-start.graphml');

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect(page.locator('#source-session-status')).toContainText('Unknown');
  await expect(page.locator('#source-session-status')).toContainText('status unavailable, retrying');
  await expect(page.locator('#btn-stop')).toBeEnabled();
  await expect(page.locator('#btn-run')).toBeDisabled();
  await expect(page.locator('#btn-play')).toBeDisabled();

  releaseObservation();
  await expect(page.locator('#source-session-status')).toHaveText('Listening · 1 local source');
});

/**
 * The regression guard for #300, at the browser boundary the defect actually lived on.
 *
 * A listening source emits a new traversal per admitted message and the document is told none of
 * their ids, so the execution-id binding the editor had could only ever drop them: the pill said
 * LISTENING and the canvas stayed blank for as long as the source ran. The document now matches on
 * the deployment its session reports, which is the one identity it can hold in advance.
 */
test('a listening source paints its nodes from traversals the document was never told about', async ({ page }) => {
  await stubRuntime(page, { sourceTraffic: { nodeIds: ['source', 'end'], admissions: 6 } });
  await page.goto('/');
  await openGraph(page, sourceGraph('external.consume'), 'listening.graphml');

  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect(page.locator('#source-session-status')).toContainText('Listening · 1 local source');

  const painted = () => page.evaluate(() => {
    const document_ = window.ravenroot.activeDocument();
    return {
      deploymentId: document_.sourceSession.deploymentId,
      boundExecutionId: document_.execution.executionId,
      source: document_.cy.getElementById('source').data('runtimeState') || 'idle',
      end: document_.cy.getElementById('end').data('runtimeState') || 'idle',
      // Distinct traversal ids attributed to this document. The number the defect pinned at zero:
      // every one of these ids came into existence inside the runtime, after the session started,
      // and nothing ever told the document about any of them.
      traversals: new Set((document_.execution.events || [])
        .map(event => event.executionId).filter(Boolean)).size,
    };
  });

  await expect.poll(async () => (await painted()).traversals).toBeGreaterThanOrEqual(6);
  const report = await painted();
  expect(report.deploymentId, 'the document holds the deployment, not an execution').toBeTruthy();
  expect(report.boundExecutionId, 'a listener session binds no single execution id').toBeNull();
  expect(report.source).toBe('completed');
  expect(report.end).toBe('completed');
});

/**
 * The other half of the same rule: a deployment nobody in this workspace is watching still paints
 * nothing. The routing rule that dropped a source's own events was written to prevent exactly this,
 * and widening it must not have cost that.
 */
test('another deployment traffic is still dropped rather than painted on whichever graph is open',
  async ({ page }) => {
    await stubRuntime(page, { sourceTraffic: { nodeIds: ['source'], admissions: 4 } });
    await page.route('**/v1/events**', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sourceEventStream('a-deployment-this-workspace-never-started', ['source'], 4),
      });
    });
    await page.goto('/');
    await openGraph(page, sourceGraph('external.consume'), 'listening.graphml');

    page.once('dialog', dialog => dialog.accept());
    await page.locator('#btn-run').click();
    await expect(page.locator('#source-session-status')).toContainText('Listening · 1 local source');

    await page.waitForTimeout(1_500);
    expect(await page.evaluate(() => window.ravenroot.activeDocument().cy
      .getElementById('source').data('runtimeState') || 'idle')).toBe('idle');
  });

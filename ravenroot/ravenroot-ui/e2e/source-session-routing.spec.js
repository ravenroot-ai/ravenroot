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

async function stubRuntime(page, { sourceResponder } = {}) {
  const sourceCalls = [];
  const executionCalls = [];
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json', body: SOURCE_CATALOG,
  }));
  await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
  await page.route('**/v1/source-sessions**', async route => {
    const request = route.request();
    sourceCalls.push({ method: request.method(), url: request.url(), body: request.postData() || '' });
    const sessionId = new URL(request.url()).searchParams.get('id')
      || decodeURIComponent(new URL(request.url()).pathname.split('/').at(-1));
    if (sourceResponder) {
      await sourceResponder({ route, request, sessionId, sourceCalls });
      return;
    }
    const state = request.method() === 'POST' ? 'STARTING'
      : request.method() === 'DELETE' ? 'STOPPED' : 'LISTENING';
    await route.fulfill({
      status: request.method() === 'POST' ? 202 : 200,
      contentType: 'application/json',
      body: JSON.stringify({ sessionId, state, sourceCount: 1, scope: 'LOCAL_PROCESS', diagnostic: null }),
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
  return { sourceCalls, executionCalls };
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
  await expect(page.locator('#btn-force-stop')).toBeDisabled();

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

import { expect, test } from '@playwright/test';

const EXECUTION_ID = '11111111-1111-4111-8111-111111111111';

function json(route, status, body) {
  return route.fulfill({ status, contentType: 'application/json; charset=utf-8', body: JSON.stringify(body) });
}

async function installRuntime(page, {
  pauseGate = null, pauseStatus = 200, failAuthoritativeAfterPause = false,
} = {}) {
  const calls = [];
  let state = { status: 'RUNNING', paused: false };
  let pauseResponded = false;
  let readsAfterPauseResponse = 0;
  await page.route('**/v1/node-types', route => json(route, 200, []));
  await page.route('**/v1/events**', route => route.fulfill({
    status: 200, contentType: 'text/event-stream; charset=utf-8', body: '',
  }));
  await page.route('**/v1/executions**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const entry = {
      method: request.method(), path: url.pathname, search: url.search,
      authorization: await request.headerValue('authorization'), body: request.postData(),
    };
    calls.push(entry);
    if (request.method() === 'POST' && url.pathname === '/v1/executions') {
      return json(route, 200, {
        executionId: EXECUTION_ID, graphVersion: 'graph-v1', executionPolicy: 'TEST_PASSTHROUGH',
      });
    }
    if (request.method() === 'POST' && url.pathname.endsWith('/pause')) {
      if (pauseGate) await pauseGate;
      state = { status: 'RUNNING', paused: true };
      pauseResponded = true;
      if (pauseStatus !== 200) return json(route, pauseStatus, { error: 'pause response lost' });
      return json(route, 200, { outcome: 'PAUSED', traversalId: EXECUTION_ID, note: 'paused' });
    }
    if (request.method() === 'POST' && url.pathname.endsWith('/resume')) {
      state = { status: 'RUNNING', paused: false };
      return json(route, 200, { outcome: 'RESUMED', traversalId: EXECUTION_ID, note: 'resumed' });
    }
    if (request.method() === 'POST' && url.pathname.endsWith('/cancel')) {
      state = { status: 'FAILED', paused: false, terminationReason: 'CANCELLED' };
      return json(route, 200, { outcome: 'CANCELLED', traversalId: EXECUTION_ID, note: 'cancelled' });
    }
    if (request.method() === 'GET' && url.pathname === `/v1/executions/${EXECUTION_ID}`) {
      if (pauseResponded) readsAfterPauseResponse += 1;
      if (pauseResponded && failAuthoritativeAfterPause) {
        return json(route, 503, { error: 'execution observation disconnected' });
      }
      return json(route, 200, {
        executionId: EXECUTION_ID, ...state,
        handledFailureNodes: [], defaultedNodes: [], bypassedNodes: [],
      });
    }
    return json(route, 404, { error: 'unexpected route' });
  });
  return {
    calls,
    get readsAfterPauseResponse() { return readsAfterPauseResponse; },
  };
}

async function authenticateAndStart(page) {
  await page.goto('/');
  await page.locator('#access-token').fill('tenant-a-operator-token');
  await page.locator('#btn-authenticate').click();
  await expect(page.locator('#btn-play')).toBeEnabled();
  await page.locator('#btn-play').click();
  await expect(page.locator('#btn-pause')).toBeVisible();
  await expect(page.locator('#btn-pause')).toBeEnabled();
}

test('Pause, Resume, and cooperative Cancel use authenticated execution routes and authoritative state', async ({ page }) => {
  let releasePause;
  const pauseGate = new Promise(resolve => { releasePause = resolve; });
  const runtime = await installRuntime(page, { pauseGate });
  await authenticateAndStart(page);

  await page.locator('#btn-pause').click();
  await expect.poll(() => runtime.calls.filter(call => call.path.endsWith('/pause')).length).toBe(1);
  await expect(page.locator('#btn-pause')).toBeDisabled();
  await page.locator('#btn-pause').evaluate(button => {
    button.click();
    button.click();
  });
  expect(runtime.calls.filter(call => call.path.endsWith('/pause'))).toHaveLength(1);
  releasePause();

  await expect(page.locator('#btn-resume')).toBeVisible();
  await expect(page.locator('#btn-resume')).toBeEnabled();
  await expect(page.locator('#activity-log')).toContainText('authoritative state running, paused');
  await page.locator('#btn-resume').click();
  await expect(page.locator('#btn-pause')).toBeVisible();
  await expect(page.locator('#activity-log')).toContainText('authoritative state running, not paused');
  await page.locator('#btn-cancel').click();
  await expect(page.locator('#btn-cancel')).toBeHidden();
  await expect(page.locator('#activity-log')).toContainText('CANCELLED');

  const controls = runtime.calls.filter(call => /\/(pause|resume|cancel)$/.test(call.path));
  expect(controls.map(call => [call.method, call.path])).toEqual([
    ['POST', `/v1/executions/${EXECUTION_ID}/pause`],
    ['POST', `/v1/executions/${EXECUTION_ID}/resume`],
    ['POST', `/v1/executions/${EXECUTION_ID}/cancel`],
  ]);
  for (const call of controls) {
    expect(call.authorization).toBe('Bearer tenant-a-operator-token');
    expect(call.body).toBeNull();
    expect(call.search).toBe('');
  }

  await page.locator('#menu-run').click();
  for (const command of ['run.stopDeployment', 'run.shutdown']) {
    const item = page.locator(`#application-menu [data-command-id="${command}"]`);
    await expect(item).toHaveAttribute('aria-disabled', 'true');
    await expect(item).toHaveAttribute('aria-label', /Unavailable: this runtime does not advertise/);
  }
});

test('a closed document fences a delayed Pause response before authoritative readback', async ({ page }) => {
  let releasePause;
  const pauseGate = new Promise(resolve => { releasePause = resolve; });
  const runtime = await installRuntime(page, { pauseGate });
  await authenticateAndStart(page);

  await page.locator('#btn-pause').click();
  await expect.poll(() => runtime.calls.filter(call => call.path.endsWith('/pause')).length).toBe(1);
  await page.locator('#menu-file').click();
  await page.locator('#application-menu [data-command-id="file.close"]').click();
  await expect(page.locator('#graph-title')).toHaveText('No graph loaded');
  releasePause();
  await page.waitForTimeout(100);

  expect(runtime.readsAfterPauseResponse).toBe(0);
  await expect(page.locator('#activity-log')).not.toContainText('authoritative state running, paused');
});

test('a lost command response converges from authoritative GET', async ({ page }) => {
  const recovered = await installRuntime(page, { pauseStatus: 503 });
  await authenticateAndStart(page);
  await page.locator('#btn-pause').click();
  await expect(page.locator('#btn-resume')).toBeVisible();
  await expect(page.locator('#activity-log')).toContainText('displayed lifecycle state comes from the authoritative execution read');
  expect(recovered.readsAfterPauseResponse).toBeGreaterThan(0);
});

test('a lost authoritative read leaves lifecycle state unknown and controls disabled', async ({ page }) => {
  const disconnected = await installRuntime(page, { failAuthoritativeAfterPause: true });
  await authenticateAndStart(page);
  await page.locator('#btn-pause').click();
  await expect(page.locator('#activity-summary')).toContainText('Status unknown');
  await expect(page.locator('#btn-pause')).toBeDisabled();
  await expect(page.locator('#btn-cancel')).toBeDisabled();
  await expect(page.locator('#activity-log')).toContainText('no success state was assumed');
  expect(disconnected.readsAfterPauseResponse).toBeGreaterThan(0);
});

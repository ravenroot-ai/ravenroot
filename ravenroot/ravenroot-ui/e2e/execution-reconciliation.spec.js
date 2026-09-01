import { expect, test } from '@playwright/test';

test('lost terminal SSE plus failed GET becomes actionable without permitting a concurrent POST', async ({ page }, testInfo) => {
  let postCount = 0;
  let terminalAvailable = false;

  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200,
    contentType: 'application/json; charset=utf-8',
    body: '[]',
  }));
  // Deliberately no terminal frame: completion must come from reconciliation, not SSE.
  await page.route('**/v1/events', route => route.fulfill({
    status: 200,
    contentType: 'text/event-stream; charset=utf-8',
    body: '',
  }));
  await page.route('**/v1/executions**', route => {
    if (route.request().method() === 'POST') {
      postCount += 1;
      return route.fulfill({
        status: 200,
        contentType: 'application/json; charset=utf-8',
        body: JSON.stringify({
          executionId: `execution-${postCount}`,
          graphVersion: `graph-${postCount}`,
          executionPolicy: 'TEST_PASSTHROUGH',
        }),
      });
    }
    if (!terminalAvailable) {
      return route.fulfill({
        status: 503,
        contentType: 'application/json; charset=utf-8',
        body: JSON.stringify({ error: 'reconciliation temporarily unavailable' }),
      });
    }
    const firstExecution = route.request().url().endsWith('/execution-1');
    return route.fulfill({
      status: 200,
      contentType: 'application/json; charset=utf-8',
      body: JSON.stringify({
        status: 'COMPLETED', executionId: 'execution-1',
        defaultedNodes: firstExecution ? ['defaulted-in-unknown-recovery'] : [],
      }),
    });
  });

  await page.goto('/');
  const testButton = page.locator('#btn-play');
  await expect(testButton).toBeEnabled();
  await testButton.click();
  await expect.poll(() => postCount).toBe(1);
  await expect(page.locator('#activity-summary')).toContainText('Status unknown', { timeout: 6_000 });
  await expect(testButton).toBeEnabled();
  await expect(page.locator('#activity-log')).toContainText('automatic reconciliation continues');

  await testInfo.attach('execution-status-unknown.png', {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png',
  });

  // An enabled control is not permission to race the unknown execution. Its click is GET-only.
  await testButton.click();
  await expect(page.locator('#activity-log')).toContainText('No new execution was submitted');
  expect(postCount).toBe(1);

  // Once the same GET proves terminal state, the guarded click settles once and may submit.
  terminalAvailable = true;
  await testButton.click();
  await expect.poll(() => postCount).toBe(2);
  await expect(page.locator('#activity-log .activity-title', { hasText: 'Execution status recovered' })).toHaveCount(1);
  await expect(page.locator('#activity-log .activity-detail', {
    hasText: 'defaulted-in-unknown-recovery',
  })).toHaveCount(1);
});

async function installDelayedUnknownRuntime(page) {
  let postCount = 0;
  let delayedLookups = 0;
  let delayTerminal = false;
  let releaseTerminal;
  const terminalGate = new Promise(resolve => { releaseTerminal = resolve; });
  await page.route('**/v1/node-types', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events', route => route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.route('**/v1/executions**', async route => {
    if (route.request().method() === 'POST') {
      postCount += 1;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ executionId: `execution-${postCount}`, graphVersion: `graph-${postCount}` }),
      });
    }
    if (!delayTerminal) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"offline"}' });
    }
    delayedLookups += 1;
    await terminalGate;
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ status: 'COMPLETED', handledFailureNodes: [], defaultedNodes: [], bypassedNodes: [] }),
    });
  });
  return {
    get postCount() { return postCount; },
    get delayedLookups() { return delayedLookups; },
    delay() { delayTerminal = true; },
    release() { releaseTerminal(); },
  };
}

for (const [label, selectors] of [
  ['Test and Test', ['#btn-play', '#btn-play']],
  ['Test and Run', ['#btn-play', '#btn-run']],
]) {
  test(`${label} share one delayed unknown preflight and only one command reaches POST`, async ({ page }) => {
    const runtime = await installDelayedUnknownRuntime(page);
    await page.goto('/');
    await page.locator('#btn-play').click();
    await expect(page.locator('#activity-summary')).toContainText('Status unknown', { timeout: 6_000 });
    runtime.delay();
    await page.evaluate(clickSelectors => {
      window.confirm = () => true;
      clickSelectors.forEach(selector => document.querySelector(selector).click());
    }, selectors);
    await expect.poll(() => runtime.delayedLookups).toBe(1);
    expect(runtime.postCount).toBe(1);
    runtime.release();
    await expect.poll(() => runtime.postCount).toBe(2);
    await expect(page.locator('#activity-log .activity-title', {
      hasText: 'Execution status check already in progress',
    })).toHaveCount(1);
  });
}

for (const invalidation of ['switch', 'close']) {
  test(`${invalidation} during delayed preflight invalidates the command before POST`, async ({ page }) => {
    const runtime = await installDelayedUnknownRuntime(page);
    await page.goto('/');
    await page.locator('#btn-play').click();
    await expect(page.locator('#activity-summary')).toContainText('Status unknown', { timeout: 6_000 });
    runtime.delay();
    await page.locator('#btn-play').click();
    await expect.poll(() => runtime.delayedLookups).toBe(1);
    if (invalidation === 'switch') {
      await page.locator('#btn-new').click();
    } else {
      await page.evaluate(() => window.ravenroot.closeDocument(window.ravenroot.workspace.activeId));
    }
    runtime.release();
    await page.waitForTimeout(300);
    expect(runtime.postCount).toBe(1);
  });
}

test('ordinary terminal polling reports handled and bypassed outcome fields once without diagnostics', async ({ page }) => {
  let postCount = 0;
  await page.route('**/v1/node-types', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events', route => route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.route('**/v1/executions**', route => {
    if (route.request().method() === 'POST') {
      postCount += 1;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ executionId: 'poll-execution', graphVersion: 'poll-graph' }),
      });
    }
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        status: 'COMPLETED', handledFailureNodes: ['handled-node'], bypassedNodes: ['bypassed-node'],
        payload: 'NEVER_SHOW_PAYLOAD', error: 'NEVER_SHOW_ERROR', diagnostic: 'NEVER_SHOW_DIAGNOSTIC',
      }),
    });
  });
  await page.goto('/');
  await page.locator('#btn-play').click();
  await expect.poll(() => postCount).toBe(1);
  await expect(page.locator('#activity-log .activity-detail', { hasText: 'handled-node' })).toHaveCount(1);
  await expect(page.locator('#activity-log .activity-detail', { hasText: 'bypassed-node' })).toHaveCount(1);
  await expect(page.locator('#activity-log')).not.toContainText(/NEVER_SHOW_/);
});

async function installSseTerminalRace(page) {
  let postCount = 0;
  let getCount = 0;
  let releaseSse;
  let resolveOutcomeGate;
  const sseGate = new Promise(resolve => { releaseSse = resolve; });
  const outcomeGate = new Promise(resolve => { resolveOutcomeGate = resolve; });
  await page.route('**/v1/node-types', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events', async route => {
    await sseGate;
    const event = {
      type: 'EXECUTION_COMPLETED', executionId: 'sse-execution', graphVersion: 'sse-graph',
      occurredAt: new Date().toISOString(),
    };
    const frame = `event: execution\ndata: ${JSON.stringify(event)}\n\n`;
    return route.fulfill({ status: 200, contentType: 'text/event-stream', body: frame + frame });
  });
  await page.route('**/v1/executions**', async route => {
    if (route.request().method() === 'POST') {
      postCount += 1;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ executionId: 'sse-execution', graphVersion: 'sse-graph' }),
      });
    }
    getCount += 1;
    await outcomeGate;
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        status: 'COMPLETED', bypassedNodes: ['sse-bypassed'], payload: 'SSE_SECRET_PAYLOAD',
      }),
    });
  });
  return {
    get postCount() { return postCount; }, get getCount() { return getCount; },
    sendTerminal() { releaseSse(); }, releaseOutcome() { resolveOutcomeGate(); },
  };
}

test('SSE replay wins the terminal race but performs one bound outcome report', async ({ page }) => {
  const runtime = await installSseTerminalRace(page);
  await page.goto('/');
  await page.locator('#btn-play').click();
  await expect.poll(() => runtime.postCount).toBe(1);
  runtime.sendTerminal();
  await expect.poll(() => runtime.getCount).toBeGreaterThanOrEqual(1);
  runtime.releaseOutcome();
  await expect(page.locator('#activity-log .activity-detail', { hasText: 'sse-bypassed' })).toHaveCount(1);
  await expect(page.locator('#activity-log')).not.toContainText('SSE_SECRET_PAYLOAD');
});

test('SSE and an unknown-state preflight racing to terminal still report and submit once', async ({ page }) => {
  let postCount = 0;
  let terminalLookups = 0;
  let terminalPhase = false;
  let releaseSse;
  let releasePreflight;
  let releaseOutcome;
  const sseGate = new Promise(resolve => { releaseSse = resolve; });
  const preflightGate = new Promise(resolve => { releasePreflight = resolve; });
  const outcomeGate = new Promise(resolve => { releaseOutcome = resolve; });
  await page.route('**/v1/node-types', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events', async route => {
    await sseGate;
    const event = {
      type: 'EXECUTION_COMPLETED', executionId: 'race-execution-1', graphVersion: 'race-graph-1',
      occurredAt: new Date().toISOString(),
    };
    const frame = `event: execution\ndata: ${JSON.stringify(event)}\n\n`;
    return route.fulfill({ status: 200, contentType: 'text/event-stream', body: frame + frame });
  });
  await page.route('**/v1/executions**', async route => {
    if (route.request().method() === 'POST') {
      postCount += 1;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ executionId: `race-execution-${postCount}`, graphVersion: `race-graph-${postCount}` }),
      });
    }
    if (!terminalPhase) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"offline"}' });
    }
    terminalLookups += 1;
    const lookup = terminalLookups;
    await (lookup === 1 ? preflightGate : outcomeGate);
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        status: 'COMPLETED', defaultedNodes: lookup === 2 ? ['race-defaulted'] : [],
      }),
    });
  });

  await page.goto('/');
  await page.locator('#btn-play').click();
  await expect(page.locator('#activity-summary')).toContainText('Status unknown', { timeout: 6_000 });
  terminalPhase = true;
  await page.locator('#btn-play').click();
  await expect.poll(() => terminalLookups).toBe(1);
  releaseSse();
  await expect.poll(() => terminalLookups).toBe(2);
  releasePreflight();
  await expect.poll(() => postCount).toBe(2);
  await expect(page.locator('#activity-log .activity-detail', { hasText: 'race-defaulted' })).toHaveCount(0);
  releaseOutcome();
  await expect(page.locator('#activity-log .activity-detail', { hasText: 'race-defaulted' })).toHaveCount(1);
});

test('SSE outcome finishing after a document switch does not report into the active document', async ({ page }) => {
  const runtime = await installSseTerminalRace(page);
  await page.goto('/');
  await page.locator('#btn-play').click();
  await expect.poll(() => runtime.postCount).toBe(1);
  runtime.sendTerminal();
  await expect.poll(() => runtime.getCount).toBeGreaterThanOrEqual(1);
  await page.locator('#btn-new').click();
  runtime.releaseOutcome();
  await page.waitForTimeout(300);
  await expect(page.locator('#activity-log')).not.toContainText('sse-bypassed');
});

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

test('three rapid runs abort the oldest hanging outcome lookup without blocking the third command', async ({ page }) => {
  let postCount = 0;
  let eventConnection = 0;
  const eventGates = [deferred(), deferred()];
  const outcomeGates = new Map([['rapid-1', deferred()], ['rapid-2', deferred()]]);
  const getCounts = new Map();
  const requests = new Map();
  const failedRequests = [];
  page.on('requestfailed', request => failedRequests.push(request));
  await page.route('**/v1/node-types', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events', async route => {
    const index = eventConnection++;
    if (index >= eventGates.length) {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
    }
    await eventGates[index].promise;
    const execution = `rapid-${index + 1}`;
    const event = {
      type: 'EXECUTION_COMPLETED', executionId: execution, graphVersion: `graph-${index + 1}`,
      processInstanceId: `process-${index + 1}`,
      occurredAt: `2026-08-28T11:00:0${index}Z`,
    };
    return route.fulfill({
      status: 200, contentType: 'text/event-stream',
      body: `event: execution\ndata: ${JSON.stringify(event)}\n\n`,
    });
  });
  await page.route('**/v1/executions**', async route => {
    if (route.request().method() === 'POST') {
      postCount += 1;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          executionId: `rapid-${postCount}`, graphVersion: `graph-${postCount}`,
          processInstanceId: `process-${postCount}`,
        }),
      });
    }
    const id = route.request().url().split('/').pop();
    const count = (getCounts.get(id) || 0) + 1;
    getCounts.set(id, count);
    const byExecution = requests.get(id) || [];
    byExecution.push(route.request());
    requests.set(id, byExecution);
    if (count === 1) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"RUNNING"}' });
    }
    const gate = outcomeGates.get(id);
    if (gate) await gate.promise;
    try {
      return await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ status: 'COMPLETED', defaultedNodes: [`outcome-${id}`] }),
      });
    } catch {
      return undefined; // The browser aborted this intercepted response before its fixture gate opened.
    }
  });

  await page.goto('/');
  await page.locator('#btn-play').click();
  await expect.poll(() => postCount).toBe(1);
  await expect.poll(() => eventConnection).toBe(1);
  // The second request is deliberately a blocked reconciliation poll. Only after the terminal SSE
  // aborts it can the third request be known to be the outcome lookup this scenario is bounding.
  await expect.poll(() => getCounts.get('rapid-1') || 0).toBe(2);
  eventGates[0].resolve();
  await expect(page.locator('#btn-play')).toBeEnabled();
  await expect.poll(() => getCounts.get('rapid-1') || 0).toBe(3);
  const firstOutcomeRequest = requests.get('rapid-1')[2];
  await page.locator('#btn-play').click();
  await expect.poll(() => postCount).toBe(2);
  await expect.poll(() => eventConnection).toBe(2);
  await expect.poll(() => getCounts.get('rapid-2') || 0).toBe(2);
  eventGates[1].resolve();
  await expect(page.locator('#btn-play')).toBeEnabled();
  await expect.poll(() => getCounts.get('rapid-2') || 0).toBe(3);

  await page.locator('#btn-play').click();
  await expect.poll(() => postCount).toBe(3);
  await expect(page.locator('#activity-log')).toContainText('Outcome lookup cancelled');
  await expect.poll(() => failedRequests.includes(firstOutcomeRequest)).toBe(true);
  outcomeGates.get('rapid-1').resolve();
  outcomeGates.get('rapid-2').resolve();
  await expect(page.locator('#activity-log .activity-detail', { hasText: 'outcome-rapid-2' })).toHaveCount(1);
  await expect(page.locator('#activity-log .activity-detail', { hasText: 'outcome-rapid-1' })).toHaveCount(0);
});

test('closing a rebound document aborts and releases its hanging retired outcome lookup', async ({ page }) => {
  let postCount = 0;
  const eventGate = deferred();
  const outcomeGate = deferred();
  let getCount = 0;
  const failedRequests = [];
  page.on('requestfailed', request => failedRequests.push(request.url()));
  await page.route('**/v1/node-types', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events', async route => {
    await eventGate.promise;
    const event = {
      type: 'EXECUTION_COMPLETED', executionId: 'close-1', graphVersion: 'close-graph-1',
      occurredAt: '2026-08-28T12:00:00Z',
    };
    return route.fulfill({ status: 200, contentType: 'text/event-stream', body: `event: execution\ndata: ${JSON.stringify(event)}\n\n` });
  });
  await page.route('**/v1/executions**', async route => {
    if (route.request().method() === 'POST') {
      postCount += 1;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ executionId: `close-${postCount}`, graphVersion: `close-graph-${postCount}` }),
      });
    }
    getCount += 1;
    if (getCount === 1) return route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"RUNNING"}' });
    await outcomeGate.promise;
    try {
      return await route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"COMPLETED"}' });
    } catch {
      return undefined;
    }
  });

  await page.goto('/');
  await page.locator('#btn-play').click();
  await expect.poll(() => postCount).toBe(1);
  eventGate.resolve();
  await expect.poll(() => getCount).toBe(2);
  await page.locator('#btn-play').click();
  await expect.poll(() => postCount).toBe(2);
  await page.evaluate(() => window.ravenroot.closeDocument(window.ravenroot.workspace.activeId));
  await expect.poll(() => failedRequests.some(url => url.endsWith('/close-1'))).toBe(true);
  outcomeGate.resolve();
});

for (const [label, includeDelayedOld] of [
  ['rejects a delayed old SSE before the genuine new terminal', true],
  ['accepts the genuine new terminal when the old terminal was lost', false],
]) {
test(`identical id/client/version rebinding ${label}`, async ({ page }) => {
  let postCount = 0;
  let allowTerminalPreflight = false;
  let runTwoGetCount = 0;
  const eventsGate = deferred();
  const hangingPollGate = deferred();
  await page.route('**/v1/node-types', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events', async route => {
    await eventsGate.promise;
    const oldEvent = {
      type: 'EXECUTION_COMPLETED', executionId: 'same-execution', graphVersion: 'same-graph',
      processInstanceId: '11111111-1111-1111-1111-111111111111',
      occurredAt: '2026-08-28T13:00:00Z',
    };
    const newEvent = {
      ...oldEvent,
      processInstanceId: '22222222-2222-2222-2222-222222222222',
      occurredAt: '2026-08-28T13:00:01Z',
    };
    const frame = event => `event: execution\ndata: ${JSON.stringify(event)}\n\n`;
    return route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: (includeDelayedOld ? frame(oldEvent) : '') + frame(newEvent),
    });
  });
  await page.route('**/v1/executions**', async route => {
    if (route.request().method() === 'POST') {
      postCount += 1;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          executionId: 'same-execution', graphVersion: 'same-graph',
          processInstanceId: postCount === 1
            ? '11111111-1111-1111-1111-111111111111'
            : '22222222-2222-2222-2222-222222222222',
        }),
      });
    }
    if (postCount < 2) {
      if (!allowTerminalPreflight) {
        return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"offline"}' });
      }
      allowTerminalPreflight = false;
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"COMPLETED"}' });
    }
    runTwoGetCount += 1;
    if (runTwoGetCount === 1) {
      await hangingPollGate.promise;
      try {
        return await route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"RUNNING"}' });
      } catch {
        return undefined;
      }
    }
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: '{"status":"COMPLETED","defaultedNodes":["genuine-new-outcome"]}',
    });
  });

  await page.goto('/');
  await page.locator('#btn-play').click();
  await expect(page.locator('#activity-summary')).toContainText('Status unknown', { timeout: 6_000 });
  allowTerminalPreflight = true;
  await page.locator('#btn-play').click();
  await expect.poll(() => postCount).toBe(2);
  await expect.poll(() => runTwoGetCount).toBe(1);
  await expect.poll(() => page.evaluate(() => ({
    executionId: window.ravenroot.workspace.active.execution.executionId,
    processInstanceId: window.ravenroot.workspace.active.execution.processInstanceId,
    finished: window.ravenroot.workspace.active.execution.finished.has('same-execution'),
  }))).toEqual({
    executionId: 'same-execution',
    processInstanceId: '22222222-2222-2222-2222-222222222222',
    finished: false,
  });
  eventsGate.resolve();
  await expect(page.locator('#activity-log .activity-detail', { hasText: 'genuine-new-outcome' })).toHaveCount(1);
  await expect(page.locator('#activity-log')).toContainText('22222222');
  await expect(page.locator('#activity-log')).not.toContainText('11111111');
  hangingPollGate.resolve();
});
}

import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

import { expect, test } from '@playwright/test';

const ORIGIN = process.env.RAVENROOT_SOURCE_SESSION_ORIGIN;
const GRAPH_PATH = process.env.RAVENROOT_SOURCE_SESSION_GRAPH;
const WINDOW_MS = Number(process.env.RAVENROOT_SOURCE_SESSION_WINDOW_MS || 22_000);
const OUTPUT_DIR = process.env.RAVENROOT_SOURCE_SESSION_OUTPUT_DIR || '.';
const SOURCE_NODE = 'consume';
const LOG_NODE = 'log-admission';

function requireHarnessEnvironment() {
  if (!ORIGIN || !GRAPH_PATH) {
    throw new Error('The source-session editor harness supplies the server origin and graph path');
  }
}

// Everything the canvas is claiming, read from the editor's own records rather than from pixels: a
// screenshot proves a colour, this proves which document the events were attributed to and what the
// nodes were told.
async function canvasReport(page) {
  return page.evaluate(([sourceNode, logNode]) => {
    const document_ = window.ravenroot.activeDocument();
    const read = id => {
      const node = document_.cy?.getElementById(id);
      if (!node?.length) return null;
      return {
        runtimeState: node.data('runtimeState') || 'idle',
        observed: Boolean(node.data('runtimeObserved')),
        instances: Number(node.data('instances')) || 0,
        failures: Number(node.data('runtimeFailures')) || 0,
      };
    };
    const events = document_.execution.events || [];
    return {
      sessionState: document_.sourceSession.state,
      deploymentId: document_.sourceSession.deploymentId,
      boundExecutionId: document_.execution.executionId,
      source: read(sourceNode),
      log: read(logNode),
      // The assistant tail is deliberately bounded, so it reports the LAST few traversals rather
      // than all of them. Cumulative evidence comes from the monitoring projection below.
      attributedEvents: events.length,
      recentTraversals: new Set(events.map(event => event.executionId).filter(Boolean)).size,
      // One entry per process instance the projection has attributed to this deployment, which is
      // one per admitted message: the number that would have stayed at zero before the fix, and the
      // number that would have stayed at one if the projection still reset on every new traversal.
      processesObserved: document_.execution.monitoringFlow?.seen?.size ?? 0,
      edgeFlow: [...(document_.execution.monitoringFlow?.edges?.entries() || [])]
        .map(([edgeId, flow]) => [edgeId, flow.count]),
    };
  }, [SOURCE_NODE, LOG_NODE]);
}

// What the runtime published over the same window, read through the endpoint the issue names. The
// gap between this number and what the canvas shows IS the defect, so the check states both.
async function deliveredEventCount(page) {
  return page.evaluate(async () => {
    const response = await fetch('/v1/events/recent?include=diagnostics');
    if (!response.ok) throw new Error(`recent events failed with HTTP ${response.status}`);
    const body = await response.json();
    const events = body.events || [];
    return { count: events.length, continuity: body.continuity,
      lastSequence: body.lastSequence, oldestAvailable: body.oldestAvailable,
      // Kept for the failure message: whether the built-in log node's output survives the wire is a
      // property of one event, and naming the event is the difference between a diagnosis and a guess.
      logCompletion: events.find(event => event.type === 'NODE_COMPLETED'
        && event.nodeId === 'log-admission') || null };
  });
}

test('one refused start produces one actionable row and focuses its node property', async ({ page }) => {
  requireHarnessEnvironment();
  const graph = (await readFile(GRAPH_PATH, 'utf8'))
    .replace('<data key="intervalMs">700</data>', '<data key="intervalMs">not-an-integer</data>');
  const sourceStarts = [];
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().includes('/v1/source-sessions')) {
      sourceStarts.push(request.url());
    }
  });

  await page.goto(`${ORIGIN}/`);
  await expect(page.locator('#runtime-connection')).toHaveClass(/connected/, { timeout: 30_000 });
  await page.locator('#file-inp').setInputFiles({
    name: 'inadmissible-source.graphml', mimeType: 'application/xml', buffer: Buffer.from(graph),
  });
  await expect(page.locator('#btn-run')).toBeEnabled();
  await page.locator('#btn-run').click();

  const refusal = page.locator('#activity-log .activity-entry.failed')
    .filter({ has: page.locator('.activity-title', { hasText: 'Graph admission refused' }) });
  await expect(refusal).toHaveCount(1);
  await expect(refusal.locator('.activity-detail')).toContainText('PROPERTY_TYPE_INVALID');
  await expect(refusal.locator('.activity-detail')).toContainText('property intervalMs');
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('consume').selected())).toBe(true);
  await expect(page.locator('[data-catalog-property="intervalMs"]')).toBeFocused();
  expect(sourceStarts, 'inspection must refuse before any listener-start request').toEqual([]);
});

test('a listening source graph lights up in the editor while it admits real traffic', async ({ page }) => {
  requireHarnessEnvironment();
  const consoleErrors = [];
  page.on('pageerror', error => consoleErrors.push(String(error)));
  // A refused session start is the one failure whose reason lives on the wire and nowhere else: the
  // editor deliberately renders a fixed sentence rather than the server's text, so a harness that
  // did not capture the response would report "Failed" with nothing to act on.
  const sessionResponses = [];
  page.on('response', response => {
    if (!response.url().includes('/v1/source-sessions')) return;
    // Recorded synchronously and completed later: awaiting the body first loses the exchange
    // entirely when the assertion that needs it runs in the same tick as the refusal.
    const entry = { line: `${response.request().method()} ${response.status()} <body pending>` };
    sessionResponses.push(entry);
    void response.text().then(
      body => { entry.line = `${response.request().method()} ${response.status()} ${body}`; },
      () => { entry.line = `${response.request().method()} ${response.status()} <unreadable>`; });
  });

  await page.goto(`${ORIGIN}/`);
  await expect(page.locator('#runtime-connection')).toHaveClass(/connected/, { timeout: 30_000 });

  await page.locator('#file-inp').setInputFiles({
    name: 'listening-source.graphml',
    mimeType: 'application/xml',
    buffer: Buffer.from(await readFile(GRAPH_PATH)),
  });
  await expect(page.locator('#btn-run')).toBeEnabled();

  // Run on a graph with an effective SOURCE starts a listener session rather than one execution.
  const confirmation = new Promise(resolve => page.once('dialog', async dialog => {
    resolve(dialog.message());
    await dialog.accept();
  }));
  await page.locator('#btn-run').click();
  await expect(confirmation).resolves.toMatch(/local listener session/);
  // Reached either way, then judged, so a refusal reports the server's own answer instead of timing
  // out against a pill that will never change.
  await expect.poll(async () => page.locator('#source-session-status').getAttribute('class'), {
    timeout: 30_000,
    intervals: [250],
  }).toMatch(/listening|failed|degraded/);
  const lifecycle = await page.locator('#source-session-status').getAttribute('class');
  expect(lifecycle, `the session did not reach LISTENING. Source-session exchanges:\n`
    + `${sessionResponses.map(entry => entry.line).join('\n') || '<none observed>'}\n`
    + `Activity panel:\n${await page.locator('#activity-log').innerText()}`).toMatch(/listening/);

  const startedAt = Date.now();
  const before = await canvasReport(page);
  expect(before.deploymentId, 'the session must name the deployment its traversals belong to').toBeTruthy();
  expect(before.boundExecutionId, 'a listener session binds no single execution id').toBeNull();

  // The measurement the issue prescribes: watch the whole window, then compare what the runtime
  // published against what the canvas made of it. A seven-second sample can land entirely between
  // admissions and show nothing, which reads as a broken stream and is not evidence of anything.
  await page.waitForTimeout(Math.max(0, WINDOW_MS - (Date.now() - startedAt)));

  // Fit before the evidence is captured: the screenshot is the artifact a reader looks at first, and
  // a graph zoomed to one node proves nothing about the other four.
  await page.locator('[data-command-id="view.fit"]:visible').click();
  await page.waitForTimeout(800);
  const report = await canvasReport(page);
  const published = await deliveredEventCount(page);
  const evidence = () => `over ${WINDOW_MS} ms the runtime published ${JSON.stringify(published)} and `
    + `the editor attributed events from ${report.processesObserved} traversals.\n`
    + `Canvas: ${JSON.stringify(report)}`;
  expect(published.count, `the source must actually admit traffic for the window to mean anything: `
    + `${JSON.stringify(published)}; canvas ${JSON.stringify(report)}`).toBeGreaterThanOrEqual(20);
  // The number the defect pinned at zero. Twenty admissions in the window, and the editor is bound
  // to the deployment rather than to any one of their ids, so it sees all of them.
  expect(report.processesObserved, evidence()).toBeGreaterThanOrEqual(20);
  expect(report.recentTraversals, evidence()).toBeGreaterThan(1);
  await page.screenshot({ path: join(OUTPUT_DIR, 'listening-source-editor.png'), fullPage: true });

  // 1. The nodes are painted, by traffic this document never submitted and whose ids it never learnt.
  expect(report.source, 'the source node').toMatchObject({ observed: true });
  expect(report.log, 'the log node').toMatchObject({ observed: true });
  expect(['active', 'completed']).toContain(report.log.runtimeState);
  expect(report.log.failures).toBe(0);

  // 2. Monitoring accumulates across traversals instead of being reset by each new one. Before the
  // fix this projection was rebound -- and cleared -- by every arriving traversal, so no edge could
  // ever report a count above one however long the source ran.
  expect(report.edgeFlow.length, evidence()).toBeGreaterThan(0);
  for (const [edgeId, count] of report.edgeFlow) {
    expect(count, `edge ${edgeId} across ${report.processesObserved} traversals`)
      .toBeGreaterThanOrEqual(20);
  }

  // 3. The log node's output reaches the panel. The issue predicted this needs no separate work --
  // the output was always produced, published and delivered, and only ever dropped at routing.
  //
  // Since #458 the panel opens at the Output observance level, where a `log` node's emission is
  // presented as concise workflow output: the emitted value IS the row, and the generic
  // `NODE_COMPLETED · <node-id>` title with its process/traversal/invocation/attempt identifiers is
  // deliberately not something a reader has to see past to find it. Trace still renders the technical
  // form; `e2e/activity-visibility-modes.spec.js` and `test/activity-visibility.test.js` pin both, so
  // what is asserted here is the level a freshly loaded editor actually presents.
  const activity = page.locator('#activity-log .activity-entry.output-value');
  await expect(activity.last(), `published log completion: ${JSON.stringify(published.logCompletion)}`)
    .toContainText(/admitted message-\d+/, { timeout: 10_000 });

  // 4. Stop ends it cleanly, and the pill says so rather than the canvas quietly freezing.
  await page.locator('#btn-stop').click();
  await expect(page.locator('#source-session-status')).toContainText('Stopped', { timeout: 30_000 });

  expect(consoleErrors, 'the observation window must produce no uncaught page errors').toEqual([]);
});

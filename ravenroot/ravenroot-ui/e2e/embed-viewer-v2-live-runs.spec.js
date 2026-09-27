import { expect, test } from '@playwright/test';
import path from 'node:path';

const evidencePath = name => process.env.RR_VISUAL_EVIDENCE_DIR
  ? path.join(process.env.RR_VISUAL_EVIDENCE_DIR, name) : undefined;

const projection = {
  viewerSourceVersion: '2', lifecycle: 'READY', canonicalDigest: 'v1',
  source: { kind: 'deployment', deploymentId: 'orders', graphVersion: 'v1', incarnationId: 'inc-2' },
  projection: { viewerContractVersion: '1.0', graphId: 'orders', graphVersionId: 'v1',
    canonicalDigest: 'v1', nodes: [
      { id: 'start', kind: 'START', label: 'Start', visualType: 'start', layout: { x: 80, y: 100, width: 80, height: 80 } },
      { id: 'worker', kind: 'BEHAVIOR', label: 'Worker', visualType: 'actor', layout: { x: 280, y: 100, width: 120, height: 52 } },
    ], edges: [{ id: 'route', source: 'start', target: 'worker', label: 'next', visualType: 'continue' }] },
};

async function mount(page, source = projection, theme = 'dark') {
  await page.goto('/');
  await page.evaluate(async ({ source, theme }) => {
    document.documentElement.dataset.theme = theme;
    const link = document.createElement('link');
    link.rel = 'stylesheet'; link.href = '/embed-viewer.css'; document.head.append(link);
    await new Promise(resolve => link.addEventListener('load', resolve, { once: true }));
    document.body.innerHTML = `<span class="embed-focus-sentinel" tabindex="0"></span>
      <main id="ravenroot-embed-viewer" class="embed-viewer">
        <header class="embed-viewer-header"><h1 class="embed-viewer-title">Graph</h1>
          <p class="embed-viewer-metadata" data-viewer-metadata hidden></p>
          <nav class="embed-viewer-controls" aria-label="Graph view controls">
          <label class="embed-mode-label">View <select data-viewer-mode><option value="design">Design</option>
            <option value="monitoring">Monitoring</option></select></label>
          <button data-viewer-command="render">Render</button>
          <label class="embed-run-label">Run <select data-viewer-run aria-label="Live run"></select></label>
          <span class="embed-visually-hidden" data-viewer-run-empty role="status">No authorized runs.</span>
          <span class="embed-zoom-controls"><button data-viewer-command="fit">Fit</button></span>
          <button class="embed-maximize" data-viewer-maximize aria-pressed="false"
            aria-label="Maximize embedded graph"><span aria-hidden="true"></span></button></nav></header>
        <div class="embed-viewer-canvas" data-viewer-canvas tabindex="0"></div>
        <canvas class="embed-viewer-minimap" data-viewer-minimap tabindex="0"></canvas>
        <section class="embed-viewer-alternative"><ol data-viewer-alternative></ol></section>
        <p class="embed-viewer-status" data-viewer-status></p></main>
      <span class="embed-focus-sentinel" tabindex="0"></span>`;
    const { createEmbedViewer } = await import('/embed-viewer.js');
    window.selectionLog = [];
    window.v2Viewer = createEmbedViewer(document.querySelector('main'), {
      theme,
      onRunSelected: (id, generation) => window.selectionLog.push({ id, generation }),
    });
    await window.v2Viewer.mount(source);
  }, { source, theme });
}

test('maximized embed falls back safely, preserves the mounted graph, and restores on Escape', async ({ page }, testInfo) => {
  for (const theme of ['dark', 'light']) {
    await mount(page, projection, theme);
    await page.evaluate(value => {
      document.documentElement.dataset.theme = value;
      document.querySelector('main').requestFullscreen = undefined;
    }, theme);
    await page.getByRole('combobox').first().selectOption('monitoring');
    const button = page.locator('[data-viewer-maximize]');
    const before = await page.evaluate(() => window.v2Viewer.presentationSnapshot());
    await expect(page.locator('.d3-node-labels text').first()).toHaveAttribute(
      'fill', theme === 'dark' ? '#e6edf3' : '#1f2328');
    if (evidencePath(`embed-${theme}-normal.png`)) {
      await page.screenshot({ path: evidencePath(`embed-${theme}-normal.png`) });
    }
    await button.click();
    await expect(page.locator('main')).toHaveClass(/embed-viewer--maximized/);
    await expect(button).toHaveAttribute('aria-label', 'Restore embedded graph');
    expect(await page.locator('.embed-viewer-controls > :not(.embed-maximize)')
      .evaluateAll(elements => elements.map(element => getComputedStyle(element).display)))
      .toEqual(['none', 'none', 'none', 'none', 'none']);
    await expect(page.locator('.embed-viewer-minimap')).toBeHidden();
    const restoreBox = await button.boundingBox();
    expect(restoreBox).toMatchObject({ width: 40, height: 40 });
    const shellBox = await page.locator('main').boundingBox();
    const canvasBox = await page.locator('[data-viewer-canvas]').boundingBox();
    expect(canvasBox).toMatchObject({ x: shellBox.x, y: shellBox.y,
      width: shellBox.width, height: shellBox.height });
    await testInfo.attach(`embed-${theme}-maximized.png`, {
      body: await page.screenshot(), contentType: 'image/png',
    });
    if (evidencePath(`embed-${theme}-maximized.png`)) {
      await page.screenshot({ path: evidencePath(`embed-${theme}-maximized.png`) });
    }
    await page.keyboard.press('Escape');
    await expect(page.locator('main')).not.toHaveClass(/embed-viewer--maximized/);
    await expect(button).toBeFocused();
    expect(await page.evaluate(() => window.v2Viewer.presentationSnapshot())).toEqual(before);
  }
});

test('deployed groups show hidden member and internal-edge activity, then expose member tooltip details', async ({ page }, testInfo) => {
  const grouped = structuredClone(projection);
  grouped.projection.nodes.push({ id: 'end', kind: 'END', label: 'End', visualType: 'end',
    layout: { x: 480, y: 100, width: 80, height: 80 } });
  grouped.projection.edges.push({ id: 'finish', source: 'worker', target: 'end', label: 'done', visualType: 'completed' });
  grouped.projection.groups = [{ id: 'pipeline', name: 'Pipeline', memberNodeIds: ['start', 'worker'],
    anchorNodeId: 'start', collapsed: true }];
  await mount(page, grouped);
  await page.evaluate(() => window.v2Viewer.updateRuns({ runs: [
    { processInstanceId: 'run-a', status: 'ACTIVE', outcome: null },
  ] }));
  const generation = await page.evaluate(() => window.selectionLog.at(-1).generation);
  await page.getByRole('combobox').first().selectOption('monitoring');
  await page.evaluate(({ source, currentGeneration }) => {
    window.v2Viewer.observe({ type: 'reset', ...source, processInstanceId: 'run-a' }, currentGeneration);
    window.v2Viewer.observe({ type: 'execution', ...source, processInstanceId: 'run-a', cursor: 'n1',
      event: { type: 'NODE_STARTED', nodeId: 'worker', executionId: 'run-a', sequence: 1,
        activeInstances: 2, inFlightArrivals: 3, occurredAt: '2026-09-26T21:21:45Z', processingDuration: 0.004121079 } }, currentGeneration);
    window.v2Viewer.observe({ type: 'execution', ...source, processInstanceId: 'run-a', cursor: 'e1',
      event: { type: 'EDGE_TRAVERSED', edgeId: 'route', executionId: 'run-a', sequence: 2,
        occurredAt: new Date().toISOString() } }, currentGeneration);
  }, { source: grouped.source, currentGeneration: generation });
  const summary = page.getByRole('button', { name: 'Expand visual group Pipeline, 2 members' });
  await expect(summary).toHaveAttribute('data-member-edge-pulses', '1');
  await expect(summary.locator('rect')).toHaveAttribute('stroke-width', '5');
  const externalFlow = await page.evaluate(({ source, currentGeneration }) => {
    const result = window.v2Viewer.observe({ type: 'execution', ...source, processInstanceId: 'run-a', cursor: 'e2',
      event: { type: 'EDGE_TRAVERSED', edgeId: 'finish', executionId: 'run-a', sequence: 3,
        occurredAt: new Date().toISOString() } }, currentGeneration);
    const paths = [...document.querySelectorAll('.d3-visual-edges path')].map(path => ({
      edge: path.getAttribute('data-original-edge-id'), className: path.getAttribute('class'),
      width: path.getAttribute('stroke-width'),
    }));
    return { result, paths };
  }, { source: grouped.source, currentGeneration: generation });
  expect(externalFlow).toMatchObject({ result: { accepted: true, reason: 'execution' },
    paths: expect.arrayContaining([{ edge: 'finish', className: expect.stringContaining('d3-edge--active'),
      width: expect.any(String) }]) });
  const projectedFinish = page.locator('.d3-visual-edges [data-original-edge-id="finish"]');
  await expect(projectedFinish).toHaveClass(/d3-edge--active/);
  expect(Number(await projectedFinish.getAttribute('stroke-width'))).toBeGreaterThan(1.8);
  const duplicate = await page.evaluate(({ source, currentGeneration }) => window.v2Viewer.observe({
    type: 'execution', ...source, processInstanceId: 'run-a', cursor: 'e2',
    event: { type: 'EDGE_TRAVERSED', edgeId: 'finish', executionId: 'run-a', sequence: 3,
      occurredAt: new Date().toISOString() },
  }, currentGeneration), { source: grouped.source, currentGeneration: generation });
  expect(duplicate).toMatchObject({ accepted: false, reason: 'duplicate' });
  await expect(projectedFinish).toHaveClass(/d3-edge--active/);
  await expect(summary).toHaveAttribute('data-member-edge-pulses', '0', { timeout: 3_000 });
  await expect(projectedFinish).not.toHaveClass(/d3-edge--active/, { timeout: 3_000 });
  expect(Number(await projectedFinish.getAttribute('stroke-width'))).toBe(1.8);
  await summary.click();
  const collapse = page.getByRole('button', { name: 'Collapse visual group Pipeline, 2 members' });
  await expect(collapse).toBeVisible();
  await page.locator('.d3-nodes circle[data-node-id="worker"]').hover();
  const tooltip = page.locator('.embed-runtime-tooltip');
  await expect(tooltip).toContainText('Worker');
  await expect(tooltip).toContainText('ID: worker');
  await expect(tooltip).toContainText('State: active');
  await expect(tooltip).toContainText('Active instances: 2');
  await expect(tooltip).toContainText('In-flight arrivals: 3');
  await expect(tooltip).toContainText('Last event: NODE_STARTED');
  await expect(tooltip).toContainText('Processing duration: 0.004121079s');
  await expect(tooltip).toContainText('Fallback: no');
  await expect(tooltip).toContainText('Bypassed: no');
  const box = await tooltip.boundingBox();
  const canvas = await page.locator('[data-viewer-canvas]').boundingBox();
  expect(box.x).toBeGreaterThanOrEqual(canvas.x);
  expect(box.y).toBeGreaterThanOrEqual(canvas.y);
  expect(box.x + box.width).toBeLessThanOrEqual(canvas.x + canvas.width + 1);
  expect(box.y + box.height).toBeLessThanOrEqual(canvas.y + canvas.height + 1);
  if (evidencePath('embed-group-dark-expanded-tooltip.png')) {
    await page.screenshot({ path: evidencePath('embed-group-dark-expanded-tooltip.png') });
  }
  await collapse.click();
  await page.evaluate(({ source, currentGeneration }) => {
    window.v2Viewer.observe({ type: 'execution', ...source, processInstanceId: 'run-a', cursor: 'e3',
      event: { type: 'EDGE_TRAVERSED', edgeId: 'route', executionId: 'run-a', sequence: 4,
        occurredAt: new Date().toISOString() } }, currentGeneration);
  }, { source: grouped.source, currentGeneration: generation });
  await expect(summary).toHaveAttribute('data-selected', 'true');
  await expect(summary).toHaveAttribute('data-member-edge-pulses', '1');
  await expect(summary.locator('rect')).toHaveAttribute('stroke-width', '5');
  await page.evaluate(() => {
    window.v2Viewer.updateRuns({ runs: [
      { processInstanceId: 'run-a', status: 'ACTIVE', outcome: null },
      { processInstanceId: 'run-b', status: 'ACTIVE', outcome: null },
    ] }, 'run-a');
  });
  await page.getByRole('combobox', { name: 'Live run' }).selectOption('run-b');
  await expect(summary).toHaveAttribute('data-member-edge-pulses', '0');
  await expect(summary).toHaveAttribute('data-selected', 'true');
  await expect(summary.locator('rect')).toHaveAttribute('stroke-width', '3');
  await testInfo.attach('embed-group-dark-collapsed.png', {
    body: await page.screenshot(), contentType: 'image/png',
  });
  if (evidencePath('embed-group-dark-collapsed.png')) {
    await page.screenshot({ path: evidencePath('embed-group-dark-collapsed.png') });
  }

  await mount(page, grouped, 'light');
  await page.getByRole('combobox').first().selectOption('monitoring');
  const lightSummary = page.getByRole('button', { name: 'Expand visual group Pipeline, 2 members' });
  await expect(lightSummary).toBeVisible();
  await testInfo.attach('embed-group-light-collapsed.png', {
    body: await page.screenshot(), contentType: 'image/png',
  });
  if (evidencePath('embed-group-light-collapsed.png')) {
    await page.screenshot({ path: evidencePath('embed-group-light-collapsed.png') });
  }
  await lightSummary.click();
  await page.locator('.d3-nodes circle[data-node-id="worker"]').hover();
  await expect(page.locator('.embed-runtime-tooltip')).toContainText('ID: worker');
  if (evidencePath('embed-group-light-expanded-tooltip.png')) {
    await page.screenshot({ path: evidencePath('embed-group-light-expanded-tooltip.png') });
  }
});

test('v2 selector reconciles zero, one, and three runs and fences rapid stale delivery', async ({ page }) => {
  await mount(page);
  const run = page.getByRole('combobox', { name: 'Live run' });
  await page.evaluate(() => window.v2Viewer.updateRuns({ runs: [] }));
  await expect(run).toBeDisabled();
  await expect(page.getByText('No authorized runs.')).toBeVisible();

  await page.evaluate(() => window.v2Viewer.updateRuns({ runs: [
    { processInstanceId: 'run-a', status: 'ACTIVE', outcome: null },
  ] }));
  await expect(run).toHaveValue('run-a');

  await page.evaluate(() => window.v2Viewer.updateRuns({ runs: [] }));
  await page.evaluate(() => window.v2Viewer.updateRuns({ runs: [
    { processInstanceId: 'run-a', status: 'ACTIVE', outcome: null },
    { processInstanceId: 'run-b', status: 'ACTIVE', outcome: null },
    { processInstanceId: 'run-c', status: 'COMPLETED', outcome: 'SUCCESS' },
  ] }, null));
  await expect(run).toHaveValue('');
  await run.selectOption('run-b');
  const generation = await page.evaluate(() => window.selectionLog.at(-1).generation);
  const stale = await page.evaluate(({ source, currentGeneration }) => window.v2Viewer.observe({
    type: 'execution', ...source, processInstanceId: 'run-a', cursor: 'old',
    event: { type: 'NODE_FAILED', nodeId: 'worker', executionId: 'old', sequence: 1 },
  }, currentGeneration - 1), { source: projection.source, currentGeneration: generation });
  expect(stale.reason).toBe('stale-generation');

  await page.evaluate(({ source, currentGeneration }) => {
    window.v2Viewer.observe({ type: 'reset', ...source, processInstanceId: 'run-b' }, currentGeneration);
    window.v2Viewer.observe({ type: 'execution', ...source, processInstanceId: 'run-b', cursor: null,
      event: { type: 'NODE_COMPLETED', nodeId: 'worker', executionId: 'new', sequence: 1 } }, currentGeneration);
  }, { source: projection.source, currentGeneration: generation });
  await expect(page.locator('main')).toHaveAttribute('data-viewer-continuity', 'live');

  await page.evaluate(() => window.v2Viewer.clearRuntime('Live observation authorization ended.'));
  await expect(page.locator('[data-viewer-status]')).toHaveText('Live observation authorization ended.');
});

test('v2 public controls are semantic, keyboard accessible, and responsive', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 700 });
  await mount(page);
  await expect(page.getByRole('option', { name: 'Design' })).toHaveCount(1);
  await expect(page.getByRole('option', { name: 'Monitoring' })).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'Render' })).toBeVisible();
  await expect(page.getByText(/Cyto|N8N|Elastic/)).toHaveCount(0);
  for (const theme of ['light', 'dark']) {
    await page.evaluate(value => { document.documentElement.dataset.theme = value; }, theme);
    for (const mode of ['design', 'monitoring']) {
      await page.getByRole('combobox').first().selectOption(mode);
      await expect(page.locator('main')).toHaveAttribute('data-viewer-renderer', mode);
      await testInfo.attach(`v2-responsive-${theme}-${mode}.png`, {
        body: await page.locator('main').screenshot(), contentType: 'image/png',
      });
    }
  }
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Tab');
  await expect(page.locator('.embed-viewer-header')).toBeVisible();
});

test('v2 clears runtime on revocation, replacement, and replay-gap reconciliation', async ({ page }) => {
  await mount(page);
  await page.evaluate(() => window.v2Viewer.updateRuns({ runs: [
    { processInstanceId: 'run-a', status: 'ACTIVE', outcome: null },
  ] }));
  const generation = await page.evaluate(() => window.selectionLog.at(-1).generation);
  const revoked = await page.evaluate(({ source, currentGeneration }) => {
    window.v2Viewer.observe({ type: 'execution', ...source, processInstanceId: 'run-a', cursor: 'one',
      event: { type: 'NODE_STARTED', nodeId: 'worker', executionId: 'run', sequence: 1 } }, currentGeneration);
    return window.v2Viewer.observe({ type: 'invalidated', ...source, processInstanceId: 'run-a',
      reason: 'AUTHORITY_CHANGED' }, currentGeneration);
  }, { source: projection.source, currentGeneration: generation });
  expect(revoked).toMatchObject({ terminal: true, reason: 'authority-changed' });
  await expect(page.locator('main')).toHaveAttribute('data-viewer-continuity', 'detached');

  const replaced = await page.evaluate(({ source, currentGeneration }) => window.v2Viewer.observe({
    type: 'execution', ...source, incarnationId: 'inc-replacement', processInstanceId: 'run-a',
    cursor: 'replacement', event: { type: 'NODE_COMPLETED', nodeId: 'worker', executionId: 'old', sequence: 2 },
  }, currentGeneration), { source: projection.source, currentGeneration: generation });
  expect(replaced).toMatchObject({ terminal: true, reason: 'binding-mismatch' });

  await page.evaluate(({ source, currentGeneration }) => {
    window.v2Viewer.observe({ type: 'reset', ...source, processInstanceId: 'run-a' }, currentGeneration);
    window.v2Viewer.observe({ type: 'execution', ...source, processInstanceId: 'run-a', cursor: null,
      event: { type: 'NODE_COMPLETED', nodeId: 'worker', executionId: 'durable', sequence: 1 } }, currentGeneration);
  }, { source: projection.source, currentGeneration: generation });
  await expect(page.locator('main')).toHaveAttribute('data-viewer-continuity', 'live');
});

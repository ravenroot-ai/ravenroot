import { expect, test } from '@playwright/test';

const projection = {
  viewerSourceVersion: '2', lifecycle: 'READY', canonicalDigest: 'v1',
  source: { kind: 'deployment', deploymentId: 'orders', graphVersion: 'v1', incarnationId: 'inc-2' },
  projection: { viewerContractVersion: '1.0', graphId: 'orders', graphVersionId: 'v1',
    canonicalDigest: 'v1', nodes: [
      { id: 'start', kind: 'START', label: 'Start', visualType: 'start', layout: { x: 80, y: 100, width: 80, height: 80 } },
      { id: 'worker', kind: 'BEHAVIOR', label: 'Worker', visualType: 'actor', layout: { x: 280, y: 100, width: 120, height: 52 } },
    ], edges: [{ id: 'route', source: 'start', target: 'worker', label: 'next', visualType: 'continue' }] },
};

async function mount(page) {
  await page.goto('/');
  await page.evaluate(async source => {
    const link = document.createElement('link');
    link.rel = 'stylesheet'; link.href = '/embed-viewer.css'; document.head.append(link);
    await new Promise(resolve => link.addEventListener('load', resolve, { once: true }));
    document.body.innerHTML = `<span class="embed-focus-sentinel" tabindex="0"></span>
      <main id="ravenroot-embed-viewer" class="embed-viewer">
        <header class="embed-viewer-header"><p data-viewer-metadata></p>
          <label>View <select data-viewer-mode><option value="design">Design</option>
            <option value="monitoring">Monitoring</option></select></label>
          <button data-viewer-command="render">Render</button>
          <label>Run <select data-viewer-run aria-label="Live run"></select></label>
          <span data-viewer-run-empty role="status">No authorized runs.</span>
          <button data-viewer-command="fit">Fit</button></header>
        <div data-viewer-canvas tabindex="0" style="height:420px"></div>
        <canvas data-viewer-minimap tabindex="0"></canvas><ol data-viewer-alternative></ol>
        <p data-viewer-status></p></main>
      <span class="embed-focus-sentinel" tabindex="0"></span>`;
    const { createEmbedViewer } = await import('/embed-viewer.js');
    window.selectionLog = [];
    window.v2Viewer = createEmbedViewer(document.querySelector('main'), {
      onRunSelected: (id, generation) => window.selectionLog.push({ id, generation }),
    });
    await window.v2Viewer.mount(source);
  }, projection);
}

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

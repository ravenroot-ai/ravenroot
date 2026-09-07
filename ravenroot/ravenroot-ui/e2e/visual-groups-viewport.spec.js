import { expect, test } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
import { visualGroupFixtureGraphML } from '../test/fixtures/visual-groups.js';

test.use({ viewport: { width: 1600, height: 1000 }, video: 'on' });

async function openFixture(page, { collapsed = true, includeGroups = true } = {}) {
  await page.route('**/v1/node-types', route => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events', route => route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
  await page.waitForFunction(() => window.cy?.nodes().length === 4 && !window.cy.scratch('_rrLayoutRunning'));
  await page.locator('#replace-file-inp').setInputFiles({ name: 'synthetic-groups.graphml', mimeType: 'application/xml',
    buffer: Buffer.from(visualGroupFixtureGraphML({ collapsed, includeGroups })) });
  await page.waitForFunction(() => window.ravenroot?.workspace.active?.graph.nodes.length === 80);
  await page.waitForFunction(() => !window.cy.scratch('_rrLayoutRunning'));
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
  await page.evaluate(() => { window.cy.zoom(.68); window.cy.pan({ x: 35, y: 90 }); });
}

async function clickNode(page, id, modifiers = []) {
  const point = await page.evaluate(id => {
    const p = window.cy.getElementById(id).renderedPosition();
    const box = window.cy.container().getBoundingClientRect();
    return { x: box.x+p.x, y: box.y+p.y };
  }, id);
  for (const modifier of modifiers) await page.keyboard.down(modifier);
  await page.mouse.click(point.x, point.y);
  for (const modifier of modifiers) await page.keyboard.up(modifier);
}

async function settle(page) {
  await page.waitForFunction(() => !window.ravenroot.workspace.active.visualGroupsRenderer?.isAnimating);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function startSampling(page) {
  await page.evaluate(() => {
    const owner = window.ravenroot.workspace.active;
    const cy = owner.cy;
    const samples = [];
    const start = performance.now();
    window.__visualGroupSamples = samples;
    window.__sampleVisualGroups = true;
    const sample = () => {
      if (!window.__sampleVisualGroups || window.ravenroot.workspace.active !== owner) return;
      const anchor = cy.getElementById('node-1');
      const external = cy.getElementById('node-0');
      const ghost = cy.nodes('[rrOriginalNodeId="node-2"]');
      const original = cy.getElementById('node-2');
      samples.push({ time: performance.now()-start, zoom: cy.zoom(), pan: cy.pan(),
        anchor: anchor.renderedPosition(), external: external.renderedPosition(),
        dimensions: [anchor.width(), anchor.height(), anchor.style('font-size')],
        ghostExpected: [original.width(), original.height(), original.style('font-size')],
        positions: owner.graph.nodes.map(node => [node.id, cy.getElementById(node.id).position()]),
        ghost: ghost.nonempty() ? { ...ghost.position(), width: ghost.width(), height: ghost.height(),
          font: ghost.style('font-size'), opacity: Number(ghost.style('opacity')) } : null,
        animating: Boolean(owner.visualGroupsRenderer?.isAnimating),
      });
      requestAnimationFrame(sample);
    };
    sample();
  });
}

async function stopSampling(page) {
  return page.evaluate(() => { window.__sampleVisualGroups = false; return window.__visualGroupSamples; });
}

function verifyFrames(samples, { motion = true } = {}) {
  expect(samples.length).toBeGreaterThan(2);
  const first = samples[0];
  for (const frame of samples) {
    expect(Math.abs(frame.zoom-first.zoom)).toBeLessThanOrEqual(1e-6);
    expect(Math.hypot(frame.anchor.x-first.anchor.x, frame.anchor.y-first.anchor.y)).toBeLessThanOrEqual(1);
    expect(Math.hypot(frame.external.x-first.external.x, frame.external.y-first.external.y)).toBeLessThanOrEqual(1);
    expect(frame.dimensions).toEqual(first.dimensions);
    expect(frame.positions).toEqual(first.positions);
    if (frame.ghost) expect([frame.ghost.width, frame.ghost.height, frame.ghost.font]).toEqual(frame.ghostExpected);
  }
  if (motion) {
    const moving = samples.filter(frame => frame.ghost && frame.ghost.opacity > .02 && frame.ghost.opacity < .98);
    expect(moving.length, 'normal motion must have intermediate rendered member frames').toBeGreaterThan(2);
    expect(new Set(moving.map(frame => frame.ghost.x.toFixed(2))).size).toBeGreaterThan(2);
  }
}

test('dense overview expands and collapses for twenty cycles with fixed viewport, anchor and external geometry', async ({ page }, testInfo) => {
  test.setTimeout(120_000);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await openFixture(page);
  const id = await page.evaluate(() => window.ravenroot.workspace.active.visualGroupsRenderer.projection.groups[0].summaryId);
  await clickNode(page, id);
  await expect(page.locator('#info-body')).toContainText('Visual group');
  await expect(page.locator('#info-body')).toContainText('24 members');
  await page.screenshot({ path: testInfo.outputPath('overview.png') });
  await startSampling(page);
  for (let cycle = 0; cycle < 20; cycle += 1) {
    await page.locator('#info-body').getByRole('button', { name: 'Expand', exact: true }).click();
    await settle(page);
    if (cycle === 0) await page.screenshot({ path: testInfo.outputPath('expanded.png') });
    await expect(page.locator('#info-body').getByRole('button', { name: 'Collapse', exact: true })).toBeVisible();
    await page.locator('#info-body').getByRole('button', { name: 'Collapse', exact: true }).click();
    await settle(page);
  }
  const samples = await stopSampling(page);
  verifyFrames(samples);
  expect(errors).toEqual([]);
  const summary = { browser: testInfo.project.use.browserName || 'chromium', cycles: 20, samples: samples.length,
    animationFrames: samples.filter(frame => frame.animating).length,
    elapsedMs: samples.at(-1).time, maxFrameGapMs: Math.max(...samples.slice(1).map((frame, index) => frame.time-samples[index].time)),
    zoom: samples[0].zoom, anchor: samples[0].anchor, external: samples[0].external };
  const measurementPath = testInfo.outputPath('frame-measurements.json');
  await writeFile(measurementPath, JSON.stringify({ summary, samples }));
  await testInfo.attach('frame-measurements.json', { path: measurementPath, contentType: 'application/json' });
});

test('physical multi-selection creates a group through the minibar and reduced motion keeps geometry fixed', async ({ page }, testInfo) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await openFixture(page, { includeGroups: false });
  await clickNode(page, 'node-1');
  await expect.poll(() => page.evaluate(() => window.cy.nodes(':selected').map(node => node.id()))).toEqual(['node-1']);
  await clickNode(page, 'node-2', ['ControlOrMeta']);
  await expect.poll(() => page.evaluate(() => window.cy.nodes(':selected').map(node => node.id()).sort())).toEqual(['node-1', 'node-2']);
  const point = await page.evaluate(() => {
    const p = window.cy.getElementById('node-0').renderedPosition(); const rect = window.cy.container().getBoundingClientRect();
    return { x: rect.x+p.x, y: rect.y+p.y };
  });
  await page.mouse.move(point.x, point.y);
  await page.locator('.graph-node-action[data-node-action="group"]:visible').click();
  const dialog = page.getByRole('dialog', { name: 'Group selection', exact: true });
  await dialog.getByRole('textbox', { name: 'Group name' }).fill('Selected section');
  await startSampling(page);
  await dialog.getByRole('button', { name: 'Create group', exact: true }).click();
  await settle(page);
  await expect(page.locator('#info-body')).toContainText('2 members');
  await page.locator('#info-body').getByRole('button', { name: 'Expand', exact: true }).click();
  await settle(page);
  const samples = await stopSampling(page); verifyFrames(samples, { motion: false });
  expect(samples.every(frame => !frame.ghost)).toBe(true);
  const measurementPath = testInfo.outputPath('reduced-motion-frames.json');
  await writeFile(measurementPath, JSON.stringify(samples));
  await testInfo.attach('reduced-motion-frames.json', { path: measurementPath, contentType: 'application/json' });
});

test('rapid toggles preserve user wheel zoom and export finishes canonical geometry', async ({ page }) => {
  await openFixture(page);
  const id = await page.evaluate(() => window.ravenroot.workspace.active.visualGroupsRenderer.projection.groups[0].summaryId);
  await clickNode(page, id);
  await page.locator('#info-body').getByRole('button', { name: 'Expand', exact: true }).click();
  // Dispatch actual command controls in one task so each request supersedes an in-flight frame.
  await page.evaluate(() => {
    for (let i = 0; i < 9; i += 1) {
      const button = [...document.querySelectorAll('#info-body button')].find(control => /^(Expand|Collapse)$/.test(control.textContent));
      button.click();
    }
  });
  await settle(page);
  const expand = page.locator('#info-body').getByRole('button', { name: 'Expand', exact: true });
  if (await expand.count()) await expand.click();
  const before = await page.evaluate(() => ({ zoom: window.cy.zoom(), positions: window.ravenroot.workspace.active.graph.nodes.map(node => [node.id, window.cy.getElementById(node.id).position()]) }));
  const box = await page.locator('.doc-pane--active .doc-canvas').boundingBox();
  await page.mouse.move(box.x+box.width*.6, box.y+box.height*.5);
  await page.mouse.wheel(0, -120);
  await settle(page);
  expect(await page.evaluate(() => window.cy.zoom())).toBeGreaterThan(before.zoom);
  const downloadPromise = page.waitForEvent('download');
  await page.locator('#info-body').getByRole('button', { name: 'Collapse', exact: true }).click();
  await page.locator('#btn-export').click();
  const download = await downloadPromise;
  const stream = await download.createReadStream();
  let xml = ''; for await (const chunk of stream) xml += chunk.toString();
  expect(xml).not.toContain('rrVisualRole');
  expect((xml.match(/<node\b/g) || []).length).toBe(80);
  expect((xml.match(/<edge\b/g) || []).length).toBe(180);
  expect(await page.evaluate(() => window.ravenroot.workspace.active.graph.nodes.map(node => [node.id, window.cy.getElementById(node.id).position()]))).toEqual(before.positions);
  // A new document must not receive old frame callbacks or inherit a synthetic element.
  await page.locator('#info-body').getByRole('button', { name: 'Expand', exact: true }).click();
  const result = await page.evaluate(() => {
    const old = window.ravenroot.workspace.active;
    const oldCy = old.cy;
    window.ravenroot.openDocument({ name: 'next.graphml' });
    return { oldId: old.id, newId: window.ravenroot.workspace.active.id, oldCyDestroyed: oldCy.destroyed() };
  });
  await page.waitForFunction(() => !window.cy.scratch('_rrLayoutRunning'));
  await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 350)));
  expect(await page.evaluate(() => ({ id: window.ravenroot.workspace.active.id, nodes: window.cy.nodes().length,
    synthetic: window.cy.nodes().filter(node => Boolean(node.data('rrVisualRole'))).length })))
    .toEqual({ id: result.newId, nodes: 4, synthetic: 0 });
});

test('Monitoring keyboard groups retain canonical simulation identity, viewport and truthful member observations', async ({ page }, testInfo) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await openFixture(page);
  await page.locator('#btn-monitoring').click();
  await page.waitForFunction(() => window.ravenroot.workspace.active.renderer?.elasticMount?.visualGroupProjection?.groups.length === 2);
  await page.evaluate(() => {
    const mount = window.ravenroot.workspace.active.renderer.elasticMount;
    // A settled renderer isolates presentation drift; the unit test also covers an active engine.
    mount.simulation.stop().alpha(0);
    window.__monitorSimulation = mount.simulation;
    window.__monitorCalls = { stop: 0, restart: 0 };
    for (const method of ['stop', 'restart']) {
      const original = mount.simulation[method];
      mount.simulation[method] = function(...args) { window.__monitorCalls[method] += 1; return original.apply(this, args); };
    }
    window.__monitorSamples = [];
    window.__sampleMonitoring = true;
    const sample = () => {
      if (!window.__sampleMonitoring) return;
      const owner = window.ravenroot.workspace.active;
      const renderer = owner.renderer;
      window.__monitorSamples.push({ transform: renderer.zoomGroup.attr('transform'),
        nodes: renderer.nodes.map(node => [node.id, node.x, node.y, node.r]),
        animating: renderer.elasticMount.visualGroupAnimating });
      requestAnimationFrame(sample);
    };
    sample();
  });
  const expand = page.getByRole('button', { name: 'Expand visual group Request processing, 24 members', exact: true });
  await expand.focus(); await expand.press('Enter');
  await page.waitForFunction(() => !window.ravenroot.workspace.active.renderer.elasticMount.visualGroupAnimating);
  const collapse = page.getByRole('button', { name: 'Collapse visual group Request processing, 24 members', exact: true });
  await expect(collapse).toBeFocused();
  await collapse.press('Enter');
  await page.waitForFunction(() => !window.ravenroot.workspace.active.renderer.elasticMount.visualGroupAnimating);
  await expect(expand).toBeFocused();
  const result = await page.evaluate(() => {
    window.__sampleMonitoring = false;
    const mount = window.ravenroot.workspace.active.renderer.elasticMount;
    mount.updateNode('node-1', { runtimeObserved: true, runtimeState: 'failed' });
    return { sameSimulation: mount.simulation === window.__monitorSimulation,
      calls: window.__monitorCalls, samples: window.__monitorSamples,
      nodes: mount.nodes.length, links: mount.links.length,
      observations: document.querySelector('[aria-label="Expand visual group Request processing, 24 members"]')?.getAttribute('data-member-observations') };
  });
  expect(result.sameSimulation).toBe(true); expect(result.calls).toEqual({ stop: 0, restart: 0 });
  expect(result.nodes).toBe(80); expect(result.links).toBe(180);
  expect(result.observations).toContain('"failed":1');
  expect(result.samples.filter(frame => frame.animating).length).toBeGreaterThan(2);
  result.samples.forEach(frame => {
    expect(frame.transform).toBe(result.samples[0].transform);
    expect(frame.nodes).toEqual(result.samples[0].nodes);
  });
  const measurementPath = testInfo.outputPath('monitoring-frames.json');
  await writeFile(measurementPath, JSON.stringify(result));
  await testInfo.attach('monitoring-frames.json', { path: measurementPath, contentType: 'application/json' });
  expect(errors).toEqual([]);
});

test.describe('touch grouping controls', () => {
  test.use({ hasTouch: true, isMobile: true });
  test('a summary and expanded header expose group commands without hover', async ({ page }, testInfo) => {
    await openFixture(page);
    expect(await page.evaluate(() => matchMedia('(hover: none)').matches)).toBe(true);
    const point = await page.evaluate(() => {
      const owner = window.ravenroot.workspace.active;
      const id = owner.visualGroupsRenderer.projection.groups[0].summaryId;
      const p = owner.cy.getElementById(id).renderedPosition();
      const box = owner.cy.container().getBoundingClientRect();
      return { x: box.x+p.x, y: box.y+p.y };
    });
    await page.touchscreen.tap(point.x, point.y);
    await expect(page.locator('#info-body')).toContainText('24 members');
    await page.locator('#info-body').getByRole('button', { name: 'Expand', exact: true }).tap();
    await settle(page);
    await page.locator('#info-body').getByRole('button', { name: 'Collapse', exact: true }).tap();
    await settle(page);
    await page.screenshot({ path: testInfo.outputPath('touch-group-controls.png') });
  });
});

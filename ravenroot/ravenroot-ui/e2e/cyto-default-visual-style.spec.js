import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const graphMl = readFileSync(
  new URL('../test/fixtures/lifecycle-document.graphml', import.meta.url), 'utf8');

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

const activeView = page => page.evaluate(() => {
  const owner = window.ravenroot.activeDocument();
  return owner ? {
    id: owner.id,
    renderMode: owner.renderMode,
    style: owner.visualStyle,
    layout: owner.layoutMode,
    renderer: owner.renderer?.kind ?? null,
    positions: Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()])),
    modelPositions: Object.fromEntries(owner.graph.nodes.map(node => [node.id, { x: node.ox, y: node.oy }])),
    viewport: { zoom: owner.cy.zoom(), pan: owner.cy.pan() },
    history: owner.history.state(),
  } : null;
});

function coordinatePayload(xml) {
  return [...xml.matchAll(/<node id="([^"]+)"[^>]*>([\s\S]*?)<\/node>/g)].map(([, id, body]) => {
    const x = body.match(/<data key="rr-node-layoutx">([^<]+)<\/data>/)?.[1];
    const y = body.match(/<data key="rr-node-layouty">([^<]+)<\/data>/)?.[1];
    return [id, x, y];
  });
}

function coordinateMap(xml) {
  return Object.fromEntries(coordinatePayload(xml).map(([id, x, y]) => [id, {
    x: Number(x), y: Number(y),
  }]));
}

async function openGraphMl(page, name = 'loaded.graphml') {
  await page.locator('#file-inp').setInputFiles({
    name,
    mimeType: 'application/graphml+xml',
    buffer: Buffer.from(graphMl),
  });
  await expect.poll(async () => (await activeView(page))?.style).toBe('cyto');
}

test.beforeEach(async ({ page }) => {
  await stubService(page);
});

test('first renderable paint is Design, with no Monitoring or pre-canvas checked-state flash', async ({ page }) => {
  await page.addInitScript(() => {
    window.__visualStylePaintTrace = [];
    const snapshot = phase => {
      const byId = id => document.getElementById(id)?.classList.contains('active') ?? false;
      window.__visualStylePaintTrace.push({
        phase,
        renderable: Boolean(document.querySelector('.doc-canvas canvas')),
        design: byId('btn-design'),
        monitoring: byId('btn-monitoring'),
      });
    };
    document.addEventListener('DOMContentLoaded', () => {
      snapshot('dom');
      new MutationObserver(() => snapshot('mutation')).observe(document.documentElement, {
        subtree: true, childList: true, attributes: true, attributeFilter: ['class'],
      });
    });
  });

  await page.goto('/');
  await expect(page.locator('#btn-design')).toHaveClass(/active/);
  await expect(page.locator('#btn-monitoring')).not.toHaveClass(/active/);
  await expect.poll(() => activeView(page)).toMatchObject({
    renderMode: 'design', style: 'cyto', renderer: 'cytoscape',
  });

  const trace = await page.evaluate(() => window.__visualStylePaintTrace);
  expect(trace.filter(entry => !entry.renderable).every(
    entry => !entry.design && !entry.monitoring,
  )).toBe(true);
  const firstRenderable = trace.find(entry => entry.renderable);
  expect(firstRenderable).toMatchObject({ design: true, monitoring: false });
  expect(trace.some(entry => entry.monitoring)).toBe(false);

  await page.locator('[data-menu="layout"]').click();
  await expect(page.getByRole('menuitemradio', { name: 'Design' }))
    .toHaveAttribute('aria-checked', 'true');

  const id = await page.evaluate(() => window.ravenroot.workspace.activeId);
  await page.evaluate(documentId => window.ravenroot.closeDocument(documentId), id);
  await expect(page.locator('#btn-design')).not.toHaveClass(/active/);
  await expect(page.locator('#btn-monitoring')).not.toHaveClass(/active/);
  await expect(page.locator('[data-command-id^="style."].active, [data-command-id^="layout."].active'))
    .toHaveCount(0);
});

test('new and opened documents default independently to Design while a Monitoring sibling stays isolated', async ({ page }) => {
  await page.goto('/');
  const first = (await activeView(page)).id;
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => activeView(page)).toMatchObject({ id: first, renderMode: 'monitoring' });

  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'second.graphml' }));
  await expect.poll(() => activeView(page)).toMatchObject({ id: second, renderMode: 'design', style: 'cyto' });

  await openGraphMl(page, 'third.graphml');
  const third = (await activeView(page)).id;
  expect(third).not.toBe(first);
  expect(third).not.toBe(second);

  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  await expect.poll(() => activeView(page)).toMatchObject({ id: first, renderMode: 'monitoring' });
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);
  await expect.poll(() => activeView(page)).toMatchObject({ id: second, style: 'cyto' });
  expect(await page.evaluate(ids => ids.map(id => window.ravenroot.workspace.find(id).renderMode),
    [first, second, third])).toEqual(['monitoring', 'design', 'design']);
});

test('legacy finite, invalid and missing choices normalize to Design on activation', async ({ page }) => {
  await page.goto('/');
  const legacy = (await activeView(page)).id;
  const invalid = await page.evaluate(() => window.ravenroot.openDocument({ name: 'invalid.graphml' }));
  const missing = await page.evaluate(() => window.ravenroot.openDocument({ name: 'missing.graphml' }));

  await page.evaluate(({ legacyId, invalidId, missingId }) => {
    const legacyRecord = window.ravenroot.workspace.find(legacyId);
    delete legacyRecord.visualStyle;
    legacyRecord.layoutMode = 'n8n4';
    const invalidRecord = window.ravenroot.workspace.find(invalidId);
    invalidRecord.visualStyle = 'legacy-garbage';
    invalidRecord.layoutMode = 'cose';
    const missingRecord = window.ravenroot.workspace.find(missingId);
    delete missingRecord.visualStyle;
    missingRecord.layoutMode = 'dagre';
  }, { legacyId: legacy, invalidId: invalid, missingId: missing });

  await page.evaluate(id => window.ravenroot.activateDocument(id), legacy);
  await expect.poll(() => activeView(page)).toMatchObject({
    renderMode: 'design', style: 'cyto', layout: 'cyto',
  });
  await page.evaluate(id => window.ravenroot.activateDocument(id), invalid);
  await expect.poll(() => activeView(page)).toMatchObject({
    renderMode: 'design', style: 'cyto', layout: 'cyto',
  });
  await page.evaluate(id => window.ravenroot.activateDocument(id), missing);
  await expect.poll(() => activeView(page)).toMatchObject({
    renderMode: 'design', style: 'cyto', layout: 'cyto',
  });
});

test('GraphML coordinates remain exact until explicit Design performs its complete visual layout', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(({ xml }) => window.ravenroot.replaceActiveDocumentFromText(xml, 'coordinates.graphml'), {
    xml: graphMl,
  });
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', {
    timeout: 10_000,
  });
  await expect.poll(() => activeView(page)).toMatchObject({
    renderMode: 'design', style: 'cyto', layout: 'cyto', renderer: 'cytoscape',
  });
  const before = await activeView(page);

  expect(await activeView(page)).toMatchObject({
    positions: before.positions,
    modelPositions: before.modelPositions,
    viewport: before.viewport,
    history: before.history,
    style: 'cyto',
    layout: 'cyto',
  });

  const downloadPromise = page.waitForEvent('download');
  await page.locator('#btn-export').click();
  const download = await downloadPromise;
  const exported = readFileSync(await download.path(), 'utf8');
  expect(coordinatePayload(exported)).toEqual(coordinatePayload(graphMl));

  await page.locator('#btn-design').click();
  await expect(page.locator('.doc-pane--active')).toHaveAttribute('aria-busy', 'true');
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', {
    timeout: 10_000,
  });
  await expect.poll(async () => (await activeView(page)).positions).not.toEqual(before.positions);
  expect(await activeView(page)).toMatchObject({
    modelPositions: before.modelPositions,
    history: before.history,
    style: 'cyto',
    layout: 'cyto',
  });
});

test('replace retires Monitoring and restores the incoming graph with Design rendering', async ({ page }) => {
  await page.goto('/');
  const target = (await activeView(page)).id;
  const historyBefore = (await activeView(page)).history;
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => activeView(page)).toMatchObject({
    id: target, renderMode: 'monitoring', style: 'cyto', layout: 'elastic', renderer: 'elastic',
  });
  const retired = await page.evaluate(id => {
    const owner = window.ravenroot.workspace.find(id);
    window.__replaceRetiredCy = owner.cy;
    window.__replaceRetiredRenderer = owner.renderer;
    return {
      layoutGeneration: owner.layoutSessionToken.generation,
      rendererGeneration: owner.renderer.token.generation,
    };
  }, target);

  await page.evaluate(({ xml }) => window.ravenroot.replaceActiveDocumentFromText(xml, 'replacement.graphml'), {
    xml: graphMl,
  });
  await expect.poll(() => activeView(page)).toMatchObject({
    id: target, renderMode: 'design', style: 'cyto', layout: 'cyto', renderer: 'cytoscape',
  });
  const incoming = coordinateMap(graphMl);
  const settled = await activeView(page);
  expect(settled.positions).toEqual(incoming);
  expect(settled.modelPositions).toEqual(incoming);
  expect(settled.history).toEqual(historyBefore);
  expect(await page.evaluate(([id, previous]) => {
    const owner = window.ravenroot.workspace.find(id);
    return {
      hosts: owner.pane.querySelectorAll('.doc-elastic-host').length,
      oldCyDestroyed: window.__replaceRetiredCy.destroyed(),
      oldRendererRetired: window.__replaceRetiredRenderer.token.generation
        === previous.rendererGeneration && owner.renderer.token.generation !== previous.rendererGeneration,
      oldLayoutRetired: owner.layoutSessionToken?.generation !== previous.layoutGeneration,
    };
  }, [target, retired])).toEqual({
    hosts: 0,
    oldCyDestroyed: true,
    oldRendererRetired: true,
    oldLayoutRetired: true,
  });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() =>
    requestAnimationFrame(() => requestAnimationFrame(resolve)))));
  expect(await activeView(page)).toMatchObject({
    renderMode: 'design', style: 'cyto', layout: 'cyto', renderer: 'cytoscape',
    positions: incoming, modelPositions: incoming, history: historyBefore,
  });
});

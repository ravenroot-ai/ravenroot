import { expect, test } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const projection = {
  viewerContractVersion: '1.0', graphId: 'visual-parity', graphVersionId: 'graph-v1',
  canonicalDigest: 'graph-v1',
  nodes: [
    { id: 'start', kind: 'START', label: 'Start', visualType: 'start',
      layout: { x: 100, y: 180, width: 84, height: 84 } },
    { id: 'worker', kind: 'BEHAVIOR', label: 'Worker', visualType: 'actor', bypassed: true,
      layout: { x: 330, y: 180, width: 120, height: 52 } },
    { id: 'agent', kind: 'BEHAVIOR', label: 'Agent', visualType: 'agent',
      layout: { x: 445, y: 330, width: 72, height: 72 } },
    { id: 'error', kind: 'ERROR', label: 'Failure', visualType: 'error',
      layout: { x: 560, y: 180, width: 72, height: 72 } },
    { id: 'custom', kind: 'BEHAVIOR', label: 'Quartz', visualType: 'quartz-worker',
      layout: { x: 680, y: 330, width: 100, height: 52 } },
  ],
  edges: [
    { id: 'route-continue', source: 'start', target: 'worker', label: 'continue', visualType: 'continue' },
    { id: 'route-agent', source: 'worker', target: 'agent', label: 'delegates', visualType: 'default' },
    { id: 'route-failure', source: 'worker', target: 'error', label: 'failed', visualType: 'failed' },
    { id: 'route-custom', source: 'agent', target: 'custom', label: 'continues', visualType: 'continue' },
  ],
};

const envelope = {
  viewerSourceVersion: '1',
  source: { kind: 'deployment', deploymentId: 'visual-parity', graphVersion: 'graph-v1',
    incarnationId: 'inc-visual' },
  lifecycle: 'READY', canonicalDigest: 'graph-v1', projection,
};
const envelopeV2 = { ...envelope, viewerSourceVersion: '2' };

// Projection bounds and native authoring normalization legitimately differ, especially for curved
// routes. Semantic style equality above is strict; this budget catches gross paint drift without
// forcing the embedded viewer to rewrite established native geometry.
const MAX_PIXEL_DIFFERENCE_RATIO = 0.15;

async function stubViewerService(page) {
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json', body: '[]',
  }));
  await page.route('**/v1/events?include=diagnostics', route => route.fulfill({
    status: 200, contentType: 'text/event-stream', body: '',
  }));
  await page.route('**/v1/deployments**', route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname.endsWith('/view')) return route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify(envelope),
    });
    if (url.pathname.endsWith('/events')) return route.fulfill({
      status: 200, contentType: 'text/event-stream', body:
        'event: source-gap\ndata: {"reason":"REPLAY_WINDOW_EXCEEDED"}\n\n',
    });
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ deployments: [{ deploymentId: 'visual-parity', state: 'READY',
        tenantId: 'local', sourceCount: 0, graphVersion: 'graph-v1', scope: 'LOCAL_PROCESS',
        diagnostic: null, continuity: 'PROCESS_LOCAL', deploymentRevision: null,
        desiredState: null, observedState: null, recoveryFailure: null }] }),
    });
  });
}

async function openNativeViewer(page) {
  await page.goto('/');
  await page.locator('#access-token').fill('visual-token');
  await page.locator('#btn-authenticate').click();
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Deployments…' }).click();
  await page.locator('[data-deployment-view="visual-parity"]').click();
  await expect(page.locator('.doc-pane--active')).toHaveAttribute('data-deployment-continuity', 'GAP');
}

async function mountEmbedTwin(page, theme, renderer) {
  await page.evaluate(async ({ source, selectedTheme, selectedRenderer }) => {
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = '/embed-viewer.css';
    await new Promise((resolve, reject) => {
      link.addEventListener('load', resolve, { once: true });
      link.addEventListener('error', reject, { once: true });
      document.head.append(link);
    });
    const native = document.querySelector('.doc-pane--active .doc-elastic-host.active')
      || document.querySelector('.doc-pane--active .doc-canvas');
    const rect = native.getBoundingClientRect();
    const host = document.createElement('section');
    host.id = 'visual-parity-host';
    host.innerHTML = `<span class="embed-focus-sentinel" tabindex="0"></span>
      <main id="ravenroot-embed-viewer" class="embed-viewer" data-viewer-state="loading">
        <header hidden><p data-viewer-metadata></p><select data-viewer-mode>
          <option value="cyto">Cyto</option><option value="n8n">N8N</option>
          <option value="elastic">Elastic</option></select>
          <button data-viewer-command="fit">Fit</button></header>
        <div class="embed-viewer-canvas" data-viewer-canvas tabindex="0"></div>
        <canvas data-viewer-minimap tabindex="0"></canvas>
        <ol data-viewer-alternative></ol><p data-viewer-status></p>
      </main><span class="embed-focus-sentinel" tabindex="0"></span>`;
    Object.assign(host.style, { position: 'fixed', zIndex: '99999', left: `${rect.left}px`,
      top: `${rect.top}px`, width: `${rect.width}px`, height: `${rect.height}px`, background: 'transparent' });
    const root = host.querySelector('#ravenroot-embed-viewer');
    Object.assign(root.style, { width: '100%', height: '100%', display: 'block', padding: '0' });
    const canvas = host.querySelector('[data-viewer-canvas]');
    Object.assign(canvas.style, { width: '100%', height: '100%', position: 'absolute', inset: '0' });
    host.querySelector('[data-viewer-minimap]').style.display = 'none';
    host.querySelector('[data-viewer-alternative]').style.display = 'none';
    host.querySelector('[data-viewer-status]').style.display = 'none';
    document.body.append(host);
    const { createEmbedViewer } = await import('/embed-viewer.js');
    window.__visualParityViewer = createEmbedViewer(root, { theme: selectedTheme });
    await window.__visualParityViewer.mount(source);
    if (selectedRenderer !== 'cyto') {
      const mode = root.querySelector('[data-viewer-mode]');
      mode.value = selectedRenderer;
      mode.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }, { source: envelope, selectedTheme: theme, selectedRenderer: renderer });
  await expect(page.locator('#ravenroot-embed-viewer')).toHaveAttribute('data-viewer-state', 'ready');
  await expect(page.locator('#ravenroot-embed-viewer')).toHaveAttribute('data-viewer-renderer', renderer);
}

async function mountV2EmbedTwin(page, theme, mode) {
  await page.evaluate(async ({ source, selectedTheme, selectedMode }) => {
    const link = document.createElement('link'); link.rel = 'stylesheet'; link.href = '/embed-viewer.css';
    document.head.append(link);
    await new Promise((resolve, reject) => { link.addEventListener('load', resolve, { once: true });
      link.addEventListener('error', reject, { once: true }); });
    const native = document.querySelector('.doc-pane--active .doc-elastic-host.active')
      || document.querySelector('.doc-pane--active .doc-canvas');
    const rect = native.getBoundingClientRect();
    const host = document.createElement('section'); host.id = 'visual-parity-host';
    host.innerHTML = `<span class="embed-focus-sentinel" tabindex="0"></span>
      <main id="ravenroot-embed-viewer" class="embed-viewer"><header hidden>
        <p data-viewer-metadata></p><select data-viewer-mode><option value="design">Design</option>
        <option value="monitoring">Monitoring</option></select>
        <button data-viewer-command="render">Render</button><select data-viewer-run></select>
        <span data-viewer-run-empty></span></header>
        <div data-viewer-canvas tabindex="0"></div><canvas data-viewer-minimap tabindex="0"></canvas>
        <ol data-viewer-alternative></ol><p data-viewer-status></p></main>
        <span class="embed-focus-sentinel" tabindex="0"></span>`;
    Object.assign(host.style, { position: 'fixed', zIndex: '99999', left: `${rect.left}px`,
      top: `${rect.top}px`, width: `${rect.width}px`, height: `${rect.height}px` });
    const root = host.querySelector('main'); Object.assign(root.style, { width: '100%', height: '100%' });
    Object.assign(root.querySelector('[data-viewer-canvas]').style,
      { width: '100%', height: '100%', position: 'absolute', inset: '0' });
    root.querySelector('[data-viewer-minimap]').style.display = 'none';
    root.querySelector('[data-viewer-alternative]').style.display = 'none';
    root.querySelector('[data-viewer-status]').style.display = 'none';
    document.body.append(host);
    const { createEmbedViewer } = await import('/embed-viewer.js');
    window.__visualParityViewer = createEmbedViewer(root, { theme: selectedTheme });
    await window.__visualParityViewer.mount(source);
    if (selectedMode === 'monitoring') {
      const select = root.querySelector('[data-viewer-mode]'); select.value = selectedMode;
      select.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }, { source: envelopeV2, selectedTheme: theme, selectedMode: mode });
  await expect(page.locator('#ravenroot-embed-viewer')).toHaveAttribute('data-viewer-renderer', mode);
}

const nativeCardSnapshot = (page, renderer) => page.evaluate(selectedRenderer => {
  const instance = window.ravenroot.activeDocument().cy;
  instance.fit(60);
  const nodeStyle = ['shape', 'width', 'height', 'background-color', 'background-image',
    'background-width', 'background-height', 'background-fit', 'background-clip', 'border-color',
    'border-width', 'border-style', 'font-size', 'font-weight', 'text-valign',
    'text-halign', 'text-margin-x', 'text-margin-y', 'padding'];
  const edgeStyle = ['width', 'line-color', 'line-style', 'target-arrow-shape',
    'target-arrow-color', 'curve-style'];
  return {
    renderer: selectedRenderer,
    nodes: instance.nodes().map(node => ({
      id: node.id(), label: node.style('label'), icon: node.style('background-image'),
      type: node.data('nodeType'),
      position: { x: node.position('x'), y: node.position('y') },
      style: Object.fromEntries(nodeStyle.map(name => [name, node.style(name)])),
    })),
    edges: instance.edges().map(edge => ({
      id: edge.id(), source: edge.source().id(), target: edge.target().id(),
      label: edge.data('label'), type: edge.data('edgeType'),
      style: Object.fromEntries(edgeStyle.map(name => [name, edge.style(name)])),
    })),
  };
}, renderer);

const nativeElasticSnapshot = page => page.evaluate(source => {
  const owner = window.ravenroot.activeDocument();
  const renderer = owner.renderer;
  const authored = new Map(source.nodes.map(node => [node.id, node.layout]));
  for (const node of renderer.nodes) {
    const layout = authored.get(node.id);
    if (layout) Object.assign(node, { x: layout.x, y: layout.y, vx: 0, vy: 0, fx: null, fy: null });
  }
  renderer.simulation.alpha(1).stop().tick(40);
  renderer.paint();
  const root = document.querySelector('.doc-pane--active .doc-elastic-host.active');
  return {
    renderer: 'elastic',
    nodes: [...root.querySelectorAll('.d3-nodes circle')].map(node => ({
      fill: node.getAttribute('fill'), stroke: node.getAttribute('stroke'), r: node.getAttribute('r'),
    })),
    edges: [...root.querySelectorAll('.d3-edges path')].map(edge => ({
      stroke: edge.getAttribute('stroke'), width: edge.getAttribute('stroke-width'),
    })),
  };
}, projection);

async function rendererPng(page, surface, renderer) {
  if (renderer === 'elastic') {
    return page.locator(surface === 'native'
      ? '.doc-pane--active .doc-elastic-host.active svg.d3-elastic'
      : '#ravenroot-embed-viewer svg.embed-viewer-elastic').screenshot();
  }
  return page.locator(surface === 'native'
    ? '.doc-pane--active .doc-canvas canvas[data-id="layer2-node"]'
    : '#ravenroot-embed-viewer [data-viewer-canvas] canvas[data-id="layer2-node"]').screenshot();
}

async function comparePng(page, nativePng, embeddedPng) {
  return page.evaluate(async ({ nativeBase64, embeddedBase64 }) => {
    const decode = base64 => createImageBitmap(new Blob([
      Uint8Array.from(atob(base64), character => character.charCodeAt(0)),
    ], { type: 'image/png' }));
    const [nativeImage, embeddedImage] = await Promise.all([decode(nativeBase64), decode(embeddedBase64)]);
    if (nativeImage.width !== embeddedImage.width || nativeImage.height !== embeddedImage.height) {
      return { ratio: 1, different: nativeImage.width * nativeImage.height,
        total: nativeImage.width * nativeImage.height, diff: '' };
    }
    const width = nativeImage.width;
    const height = nativeImage.height;
    const canvas = document.createElement('canvas');
    canvas.width = width; canvas.height = height;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    context.drawImage(nativeImage, 0, 0);
    const nativePixels = context.getImageData(0, 0, width, height);
    context.clearRect(0, 0, width, height);
    context.drawImage(embeddedImage, 0, 0);
    const embeddedPixels = context.getImageData(0, 0, width, height);
    const diff = context.createImageData(width, height);
    let different = 0;
    for (let offset = 0; offset < nativePixels.data.length; offset += 4) {
      const delta = Math.max(...[0, 1, 2, 3].map(channel =>
        Math.abs(nativePixels.data[offset + channel] - embeddedPixels.data[offset + channel])));
      if (delta > 24) {
        different += 1;
        diff.data.set([255, 0, 96, 255], offset);
      } else {
        const gray = Math.round((nativePixels.data[offset] + nativePixels.data[offset + 1]
          + nativePixels.data[offset + 2]) / 3);
        diff.data.set([gray, gray, gray, 70], offset);
      }
    }
    context.putImageData(diff, 0, 0);
    return { ratio: different / (width * height), different, total: width * height,
      diff: canvas.toDataURL('image/png').split(',')[1] };
  }, { nativeBase64: nativePng.toString('base64'), embeddedBase64: embeddedPng.toString('base64') });
}

async function persistEvidence(testInfo, theme, renderer, nativePng, embeddedPng, comparison) {
  const files = [
    [`embedded-viewer-${theme}-native-${renderer}.png`, nativePng],
    [`embedded-viewer-${theme}-embed-${renderer}.png`, embeddedPng],
    [`embedded-viewer-${theme}-${renderer}-diff.png`, Buffer.from(comparison.diff, 'base64')],
  ];
  for (const [name, body] of files) {
    await testInfo.attach(name, { body, contentType: 'image/png' });
    if (process.env.RR_VISUAL_EVIDENCE_DIR) {
      await mkdir(process.env.RR_VISUAL_EVIDENCE_DIR, { recursive: true });
      await writeFile(join(process.env.RR_VISUAL_EVIDENCE_DIR,
        name.replace('embedded-viewer-', '')), body);
    }
  }
}

function semanticPresentation(snapshot) {
  return {
    renderer: snapshot.renderer,
    nodes: snapshot.nodes.map(node => ({
      id: node.id,
      // Legacy Standard and legacy Cyto intentionally predate the deterministic card initial.
      label: node.type === 'quartz-worker' ? node.label.replace(/^\S+\s/u, '') : node.label,
      type: node.type, position: node.position,
      style: Object.fromEntries(['background-color', 'border-color', 'border-style']
        .map(name => [name, node.style[name]])),
    })),
    edges: snapshot.edges.map(edge => ({
      id: edge.id, source: edge.source, target: edge.target, label: edge.label, type: edge.type,
      style: Object.fromEntries(['line-color', 'line-style', 'target-arrow-color', 'target-arrow-shape']
        .map(name => [name, edge.style[name]])),
    })),
  };
}

function designPresentation(snapshot) {
  const styleNames = [
    'shape', 'width', 'height', 'background-color', 'background-image',
    'background-width', 'background-height', 'background-fit', 'background-clip',
    'border-color', 'border-width', 'border-style', 'font-size', 'font-weight',
    'text-valign', 'text-halign', 'text-margin-x', 'text-margin-y', 'padding',
  ];
  return {
    nodes: snapshot.nodes.map(node => ({
      id: node.id, label: node.label, type: node.type,
      style: Object.fromEntries(styleNames.map(name => [name, node.style[name]])),
    })),
    edges: semanticPresentation(snapshot).edges,
  };
}

for (const theme of ['dark', 'light']) {
  test(`native and embedded Cyto, N8N, and Elastic stay within visual parity budget in ${theme}`,
    async ({ page }, testInfo) => {
    await stubViewerService(page);
    await openNativeViewer(page);
    await page.evaluate(selected => window.ravenroot.setApplicationTheme(selected), theme);
    for (const renderer of ['cyto', 'n8n', 'elastic']) {
      // This historical matrix verifies the legacy public renderer contracts. v2 below deliberately
      // uses the native default Design presentation instead of forcing this legacy standard style.
      if (renderer === 'cyto') await page.evaluate(() => window.ravenroot.setVisualStyle('standard'));
      if (renderer === 'n8n') await page.evaluate(() => window.ravenroot.setVisualStyle('n8n'));
      if (renderer === 'elastic') await page.locator('#btn-monitoring').click();
      if (renderer === 'elastic') {
        await expect(page.locator('.doc-pane--active .doc-elastic-host.active')).toBeVisible();
      }
      const native = renderer === 'elastic'
        ? await nativeElasticSnapshot(page) : await nativeCardSnapshot(page, renderer);
      const nativePng = await rendererPng(page, 'native', renderer);
      await mountEmbedTwin(page, theme, renderer);
      const embedded = await page.evaluate(() => window.__visualParityViewer.presentationSnapshot());
      const embeddedPng = await rendererPng(page, 'embed', renderer);
      // Renderer-owned geometry differs legitimately: the native canvas normalizes imported node
      // bounds and owns authored route control points, while the embed keeps projection bounds and
      // computes safe routes. Identity, meaning and state carriers must remain equal.
      if (renderer === 'elastic') expect(embedded).toEqual(native);
      else expect(semanticPresentation(embedded)).toEqual(semanticPresentation(native));
      const comparison = await comparePng(page, nativePng, embeddedPng);
      console.info(`[visual-parity] ${theme}/${renderer} ${comparison.different}/${comparison.total}`
        + ` pixels differ (${comparison.ratio.toFixed(6)})`);
      expect(comparison.ratio, `${renderer} ${theme} pixel-difference ratio`)
        .toBeLessThanOrEqual(MAX_PIXEL_DIFFERENCE_RATIO);
      await persistEvidence(testInfo, theme, renderer, nativePng, embeddedPng, comparison);
      await page.evaluate(() => {
        window.__visualParityViewer.destroy();
        document.getElementById('visual-parity-host').remove();
      });
    }
  });
}

for (const theme of ['dark', 'light']) {
  test(`v2 native and embedded Design and Monitoring parity evidence in ${theme}`,
    async ({ page }, testInfo) => {
      await stubViewerService(page); await openNativeViewer(page);
      await page.evaluate(selected => window.ravenroot.setApplicationTheme(selected), theme);
      for (const [mode, renderer] of [['design', 'cyto'], ['monitoring', 'elastic']]) {
        if (renderer === 'elastic') {
          await page.locator('#btn-monitoring').click();
          await expect(page.locator('.doc-pane--active .doc-elastic-host.active')).toBeVisible();
        }
        const native = renderer === 'elastic'
          ? await nativeElasticSnapshot(page) : await nativeCardSnapshot(page, renderer);
        native.renderer = mode;
        const nativePng = await rendererPng(page, 'native', renderer);
        await mountV2EmbedTwin(page, theme, mode);
        const embedded = await page.evaluate(() => window.__visualParityViewer.presentationSnapshot());
        embedded.renderer = mode;
        const embeddedPng = await rendererPng(page, 'embed', renderer);
        if (mode === 'monitoring') expect(embedded).toEqual(native);
        else expect(designPresentation(embedded)).toEqual(designPresentation(native));
        if (mode === 'design') {
          const unknown = embedded.nodes.find(node => node.id === 'custom');
          expect(unknown.style).toMatchObject({
            width: '80px', height: '80px', 'background-width': '100%',
            'background-height': '100%', 'background-fit': 'none',
            'font-size': '20px', 'text-valign': 'bottom', 'text-halign': 'center',
          });
          expect(decodeURIComponent(unknown.icon)).toContain('>Q</text>');
        }
        const comparison = await comparePng(page, nativePng, embeddedPng);
        expect(comparison.ratio).toBeLessThanOrEqual(MAX_PIXEL_DIFFERENCE_RATIO);
        await persistEvidence(testInfo, `v2-${theme}`, mode, nativePng, embeddedPng, comparison);
        await page.evaluate(() => { window.__visualParityViewer.destroy();
          document.getElementById('visual-parity-host').remove(); });
      }
    });
}

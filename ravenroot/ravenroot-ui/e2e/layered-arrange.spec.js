import { readFileSync } from 'node:fs';

import { expect, test } from '@playwright/test';

import {
  backEdgesInsideBand, bodyOf, countCrossings, edgesThroughBoxes, geometryColumns, labelBoxOf, labelOverlaps,
  layerDiscreteness, sharedRuns,
} from '../src/layered-metrics.js';

// The acceptance test bench of the layered arrangements: one coordinator delegating to twelve
// peers that fan back into one join, behind an intake chain and ahead of a review tail, with
// escalation, audit, error and recovery nodes reachable from almost everywhere.
const testBench = readFileSync(new URL('../test/fixtures/layout-test-bench.graphml', import.meta.url), 'utf8');

const ESTABLISHED = ['Arrange — Hierarchical', 'Arrange — Flow', 'Arrange — Organic'];
const LAYERED = ['Arrange — Hierarchical (new)', 'Arrange — Flow (new)'];
const COUNTERPART = new Map([
  ['Arrange — Hierarchical (new)', 'Arrange — Hierarchical'],
  ['Arrange — Flow (new)', 'Arrange — Flow'],
]);
const NODE_SIZE = 80;
const PEERS = ['product-architecture', 'core-runtime', 'integrations', 'frontend', 'graph-rendering', 'qa',
  'platform', 'ai-agents', 'open-source', 'legal', 'security', 'docs'];

async function openLayoutMenu(page) {
  await page.locator('#menu-layout').click();
  await expect(page.locator('#application-menu')).toBeVisible();
}

async function arrange(page, label) {
  await openLayoutMenu(page);
  const started = Date.now();
  await page.getByRole('menuitem', { name: label, exact: true }).click();
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', { timeout: 15_000 });
  const wallMs = Date.now() - started;
  // Edge geometry is projected during the next paint; give the renderer two frames.
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  return wallMs;
}

const positions = page => page.evaluate(() => Object.fromEntries(
  window.cy.nodes().map(node => [node.id(), { x: node.position('x'), y: node.position('y') }]),
));

// Nodes as body and label boxes, edges as the polylines the canvas actually paints: segment
// points as they are, Bézier runs sampled. The same sampler serves every arrangement, so the
// numbers compare like with like.
const geometry = page => page.evaluate(() => {
  const nodes = window.cy.nodes().map(node => {
    const position = node.position();
    const width = node.outerWidth();
    const height = node.outerHeight();
    const box = node.boundingBox({ includeLabels: true, includeOverlays: false, includeEdges: false });
    const body = { left: position.x - width / 2, right: position.x + width / 2, top: position.y - height / 2, bottom: position.y + height / 2 };
    const label = box.y2 > body.bottom + 0.5 ? { left: box.x1, right: box.x2, top: body.bottom, bottom: box.y2 } : null;
    return { id: node.id(), x: position.x, y: position.y, kind: node.data('kind'), body, label };
  });
  const edges = window.cy.edges().map(edge => {
    const scratch = edge._private.rscratch || {};
    const raw = scratch.allpts || [];
    const at = index => ({ x: raw[index], y: raw[index + 1] });
    const points = [];
    if (['bezier', 'multibezier', 'self', 'compound'].includes(scratch.edgeType)) {
      let from = at(0);
      points.push(from);
      for (let index = 2; index + 3 < raw.length; index += 4) {
        const control = at(index);
        const to = at(index + 2);
        for (let step = 1; step <= 8; step++) {
          const t = step / 8;
          const mt = 1 - t;
          points.push({
            x: mt * mt * from.x + 2 * mt * t * control.x + t * t * to.x,
            y: mt * mt * from.y + 2 * mt * t * control.y + t * t * to.y,
          });
        }
        from = to;
      }
    } else {
      for (let index = 0; index + 1 < raw.length; index += 2) points.push(at(index));
    }
    return {
      id: edge.id(), source: edge.source().id(), target: edge.target().id(), edgeType: scratch.edgeType, points,
      curveStyle: edge.style('curve-style'),
      sourceEndpoint: edge.style('source-endpoint'), targetEndpoint: edge.style('target-endpoint'),
    };
  });
  const drawing = window.cy.scratch('_rrLayeredDrawing');
  return { nodes, edges, elapsedMs: drawing ? drawing.elapsedMs : null };
});

function judge(sample) {
  const polylines = sample.edges.filter(edge => edge.source !== edge.target && edge.points.length > 1);
  const { columnOf } = geometryColumns(sample.nodes);
  // Layer discreteness is judged against a layering computed from the graph alone, so a scatter
  // of nodes fails it instead of clustering into as many columns as it has nodes.
  const layering = layerDiscreteness(sample.nodes, sample.edges);
  // Piled edges are judged at the stroke width; the fan a node's adjacent ports form is the one
  // exception the criteria allow, reported apart so it stays visible.
  const runs = sharedRuns(polylines, { minLength: NODE_SIZE, tolerance: 3, ignoreSharedEndpoints: true });
  const errorNode = sample.nodes.find(node => node.kind === 'ERROR');
  const intoError = polylines.filter(edge => edge.target === errorNode.id);
  // An explicit endpoint ("-40px 12px", relative to the node centre) names its side exactly; the
  // established arrangements use symbolic endpoints, so their side is the nearest border to the
  // painted line end, which stops short of the border by the arrowhead.
  const sideOf = edge => {
    const explicit = /^(-?[\d.]+)px\s+(-?[\d.]+)px$/.exec(edge.targetEndpoint || '');
    const { body } = errorNode;
    if (explicit) {
      const [ox, oy] = [Number(explicit[1]), Number(explicit[2])];
      const halfWidth = (body.right - body.left) / 2;
      const halfHeight = (body.bottom - body.top) / 2;
      if (Math.abs(Math.abs(ox) - halfWidth) < 0.5) return ox < 0 ? 'west' : 'east';
      if (Math.abs(Math.abs(oy) - halfHeight) < 0.5) return oy < 0 ? 'north' : 'south';
    }
    const point = edge.points[edge.points.length - 1];
    const candidates = [['west', Math.abs(point.x - body.left)], ['east', Math.abs(point.x - body.right)],
      ['north', Math.abs(point.y - body.top)], ['south', Math.abs(point.y - body.bottom)]];
    return candidates.sort((a, b) => a[1] - b[1])[0][0];
  };
  return {
    crossings: countCrossings(polylines),
    labelOverlaps: labelOverlaps(sample.nodes).length,
    throughBodies: edgesThroughBoxes(polylines, sample.nodes, bodyOf).length,
    throughLabels: edgesThroughBoxes(polylines, sample.nodes, labelBoxOf).length,
    piles: runs.length,
    fans: runs.fans.length,
    layerCount: layering.layerCount,
    columns: layering.columns.length,
    splitLayers: layering.splitLayers.length,
    nonMonotone: layering.nonMonotone.length,
    layered: layering.ok,
    peerColumns: new Set(PEERS.map(id => layering.columnOf.get(id))).size,
    backInsideBand: backEdgesInsideBand(polylines, sample.nodes, columnOf).length,
    failureSides: new Set(intoError.map(sideOf)).size,
    failurePorts: new Set(intoError.map(edge => edge.targetEndpoint)).size,
    failureEdges: intoError.length,
    elapsedMs: sample.elapsedMs,
  };
}

function slug(label) {
  return label.toLowerCase().replace(/[^a-z]+/g, '-').replace(/^-|-$/g, '');
}

async function screenshots(page, label) {
  const name = slug(label);
  // The editor floors its automatic fit at a readable zoom; the evidence must show the whole
  // drawing, so fit without that floor for the picture and restore the editor's own fit after.
  await page.evaluate(() => { window.cy.fit(undefined, 40); });
  await page.screenshot({ path: test.info().outputPath(`${name}-fit.png`), fullPage: true });
  await page.evaluate(() => { window.cy.zoom(1); window.cy.center(); });
  await page.screenshot({ path: test.info().outputPath(`${name}-zoom-100.png`), fullPage: true });
  await page.evaluate(() => { window.cy.fit(undefined, 65); });
}

test.describe('Layered arrangements on the test bench', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.evaluate(xml => window.ravenroot.replaceActiveDocumentFromText(xml, 'layout-test-bench.graphml'), testBench);
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', { timeout: 15_000 });
  });

  test('judges every arrangement by the same geometry and holds the layered ones to the criteria', async ({ page }) => {
    // The metrics are viewport-independent; the screenshots are evidence and should show the
    // whole drawing rather than a window onto it.
    await page.setViewportSize({ width: 2560, height: 1500 });
    const before = await geometry(page);
    await screenshots(page, 'design-default');
    const verdicts = new Map([['Design default (Cyto)', judge(before)]]);
    const walls = new Map();
    for (const label of [...ESTABLISHED, ...LAYERED]) {
      walls.set(label, await arrange(page, label));
      verdicts.set(label, judge(await geometry(page)));
      await screenshots(page, label);
    }
    const rows = [...verdicts].map(([label, verdict]) => ({ arrangement: label, wallMs: walls.get(label) ?? '', ...verdict }));
    console.log(`\n${JSON.stringify(rows, null, 2)}\n`);
    await test.info().attach('arrangement-metrics.json', { body: JSON.stringify(rows, null, 2), contentType: 'application/json' });

    for (const label of LAYERED) {
      const verdict = verdicts.get(label);
      const counterpart = verdicts.get(COUNTERPART.get(label));
      expect.soft(verdict.labelOverlaps, `${label}: label overlaps`).toBe(0);
      expect.soft(verdict.throughBodies, `${label}: edges through node bodies`).toBe(0);
      expect.soft(verdict.throughLabels, `${label}: edges through labels`).toBe(0);
      expect.soft(verdict.piles, `${label}: unrelated edges drawn on top of each other`).toBe(0);
      expect.soft(verdict.layered, `${label}: one column per structural layer, edges forward`).toBe(true);
      expect.soft(verdict.columns, `${label}: columns vs structural layers (${verdict.layerCount})`).toBe(verdict.layerCount);
      expect.soft(verdict.peerColumns, `${label}: the twelve peers on one column`).toBe(1);
      expect.soft(verdict.backInsideBand, `${label}: back edges inside the band`).toBe(0);
      expect.soft(verdict.crossings, `${label}: crossings vs ${COUNTERPART.get(label)} (${counterpart.crossings})`)
        .toBeLessThanOrEqual(counterpart.crossings / 2);
      expect.soft(verdict.failureSides, `${label}: sides used by failure edges`).toBeLessThanOrEqual(2);
      expect.soft(verdict.failurePorts, `${label}: distinct failure ports`).toBe(verdict.failureEdges);
      expect.soft(verdict.elapsedMs, `${label}: engine time`).toBeLessThan(2000);
      expect.soft(walls.get(label), `${label}: wall time including animation`).toBeLessThan(5000);
    }
  });

  test('holds a layer check that fails on Organic, so the check discriminates', async ({ page }) => {
    await arrange(page, 'Arrange — Organic');
    const verdict = judge(await geometry(page));
    expect(verdict.layered).toBe(false);
    expect(verdict.columns).toBeGreaterThan(verdict.layerCount);
    expect(verdict.nonMonotone).toBeGreaterThan(0);
    expect(verdict.peerColumns).toBeGreaterThan(1);
    await arrange(page, 'Arrange — Hierarchical (new)');
    expect(judge(await geometry(page)).layered).toBe(true);
  });

  test('is deterministic, records one undo entry, keeps edge identity and leaves Keep positions alone', async ({ page }) => {
    const edgeIds = await page.evaluate(() => window.cy.edges().map(edge => edge.id()).sort());
    const initial = await positions(page);
    for (const label of LAYERED) {
      await arrange(page, label);
      const first = await positions(page);
      expect(first).not.toEqual(initial);
      expect(await page.evaluate(() => window.cy.edges().map(edge => edge.id()).sort())).toEqual(edgeIds);
      await expect.poll(() => page.evaluate(() => ({
        depth: window.ravenroot.activeDocument().history.depth(),
        label: window.ravenroot.activeDocument().history.undoLabel(),
      }))).toEqual({ depth: 1, label });
      await arrange(page, 'Arrange — Organic');
      await arrange(page, label);
      expect(await positions(page)).toEqual(first);
      await arrange(page, 'Keep positions');
      expect(await positions(page)).toEqual(first);
      await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().history.depth())).toBe(3);
      await page.locator('#btn-undo').click();
      await page.locator('#btn-undo').click();
      await page.locator('#btn-undo').click();
      await expect.poll(() => positions(page)).toEqual(initial);
      await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().history.depth())).toBe(0);
    }
  });

  test('is Design-only and sits after the established arrangements in the Layout menu', async ({ page }) => {
    await openLayoutMenu(page);
    const labels = await page.locator('#application-menu .application-menu-item span:first-child').allTextContents();
    expect(labels.slice(-6)).toEqual([...ESTABLISHED, 'Keep positions', ...LAYERED]);
    await page.keyboard.press('Escape');
    await openLayoutMenu(page);
    await page.getByRole('menuitemradio', { name: 'Monitoring' }).click();
    await openLayoutMenu(page);
    for (const label of LAYERED) {
      await expect(page.getByRole('menuitem', { name: label, exact: true })).toHaveAttribute('aria-disabled', 'true');
    }
  });
});

test.describe('Layered arrangements on parallel edges', () => {
  const parallel = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
    <key id="name" for="node" attr.name="name" attr.type="string"/>
    <key id="kind" for="node" attr.name="kind" attr.type="string"/>
    <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
    <graph id="parallel" edgedefault="directed">
      <node id="a"><data key="name">Start</data><data key="kind">START</data></node>
      <node id="b"><data key="name">Middle</data><data key="kind">PASSTHROUGH</data></node>
      <node id="c"><data key="name">End</data><data key="kind">END</data></node>
      <node id="loop"><data key="name">Loop</data><data key="kind">PASSTHROUGH</data></node>
      <edge id="ab" source="a" target="b"><data key="outcome">continue</data></edge>
      <edge id="p0" source="b" target="c"><data key="outcome">Approved</data></edge>
      <edge id="p1" source="b" target="c"><data key="outcome">Retry</data></edge>
      <edge id="p2" source="b" target="c"><data key="outcome">Continue</data></edge>
      <edge id="bl" source="b" target="loop"><data key="outcome">continue</data></edge>
      <edge id="ll" source="loop" target="loop"><data key="outcome">again</data></edge>
    </graph></graphml>`;

  test('gives every parallel edge its own east and west port and keeps the self-loop', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(xml => window.ravenroot.replaceActiveDocumentFromText(xml, 'parallel.graphml'), parallel);
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', { timeout: 15_000 });
    for (const label of LAYERED) {
      await arrange(page, label);
      const sample = await geometry(page);
      const bundle = sample.edges.filter(edge => edge.id.startsWith('p'));
      expect(bundle).toHaveLength(3);
      expect(new Set(bundle.map(edge => edge.sourceEndpoint)).size).toBe(3);
      expect(new Set(bundle.map(edge => edge.targetEndpoint)).size).toBe(3);
      expect(bundle.every(edge => !edge.sourceEndpoint.startsWith('-'))).toBe(true);
      expect(bundle.every(edge => edge.targetEndpoint.startsWith('-'))).toBe(true);
      expect(bundle.every(edge => ['round-segments', 'segments', 'straight'].includes(edge.curveStyle))).toBe(true);
      expect(sample.edges.find(edge => edge.id === 'll').edgeType).toBe('self');
    }
  });
});

test.describe('Layered arrangements on a large graph', () => {
  // 200 nodes and 400 edges shaped like a workflow: fan-out and fan-in blocks along a spine,
  // extra edges between near neighbours, a few retries running backwards.
  function largeGraphMl(nodeCount = 200, edgeCount = 400) {
    const nodes = Array.from({ length: nodeCount }, (_, index) => {
      const kind = index === 0 ? 'START' : index === nodeCount - 1 ? 'END' : 'PASSTHROUGH';
      return `<node id="n${index}"><data key="name">Node ${index}</data><data key="kind">${kind}</data></node>`;
    });
    const edges = [];
    const seen = new Set();
    const add = (from, to) => {
      const key = `${from}>${to}`;
      if (from === to || from < 0 || to < 0 || from >= nodeCount || to >= nodeCount || seen.has(key)) return;
      seen.add(key);
      edges.push(`<edge id="e${edges.length}" source="n${from}" target="n${to}"><data key="outcome">step</data></edge>`);
    };
    for (let index = 1; index < nodeCount; index++) add(Math.floor((index - 1) / 3), index);
    let seed = 7;
    const random = () => { seed = (seed * 48271) % 2147483647; return seed / 2147483647; };
    while (edges.length < edgeCount) {
      const from = Math.floor(random() * nodeCount);
      const span = 1 + Math.floor(random() * 12);
      add(from, random() < 0.08 ? from - span : from + span);
    }
    return `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
      <key id="name" for="node" attr.name="name" attr.type="string"/>
      <key id="kind" for="node" attr.name="kind" attr.type="string"/>
      <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
      <graph id="large" edgedefault="directed">${nodes.join('')}${edges.join('')}</graph></graphml>`;
  }

  test('arranges 200 nodes and 400 edges within the time budget', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(xml => window.ravenroot.replaceActiveDocumentFromText(xml, 'large.graphml'), largeGraphMl());
    await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', { timeout: 60_000 });
    for (const label of LAYERED) {
      const wallMs = await arrange(page, label);
      const sample = await geometry(page);
      console.log(`${label} on 200/400: engine ${Math.round(sample.elapsedMs)} ms, wall ${wallMs} ms`);
      expect(sample.elapsedMs, `${label}: engine time on 200 nodes / 400 edges`).toBeLessThan(5000);
      expect(sample.edges).toHaveLength(400);
      expect(labelOverlaps(sample.nodes)).toEqual([]);
    }
  });
});

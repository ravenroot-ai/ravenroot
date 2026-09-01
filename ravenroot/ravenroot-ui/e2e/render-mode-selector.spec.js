import { expect, test } from '@playwright/test';

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

const activeToolbarModes = page => page.locator(
  '.layout-mirrors [role="radio"][aria-checked="true"]');

const positions = page => page.evaluate(() => Object.fromEntries(window.cy.nodes().map(node => [
  node.id(),
  { x: Math.round(node.position('x') * 1000) / 1000,
    y: Math.round(node.position('y') * 1000) / 1000 },
])));

const nodePoint = (page, id) => page.evaluate(nodeId => {
  const rect = window.cy.container().getBoundingClientRect();
  const position = window.cy.getElementById(nodeId).renderedPosition();
  return { x: rect.left + position.x, y: rect.top + position.y };
}, id);

const blankCanvasPoint = page => page.evaluate(() => {
  const rect = window.cy.container().getBoundingClientRect();
  const boxes = window.cy.nodes().map(node => node.renderedBoundingBox({ includeLabels: true }));
  for (let y = 30; y < rect.height - 30; y += 24) {
    for (let x = 30; x < rect.width - 30; x += 24) {
      const pageX = rect.left + x;
      const pageY = rect.top + y;
      if (document.elementFromPoint(pageX, pageY)?.tagName === 'CANVAS'
          && boxes.every(box => x < box.x1 - 16 || x > box.x2 + 16
            || y < box.y1 - 16 || y > box.y2 + 16)) {
        return { x: pageX, y: pageY };
      }
    }
  }
  throw new Error('No blank canvas point available');
});

async function beginRepeatedDesign(page) {
  await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    window.__issue605LayoutEvents = [];
    const record = (phase, event) => {
      window.__issue605LayoutEvents.push({
        phase,
        algorithm: event.layout?.options?.name || 'unknown',
        busy: owner.pane.getAttribute('aria-busy') === 'true',
        addDisabled: document.querySelector('#btn-add-node').disabled,
        historyDepth: owner.history.depth(),
      });
    };
    owner.cy.on('layoutstart', event => record('start', event));
    owner.cy.on('layoutstop', event => {
      record('stop', event);
      queueMicrotask(() => record('after-stop', event));
    });
    new MutationObserver((_records, observer) => {
      if (owner.pane.getAttribute('aria-busy') === 'true') return;
      window.__issue605LayoutEvents.push({
        phase: 'released',
        algorithm: 'cyto-routing',
        busy: false,
        addDisabled: document.querySelector('#btn-add-node').disabled,
        historyDepth: owner.history.depth(),
      });
      observer.disconnect();
    }).observe(owner.pane, { attributes: true, attributeFilter: ['aria-busy'] });
    owner.cy.one('layoutstart', () => document.querySelector('#btn-design').click());
    document.querySelector('#btn-design').click();
  });
  await expect.poll(() => page.evaluate(() =>
    window.__issue605LayoutEvents.filter(event => event.phase === 'start').length)).toBeGreaterThanOrEqual(2);
}

async function selectDesign(page) {
  const pane = page.locator('.doc-pane--active');
  await page.locator('#btn-design').click();
  await expect(pane).toHaveAttribute('aria-busy', 'true');
  await expect(pane).not.toHaveAttribute('aria-busy', 'true', { timeout: 10_000 });
  await expect.poll(() => page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    return [owner.renderMode, owner.layoutMode, owner.visualStyle];
  })).toEqual(['design', 'cyto', 'cyto']);
}

test.beforeEach(async ({ page }) => {
  await stubService(page);
  await page.goto('/');
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true', {
    timeout: 10_000,
  });
});

test('exposes exactly Design and Monitoring in one exclusive toolbar and menu group', async ({ page }) => {
  await page.setViewportSize({ width: 2400, height: 900 });
  const toolbar = page.locator('.layout-mirrors[role="radiogroup"]');
  await expect(toolbar).toHaveAccessibleName('Graph view');
  await expect(toolbar.locator('[role="radio"]')).toHaveCount(2);
  await expect(toolbar.locator('[role="radio"]')).toHaveText([/Design/, /Monitoring/]);
  await expect(activeToolbarModes(page)).toHaveCount(1);
  await expect(page.locator('#btn-design')).toHaveAttribute('aria-checked', 'true');

  await page.locator('[data-menu="layout"]').click();
  const modes = page.locator('#application-menu [role="menuitemradio"][data-command-id^="layout."]');
  await expect(modes).toHaveCount(2);
  await expect(modes).toHaveText([/Design/, /Monitoring/]);
  await expect(page.locator('#application-menu [data-command-id^="style."]')).toHaveCount(0);
  await expect(page.getByRole('menuitemradio', { name: 'Design' })).toHaveAttribute('aria-checked', 'true');
});

test('implements one roving radio stop with arrow, Home, End, Tab and Shift+Tab semantics', async ({ page }) => {
  await page.setViewportSize({ width: 2400, height: 900 });
  const toolbar = page.locator('.layout-mirrors[role="radiogroup"]');
  await expect(toolbar.locator('[role="radio"][tabindex="0"]')).toHaveCount(1);
  await expect(page.locator('#btn-design')).toHaveAttribute('tabindex', '0');

  await page.locator('#btn-design').focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('#btn-monitoring')).toBeFocused();
  await expect(page.locator('#btn-monitoring')).toHaveAttribute('aria-checked', 'true');
  await expect(activeToolbarModes(page)).toHaveCount(1);

  await page.keyboard.press('Home');
  await expect(page.locator('#btn-design')).toBeFocused();
  await expect(page.locator('#btn-design')).toHaveAttribute('aria-checked', 'true');
  await page.keyboard.press('End');
  await expect(page.locator('#btn-monitoring')).toBeFocused();
  await page.keyboard.press('Tab');
  await expect.poll(() => page.evaluate(() => Boolean(
    document.activeElement?.closest('.layout-mirrors')))).toBe(false);
  await page.keyboard.press('Shift+Tab');
  await expect(page.locator('#btn-monitoring')).toBeFocused();
});

test('keeps the semantic radio group reachable at the responsive breakpoint', async ({ page }) => {
  await page.setViewportSize({ width: 1180, height: 800 });
  const toolbar = page.locator('.layout-mirrors[role="radiogroup"]');
  await expect(toolbar).toBeVisible();
  await expect(toolbar.locator('[role="radio"]')).toHaveCount(2);
  await expect(toolbar.locator('[role="radio"][tabindex="0"]')).toHaveCount(1);
  await expect(activeToolbarModes(page)).toHaveCount(1);
  await expect.poll(() => page.evaluate(() =>
    document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);

  await page.locator('[data-menu="layout"]').click();
  await expect(page.getByRole('menuitemradio', { name: 'Design' })).toBeVisible();
  await expect(page.getByRole('menuitemradio', { name: 'Monitoring' })).toBeVisible();
});

test('normalizes legacy document modes on activation without exposing hidden algorithms', async ({ page }) => {
  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'legacy.graphml' }));
  await page.evaluate(id => {
    const record = window.ravenroot.workspace.find(id);
    delete record.renderMode;
    record.layoutMode = 'elastic';
    record.visualStyle = 'n8n4';
  }, first);
  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  await expect(activeToolbarModes(page)).toHaveCount(1);
  await expect(page.locator('#btn-monitoring')).toHaveAttribute('aria-checked', 'true');
  expect(await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    return [owner.renderMode, owner.layoutMode, owner.visualStyle, owner.renderer.kind];
  })).toEqual(['monitoring', 'elastic', 'cyto', 'elastic']);

  await page.evaluate(id => {
    const record = window.ravenroot.workspace.find(id);
    delete record.renderMode;
    record.layoutMode = 'n8n3';
    record.visualStyle = 'n8n3';
    window.ravenroot.activateDocument(id);
  }, second);
  await expect(activeToolbarModes(page)).toHaveCount(1);
  await expect(page.locator('#btn-design')).toHaveAttribute('aria-checked', 'true');
  expect(await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    return [owner.renderMode, owner.layoutMode, owner.visualStyle];
  })).toEqual(['design', 'cyto', 'cyto']);
});

test('Design performs the same deterministic full relayout from different coordinates', async ({ page }) => {
  await page.locator('#btn-monitoring').click();
  await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
  await page.evaluate(() => {
    window.cy.nodes().forEach((node, index) =>
      node.position({ x: 1200 - index * 173, y: -700 + index * 241 }));
  });
  const firstInput = await positions(page);
  await selectDesign(page);
  const first = await positions(page);
  expect(first).not.toEqual(firstInput);
  expect(await page.evaluate(() => window.cy.edges().toArray()
    .every(edge => edge.style('curve-style') === 'unbundled-bezier'))).toBe(true);

  await page.evaluate(() => {
    window.cy.nodes().forEach((node, index) =>
      node.position({ x: -900 + index * 89, y: 1400 - index * 137 }));
  });
  await selectDesign(page);
  expect(await positions(page)).toEqual(first);
});

test('Monitoring owns the continuous renderer lifecycle and Design restores authoring', async ({ page }) => {
  await page.locator('#btn-monitoring').click();
  await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
  await expect(page.locator('#elastic-ctrl')).toHaveClass(/visible/);
  expect(await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    return {
      renderMode: owner.renderMode,
      layoutMode: owner.layoutMode,
      visualStyle: owner.visualStyle,
      rendererKind: owner.renderer.kind,
      simulationLive: owner.renderer.simulation.alpha() > 0,
    };
  })).toEqual({
    renderMode: 'monitoring', layoutMode: 'elastic', visualStyle: 'cyto',
    rendererKind: 'elastic', simulationLive: true,
  });

  await selectDesign(page);
  await expect(page.locator('.doc-elastic-host.active')).toHaveCount(0);
  await expect(page.locator('#elastic-ctrl')).not.toHaveClass(/visible/);
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-add-node')).toBeEnabled();
});

test('Design relayout retires edge gestures and owns the canvas until final routing', async ({ page }) => {
  await page.locator('#btn-modify').click();
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('e');
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'composing');

  await page.locator('#btn-design').click();
  const pane = page.locator('.doc-pane--active');
  await expect(pane).toHaveAttribute('aria-busy', 'true');
  await expect(page.locator('#cy-wrap')).toHaveAttribute('data-edge-gesture-state', 'idle');
  await expect(page.locator('#btn-add-node')).toBeDisabled();
  await page.locator('#cy-wrap').focus();
  await page.keyboard.press('e');
  await expect(page.locator('#graph-live')).toContainText('Layout in progress');
  await expect(page.locator('#cy-wrap')).not.toHaveAttribute('data-edge-gesture-state', 'composing');

  await expect(pane).not.toHaveAttribute('aria-busy', 'true', { timeout: 10_000 });
  await expect(page.locator('#btn-add-node')).toBeEnabled();
  await expect(page.locator('#graph-live')).toContainText('layout complete');
});

test('repeated Design requests keep one latest owner through final routing and release authoring once', async ({ page }) => {
  await page.locator('#btn-modify').click();
  const pane = page.locator('.doc-pane--active');
  const before = await page.evaluate(() => ({
    nodes: window.cy.nodes().length,
    history: window.ravenroot.activeDocument().history.depth(),
  }));

  await beginRepeatedDesign(page);
  await expect(pane).toHaveAttribute('aria-busy', 'true');
  await expect(page.locator('#btn-add-node')).toBeDisabled();
  await expect(page.locator('#btn-undo')).toBeDisabled();

  const movingNode = await nodePoint(page, 'start');
  await page.mouse.move(movingNode.x, movingNode.y);
  await page.mouse.down();
  await page.mouse.move(movingNode.x + 70, movingNode.y + 35, { steps: 4 });
  await page.mouse.up();
  const blankWhileBusy = await blankCanvasPoint(page);
  await page.mouse.click(blankWhileBusy.x, blankWhileBusy.y);
  expect(await page.evaluate(() => ({
    nodes: window.cy.nodes().length,
    history: window.ravenroot.activeDocument().history.depth(),
  }))).toEqual(before);

  await expect.poll(() => page.evaluate(() => window.__issue605LayoutEvents.some(
    event => event.phase === 'released' && !event.busy))).toBe(true);
  await expect(pane).not.toHaveAttribute('aria-busy', 'true');
  const events = await page.evaluate(() => window.__issue605LayoutEvents);
  expect(events.filter(event => event.phase === 'released' && !event.busy)).toHaveLength(1);
  expect(events.at(-1)).toMatchObject({
    phase: 'released', algorithm: 'cyto-routing', busy: false, addDisabled: false,
  });
  expect(events.filter(event => event.phase === 'start').every(
    event => event.busy && event.addDisabled && event.historyDepth === before.history)).toBe(true);
  await expect(page.locator('#graph-live')).toContainText('layout complete');

  const beforeMove = await page.evaluate(() => ({
    renderer: window.cy.getElementById('start').position(),
    depth: window.ravenroot.activeDocument().history.depth(),
  }));
  const start = await nodePoint(page, 'start');
  await page.mouse.click(start.x, start.y);
  await expect.poll(() => page.evaluate(() => window.cy.getElementById('start').selected())).toBe(true);
  const selected = await nodePoint(page, 'start');
  await page.mouse.move(selected.x, selected.y);
  await page.mouse.down();
  await page.mouse.move(selected.x + 80, selected.y + 45, { steps: 8 });
  await page.mouse.up();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().history.depth()))
    .toBe(beforeMove.depth + 1);
  await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Move start/);
  await page.locator('#btn-undo').click();
  await expect.poll(() => page.evaluate(expected => {
    const owner = window.ravenroot.activeDocument();
    const renderer = owner.cy.getElementById('start').position();
    const model = owner.graph.nodeMap.start;
    return renderer.x === expected.renderer.x && renderer.y === expected.renderer.y
      && model.ox === expected.renderer.x && model.oy === expected.renderer.y;
  }, beforeMove)).toBe(true);
});

test('a background Design owner cannot move or block the active Design document', async ({ page }) => {
  const first = await page.evaluate(() => window.ravenroot.activeDocument().id);
  await page.locator('#btn-monitoring').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().renderer.kind))
    .toBe('elastic');
  const second = await page.evaluate(() => window.ravenroot.openDocument({ name: 'editing.graphml' }));
  await selectDesign(page);
  await page.locator('#btn-modify').click();

  await page.evaluate(id => window.ravenroot.activateDocument(id), first);
  const firstPane = page.locator(`.doc-pane[data-document-id="${first}"]`);
  const secondPane = page.locator(`.doc-pane[data-document-id="${second}"]`);
  await page.locator('#btn-design').click();
  await expect(firstPane).toHaveAttribute('aria-busy', 'true');
  await page.evaluate(id => window.ravenroot.activateDocument(id), second);
  await expect(secondPane).toHaveClass(/doc-pane--active/);
  await expect(secondPane).not.toHaveAttribute('aria-busy', 'true');
  await expect(page.locator('#btn-add-node')).toBeEnabled();

  await page.locator('#btn-add-node').click();
  const nodeId = await page.locator('#node-editor input[name="id"]').inputValue();
  await page.locator('#node-editor input[name="name"]').fill('Background-safe edit');
  await page.locator('#node-editor button[type="submit"]').click();
  await expect.poll(() => page.evaluate(id => window.cy.getElementById(id).nonempty(), nodeId)).toBe(true);
  const beforeFirstSettles = await positions(page);

  await expect(firstPane).not.toHaveAttribute('aria-busy', 'true');
  expect(await page.evaluate(() => window.ravenroot.activeDocument().id)).toBe(second);
  expect(await positions(page)).toEqual(beforeFirstSettles);
  expect(await page.evaluate(id => {
    const owner = window.ravenroot.activeDocument();
    return Boolean(owner.graph.nodeMap[id] && owner.cy.getElementById(id).nonempty());
  }, nodeId)).toBe(true);
});

test('Monitoring retires an in-flight Design owner without accepting stale coordinates', async ({ page }) => {
  await page.evaluate(() => {
    window.cy.nodes().forEach((node, index) => node.position({
      x: 1300 - index * 211,
      y: -800 + index * 173,
    }));
    const owner = window.ravenroot.activeDocument();
    owner.cy.one('layoutstart', () => document.querySelector('#btn-monitoring').click());
    document.querySelector('#btn-design').click();
  });
  await expect.poll(() => page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    return [owner.renderMode, owner.layoutMode, owner.renderer.kind, owner.layoutBusy];
  })).toEqual(['monitoring', 'elastic', 'elastic', false]);
  const settled = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    return {
      positions: Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()])),
      model: Object.fromEntries(owner.graph.nodes.map(node => [node.id, { x: node.ox, y: node.oy }])),
    };
  });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() =>
    requestAnimationFrame(() => requestAnimationFrame(resolve)))));
  expect(await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    return {
      positions: Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()])),
      model: Object.fromEntries(owner.graph.nodes.map(node => [node.id, { x: node.ox, y: node.oy }])),
    };
  })).toEqual(settled);
  await expect(page.locator('#btn-monitoring')).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('#btn-design')).toHaveAttribute('aria-checked', 'false');
});

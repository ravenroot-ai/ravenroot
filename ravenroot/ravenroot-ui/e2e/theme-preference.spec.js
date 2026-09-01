import { expect, test } from '@playwright/test';
import { scanForViolations } from './accessibility-helpers.mjs';

async function chooseTheme(page, name) {
  await page.locator('#menu-view').click();
  await page.getByRole('menuitemradio', { name }).click();
}

test('resolves system preference before first paint and restores a persisted user choice', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' });
  await page.addInitScript(() => {
    if (!sessionStorage.getItem('theme-test-initialized')) {
      localStorage.removeItem('ravenroot.ui.theme');
      sessionStorage.setItem('theme-test-initialized', 'true');
    }
    window.__themeAtFirstFrame = new Promise(resolve =>
      requestAnimationFrame(() => resolve(document.documentElement.dataset.theme)));
  });
  await page.goto('/');
  expect(await page.evaluate(() => window.__themeAtFirstFrame)).toBe('light');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');

  await chooseTheme(page, 'Dark theme');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  expect(await page.evaluate(() => localStorage.getItem('ravenroot.ui.theme'))).toBe('dark');

  await page.emulateMedia({ colorScheme: 'light' });
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
});

test('switches both application and Cyto palettes without layout or model/view mutation', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(() => window.ravenroot.setApplicationTheme('dark'));
  await page.locator('#btn-design').click();
  await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    owner.cy.zoom(0.83);
    owner.cy.pan({ x: 117, y: 73 });
    owner.cy.getElementById('start').select();
    window.__themeLayoutCalls = 0;
    const originalLayout = owner.cy.layout.bind(owner.cy);
    owner.cy.layout = (...args) => {
      window.__themeLayoutCalls += 1;
      return originalLayout(...args);
    };
    window.__themeBaseline = {
      graph: JSON.stringify(owner.graph),
      positions: JSON.stringify(Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()]))),
      viewport: JSON.stringify({ zoom: owner.cy.zoom(), pan: owner.cy.pan() }),
      history: JSON.stringify(owner.history.state()),
      style: owner.visualStyle,
      selection: owner.cy.$(':selected').map(element => element.id()).sort().join(','),
      canvas: getComputedStyle(document.documentElement).getPropertyValue('--surface-canvas').trim(),
      node: owner.cy.getElementById('start').style('background-color'),
    };
  });

  await chooseTheme(page, 'Light theme');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  const result = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    const baseline = window.__themeBaseline;
    return {
      layoutCalls: window.__themeLayoutCalls,
      graph: JSON.stringify(owner.graph) === baseline.graph,
      positions: JSON.stringify(Object.fromEntries(owner.cy.nodes().map(node => [node.id(), node.position()]))) === baseline.positions,
      viewport: JSON.stringify({ zoom: owner.cy.zoom(), pan: owner.cy.pan() }) === baseline.viewport,
      history: JSON.stringify(owner.history.state()) === baseline.history,
      style: owner.visualStyle === baseline.style,
      selection: owner.cy.$(':selected').map(element => element.id()).sort().join(',') === baseline.selection,
      canvasChanged: getComputedStyle(document.documentElement).getPropertyValue('--surface-canvas').trim() !== baseline.canvas,
      nodeChanged: owner.cy.getElementById('start').style('background-color') !== baseline.node,
    };
  });
  expect(result).toEqual({
    layoutCalls: 0, graph: true, positions: true, viewport: true, history: true,
    style: true, selection: true, canvasChanged: true, nodeChanged: true,
  });
});

test('recolors the live Elastic renderer without restarting its simulation or changing geometry', async ({ page }) => {
  await page.goto('/');
  await page.evaluate(() => window.ravenroot.setApplicationTheme('dark'));
  await page.locator('#btn-monitoring').click();
  await expect(page.locator('.doc-elastic-host.active')).toBeVisible();
  await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    const renderer = owner.renderer;
    renderer.simulation.stop();
    window.__elasticThemeRestarts = 0;
    const originalRestart = renderer.simulation.restart.bind(renderer.simulation);
    renderer.simulation.restart = (...args) => {
      window.__elasticThemeRestarts += 1;
      return originalRestart(...args);
    };
    window.__elasticThemeBaseline = {
      graph: JSON.stringify(owner.graph),
      history: JSON.stringify(owner.history.state()),
      positions: JSON.stringify(renderer.nodes.map(node => [node.id, node.x, node.y])),
      transform: renderer.zoomGroup.attr('transform'),
      fill: renderer.nodeSelection.attr('fill'),
      viewport: JSON.stringify({ zoom: owner.cy.zoom(), pan: owner.cy.pan() }),
    };
  });
  await chooseTheme(page, 'Light theme');
  expect(await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    const renderer = owner.renderer;
    const baseline = window.__elasticThemeBaseline;
    return {
      restarts: window.__elasticThemeRestarts,
      graph: JSON.stringify(owner.graph) === baseline.graph,
      history: JSON.stringify(owner.history.state()) === baseline.history,
      positions: JSON.stringify(renderer.nodes.map(node => [node.id, node.x, node.y])) === baseline.positions,
      transform: renderer.zoomGroup.attr('transform') === baseline.transform,
      viewport: JSON.stringify({ zoom: owner.cy.zoom(), pan: owner.cy.pan() }) === baseline.viewport,
      fillChanged: renderer.nodeSelection.attr('fill') !== baseline.fill,
    };
  })).toEqual({ restarts: 0, graph: true, history: true, positions: true,
    transform: true, viewport: true, fillChanged: true });
});

test('preserves pre-existing runtime strokes on the first Elastic frame', async ({ page }) => {
  await page.goto('/');
  const ids = await page.evaluate(() => {
    const nodes = window.ravenroot.activeDocument().cy.nodes();
    nodes[0].data('runtimeState', 'active');
    nodes[1].data('runtimeState', 'failed');
    return [nodes[0].id(), nodes[1].id()];
  });
  await page.locator('#btn-monitoring').click();
  await expect(page.locator('.doc-elastic-host.active')).toBeVisible();

  expect(await page.evaluate(expectedIds => {
    const renderer = window.ravenroot.activeDocument().renderer;
    renderer.simulation.stop();
    const rendered = {};
    renderer.nodeSelection.each(function eachRuntimeNode(node) {
      if (expectedIds.includes(node.id)) {
        rendered[node.id] = {
          state: node.runtimeState,
          stroke: this.getAttribute('stroke'),
          width: this.getAttribute('stroke-width'),
        };
      }
    });
    return rendered;
  }, ids)).toEqual({
    [ids[0]]: { state: 'active', stroke: expect.any(String), width: '4' },
    [ids[1]]: { state: 'failed', stroke: expect.any(String), width: '4' },
  });
  const strokes = await page.evaluate(expectedIds => {
    const renderer = window.ravenroot.activeDocument().renderer;
    return expectedIds.map(id => renderer.nodes.find(node => node.id === id).stroke);
  }, ids);
  expect(strokes[0]).not.toBe('#8c959f');
  expect(strokes[1]).not.toBe('#8c959f');
  expect(strokes[0]).not.toBe(strokes[1]);
});

test('offers keyboard-operable checked theme choices with visible focus in both palettes', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto('/');
  for (const [current, next] of [['Dark theme', 'Light theme'], ['Light theme', 'Dark theme']]) {
    await page.locator('#menu-view').click();
    await page.keyboard.type(current.slice(0, 4));
    const currentItem = page.getByRole('menuitemradio', { name: current });
    await expect(currentItem).toBeFocused();
    await expect(currentItem).toHaveAttribute('aria-checked', 'true');
    expect(await currentItem.evaluate(element => getComputedStyle(element).outlineStyle)).not.toBe('none');
    await page.keyboard.press(next.startsWith('Light') ? 'ArrowDown' : 'ArrowUp');
    await expect(page.getByRole('menuitemradio', { name: next })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.locator('html')).toHaveAttribute('data-theme', next.startsWith('Light') ? 'light' : 'dark');
  }

  for (const theme of ['dark', 'light']) {
    await page.evaluate(value => window.ravenroot.setApplicationTheme(value), theme);
    await page.waitForTimeout(200); // established controls transition paint for 150ms
    const results = await scanForViolations(page);
    expect(results.violations.filter(violation => ['color-contrast', 'focus-visible'].includes(violation.id))).toEqual([]);
  }
});

test('renders the major application surfaces in both palettes without responsive overflow', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/');
  const colors = [];
  for (const theme of ['dark', 'light']) {
    await page.evaluate(value => window.ravenroot.setApplicationTheme(value), theme);
    await page.locator('#menu-view').click();
    await expect(page.getByRole('menuitemradio', { name: `${theme === 'dark' ? 'Dark' : 'Light'} theme` }))
      .toHaveAttribute('aria-checked', 'true');
    colors.push(await page.evaluate(() => ({
      body: getComputedStyle(document.body).backgroundColor,
      surface: getComputedStyle(document.getElementById('topbar')).backgroundColor,
      canvas: getComputedStyle(document.documentElement).getPropertyValue('--surface-canvas').trim(),
      overflow: document.getElementById('topbar').scrollWidth <= document.getElementById('topbar').clientWidth,
    })));
    await testInfo.attach(`application-${theme}`, {
      body: await page.screenshot(), contentType: 'image/png',
    });
    await page.keyboard.press('Escape');
  }
  expect(colors[0]).not.toEqual(colors[1]);
  expect(colors.every(color => color.overflow)).toBe(true);
});

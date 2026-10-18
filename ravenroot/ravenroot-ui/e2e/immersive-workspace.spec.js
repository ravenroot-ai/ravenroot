import { expect, test } from '@playwright/test';
import path from 'node:path';
import { defaultLayout } from '../src/panel-layout.js';

const STORAGE_KEY = 'ravenroot.ui.immersive-layout';
const surface = (page, id) => page.locator(`[data-surface-id="${id}"]`);
const grip = (page, id) => page.locator(`[data-surface-move="${id}"]`);
const dock = (page, id) => page.locator(`[data-surface-dock="${id}"]`);
const restore = (page, id) => page.locator(`[data-surface-restore="${id}"]`);
const saved = page => page.evaluate(key => JSON.parse(localStorage.getItem(key)), STORAGE_KEY);
const capture = async (page, testInfo, name) => {
  const screenshotPath = process.env.RR_VISUAL_EVIDENCE_DIR
    ? path.join(process.env.RR_VISUAL_EVIDENCE_DIR, `${name}.png`) : undefined;
  await testInfo.attach(name, { body: await page.screenshot({ path: screenshotPath }), contentType: 'image/png' });
};

async function openWorkspace(page) {
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json; charset=utf-8', body: '[]',
  }));
  await page.route('**/v1/events', route => route.fulfill({
    status: 200, contentType: 'text/event-stream', body: '',
  }));
  await page.goto('/');
  await expect(grip(page, 'tools')).toBeVisible();
  await expect.poll(() => page.evaluate(() => Boolean(window.ravenroot?.activeDocument()?.cy)))
    .toBe(true);
}

async function expectReachable(locator, page) {
  await expect(locator).toBeVisible();
  const box = await locator.boundingBox();
  const viewport = page.viewportSize();
  expect(box.x).toBeGreaterThanOrEqual(-1);
  expect(box.y).toBeGreaterThanOrEqual(-1);
  expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1);
  expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1);
  await expect.poll(() => locator.evaluate(element => {
    const rect = element.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
    return element === hit || element.contains(hit);
  })).toBe(true);
}

// Read coordinates from the live renderer, then select with the same real mouse path as a user.
async function selectVisibleGraphNode(page) {
  await expect.poll(() => page.evaluate(() => Boolean(window.cy.scratch('_rrLayoutRunning')))).toBe(false);
  await page.locator('#zoom-ctrl [data-command-id="view.fit"]').click();
  await page.locator('.doc-pane--active').focus();
  const target = await page.evaluate(() => {
    const cy = window.cy, canvas = cy.container().getBoundingClientRect();
    const obstacles = [...document.querySelectorAll('[data-surface-id], #zoom-ctrl, #minimap, .workspace-controls, .workspace-shelf')]
      .filter(element => !element.inert && !element.hidden && getComputedStyle(element).display !== 'none'
        && getComputedStyle(element).visibility !== 'hidden').map(element => element.getBoundingClientRect());
    for (const node of cy.nodes(':visible')) {
      const position = node.renderedPosition(), x = canvas.left + position.x, y = canvas.top + position.y;
      if (x < canvas.left || x > canvas.right || y < canvas.top || y > canvas.bottom) continue;
      if (obstacles.some(box => x >= box.left && x <= box.right && y >= box.top && y <= box.bottom)) continue;
      return { id: node.id(), name: node.data('name'), x, y };
    }
    return null;
  });
  expect(target).not.toBeNull();
  await page.mouse.click(target.x, target.y);
  await expect.poll(() => page.evaluate(id => window.cy.getElementById(id).selected(), target.id)).toBe(true);
  await expect(page.locator('#info-title')).toHaveText(target.name);
  return target;
}

const graphPlacement = (page, selectedOnly = false) => page.evaluate(selected => {
  const cy = window.ravenroot.activeDocument().cy;
  const canvas = cy.container().getBoundingClientRect();
  const elements = cy.elements(selected ? ':selected' : ':visible');
  const nodes = elements.renderedBoundingBox();
  const box = { left: canvas.left + nodes.x1, right: canvas.left + nodes.x2,
    top: canvas.top + nodes.y1, bottom: canvas.top + nodes.y2 };
  const outside = box.left < canvas.left - 2 || box.right > canvas.right + 2
    || box.top < canvas.top - 2 || box.bottom > canvas.bottom + 2;
  const overlaps = [...document.querySelectorAll('[data-surface-id], #zoom-ctrl, #minimap, .workspace-controls, .workspace-shelf')].filter(element => {
    const style = getComputedStyle(element);
    if (element.inert || element.hidden || style.display === 'none' || style.visibility === 'hidden') return false;
    const rect = element.getBoundingClientRect();
    return box.left < rect.right - 2 && box.right > rect.left + 2
      && box.top < rect.bottom - 2 && box.bottom > rect.top + 2;
  }).length;
  return { outside, overlaps, nodes: elements.nodes().length };
}, selectedOnly);

test.describe('immersive workspace', () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('docks the tool palette on every edge and restores a minimized panel to its previous place', async ({ page }) => {
    await openWorkspace(page);
    for (const edge of ['left', 'bottom', 'right', 'top']) {
      await dock(page, 'tools').selectOption(edge);
      await expect(surface(page, 'tools')).toHaveAttribute('data-dock', edge);
      await expectReachable(grip(page, 'tools'), page);
    }
    await dock(page, 'inspector').selectOption('floating');
    const before = await surface(page, 'inspector').boundingBox();
    await page.locator('[data-surface-minimize="inspector"]').click();
    await expect(surface(page, 'inspector')).toBeHidden();
    await expect(restore(page, 'inspector')).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(grip(page, 'inspector')).toBeFocused();
    const after = await surface(page, 'inspector').boundingBox();
    for (const dimension of ['x', 'y', 'width', 'height']) {
      expect(Math.abs(after[dimension] - before[dimension])).toBeLessThanOrEqual(1);
    }
  });

  test('keeps authoring commands discoverable through the compact application menu', async ({ page }) => {
    await openWorkspace(page);
    const menu = page.locator('#application-menu');
    await page.locator('#menu-file').click();
    for (const command of ['file.new', 'file.save']) {
      await expect(menu.locator(`[data-command-id="${command}"]`)).toBeVisible();
    }
    await page.keyboard.press('Escape');
    await page.locator('#menu-edit').click();
    for (const command of ['edit.autosave', 'edit.connect', 'edit.addNode', 'edit.addEdge']) {
      await expect(menu.locator(`[data-command-id="${command}"]`)).toBeVisible();
    }
    await page.keyboard.press('Escape');
  });

  test('offers named keyboard controls, an escapable panel index, Focus and Reset', async ({ page }, testInfo) => {
    await openWorkspace(page);
    await restore(page, 'catalog').click();
    await page.locator('#activity-log').focus();
    await expect(page.locator('#activity-log')).toBeFocused();
    for (const id of ['catalog', 'inspector', 'activity', 'tools']) {
      await expect(grip(page, id)).toHaveAccessibleName(/Move .+/);
      await expect(dock(page, id)).toHaveAccessibleName(/Dock .+/);
    }
    await dock(page, 'inspector').selectOption('floating');
    const sizeBefore = await surface(page, 'inspector').boundingBox();
    await grip(page, 'inspector').focus();
    await page.keyboard.press('ArrowLeft');
    expect((await surface(page, 'inspector').boundingBox()).x).toBeLessThan(sizeBefore.x);
    await page.keyboard.press('Escape');
    expect((await surface(page, 'inspector').boundingBox()).x).toBeCloseTo(sizeBefore.x, 1);
    await page.keyboard.press('Shift+ArrowRight');
    expect((await surface(page, 'inspector').boundingBox()).width).toBeGreaterThan(sizeBefore.width);
    await page.keyboard.press('Alt+ArrowLeft');
    await expect(surface(page, 'inspector')).toHaveAttribute('data-dock', 'left');
    const index = page.locator('[data-workspace-action="index"]');
    await index.click();
    await expect(index).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('[data-surface-show="inspector"]')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(index).toHaveAttribute('aria-expanded', 'false');
    await expect(index).toBeFocused();

    const focus = page.locator('[data-workspace-action="focus"]');
    const beforeFocus = await saved(page);
    await focus.click();
    await expect(focus).toHaveAccessibleName('Exit Focus mode');
    await expect(surface(page, 'catalog')).toBeHidden();
    await expectReachable(focus, page);
    expect(await saved(page)).toEqual(beforeFocus);
    await page.locator('[data-command-id="view.fit"]:visible').first().click();
    await capture(page, testInfo, 'immersive-focus');
    await focus.click();
    await expect(surface(page, 'catalog')).toBeVisible();
    await page.locator('[data-workspace-action="reset"]').click();
    await expect(surface(page, 'inspector')).toHaveAttribute('data-dock', 'right');
    await expect(surface(page, 'activity')).toHaveAttribute('data-dock', 'bottom');
  });

  test('cancels a pointer move with Escape without editing the graph', async ({ page }) => {
    await openWorkspace(page);
    await dock(page, 'inspector').selectOption('floating');
    const before = await surface(page, 'inspector').boundingBox();
    const handle = await grip(page, 'inspector').boundingBox();
    await expect.poll(() => page.evaluate(() => Boolean(window.cy.scratch('_rrLayoutRunning')))).toBe(false);
    await page.evaluate(() => { window.cy.stop(); });
    const graph = await page.evaluate(() => window.ravenroot.activeDocument().cy.elements().jsons());
    await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2);
    await page.mouse.down();
    await page.mouse.move(handle.x + handle.width / 2 - 120, handle.y + handle.height / 2 + 70, { steps: 8 });
    await page.keyboard.press('Escape');
    await page.mouse.up();
    const after = await surface(page, 'inspector').boundingBox();
    expect(Math.abs(after.x - before.x)).toBeLessThanOrEqual(1);
    expect(Math.abs(after.y - before.y)).toBeLessThanOrEqual(1);
    expect(await page.evaluate(() => window.ravenroot.activeDocument().cy.elements().jsons())).toEqual(graph);

    const workspace = await page.locator('#main').boundingBox();
    const restarted = await grip(page, 'inspector').boundingBox();
    await page.mouse.move(restarted.x + restarted.width / 2, restarted.y + restarted.height / 2);
    await page.mouse.down();
    await page.mouse.move(workspace.x + 12, workspace.y + workspace.height / 2, { steps: 8 });
    await expect(page.locator('.workspace-dock-preview')).toBeVisible();
    await expect(page.locator('.workspace-dock-preview')).toHaveAttribute('data-dock', 'left');
    await page.mouse.up();
    await expect(surface(page, 'inspector')).toHaveAttribute('data-dock', 'left');
  });

  test('persists a versioned layout and recovers reachable controls on a narrow viewport', async ({ page }, testInfo) => {
    await openWorkspace(page);
    await dock(page, 'inspector').selectOption('floating');
    await grip(page, 'inspector').focus();
    await page.keyboard.press('ArrowLeft');
    await page.locator('[data-surface-minimize="activity"]').click();
    const before = await saved(page);
    expect(before.v).toBe(1);
    await page.reload();
    await expect(surface(page, 'inspector')).toHaveAttribute('data-dock', 'floating');
    await expect(restore(page, 'activity')).toBeVisible();
    expect(await saved(page)).toEqual(before);
    await page.locator('[data-command-id="view.fit"]:visible').first().click();
    await capture(page, testInfo, 'immersive-minimized-activity');
    await page.setViewportSize({ width: 390, height: 844 });
    for (const id of ['catalog', 'inspector']) await expectReachable(restore(page, id), page);
    await expectReachable(grip(page, 'tools'), page);
    await expectReachable(restore(page, 'activity'), page);
    await expectReachable(page.locator('[data-workspace-action="index"]'), page);
    await restore(page, 'inspector').click();
    await expectReachable(grip(page, 'inspector'), page);
    await expectReachable(page.locator('#zoom-ctrl [data-command-id="view.fit"]'), page);
  });

  test('migrates legacy collapsed zones and safely recovers malformed saved state', async ({ page }) => {
    const legacy = defaultLayout();
    legacy.zones.right.collapsed = true;
    await page.addInitScript(layout => {
      if (!sessionStorage.getItem('immersive-migration-seeded')) {
        localStorage.setItem('ravenroot.ui.panel-layout', JSON.stringify(layout));
        sessionStorage.setItem('immersive-migration-seeded', 'true');
      }
    }, legacy);
    // The legacy Inspector was collapsed; its recognizable restore control must survive migration.
    await page.goto('/');
    await expect(grip(page, 'catalog')).toBeVisible();
    await expect(restore(page, 'inspector')).toBeVisible();
    await restore(page, 'inspector').click();
    await expectReachable(grip(page, 'inspector'), page);
    await page.evaluate(key => {
      localStorage.removeItem('ravenroot.ui.panel-layout');
      localStorage.setItem(key, '{broken');
    }, STORAGE_KEY);
    await page.reload();
    await expect(surface(page, 'catalog')).toBeHidden();
    for (const id of ['catalog', 'inspector', 'activity', 'tools']) {
      await expectReachable(id === 'catalog' ? restore(page, id) : grip(page, id), page);
    }
  });

  test('supports keyboard docking and visible focus with reduced motion', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await openWorkspace(page);
    await grip(page, 'tools').focus();
    const focus = await grip(page, 'tools').evaluate(element => {
      const style = getComputedStyle(element);
      return { outline: style.outlineStyle, width: parseFloat(style.outlineWidth) };
    });
    expect(focus.outline).not.toBe('none');
    expect(focus.width).toBeGreaterThanOrEqual(2);
    await page.keyboard.press('Alt+ArrowLeft');
    await expect(surface(page, 'tools')).toHaveAttribute('data-dock', 'left');
    const motion = await surface(page, 'tools').evaluate(element => ({
      transition: getComputedStyle(element).transitionDuration,
      animation: getComputedStyle(element).animationName,
    }));
    expect(motion).toEqual({ transition: '0s', animation: 'none' });
  });

  test('reveals responsive and focused-away panels from the View menu', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openWorkspace(page);
    await expect(surface(page, 'inspector')).toBeHidden();
    await page.locator('#menu-view').click();
    await page.locator('#application-menu [data-command-id="view.rightInspector"]').click();
    await expectReachable(grip(page, 'inspector'), page);
    await expectReachable(page.locator('#zoom-ctrl [data-command-id="view.fit"]'), page);
    await page.locator('[data-workspace-action="focus"]').click();
    await expect(surface(page, 'inspector')).toBeHidden();
    await page.locator('#menu-view').click();
    await page.locator('#application-menu [data-command-id="view.leftPanels"]').click();
    await expectReachable(grip(page, 'catalog'), page);
    await expectReachable(page.locator('#zoom-ctrl [data-command-id="view.fit"]'), page);
    await expect(page.locator('[data-workspace-action="focus"]')).toHaveAccessibleName('Focus mode');
  });

  test('reveals the narrow Inspector on a real node selection without stealing graph focus or saving responsive geometry', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openWorkspace(page);
    await expect(surface(page, 'inspector')).toBeHidden();
    const desktopLayout = await saved(page);
    expect(desktopLayout.surfaces.inspector.minimized).toBe(false);
    await selectVisibleGraphNode(page);
    await expectReachable(grip(page, 'inspector'), page);
    expect(await surface(page, 'inspector').evaluate(element => element.inert)).toBe(false);
    await expect(page.locator('.doc-pane--active')).toBeFocused();
    expect(await saved(page)).toEqual(desktopLayout);
    await capture(page, testInfo, 'immersive-narrow-selection-inspector');
    await page.setViewportSize({ width: 1440, height: 900 });
    await expect(surface(page, 'inspector')).toHaveAttribute('data-responsive', 'false');
    await expect(surface(page, 'inspector')).toHaveAttribute('data-dock', desktopLayout.surfaces.inspector.dock);
    await expectReachable(grip(page, 'inspector'), page);
    expect(await saved(page)).toEqual(desktopLayout);
  });

  test('keeps deliberate Focus mode during automatic selection reveal and opens it through an explicit panel action', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openWorkspace(page);
    await page.locator('[data-workspace-action="focus"]').click();
    const desktopLayout = await saved(page);
    await selectVisibleGraphNode(page);
    await expect(surface(page, 'inspector')).toBeHidden();
    await expect(page.locator('[data-workspace-action="focus"]')).toHaveAccessibleName('Exit Focus mode');
    await expect(page.locator('.doc-pane--active')).toBeFocused();
    expect(await saved(page)).toEqual(desktopLayout);
    await page.locator('[data-workspace-action="index"]').click();
    await page.locator('[data-surface-show="inspector"]').click();
    await expectReachable(grip(page, 'inspector'), page);
    await expect(page.locator('[data-workspace-action="focus"]')).toHaveAccessibleName('Focus mode');
  });

  test('reveals Activity through the existing panel index on narrow screens while retaining its desktop layout', async ({ page }) => {
    await openWorkspace(page);
    await dock(page, 'activity').selectOption('floating');
    await grip(page, 'activity').focus();
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('Enter');
    const desktopLayout = await saved(page);
    const desktopRect = await surface(page, 'activity').boundingBox();
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(surface(page, 'activity')).toBeHidden();
    await page.locator('#menu-view').click();
    await page.locator('#application-menu [data-command-id="view.panels"]').click();
    await page.locator('[data-index-panel="activity"]').click();
    await expectReachable(grip(page, 'activity'), page);
    expect(await surface(page, 'activity').evaluate(element => element.inert)).toBe(false);
    expect(await saved(page)).toEqual(desktopLayout);
    await page.setViewportSize({ width: 1440, height: 900 });
    await expect(surface(page, 'activity')).toHaveAttribute('data-responsive', 'false');
    const restored = await surface(page, 'activity').boundingBox();
    for (const dimension of ['x', 'y', 'width', 'height']) expect(restored[dimension]).toBeCloseTo(desktopRect[dimension], 1);
    expect(await saved(page)).toEqual(desktopLayout);
  });

  test('preserves document and renderer instances through panel transitions', async ({ page }) => {
    await openWorkspace(page);
    await restore(page, 'catalog').click();
    await page.evaluate(() => {
      window.ravenroot.openDocument({ name: 'second.graphml' });
      window.__immersiveDocuments = window.ravenroot.workspace.documents.map(document_ => ({
        document: document_, renderer: document_.cy, container: document_.cy.container(),
      }));
      window.cy.stop();
      window.cy.zoom(1.1);
      window.cy.pan({ x: 110, y: 85 });
      window.__immersiveViewport = { zoom: window.cy.zoom(), pan: window.cy.pan() };
    });
    await dock(page, 'inspector').selectOption('bottom');
    await page.locator('[data-surface-minimize="catalog"]').click();
    await restore(page, 'catalog').click();
    await page.locator('[data-workspace-action="focus"]').click();
    await page.locator('[data-workspace-action="focus"]').click();
    expect(await page.evaluate(() => window.__immersiveDocuments.every(({ document: document_, renderer, container }) =>
      window.ravenroot.workspace.documents.includes(document_) && document_.cy === renderer
      && renderer.container() === container && !renderer.destroyed()))).toBe(true);
    expect(await page.evaluate(() => ({ zoom: window.cy.zoom(), pan: window.cy.pan() })))
      .toEqual(await page.evaluate(() => window.__immersiveViewport));
  });

  test('Fit and zoom to selection place graph content clear of the open overlay surfaces', async ({ page }) => {
    await openWorkspace(page);
    expect(await page.evaluate(() => window.cy.nodes().length)).toBeGreaterThan(0);
    await expect.poll(() => page.evaluate(() => Boolean(window.cy.scratch('_rrLayoutRunning')))).toBe(false);
    await page.evaluate(() => { window.cy.stop(); });
    await page.locator('[data-command-id="view.fit"]:visible').first().click();
    await expect.poll(() => graphPlacement(page)).toMatchObject({ outside: false, overlaps: 0 });
    await page.evaluate(() => {
      window.cy.elements().unselect();
      window.cy.nodes().slice(0, 2).select();
      window.cy.pan({ x: -3000, y: -3000 });
    });
    expect((await graphPlacement(page, true)).nodes).toBeGreaterThan(0);
    await page.locator('[data-workspace-action="fit-selection"]').click();
    await expect.poll(() => graphPlacement(page, true)).toMatchObject({ outside: false, overlaps: 0 });
  });

  test('fits a forty-node graph and its selection clear of an open narrow Inspector and graph controls', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openWorkspace(page);
    const nodes = Array.from({ length: 40 }, (_, index) =>
      `<node id="step-${index}"><data key="name">Workflow step ${index + 1}</data>`
      + `<data key="kind">${index === 0 ? 'START' : index === 39 ? 'END' : 'PASSTHROUGH'}</data>`
      + `<data key="x">${100 + index % 5 * 220}</data><data key="y">${100 + Math.floor(index / 5) * 120}</data></node>`).join('');
    const edges = Array.from({ length: 39 }, (_, index) =>
      `<edge id="route-${index}" source="step-${index}" target="step-${index + 1}"/>`).join('');
    const xml = `<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
      <key id="name" for="node" attr.name="name" attr.type="string"/>
      <key id="kind" for="node" attr.name="kind" attr.type="string"/>
      <key id="x" for="node" attr.name="layoutX" attr.type="double"/>
      <key id="y" for="node" attr.name="layoutY" attr.type="double"/>
      <graph id="narrow-workflow" edgedefault="directed">${nodes}${edges}</graph></graphml>`;
    await page.evaluate(source => window.ravenroot.replaceActiveDocumentFromText(source, 'narrow-workflow.graphml'), xml);
    await expect.poll(() => page.evaluate(() => Boolean(window.cy.scratch('_rrLayoutRunning')))).toBe(false);
    const navigationFloor = await page.evaluate(() => {
      window.cy.stop();
      window.cy.nodes().positions(node => ({ x: node.position('x') * 20, y: node.position('y') * 20 }));
      return window.cy.minZoom();
    });
    await restore(page, 'inspector').click();
    await expectReachable(grip(page, 'inspector'), page);
    await expectReachable(page.locator('#zoom-ctrl [data-command-id="view.fit"]'), page);
    await page.locator('#zoom-ctrl [data-command-id="view.fit"]').click();
    await expect.poll(() => graphPlacement(page)).toEqual({ outside: false, overlaps: 0, nodes: 40 });
    expect(await page.evaluate(() => window.cy.zoom())).toBeLessThan(navigationFloor);
    expect(await page.evaluate(() => window.cy.minZoom())).toBe(navigationFloor);
    await page.evaluate(() => {
      window.cy.elements().unselect();
      window.cy.nodes().slice(0, 8).select();
      window.cy.pan({ x: -3000, y: -3000 });
    });
    await page.locator('[data-workspace-action="fit-selection"]').click();
    await expect.poll(() => graphPlacement(page, true)).toEqual({ outside: false, overlaps: 0, nodes: 8 });
    expect(await page.evaluate(() => window.cy.minZoom())).toBe(navigationFloor);
  });

  test('repaints overlay names from the active language catalog without recreating the graph', async ({ page }) => {
    await page.route('**/ui-locales.json', route => route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ schema: 'ravenroot.ui-locales/v1', defaultLocale: 'en', catalogs: [
        { schema: 'ravenroot.ui-catalog/v1', locale: 'fr', messages: {
          'workspace.overlay.inspector': 'Inspecteur',
          'workspace.overlay.move': 'Déplacer {name}',
          'workspace.overlay.minimize': 'Réduire {name}',
          'workspace.overlay.restore': 'Restaurer {name}',
          'workspace.overlay.panels': 'Panneaux',
        } },
      ] }),
    }));
    await openWorkspace(page);
    await page.evaluate(() => { window.__localizedRenderer = window.ravenroot.activeDocument().cy; });
    await page.locator('#menu-view').click();
    await page.locator('[data-command-id="view.language"]').click();
    await page.locator('#language-selection').selectOption('fr');
    await page.locator('.language-dialog button[type="submit"]').click();
    await expect(grip(page, 'inspector')).toHaveAccessibleName('Déplacer Inspecteur');
    await expect(page.locator('[data-workspace-action="index"]')).toHaveAccessibleName('Panneaux');
    await page.locator('[data-surface-minimize="inspector"]').click();
    await expect(restore(page, 'inspector')).toHaveAccessibleName('Restaurer Inspecteur');
    expect(await page.evaluate(() => window.ravenroot.activeDocument().cy === window.__localizedRenderer)).toBe(true);
  });

  for (const view of [
    { name: 'desktop-light', width: 1440, height: 900, theme: 'light' },
    { name: 'desktop-dark', width: 1440, height: 900, theme: 'dark' },
    { name: 'laptop', width: 1280, height: 720, theme: 'light' },
    { name: 'narrow', width: 390, height: 844, theme: 'dark' },
  ]) {
    test(`renders reachable workspace controls at ${view.name}`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width: view.width, height: view.height });
      await openWorkspace(page);
      await page.evaluate(theme => window.ravenroot.setApplicationTheme(theme), view.theme);
      await expect(page.locator('html')).toHaveAttribute('data-theme', view.theme);
      for (const id of ['catalog', 'inspector', 'activity', 'tools']) {
        await expectReachable(await grip(page, id).isVisible() ? grip(page, id) : restore(page, id), page);
      }
      if (view.width < 900) {
        await restore(page, 'inspector').click();
        await expectReachable(grip(page, 'inspector'), page);
      }
      for (const action of ['index', 'focus', 'reset']) {
        await expectReachable(page.locator(`[data-workspace-action="${action}"]`), page);
      }
      await expectReachable(page.locator('#zoom-ctrl [data-command-id="view.fit"]'), page);
      if (await page.locator('#minimap-canvas').isVisible()) {
        await expectReachable(page.locator('#minimap-canvas'), page);
      }
      await page.locator('[data-command-id="view.fit"]:visible').first().click();
      await capture(page, testInfo, `immersive-${view.name}`);
    });
  }
});

import { expect, test } from '@playwright/test';

const WIDE = { width: 1800, height: 900 };

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

async function openDocuments(page, count, { empty = false } = {}) {
  await page.evaluate(({ total, removeElements }) => {
    while (window.ravenroot.workspace.documents.length < total) {
      window.ravenroot.openDocument({ name: `layout-${window.ravenroot.workspace.documents.length + 1}.graphml` });
    }
    if (removeElements) window.ravenroot.workspace.documents.forEach(document_ => document_.cy.elements().remove());
  }, { total: count, removeElements: empty });
}

const visibleGeometry = page => page.evaluate(() => ({
  plan: window.ravenroot.workspaceLayout().plan,
  panes: [...document.querySelectorAll('#cy .doc-pane.doc-pane--shown')].map(pane => ({
    id: pane.dataset.documentId,
    column: Number(pane.dataset.workspaceColumn),
    row: Number(pane.dataset.workspaceRow),
    width: pane.getBoundingClientRect().width,
    height: pane.getBoundingClientRect().height,
  })),
}));

test.describe('workspace layout modes', () => {
  test.use({ viewport: WIDE });

  test.beforeEach(async ({ page }) => {
    await stubService(page);
    await page.goto('/');
  });

  test('projects deterministic non-square grids in workspace order', async ({ page }) => {
    await openDocuments(page, 5, { empty: true });
    await page.evaluate(() => window.ravenroot.setWorkspaceLayout('grid'));

    await expect.poll(async () => (await visibleGeometry(page)).panes.length).toBe(5);
    const geometry = await visibleGeometry(page);
    expect([geometry.plan.columns, geometry.plan.rows]).toEqual([3, 2]);
    expect(geometry.panes.map(pane => [pane.column, pane.row]))
      .toEqual([[1, 1], [2, 1], [3, 1], [1, 2], [2, 2]]);
    await expect(page.locator('#cy .workspace-splitter--vertical')).toHaveCount(2);
    await expect(page.locator('#cy .workspace-splitter--horizontal')).toHaveCount(1);
  });

  test('renders the measured 228px pane and falls back at 226px with the real overlays present', async ({ page }) => {
    await openDocuments(page, 2, { empty: true });
    await page.evaluate(() => {
      document.getElementById('dock').hidden = true;
      const wrap = document.getElementById('cy-wrap');
      wrap.style.flex = '0 0 457px';
      window.ravenroot.setWorkspaceLayout('vertical');
    });
    await expect.poll(async () => (await visibleGeometry(page)).panes.length).toBe(2);
    let geometry = await visibleGeometry(page);
    expect(geometry.panes.map(pane => Math.round(pane.height))).toEqual([228, 228]);
    expect(await page.locator('#minimap').evaluate(element => {
      const rect = element.getBoundingClientRect();
      return [rect.width, rect.height];
    })).toEqual([160, 100]);
    expect(await page.locator('#zoom-ctrl').evaluate(element => {
      const rect = element.getBoundingClientRect();
      return [rect.width, rect.height];
    })).toEqual([32, 104]);

    await page.evaluate(() => { document.getElementById('cy-wrap').style.flexBasis = '453px'; });
    await expect.poll(async () => (await visibleGeometry(page)).plan.visibility).toBe('active-only');
    geometry = await visibleGeometry(page);
    expect(geometry.panes).toHaveLength(1);
    expect((453 - 1) / 2).toBe(226); // mutation control: the forbidden candidate pane size.
  });

  test('keeps panes, canvases and both splitter axes stable across mode changes', async ({ page }) => {
    await openDocuments(page, 2);
    await page.evaluate(() => {
      document.querySelectorAll('#cy .doc-pane').forEach((pane, index) => {
        pane.dataset.stability = `pane-${index}`;
        pane.querySelector('.doc-canvas').dataset.stability = `canvas-${index}`;
      });
      window.ravenroot.setWorkspaceLayout('vertical');
    });
    const row = page.locator('#cy .workspace-splitter--horizontal');
    await expect(row).toHaveCount(1);
    await row.evaluate(element => { element.dataset.stability = 'row'; });
    await expect(row).toHaveAttribute('aria-orientation', 'horizontal');
    await expect(row).toHaveAttribute('aria-controls', /\S+ \S+/);

    const heights = () => page.evaluate(() => [...document.querySelectorAll('#cy .doc-pane.doc-pane--shown')]
      .map(pane => Math.round(pane.getBoundingClientRect().height)));
    const before = await heights();
    await row.focus();
    await page.keyboard.press('ArrowUp');
    await expect.poll(async () => (await heights())[0]).toBeLessThan(before[0]);
    await page.keyboard.press('Home');
    await expect.poll(async () => Math.abs((await heights())[0] - (await heights())[1])).toBeLessThanOrEqual(2);

    await page.evaluate(() => window.ravenroot.setWorkspaceLayout('horizontal'));
    await page.evaluate(() => window.ravenroot.setWorkspaceLayout('vertical'));
    await expect(page.locator('#cy .workspace-splitter--horizontal[data-stability="row"]')).toHaveCount(1);
    await expect(page.locator('#cy .doc-pane[data-stability]')).toHaveCount(2);
    await expect(page.locator('#cy .doc-canvas[data-stability]')).toHaveCount(2);
    expect(await page.locator('#cy .doc-canvas').evaluateAll(items => items.every(item => item.clientWidth && item.clientHeight)))
      .toBe(true);
    expect(await page.locator('#cy .doc-pane.doc-pane--shown').evaluateAll(panes => panes.map(pane =>
      [...pane.querySelectorAll('canvas')].reduce((painted, canvas) => {
        if (!canvas.width || !canvas.height) return painted;
        const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        for (let index = 3; index < pixels.length; index += 4) if (pixels[index]) painted += 1;
        return painted;
      }, 0)).every(count => count > 0))).toBe(true);
  });

  test('persists only mode and reset removes the descriptor and equalises shares', async ({ page }) => {
    await page.evaluate(() => window.ravenroot.setWorkspaceLayout('grid'));
    expect(await page.evaluate(() => localStorage.getItem('ravenroot.workspace-layout.v1')))
      .toBe('{"version":1,"mode":"grid"}');
    await page.reload();
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspaceLayout().mode)).toBe('grid');
    await page.evaluate(() => window.ravenroot.resetWorkspaceLayout());
    expect(await page.evaluate(() => ({
      stored: localStorage.getItem('ravenroot.workspace-layout.v1'),
      layout: window.ravenroot.workspaceLayout(),
    }))).toMatchObject({ stored: null, layout: { mode: 'horizontal', columnShares: [1], rowShares: [1] } });
  });

  test('clamps automatic refits to readable labels but leaves explicit Fit user-controlled', async ({ page }) => {
    await page.waitForTimeout(700);
    await page.evaluate(() => {
      const instance = window.ravenroot.activeDocument().cy;
      instance.stop();
      instance.nodes()[0].position({ x: 0, y: 200 });
      instance.nodes()[1].position({ x: 5000, y: 200 });
    });
    const fit = page.locator('[data-command-id="view.fit"]').first();
    await fit.click();
    const explicitZoom = await page.evaluate(() => window.ravenroot.activeDocument().cy.zoom());
    expect(explicitZoom).toBeLessThan(11 / 15);

    await page.evaluate(() => {
      const wrap = document.getElementById('cy-wrap');
      wrap.style.flex = '0 0 400px';
    });
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy.zoom()))
      .toBeGreaterThanOrEqual(11 / 15);

    await fit.click();
    expect(await page.evaluate(() => window.ravenroot.activeDocument().cy.zoom())).toBeLessThan(11 / 15);
  });

  test('keeps an asymmetric graph inside its renderer viewport without hiding a feasible pane', async ({ page }) => {
    await openDocuments(page, 2);
    await page.waitForTimeout(700);
    const ids = await page.evaluate(() => {
      const [first, second] = window.ravenroot.workspace.documents;
      first.cy.stop();
      first.cy.nodes()[0].position({ x: 0, y: 180 });
      first.cy.nodes()[1].position({ x: 1100, y: 180 });
      first.container.dataset.qaIdentity = 'wide-canvas';
      second.container.dataset.qaIdentity = 'active-canvas';
      window.__workspaceZeroResizes = [];
      [first, second].forEach(document_ => {
        const resize = document_.cy.resize.bind(document_.cy);
        document_.cy.resize = () => {
          if (!document_.container.clientWidth || !document_.container.clientHeight) {
            window.__workspaceZeroResizes.push({ id: document_.id, stack: new Error().stack });
          }
          return resize();
        };
      });
      document.getElementById('dock').hidden = true;
      document.getElementById('cy-wrap').style.flex = '0 0 400px';
      window.ravenroot.setWorkspaceLayout('grid');
      return [first.id, second.id];
    });

    await expect.poll(async () => (await visibleGeometry(page)).plan.visibility).toBe('all');
    await expect.poll(async () => (await visibleGeometry(page)).panes.map(pane => pane.id)).toEqual(ids);
    await expect(page.locator('#document-position')).toHaveText('2 of 2');
    await expect(page.locator('#cy .workspace-splitter--vertical:not([hidden])')).toHaveCount(1);

    await page.locator('#document-switcher').click();
    await page.locator(`[data-document-activate="${ids[0]}"]`).click();
    await expect.poll(async () => (await visibleGeometry(page)).panes.map(pane => pane.id)).toEqual(ids);
    expect(await page.evaluate(([firstId, secondId]) => {
      const first = window.ravenroot.workspace.find(firstId);
      const second = window.ravenroot.workspace.find(secondId);
      const canvas = first.container.querySelector('canvas');
      return {
        identities: [first.container.dataset.qaIdentity, second.container.dataset.qaIdentity],
        connected: first.container.isConnected && second.container.isConnected,
        zeroResizes: window.__workspaceZeroResizes,
        activeCanvasSize: [canvas.width, canvas.height],
      };
    }, ids)).toMatchObject({
      identities: ['wide-canvas', 'active-canvas'], connected: true, zeroResizes: [],
      activeCanvasSize: [expect.any(Number), expect.any(Number)],
    });
    expect(await page.evaluate(firstId => {
      const canvas = window.ravenroot.workspace.find(firstId).container.querySelector('canvas');
      return canvas.width > 0 && canvas.height > 0;
    }, ids[0])).toBe(true);
  });
});

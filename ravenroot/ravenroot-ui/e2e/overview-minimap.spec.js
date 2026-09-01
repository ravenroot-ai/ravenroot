import { expect, test } from '@playwright/test';

const WIDE = { width: 1800, height: 900 };

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

const snapshot = page => page.evaluate(() => window.ravenroot.minimapSnapshot());

async function waitForSnapshot(page) {
  await expect.poll(() => snapshot(page)).not.toBeNull();
  return snapshot(page);
}

async function waitForOpeningLayout(page) {
  await expect.poll(() => page.evaluate(() => Boolean(window.ravenroot.activeDocument().cy.scratch('_rrLayoutRunning')))).toBe(false);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function waitForAnimationFrames(page, count = 3) {
  await page.evaluate(frameCount => new Promise(resolve => {
    const next = remaining => requestAnimationFrame(() => remaining > 1 ? next(remaining - 1) : resolve());
    next(frameCount);
  }), count);
}

test.describe('shared active-document minimap', () => {
  test.use({ viewport: WIDE });

  test.beforeEach(async ({ page }) => {
    await stubService(page);
    await page.goto('/');
    await waitForSnapshot(page);
  });

  test('is a single labelled active-document viewport and coalesces pan repaints', async ({ page }) => {
    await expect(page.locator('#minimap')).toHaveCount(1);
    await expect(page.locator('#minimap-canvas')).toHaveAttribute('role', 'application');
    await expect(page.locator('#minimap-canvas')).toHaveAccessibleName(/Overview.*untitled\.graphml.*Design renderer.*arrow keys pan graph/i);
    // The default N8N layout is animated; isolate its legitimate position events from the pan burst.
    await waitForOpeningLayout(page);
    await page.evaluate(async () => {
      const cy = window.ravenroot.activeDocument().cy;
      cy.stop();
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
    const before = await waitForSnapshot(page);
    await page.evaluate(() => {
      const cy = window.ravenroot.activeDocument().cy;
      for (let index = 0; index < 20; index += 1) cy.panBy({ x: 3, y: -2 });
    });
    await expect.poll(async () => (await snapshot(page)).paintCount).toBeGreaterThan(before.paintCount);
    const after = await snapshot(page);
    expect(after.paintCount - before.paintCount).toBeLessThanOrEqual(1);
    expect(after.documentId).toBe(await page.evaluate(() => window.ravenroot.activeDocument().id));
    expect(after.rendererKind).toBe('cytoscape');
    for (const value of Object.values(after.viewport)) expect(Number.isFinite(value)).toBe(true);
  });

  test('maps huge negative asymmetric bounds to a finite clamped viewport and supports pointer cancellation', async ({ page }) => {
    await waitForOpeningLayout(page);
    await page.evaluate(() => {
      const cy = window.ravenroot.activeDocument().cy;
      cy.stop();
      const nodes = cy.nodes();
      nodes[0].position({ x: -1_000_000, y: -500_000 });
      nodes[1].position({ x: 4_000_000, y: 10_000 });
      nodes.emit('position');
    });
    await expect.poll(async () => (await snapshot(page)).contentBounds.x1).toBeLessThan(0);
    const before = await snapshot(page);
    expect(before.contentBounds.x1).toBeLessThan(0);
    expect(before.contentBounds.x2).toBeGreaterThan(1_000_000);
    for (const box of [before.contentBounds, before.visibleBounds, before.viewport, before.map]) {
      for (const value of Object.values(box)) expect(Number.isFinite(value)).toBe(true);
    }
    const canvas = page.locator('#minimap-canvas');
    const box = await canvas.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width + 40, box.y + box.height + 40);
    await page.mouse.up();
    await expect(page.locator('#minimap')).not.toHaveClass(/is-dragging/);
    await page.evaluate(() => {
      const canvas = document.getElementById('minimap-canvas');
      const rect = canvas.getBoundingClientRect();
      canvas.dispatchEvent(new PointerEvent('pointerdown', { pointerId: 71, button: 0, clientX: rect.left + 20, clientY: rect.top + 30 }));
      canvas.dispatchEvent(new PointerEvent('pointercancel', { pointerId: 71, clientX: rect.left + 20, clientY: rect.top + 30 }));
    });
    await expect(page.locator('#minimap')).not.toHaveClass(/is-dragging/);
  });

  test('follows the active document through horizontal, vertical, grid and active-only workspace states', async ({ page }) => {
    const ids = await page.evaluate(() => {
      const first = window.ravenroot.activeDocument().id;
      const second = window.ravenroot.openDocument({ name: 'orders.graphml' });
      return [first, second];
    });
    for (const mode of ['horizontal', 'vertical', 'grid']) {
      await page.evaluate(value => window.ravenroot.setWorkspaceLayout(value), mode);
      await expect.poll(async () => (await snapshot(page)).documentId).toBe(ids[1]);
      await expect(page.locator('#minimap')).toHaveAttribute('data-document-id', ids[1]);
      await expect(page.locator('#minimap-canvas')).toHaveAccessibleName(/orders\.graphml/i);
    }
    await page.evaluate(first => {
      document.getElementById('dock').hidden = true;
      document.getElementById('cy-wrap').style.flex = '0 0 453px';
      window.ravenroot.activateDocument(first);
    }, ids[0]);
    await expect.poll(async () => (await snapshot(page)).documentId).toBe(ids[0]);
    await expect(page.locator('#minimap')).toHaveAttribute('data-document-id', ids[0]);
  });

  test('supersedes an old-document frame when activation happens in the same task', async ({ page }) => {
    await waitForOpeningLayout(page);
    const ids = await page.evaluate(() => {
      const first = window.ravenroot.activeDocument().id;
      const second = window.ravenroot.openDocument({ name: 'current.graphml' });
      return [first, second];
    });
    await waitForOpeningLayout(page);
    await page.evaluate(([first, second]) => {
      for (const id of [first, second]) window.ravenroot.workspace.find(id).cy.stop();
      // Make the new owner's visible world smaller than its graph so an arrow-key pan has a real,
      // unclamped viewport change to prove against both renderer instances below.
      const current = window.ravenroot.workspace.find(second).cy;
      current.nodes()[0].position({ x: -600, y: 0 });
      current.nodes()[1].position({ x: 600, y: 0 });
      current.nodes().emit('position');
      current.zoom(2);
    }, ids);
    await waitForAnimationFrames(page);

    for (let iteration = 0; iteration < 5; iteration += 1) {
      await page.evaluate(first => window.ravenroot.activateDocument(first), ids[0]);
      await expect.poll(async () => (await snapshot(page)).documentId).toBe(ids[0]);
      const before = await snapshot(page);

      // This is the rejected ordering exactly: the old owner enqueues a viewport repaint, then the
      // active document changes synchronously before the browser can run that animation frame.
      await page.evaluate(([first, second]) => {
        window.ravenroot.workspace.find(first).cy.panBy({ x: 1, y: -1 });
        window.ravenroot.activateDocument(second);
      }, ids);
      await waitForAnimationFrames(page);

      const ownership = await page.evaluate(() => {
        const minimap = document.getElementById('minimap');
        const canvas = document.getElementById('minimap-canvas');
        const current = window.ravenroot.minimapSnapshot();
        return {
          activeId: window.ravenroot.activeDocument().id,
          snapshotId: current?.documentId,
          dataId: minimap.dataset.documentId,
          renderer: current?.rendererKind,
          dataRenderer: minimap.dataset.rendererKind,
          label: document.getElementById('minimap-label').textContent,
          accessibleName: canvas.getAttribute('aria-label'),
          paintCount: current?.paintCount,
        };
      });
      expect(ownership).toMatchObject({
        activeId: ids[1], snapshotId: ids[1], dataId: ids[1],
        renderer: 'cytoscape', dataRenderer: 'cytoscape',
      });
      expect(ownership.label).toContain('current.graphml');
      expect(ownership.accessibleName).toMatch(/Overview.*current\.graphml.*Design renderer/i);
      expect(ownership.paintCount - before.paintCount).toBeLessThanOrEqual(1);

      const pansBeforeKey = await page.evaluate(([first, second]) => ({
        old: window.ravenroot.workspace.find(first).cy.pan(),
        current: window.ravenroot.workspace.find(second).cy.pan(),
      }), ids);
      await page.locator('#minimap-canvas').focus();
      await page.keyboard.press('ArrowRight');
      await expect.poll(() => page.evaluate(second =>
        window.ravenroot.workspace.find(second).cy.pan().x, ids[1])).not.toBe(pansBeforeKey.current.x);
      const pansAfterKey = await page.evaluate(([first, second]) => ({
        old: window.ravenroot.workspace.find(first).cy.pan(),
        current: window.ravenroot.workspace.find(second).cy.pan(),
      }), ids);
      expect(pansAfterKey.old).toEqual(pansBeforeKey.old);
      expect(pansAfterKey.current.x).not.toBe(pansBeforeKey.current.x);
    }
  });

  test('uses the explicit keyboard route without leaking canvas arrows and returns focus on Escape', async ({ page }) => {
    const canvas = page.locator('#minimap-canvas');
    await canvas.focus();
    await page.evaluate(() => {
      const cy = window.ravenroot.activeDocument().cy;
      cy.zoom({ level: Math.min(cy.maxZoom(), Math.max(2, cy.zoom() * 4)), renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
      cy.center();
    });
    const before = await page.evaluate(() => window.ravenroot.activeDocument().cy.pan());
    await page.keyboard.press('ArrowRight');
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy.pan().x)).not.toBe(before.x);
    await page.keyboard.press('Home');
    await expect.poll(async () => (await snapshot(page)).paintCount).toBeGreaterThan(0);
    await page.keyboard.press('Escape');
    expect(await page.evaluate(() => document.activeElement?.classList.contains('doc-pane'))).toBe(true);
  });

  test('keeps one valid shared overview through Monitoring and a workspace splitter resize', async ({ page }) => {
    await page.locator('#btn-monitoring').click();
    await expect.poll(async () => (await snapshot(page)).rendererKind).toBe('elastic');
    const elasticId = await page.evaluate(() => window.ravenroot.activeDocument().id);
    await expect(page.locator('#minimap')).toHaveAttribute('data-renderer-kind', 'elastic');
    await page.evaluate(() => window.ravenroot.openDocument({ name: 'resize.graphml' }));
    const splitter = page.locator('#cy .workspace-splitter--vertical').first();
    await expect(splitter).toBeVisible();
    const box = await splitter.boundingBox();
    await page.mouse.move(box.x + 1, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + 60, box.y + box.height / 2);
    await page.mouse.up();
    await page.evaluate(id => window.ravenroot.activateDocument(id), elasticId);
    await expect.poll(async () => (await snapshot(page)).rendererKind).toBe('elastic');
    const current = await snapshot(page);
    expect(current.map.width).toBeGreaterThan(0);
    expect(current.viewport.width).toBeGreaterThan(0);
  });
});

import { expect, test } from '@playwright/test';

const PANELS = ['search', 'node-types', 'node-catalog', 'edge-types', 'graph-stats', 'inspector', 'assistant', 'activity'];
const SHORTENABLE = ['node-types', 'edge-types', 'node-catalog', 'search', 'graph-stats'];
const CATALOG = JSON.stringify([
  { behavior: 'alpha', displayName: 'Alpha template', category: 'core', description: 'Alpha', visualType: 'flow', agentic: false },
  { behavior: 'beta', displayName: 'Beta template', category: 'extension', description: 'Beta', visualType: 'agent', agentic: true },
]);

async function serveCatalog(page) {
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json; charset=utf-8', body: CATALOG,
  }));
  await page.route('**/v1/events', route => route.fulfill({ status: 204, body: '' }));
}

async function shorten(page, id) {
  await page.locator(`.panel[data-panel-id="${id}"] [data-action="panel-menu"]`).click();
  await page.locator('#panel-menu [data-menu-command="shorten"]').click();
  await expect(page.locator(`.panel[data-panel-id="${id}"]`)).toHaveAttribute('data-panel-state', 'short');
}

async function expectTooltipForFocus(page, locator, text) {
  await locator.focus();
  const tooltip = page.locator('#visual-tooltip');
  await expect(tooltip).toBeVisible();
  await expect(tooltip).toHaveText(text);
  await expect(locator).toHaveAttribute('aria-describedby', /(?:^|\s)visual-tooltip(?:\s|$)/);
  return tooltip;
}

test('shows the canonical outcome suffix in the edge legend', async ({ page }) => {
  await serveCatalog(page);
  await page.goto('/');

  const outcomeLegend = page.locator('[data-legend-kind="edge"][data-legend-type="outcome"]');
  await expect(outcomeLegend).toBeVisible();
  await expect(outcomeLegend).toHaveText('*_OUTCOME');
  await expect(outcomeLegend).toHaveAttribute('aria-label', 'Filter edge type: *_OUTCOME');
});

test('inventories every panel family without native-title or unnamed glyph fallbacks', async ({ page }) => {
  await serveCatalog(page);
  await page.goto('/');
  await page.waitForSelector('[data-catalog-add="alpha"]');

  for (const id of PANELS) {
    const panel = page.locator(`.panel[data-panel-id="${id}"]`);
    await expect(panel.locator('[data-action="panel-menu"]')).toHaveAttribute('data-tooltip', /panel options$/);
    await expect(panel.locator('[data-action="panel-close"]')).toHaveAttribute('data-tooltip', /^Close .+ panel$/);
  }
  await expect(page.locator('.panel-hd [title]')).toHaveCount(0);
  await expect(page.locator('.rail [title]')).toHaveCount(0);
  // There are 11: two rails x (toggle + index) = 4, plus one identity mark per panel — five on
  // the left, now two on the right. The mark is present whether the panel is open or closed,
  // which is what makes a closed panel reachable at all.
  await expect(page.locator('.rail-btn[data-tooltip]')).toHaveCount(11);

  const legend = page.locator('[data-legend-kind]');
  await expect(legend).toHaveCount(20);
  expect(await legend.evaluateAll(items => items.every(item =>
    item.tagName === 'BUTTON' && item.tabIndex === 0 && item.dataset.tooltip && item.getAttribute('aria-label')))).toBe(true);
  await expect(page.locator('[data-catalog-add][data-tooltip]')).toHaveCount(2);
  await expect(page.locator('#search-inp')).toHaveAttribute('aria-label', 'Search nodes');

  const boundaries = page.locator('[data-splitter-kind="workspace"]:not([hidden]), [data-splitter-kind="panel"]:not([hidden])');
  expect(await boundaries.evaluateAll(items => items.every(item =>
    item.dataset.tooltip || item.dataset.tooltipExempt === 'disabled-boundary'))).toBe(true);

  const unclassified = () => page.evaluate(() => [...document.querySelectorAll(
    '#main .panel button, #main .panel input, #main .panel select, #main .panel textarea, '
      + '#main .rail button, #main [data-splitter-kind="workspace"], #main [data-splitter-kind="panel"]',
  )].filter(control => control.getClientRects().length && control.getAttribute('aria-hidden') !== 'true')
    .filter(control => !control.matches('[data-tooltip], [data-tooltip-short], [data-tooltip-disabled]')
      && !control.closest('[data-tooltip-exempt]'))
    .map(control => control.id || control.dataset.action || control.dataset.programOperation || control.outerHTML));
  expect(await unclassified()).toEqual([]);

  // Re-run the same inventory after the Inspector replaces its body with its largest dynamic form.
  await page.locator('#btn-new').click();
  await page.locator('#btn-modify').click();
  await page.evaluate(() => {
    const instance = window.cy;
    const node = instance.nodes()[0];
    node.emit('tap');
    instance.$(node).select();
  });
  await page.waitForSelector('#node-editor');
  expect(await unclassified()).toEqual([]);
});

test('uses one delayed hover/immediate-focus tooltip with transient description and complete dismissal', async ({ page }) => {
  await page.goto('/');
  const control = page.locator('[data-panel-id="search"] [data-action="panel-menu"]');
  const tooltip = page.locator('#visual-tooltip');

  const timing = await control.evaluate(async element => {
    const tip = document.getElementById('visual-tooltip');
    element.dispatchEvent(new PointerEvent('pointerover', { bubbles: true }));
    await new Promise(resolve => setTimeout(resolve, 350));
    const hiddenBeforeDelay = tip.hidden;
    await new Promise(resolve => setTimeout(resolve, 150));
    return { hiddenBeforeDelay, hiddenAfterDelay: tip.hidden };
  });
  expect(timing).toEqual({ hiddenBeforeDelay: true, hiddenAfterDelay: false });
  await expect(control).toHaveAttribute('aria-describedby', /visual-tooltip/);

  await control.evaluate(element => element.dispatchEvent(new PointerEvent('pointerout', { bubbles: true })));
  await expect(tooltip).toBeHidden();
  await control.hover();
  await expect(tooltip).toBeVisible({ timeout: 1_000 });

  const tooltipBox = await tooltip.boundingBox();
  await page.mouse.move(tooltipBox.x + tooltipBox.width / 2, tooltipBox.y + tooltipBox.height / 2);
  await expect(tooltip).toBeVisible();
  await page.mouse.move(700, 400);
  await expect(tooltip).toBeHidden();
  await expect(control).not.toHaveAttribute('aria-describedby', /visual-tooltip/);

  await expectTooltipForFocus(page, control, 'Search panel options');
  await page.keyboard.press('Escape');
  await expect(tooltip).toBeHidden();
  await expect(control).not.toHaveAttribute('aria-describedby', /visual-tooltip/);
  await expect(control).toBeFocused();
});

test('keeps focused tooltips inside wide and narrow viewports without covering their trigger', async ({ page }) => {
  for (const viewport of [{ width: 1920, height: 900 }, { width: 900, height: 700 }]) {
    await page.setViewportSize(viewport);
    await page.goto('/');
    const controls = [
      page.locator('#sidebar .rail-toggle'),
      page.locator('[data-panel-id="activity"] [data-action="panel-close"]'),
      page.locator('[data-layout-splitter="right"]'),
    ];
    for (const control of controls) {
      await control.focus();
      await expect(page.locator('#visual-tooltip')).toBeVisible();
      const geometry = await page.evaluate(element => {
        const trigger = element.getBoundingClientRect();
        const tip = document.getElementById('visual-tooltip').getBoundingClientRect();
        const overlap = Math.max(0, Math.min(trigger.right, tip.right) - Math.max(trigger.left, tip.left))
          * Math.max(0, Math.min(trigger.bottom, tip.bottom) - Math.max(trigger.top, tip.top));
        return { left: tip.left, top: tip.top, right: tip.right, bottom: tip.bottom, overlap };
      }, await control.elementHandle());
      expect(geometry.left).toBeGreaterThanOrEqual(8);
      expect(geometry.top).toBeGreaterThanOrEqual(8);
      expect(geometry.right).toBeLessThanOrEqual(viewport.width - 8);
      expect(geometry.bottom).toBeLessThanOrEqual(viewport.height - 8);
      expect(geometry.overlap).toBe(0);
      await page.keyboard.press('Escape');
    }
  }
});

test('keeps action and identity available through every load-bearing Shorten state', async ({ page }) => {
  await page.setViewportSize({ width: 1180, height: 800 });
  await serveCatalog(page);
  await page.goto('/');
  await page.waitForSelector('[data-catalog-add="alpha"]');
  for (const id of SHORTENABLE) await shorten(page, id);

  await expectTooltipForFocus(page, page.locator('#li-node-start'), 'Filter node type: Start');
  await page.keyboard.press('Escape');
  await expectTooltipForFocus(page, page.locator('#li-edge-failed'), 'Filter edge type: FAILED');
  await page.keyboard.press('Escape');
  await expectTooltipForFocus(page, page.locator('[data-catalog-add="alpha"]'), 'Add Alpha template · core');
  await page.keyboard.press('Escape');

  for (const id of SHORTENABLE) {
    await expect(page.locator(`.panel[data-panel-id="${id}"]`)).toHaveAttribute('data-panel-state', 'short');
    await expect(page.locator(`.panel[data-panel-id="${id}"] [data-action="panel-menu"]`))
      .toHaveAttribute('data-tooltip', /panel options$/);
  }
  await expect(page.locator('#visual-tooltip')).toHaveCount(1);
});

test('keeps compact Search and menu names outside the one-cell owner', async ({ page }) => {
  await page.setViewportSize({ width: 1180, height: 800 });
  await serveCatalog(page);
  await page.goto('/');
  await page.waitForSelector('[data-catalog-add="alpha"]');
  for (const id of SHORTENABLE) await shorten(page, id);
  await expect(page.locator('#sidebar')).toHaveClass(/zone--compact/);

  const search = page.locator('#search-inp');
  const tip = await expectTooltipForFocus(page, search, 'Search nodes');
  const searchGeometry = await page.evaluate(() => {
    const owner = document.querySelector('[data-panel-id="search"]').getBoundingClientRect();
    const tooltip = document.getElementById('visual-tooltip').getBoundingClientRect();
    return { ownerRight: owner.right, tipLeft: tooltip.left, viewportRight: innerWidth - 8 };
  });
  expect(searchGeometry.tipLeft).toBeGreaterThanOrEqual(searchGeometry.ownerRight);
  expect((await tip.boundingBox()).x + (await tip.boundingBox()).width)
    .toBeLessThanOrEqual(searchGeometry.viewportRight);

  const menu = page.locator('[data-panel-id="search"] [data-action="panel-menu"]');
  await expectTooltipForFocus(page, menu, 'Search panel options');
  await menu.click();
  await expect(tip).toBeHidden();
  await expect(menu).not.toHaveAttribute('aria-describedby', /visual-tooltip/);
  await expect(page.locator('#panel-menu [data-menu-command="lengthen"]')).toBeVisible();
  await expect(page.locator('#panel-menu [data-menu-command="close"]')).toBeVisible();
  await expect(page.locator('[data-panel-id="search"] .panel-title')).toHaveText('Search');
});

test('keeps an unavailable zone control focusable and gates pointer and keyboard activation', async ({ page }) => {
  await page.goto('/');
  for (const id of ['search', 'node-types', 'node-catalog', 'edge-types', 'graph-stats']) {
    await page.locator(`.panel[data-panel-id="${id}"] [data-action="panel-close"]`).click();
  }
  const toggle = page.locator('#sidebar .rail-toggle');
  await expect(toggle).toHaveAttribute('aria-disabled', 'true');
  expect(await toggle.evaluate(element => element.tabIndex)).toBe(0);
  await expectTooltipForFocus(page, toggle, 'No open panels in the left toolbox; use Panels to reopen one');
  const before = await toggle.getAttribute('aria-expanded');
  await toggle.click({ force: true });
  await expect(toggle).toHaveAttribute('aria-expanded', before);
  await toggle.focus();
  await page.keyboard.press('Enter');
  await expect(toggle).toHaveAttribute('aria-expanded', before);
  await page.keyboard.press('Space');
  await expect(toggle).toHaveAttribute('aria-expanded', before);
});

test('central gating also blocks direct program-lifecycle handlers while exposing their reason', async ({ page }) => {
  const programCatalog = JSON.stringify([{
    behavior: 'program', displayName: 'Governed program', category: 'core', description: 'Program',
    visualType: 'flow', agentic: false, capabilities: [], properties: [],
  }]);
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json; charset=utf-8', body: programCatalog,
  }));
  await page.route('**/v1/events', route => route.fulfill({ status: 204, body: '' }));
  const programRequests = [];
  page.on('request', request => {
    if (request.url().includes('/program')) programRequests.push(`${request.method()} ${request.url()}`);
  });
  await page.goto('/');
  await page.locator('#btn-new').click();
  await page.locator('#btn-modify').click();
  await page.evaluate(() => {
    const host = document.querySelector('.doc-canvas') || document.getElementById('cy');
    const instance = host?._cyreg?.cy || window.cy;
    const node = instance.nodes()[0];
    node.emit('tap');
    instance.$(node).select();
  });
  await page.waitForSelector('#node-editor');
  await page.locator('#node-editor select[name="catalogBehavior"]').selectOption('program');

  const validate = page.locator('[data-program-operation="validate"]');
  await expect(validate).toHaveAttribute('aria-disabled', 'true');
  await validate.scrollIntoViewIfNeeded();
  await page.waitForTimeout(50);
  await expectTooltipForFocus(page, validate, 'Create the artifact first');
  await validate.click({ force: true });
  await validate.focus();
  await page.keyboard.press('Enter');
  await page.keyboard.press('Space');
  await page.waitForTimeout(100);
  expect(programRequests).toEqual([]);
  await expect(page.locator('.program-status')).toHaveText('No artifact created.');
});

test('dismisses across panel lifecycle and honors reduced motion without duplicating the singleton', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  const close = page.locator('[data-panel-id="node-types"] [data-action="panel-close"]');
  await expectTooltipForFocus(page, close, 'Close Node Types panel');
  await expect(page.locator('#visual-tooltip')).toHaveCSS('transition-duration', '0s');
  await close.click();
  await expect(page.locator('#visual-tooltip')).toBeHidden();
  await expect(close).not.toHaveAttribute('aria-describedby', /visual-tooltip/);
  await page.locator('#sidebar [data-rail-panel="node-types"]').click();
  await expect(page.locator('[data-panel-id="node-types"]')).toBeVisible();

  for (let cycle = 0; cycle < 3; cycle += 1) {
    const menu = page.locator('[data-panel-id="node-types"] [data-action="panel-menu"]');
    await expectTooltipForFocus(page, menu, 'Node Types panel options');
    await menu.click();
    await expect(page.locator('#visual-tooltip')).toBeHidden();
    await page.keyboard.press('Escape');
  }
  await expect(page.locator('#visual-tooltip')).toHaveCount(1);
  expect(await page.locator('[aria-describedby~="visual-tooltip"]').count()).toBe(0);
});

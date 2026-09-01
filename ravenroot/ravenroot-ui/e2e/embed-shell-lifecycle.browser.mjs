import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

async function readyViewer(page) {
  await page.goto('/');
  await expect.poll(async () => {
    const frame = page.frames().find(candidate => new URL(candidate.url()).pathname === '/viewer');
    return frame ? frame.evaluate(() => Boolean(window.embedShellFixture)) : false;
  }).toBe(true);
  const viewer = page.frames().find(candidate => new URL(candidate.url()).pathname === '/viewer');
  await viewer.evaluate(() => window.embedShellFixture.show('ready'));
  await expect(viewer.locator('#shell')).toHaveAttribute('data-viewer-state', 'ready');
  return viewer;
}

test('native Tab and Shift+Tab leave the iframe without a messaging protocol', async ({ page }) => {
  const viewer = await readyViewer(page);

  await viewer.locator('#focus-after').focus();
  await page.keyboard.press('Tab');
  await expect(page.locator('#parent-after')).toBeFocused();

  await viewer.locator('#focus-before').focus();
  await page.keyboard.press('Shift+Tab');
  await expect(page.locator('#parent-before')).toBeFocused();
});

test('the ready shell and relationship alternative have no axe violations', async ({ page }) => {
  const viewer = await readyViewer(page);
  await expect(viewer.locator('#alternative')).toContainText('Connects to finish');
  await expect(viewer.locator('#alternative')).toContainText('Receives from start');

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations).toEqual([]);
});

test('mobile touch remains bounded and local controls meet the target floor', async ({ browser, baseURL }) => {
  const context = await browser.newContext({
    baseURL,
    hasTouch: true,
    isMobile: true,
    viewport: { width: 360, height: 740 },
  });
  try {
    const page = await context.newPage();
    const viewer = await readyViewer(page);
    const action = viewer.locator('#local-action');
    const box = await action.boundingBox();
    expect(box.width).toBeGreaterThanOrEqual(48);
    expect(box.height).toBeGreaterThanOrEqual(48);
    expect(await viewer.evaluate(() => ({
      coarse: matchMedia('(pointer: coarse)').matches,
      touchPoints: navigator.maxTouchPoints,
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
    }))).toEqual({ coarse: true, touchPoints: 1, overflow: false });

    await action.tap();
    expect(await viewer.evaluate(() => window.embedShellFixture.activations)).toBe(1);
  } finally {
    await context.close();
  }
});

test('print hides interactive chrome while preserving the structured alternative', async ({ page }) => {
  const viewer = await readyViewer(page);
  await page.emulateMedia({ media: 'print' });

  expect(await viewer.evaluate(() => ({
    controls: getComputedStyle(document.querySelector('.controls')).display,
    canvas: getComputedStyle(document.querySelector('.canvas')).display,
    status: getComputedStyle(document.querySelector('#status')).display,
    before: getComputedStyle(document.querySelector('#focus-before')).display,
    alternative: getComputedStyle(document.querySelector('.alternative')).display,
    text: document.querySelector('#alternative').textContent,
  }))).toEqual({
    controls: 'none',
    canvas: 'none',
    status: 'none',
    before: 'none',
    alternative: 'block',
    text: 'Start node startConnects to finishEnd node finishReceives from start',
  });
});

test('the maximum fixture uses bounded routes, yields, and cleans up cancellation', async ({ page }) => {
  await page.goto('/');
  await expect.poll(async () => {
    const frame = page.frames().find(candidate => new URL(candidate.url()).pathname === '/viewer');
    return frame ? frame.evaluate(() => Boolean(window.embedShellFixture)) : false;
  }).toBe(true);
  const viewer = page.frames().find(candidate => new URL(candidate.url()).pathname === '/viewer');

  const result = await viewer.evaluate(() => window.embedShellFixture.stressAndCancel());

  expect(result.routeStrategy).toBe('simple');
  expect(result.elasticAvailable).toBe(false);
  expect(result.readinessElapsed).toBeLessThan(5_000);
  expect(result.pulseObserved).toBe(true);
  expect(result.state).toBe('destroyed');
  expect(result.alternativeChildren).toBe(0);
  expect(result.detachedNodes).toBe(2_000);
  expect(result.detachedRelationships).toBe(10_000);
  expect(result.focusBoundariesRemoved).toBe(true);
});

test('a two-node/5000-edge graph stays responsive and cannot become READY after deadline', async ({ page }) => {
  await page.goto('/');
  await expect.poll(async () => {
    const frame = page.frames().find(candidate => new URL(candidate.url()).pathname === '/viewer');
    return frame ? frame.evaluate(() => Boolean(window.embedShellFixture)) : false;
  }).toBe(true);
  const viewer = page.frames().find(candidate => new URL(candidate.url()).pathname === '/viewer');

  const result = await viewer.evaluate(() => window.embedShellFixture.parallelEdgeDeadline());

  expect(result.routeStrategy).toBe('simple');
  expect(result.readinessElapsed).toBeLessThan(5_000);
  expect(result.pulseObserved).toBe(true);
  expect(result.stateAfterDeadline).toBe('error');
  expect(result.alternativeAfterDeadline).toBe(0);
  expect(result.stateAfterDestroy).toBe('destroyed');
  expect(result.detachedNodes).toBe(2);
  expect(result.detachedRelationships).toBe(10_000);
  expect(result.focusBoundariesRemoved).toBe(true);
});

import { expect, test } from '@playwright/test';

import { logSummary, scanForViolations, summarizeViolations } from './accessibility-helpers.mjs';
import { UI_ORIGIN } from './ports.mjs';

async function openNodeInspector(page) {
  await page.goto('/');
  await page.locator('#btn-modify').click();
  await page.evaluate(() => { window.cy.getElementById('dosomething').select(); });
  await expect(page.locator('#node-editor')).toBeVisible();
}

test('mouse help keeps the active edit focused, allows one open topic, and dismisses outside without an edit', async ({ page }) => {
  await openNodeInspector(page);
  const name = page.locator('#node-editor input[name="name"]');
  const catalogHelp = page.getByRole('button', { name: 'Help: Catalog type' });
  const natureHelp = page.getByRole('button', { name: 'Help: Runtime nature' });
  const popover = page.locator('#contextual-help-popover');
  const depth = await page.evaluate(() => window.ravenroot.activeDocument().history.depth());

  // Stable catalog prose is absent from the persistent form, while current runtime state remains
  // immediately readable without opening help.
  await expect(page.locator('#node-editor')).not.toContainText('Unknown behavior names are valid');
  await expect(page.locator('#node-editor .nature-state')).toContainText(/Fixed default|Effective:/);
  expect(await page.locator('#node-editor [data-contextual-help]').count()).toBeGreaterThan(3);

  await name.focus();
  await catalogHelp.click();
  await expect(name).toBeFocused();
  await expect(popover).toBeVisible();
  await expect(catalogHelp).toHaveAttribute('aria-expanded', 'true');
  await expect(catalogHelp).toHaveAttribute('aria-controls', 'contextual-help-popover');
  await expect(popover.locator('[data-contextual-help-heading]')).toHaveText('Catalog type');
  await expect(popover.locator('[data-contextual-help-body]')).toContainText('Unknown behavior names are valid');

  await natureHelp.click();
  await expect(name).toBeFocused();
  await expect(catalogHelp).toHaveAttribute('aria-expanded', 'false');
  await expect(natureHelp).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('[data-contextual-help][aria-expanded="true"]')).toHaveCount(1);
  await expect(popover.locator('[data-contextual-help-heading]')).toHaveText('Runtime nature');

  // The already-focused field is an outside target. Dismissal observes this pointerdown but does
  // not cancel it, restore some other focus target, or create a graph edit of its own.
  await name.click();
  await expect(popover).toBeHidden();
  await expect(name).toBeFocused();
  expect(await page.evaluate(() => window.ravenroot.activeDocument().history.depth())).toBe(depth);
});

test('Enter and Space open the same non-modal surface; Escape and Close return focus predictably', async ({ page }) => {
  await openNodeInspector(page);
  const trigger = page.getByRole('button', { name: 'Help: Catalog type' });
  const popover = page.locator('#contextual-help-popover');

  await trigger.focus();
  await trigger.press('Enter');
  await expect(popover).toBeFocused();
  await popover.press('Escape');
  await expect(popover).toBeHidden();
  await expect(trigger).toBeFocused();
  await expect(trigger).toHaveAttribute('aria-expanded', 'false');

  await trigger.press('Space');
  await expect(popover).toBeFocused();
  await popover.getByRole('button', { name: 'Close contextual help' }).click();
  await expect(popover).toBeHidden();
  await expect(trigger).toBeFocused();
});

test('touch help stays viewport-safe in a narrow layout and exposes the same accessible content', async ({ browser }, testInfo) => {
  const context = await browser.newContext({ baseURL: UI_ORIGIN, hasTouch: true,
    viewport: { width: 360, height: 640 } });
  const page = await context.newPage();
  try {
    await openNodeInspector(page);
    const name = page.locator('#node-editor input[name="name"]');
    const trigger = page.getByRole('button', { name: 'Help: Catalog type' });
    await name.focus();
    await trigger.tap();
    await expect(name).toBeFocused();

    const popover = page.locator('#contextual-help-popover');
    await expect(popover).toBeVisible();
    const box = await popover.boundingBox();
    expect(box.x).toBeGreaterThanOrEqual(8);
    expect(box.y).toBeGreaterThanOrEqual(8);
    expect(box.x + box.width).toBeLessThanOrEqual(352);
    expect(box.y + box.height).toBeLessThanOrEqual(632);

    const results = await scanForViolations(page);
    const touchingHelp = results.violations.filter(violation => violation.nodes.some(node =>
      node.target.some(target => target.includes('contextual-help'))));
    const summary = summarizeViolations({ ...results, violations: touchingHelp });
    await testInfo.attach('axe-contextual-help-narrow-touch.json', {
      body: JSON.stringify({ summary, raw: touchingHelp }, null, 2),
      contentType: 'application/json',
    });
    logSummary('contextual-help-narrow-touch', summary);
    // Accessibility remains report-only in this repository; semantic and viewport behavior above
    // are the feature gate, while the scan result is durable evidence for the established baseline.
    expect(Array.isArray(results.violations)).toBe(true);
  } finally {
    await context.close();
  }
});

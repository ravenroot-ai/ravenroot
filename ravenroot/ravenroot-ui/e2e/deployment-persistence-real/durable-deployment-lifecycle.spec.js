import { join } from 'node:path';

import { expect, test } from '@playwright/test';

const ORIGIN = process.env.RAVENROOT_DEPLOYMENT_PERSISTENCE_ORIGIN;
const OUTPUT_DIR = process.env.RAVENROOT_DEPLOYMENT_PERSISTENCE_OUTPUT_DIR || '.';

test('legacy Stop is 400 and the corrected UI completes the persisted lifecycle', async ({ page }) => {
  if (!ORIGIN) throw new Error('the JVM harness supplies the real server origin');
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await page.goto(`${ORIGIN}/`);
  await expect(page.locator('#runtime-connection')).toHaveClass(/connected/, { timeout: 30_000 });

  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Deployments…' }).click();
  await page.locator('#deployment-id-input').fill('browser-persisted');
  await page.locator('#deployment-register').click();

  const item = page.locator('#deployment-list .deployment-item', { hasText: 'browser-persisted' });
  await expect(item.locator('.deployment-state')).toHaveText('Ready', { timeout: 30_000 });
  await expect(item).toContainText('generation 1');

  const legacyStop = await page.evaluate(async () => {
    const response = await fetch('/v1/deployments/browser-persisted/stop', { method: 'POST' });
    return { status: response.status, body: await response.json() };
  });
  expect(legacyStop.status).toBe(400);
  expect(legacyStop.body.code).toBe('INVALID_REQUEST');

  page.once('dialog', dialog => dialog.accept('browser stop proof'));
  await item.locator('[data-deployment-action="stop"]').click();
  await expect(item.locator('.deployment-state')).toHaveText('Stopped', { timeout: 30_000 });
  await expect(item).toContainText('generation 2');

  await item.locator('[data-deployment-action="start"]').click();
  await expect(item.locator('.deployment-state')).toHaveText('Ready', { timeout: 30_000 });
  await expect(item).toContainText('generation 3');

  await item.locator('[data-deployment-action="restart"]').click();
  await expect(item.locator('.deployment-state')).toHaveText('Ready', { timeout: 30_000 });
  await expect(item).toContainText('generation 4');

  // Restart above advances one extra generation, so use a separate terminal command check only after
  // proving all four controls. The JVM assertion reads the resulting persisted terminal generation.
  let prompt = 0;
  page.on('dialog', async dialog => {
    if (dialog.type() === 'confirm') await dialog.accept();
    else if (prompt++ === 0) await dialog.accept('CANCEL_IN_FLIGHT');
    else await dialog.accept('browser persistence proof');
  });
  await item.locator('[data-deployment-action="undeploy"]').click();
  await expect(item).toHaveCount(0, { timeout: 30_000 });
  expect(await page.evaluate(() => fetch('/v1/deployments/browser-persisted').then(response => response.status)))
    .toBe(404);
  await page.screenshot({ path: join(OUTPUT_DIR, 'durable-deployment-terminal.png'), fullPage: true });
  expect(errors).toEqual([]);
});

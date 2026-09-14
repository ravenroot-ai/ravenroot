import { expect, test } from '@playwright/test';

const processId = '11111111-1111-4111-8111-111111111111';
const jobId = '22222222-2222-4222-8222-222222222222';
const artifactId = '33333333-3333-4333-8333-333333333333';

test('runner workbench exposes fenced uncertainty and renders retained evidence as text', async ({ page }) => {
  const operations = [];
  const resolutions = [];
  await page.route('**/v1/runner-plane/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname;
    let body = { items: [] };
    if (path.endsWith('/' + processId)) body = {
      workspaceId: 'workspace', runnerId: 'designated', revision: 17, jobs: [{
        runnerJobId: jobId, command: 'read', state: 'COMPLETED', fence: 7,
        definition: 'researcher', definitionVersion: 2, readOnly: true, continuationUncertain: true,
        artifacts: [{ artifactId, kind: 'STDERR', sizeBytes: 12, sha256: 'a'.repeat(64) }],
      }, { runnerJobId: '44444444-4444-4444-8444-444444444444', command: 'implement',
        state: 'UNKNOWN', fence: 2, definition: 'developer', definitionVersion: 1, artifacts: [] }],
    };
    if (path.endsWith('/artifacts/' + artifactId)) body = { content: '<img src=x onerror="window.runnerEscaped=true">', truncated: false };
    if (request.method() === 'POST') operations.push(path);
    if (path.endsWith('/resolve-continuation')) {
      resolutions.push(request.postDataJSON()); body = { revision: 18, resolution: 'ACKNOWLEDGE' };
    }
    if (path.endsWith('/health')) body = { items: [], pageMetrics: { UNKNOWN: 1 }, nextCursor: null };
    if (path.endsWith('/audit')) body = { items: [{ type: 'RUNNER_JOB_CONTINUATION_UNCERTAIN', fence: 7 }], nextOffset: 12 };
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.goto('/');
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Agents and runners…' }).click();
  const dialog = page.locator('#runner-dialog');
  await expect(dialog).toBeVisible();
  expect(await dialog.evaluate(node => node.matches(':modal'))).toBe(true);
  await dialog.getByLabel('Process instance ID').fill(processId);
  await dialog.getByRole('button', { name: 'Inspect workspace' }).click();
  await expect(dialog).toContainText('Successor delivery is uncertain');
  await expect(dialog).toContainText('Effect unknown');
  await dialog.getByRole('button', { name: /^STDERR/ }).click();
  await expect(dialog.getByLabel('Bounded artifact preview')).toContainText('<img');
  expect(await page.evaluate(() => window.runnerEscaped)).toBeUndefined();
  await expect(dialog.locator('img')).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Reconcile liveness' }).click();
  await expect.poll(() => operations.length).toBe(1);
  expect(operations[0]).toMatch(/\/reconcile$/);
  await dialog.getByRole('button', { name: 'Next audit page' }).click();
  await expect(dialog.getByLabel('Runner health and audit page')).toContainText('RUNNER_JOB_CONTINUATION_UNCERTAIN');
  await dialog.getByRole('button', { name: 'Acknowledge complete successors' }).click();
  await expect(dialog).toContainText('Confirm graph-state review before resolving');
  expect(resolutions).toEqual([]);
  await dialog.getByLabel('I reviewed graph state and understand the selected disposition.').check();
  await dialog.getByRole('button', { name: 'Acknowledge complete successors' }).click();
  await expect.poll(() => resolutions).toEqual([{ expectedRevision: 17, resolution: 'ACKNOWLEDGE' }]);
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(dialog).not.toBeVisible();
});

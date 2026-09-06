import { expect, test } from '@playwright/test';

import { logSummary, scanForViolations, summarizeViolations } from '../accessibility-helpers.mjs';

const CONTROL_ORIGIN = process.env.RAVENROOT_HUMAN_TASK_CONFIRMATION_CONTROL_ORIGIN;
const FIXTURE_TOKEN = process.env.RAVENROOT_HUMAN_TASK_CONFIRMATION_FIXTURE_TOKEN;
const NODE_ID = 'human-confirmation';

function requireHarnessEnvironment() {
  if (!CONTROL_ORIGIN || !FIXTURE_TOKEN) {
    throw new Error('The Human Task confirmation process harness environment is required');
  }
}

async function startPhase(request, phase) {
  const response = await request.post(`${CONTROL_ORIGIN}/start?phase=${phase}`);
  expect(response.status(), `start ${phase}`).toBe(200);
  const ready = await response.json();
  expect(ready).toMatchObject({ phase, deploymentId: 'human-task-e2e', nodeId: NODE_ID,
    locators: [
      { taskId: expect.any(String), generation: expect.any(Number) },
      { taskId: expect.any(String), generation: expect.any(Number) },
    ] });
  expect(new Set(ready.locators.map(locator => locator.taskId)).size).toBe(2);
  expect(new URL(ready.serviceOrigin).origin).toBe(ready.serviceOrigin);
  return ready;
}

async function stopPhase(request) {
  const response = await request.post(`${CONTROL_ORIGIN}/stop`);
  expect(response.status()).toBe(204);
}

async function authenticate(page) {
  await page.locator('#access-token').fill(FIXTURE_TOKEN);
  await page.locator('#btn-authenticate').click();
  await expect(page.locator('#runtime-connection')).toHaveClass(/connected/, { timeout: 20_000 });
  await expect(page.locator('#node-catalog [data-catalog-add="human-task"]')).toBeVisible();
}

async function createLocalHumanTaskNode(page) {
  await page.locator('#btn-new').click();
  await page.locator('#btn-modify').click();
  await page.locator('#node-catalog [data-catalog-add="human-task"]').click();
  await page.locator('#node-editor input[name="id"]').fill(NODE_ID);
  await page.locator('[data-catalog-property="title"]').fill('Confirm durable restart');
  await page.locator('[data-catalog-property="confirmationPresentationVersion"]').selectOption('1');
  await page.locator('#node-editor button[type="submit"]').click();
  await expect.poll(() => page.evaluate(nodeId => Boolean(
    window.ravenroot.activeDocument().graph.nodeMap[nodeId]), NODE_ID)).toBe(true);
  const properties = await page.evaluate(nodeId =>
    window.ravenroot.activeDocument().graph.nodeMap[nodeId].properties, NODE_ID);
  expect(properties).toMatchObject({
    confirmationPresentationVersion: '1',
    confirmationPrompt: 'Confirm this task.',
    confirmationActions: 'RESOLVE,DENY,CANCEL',
    responseContentType: 'application/vnd.ravenroot.payload+json',
    responseSchema: 'ravenroot.human-task.response',
    responseSchemaVersion: '1',
    responseKind: 'MAP',
  });
}

async function registerAuthoredGraph(page) {
  const deploymentId = 'ui-authored-confirmation-probe';
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Deployments…' }).click();
  await page.locator('#deployment-id-input').fill(deploymentId);
  await page.locator('#deployment-register').click();
  const deployment = page.locator('.deployment-item', { hasText: deploymentId });
  // The probe graph has no inbound source. REGISTERED is its successful admitted state; the
  // server-pinned graph version on the enabled context action is the proof that parsing and
  // behavior preflight accepted the generic editor's serialized node.
  await expect(deployment).toContainText('Registered', { timeout: 20_000 });
  await expect(deployment.locator('[data-deployment-context]')).toBeEnabled();
  await page.locator('#deployment-close').click();
}

async function openFixtureGraph(page, request) {
  const response = await request.get(`${CONTROL_ORIGIN}/graph`);
  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toContain('application/xml');
  await page.locator('#file-inp').setInputFiles({
    name: 'human-task-confirmation-e2e.graphml',
    mimeType: 'application/xml',
    buffer: await response.body(),
  });
  await expect.poll(() => page.evaluate(nodeId => Boolean(
    window.ravenroot.activeDocument().graph.nodeMap[nodeId]), NODE_ID)).toBe(true);
}

async function selectDeploymentContext(page, ready) {
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Deployments…' }).click();
  const deployment = page.locator('.deployment-item', { hasText: ready.deploymentId });
  await expect(deployment).toContainText('Ready');
  await deployment.locator('[data-deployment-context]').click();
  await expect(page.locator('#deployments-dialog')).not.toHaveAttribute('open', '');
  await expect.poll(() => page.evaluate(() => {
    const context = window.ravenroot.activeDocument().humanTasks;
    return { deploymentId: context.deploymentId, graphVersion: context.graphVersion };
  })).toEqual({ deploymentId: ready.deploymentId, graphVersion: ready.graphVersion });
}

async function selectHumanTaskNode(page) {
  await page.evaluate(nodeId => {
    const active = window.ravenroot.activeDocument();
    active.cy.getElementById(nodeId).emit('tap');
  }, NODE_ID);
  await expect(page.locator('.human-task-status')).toContainText('2 actionable tasks', { timeout: 20_000 });
  await expect(page.locator('[data-human-task-id]')).toHaveCount(2);
  await expect.poll(() => page.evaluate(nodeId => {
    const node = window.ravenroot.activeDocument().cy.getElementById(nodeId);
    return { label: node.renderedStyle('label'), pending: node.data('humanTaskPending'),
      escalated: node.data('humanTaskEscalated'), pulsing: node.hasClass('human-task-pulse') };
  }, NODE_ID)).toEqual({ label: expect.stringContaining('⚑ 2 · ▲ 1'), pending: 2, escalated: 1,
    pulsing: false });
}

async function assertPinnedDialog(page, taskId) {
  const dialog = page.locator('#human-task-dialog');
  await expect(dialog).toBeVisible();
  await expect(page.locator('[data-human-task-identity]')).toContainText(`Task ${taskId}`);
  expect(await page.locator('[data-human-task-prompt]').textContent()).toMatch(/^P{8192}$/);
  await expect(page.locator('[data-human-task-comment]')).toBeFocused();
  await expect(page.locator('[data-human-task-comment-hint]')).toContainText('/ 8192 UTF-8 bytes');
  await expect(page.locator('[data-human-task-action]')).toHaveText(['Confirm', 'Deny', 'Cancel']);
}

test.describe('real SQLite Human Task confirmation recovery', () => {
  test.beforeAll(requireHarnessEnvironment);
  test.afterEach(async ({ request }) => {
    if (!CONTROL_ORIGIN) return;
    await request.post(`${CONTROL_ORIGIN}/stop`).catch(() => {});
  });

  test('rehydrates pinned tasks and reconciles a lost response exactly once', async ({ page, request }, testInfo) => {
    test.setTimeout(120_000);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const first = await startPhase(request, 'first');
    await page.goto(`${first.serviceOrigin}/`);
    await authenticate(page);
    await createLocalHumanTaskNode(page);
    await registerAuthoredGraph(page);
    await openFixtureGraph(page, request);
    await selectDeploymentContext(page, first);
    await selectHumanTaskNode(page);
    await expect(page.locator('#human-task-dialog')).toBeHidden();

    const escalated = page.locator('[data-human-task-id]', { hasText: 'Escalated' });
    await expect(escalated).toHaveCount(1);
    const selectedTaskId = await escalated.getAttribute('data-human-task-id');
    await escalated.focus();
    await page.keyboard.press('Enter');
    await assertPinnedDialog(page, selectedTaskId);
    const storedLocator = await page.evaluate(() =>
      JSON.parse(localStorage.getItem('ravenroot.human-task.selection.v1')));
    expect(Object.keys(storedLocator).sort()).toEqual(['generation', 'serviceOrigin', 'taskId']);
    expect(storedLocator).toMatchObject({ serviceOrigin: first.serviceOrigin, taskId: selectedTaskId });

    const accessibility = summarizeViolations(await scanForViolations(page,
      { include: ['#human-task-dialog'] }));
    logSummary('Real Human Task confirmation dialog', accessibility);
    await testInfo.attach('real-human-task-dialog-axe.json', {
      body: Buffer.from(JSON.stringify(accessibility, null, 2)), contentType: 'application/json',
    });

    await stopPhase(request);
    await expect(page.locator('.human-task-status')).toContainText('could not be refreshed',
      { timeout: 20_000 });
    await expect(page.locator('#human-task-dialog')).toBeHidden();
    await page.locator('#btn-revoke').click();
    expect(await page.evaluate(() => JSON.parse(
      localStorage.getItem('ravenroot.human-task.selection.v1')))).toEqual(storedLocator);

    const recovery = await startPhase(request, 'recovery');
    expect(recovery).toMatchObject({ serviceOrigin: first.serviceOrigin, graphVersion: first.graphVersion,
      deploymentId: first.deploymentId, locators: first.locators });
    const exactRequest = page.waitForRequest(request_ => {
      const url = new URL(request_.url());
      return url.pathname === '/v1/human-tasks/attention'
        && url.searchParams.get('taskId') === storedLocator.taskId;
    });
    await authenticate(page);
    const exactUrl = new URL((await exactRequest).url());
    expect(exactUrl.searchParams.get('generation')).toBe(String(storedLocator.generation));
    expect(exactUrl.searchParams.has('graphVersion')).toBe(false);
    expect(exactUrl.searchParams.has('deploymentId')).toBe(false);
    expect(exactUrl.searchParams.has('processInstanceId')).toBe(false);
    await assertPinnedDialog(page, storedLocator.taskId);
    await expect(page.locator('.human-task-status')).toContainText('2 actionable tasks');
    await expect(page.locator('[data-human-task-id]')).toHaveCount(2);
    const recoveredScreenshot = testInfo.outputPath('real-human-task-recovered.png');
    await page.screenshot({ path: recoveredScreenshot, fullPage: true });
    await testInfo.attach('real-human-task-recovered.png', {
      path: recoveredScreenshot, contentType: 'image/png',
    });

    const decisionUrl = `${recovery.serviceOrigin}/v1/human-tasks/${encodeURIComponent(storedLocator.taskId)}`
      + `/confirmation/resolve?generation=${storedLocator.generation}`;
    const comment = 'R'.repeat(5_000);
    let forwarded = 0;
    let committed;
    await page.route(decisionUrl, async route => {
      forwarded += 1;
      const response = await route.fetch();
      committed = await response.json();
      await route.abort('connectionfailed');
    }, { times: 1 });
    await page.locator('[data-human-task-comment]').fill(comment);
    await page.locator('[data-human-task-action="RESOLVE"]').click();
    await expect(page.locator('[data-human-task-error]')).toContainText('POST /v1/human-tasks');
    expect(forwarded).toBe(1);
    expect(committed).toMatchObject({ schemaVersion: 1, outcome: 'APPLIED',
      task: { taskId: storedLocator.taskId, status: 'RESOLVED', availableActions: [] } });
    await page.unroute(decisionUrl);

    const replay = await request.post(decisionUrl, {
      headers: { Authorization: `Bearer ${FIXTURE_TOKEN}` },
      data: { schemaVersion: 1, comment },
    });
    expect(replay.status()).toBe(200);
    expect(await replay.json()).toMatchObject({ schemaVersion: 1, outcome: 'ALREADY_APPLIED',
      task: { taskId: storedLocator.taskId, status: 'RESOLVED', availableActions: [] } });
    await expect(page.locator('.human-task-status')).toContainText('1 actionable task');
    await page.locator('[data-human-task-close]').click();

    const remaining = page.locator('[data-human-task-id]');
    await expect(remaining).toHaveCount(1);
    const remainingTaskId = await remaining.getAttribute('data-human-task-id');
    expect(remainingTaskId).not.toBe(storedLocator.taskId);
    await remaining.click();
    await assertPinnedDialog(page, remainingTaskId);
    await page.locator('[data-human-task-comment]').fill('Reviewed once through the central form.');
    await page.locator('[data-human-task-action="RESOLVE"]').click();
    await expect(page.locator('.human-task-status')).toContainText('No actionable');
    await expect(page.locator('[data-human-task-id]')).toHaveCount(0);
    await expect.poll(() => page.evaluate(nodeId => {
      const node = window.ravenroot.activeDocument().cy.getElementById(nodeId);
      return { label: node.renderedStyle('label'), pending: node.data('humanTaskPending'),
        escalated: node.data('humanTaskEscalated'), pulsing: node.hasClass('human-task-pulse') };
    }, NODE_ID)).toEqual({ label: expect.not.stringContaining('⚑'), pending: 0, escalated: 0,
      pulsing: false });
    expect(await page.evaluate(() => localStorage.getItem('ravenroot.human-task.selection.v1'))).toBeNull();

    await stopPhase(request);
  });
});

import { expect, test } from '@playwright/test';

// The Deployments window owns registration and control of local deployments. Routing that behavior
// through the Run button would change Run's meaning; Run keeps its existing meaning end to end,
// and THIS window is where "also registered and controlled as a
// local deployment" for a source-less graph actually lives -- decoupled from any open document,
// exactly like a deployment registered through the CLI would be.
//
// Two things can only be checked here, in a real browser against the real page: that the window is
// REACHABLE through the menu as a modal, and that a full register → start → stop → restart → undeploy
// lifecycle drives the real DOM through the real `RavenrootRuntimeClient`, with the server's own
// truthful state words shown at every step (never a UI-invented translation).
//
// The route is stubbed with `page.route`, stateful across calls within one test, matching
// `credentials-window.spec.js`'s own reasoning for not teaching this to the shared fixture server.

const DEPLOYMENTS = '**/v1/deployments**';

/** A stateful stub: register creates a REGISTERED entry, start/stop/restart transition it, undeploy
 * removes it and answers with the STOPPED status captured at removal -- the real route's own
 * contract (`RouteTable`'s comment on `DELETE /v1/deployments/{id}`). */
function withDeploymentService(page) {
  const held = new Map();
  const calls = [];
  page.route(DEPLOYMENTS, async route => {
    const request = route.request();
    const url = new URL(request.url());
    const segments = url.pathname.split('/').filter(Boolean); // ['v1', 'deployments', id?, command?]
    const id = url.searchParams.get('id') || (segments.length >= 3 ? decodeURIComponent(segments[2]) : '');
    const command = segments.length > 3 ? segments[3] : null;
    calls.push({ method: request.method(), id, command });

    if (request.method() === 'POST' && !command) {
      const entry = { deploymentId: id, state: 'REGISTERED', sourceCount: 0, scope: 'LOCAL_PROCESS', diagnostic: null };
      held.set(id, entry);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(entry) });
      return;
    }
    if (request.method() === 'GET' && !id) {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ deployments: [...held.values()] }),
      });
      return;
    }
    const entry = held.get(id);
    if (!entry) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) });
      return;
    }
    if (request.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(entry) });
      return;
    }
    if (command === 'start' || command === 'restart') entry.state = 'READY';
    else if (command === 'stop') entry.state = 'STOPPED';
    else if (request.method() === 'DELETE') {
      entry.state = 'STOPPED';
      held.delete(id);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(entry) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(entry) });
  });
  return { held, calls };
}

const openDeployments = async page => {
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Deployments…' }).click();
  await expect(page.locator('#deployments-dialog')).toHaveAttribute('open', '');
};

test.describe('the deployments window', () => {
  test('is reached from the Run menu and takes the screen as a modal', async ({ page }) => {
    withDeploymentService(page);
    await page.goto('/');

    await expect(page.locator('#deployments-dialog')).not.toHaveAttribute('open', '');
    await openDeployments(page);

    expect(await page.evaluate(() =>
      document.getElementById('deployments-dialog').matches(':modal'))).toBe(true);
    await expect(page.locator('#deployment-scope')).toContainText('LOCAL_PROCESS');

    await page.locator('#deployment-close').click();
    await expect(page.locator('#deployments-dialog')).not.toHaveAttribute('open', '');
  });

  test('registers and starts a deployment from the active document, then shows it as READY',
    async ({ page }) => {
      const service = withDeploymentService(page);
      await page.goto('/');
      await openDeployments(page);

      await page.locator('#deployment-id-input').fill('orders-v3');
      await page.locator('#deployment-register').click();

      const item = page.locator('#deployment-list .deployment-item');
      await expect(item).toHaveCount(1);
      await expect(item.locator('b')).toHaveText('orders-v3');
      await expect(item.locator('.deployment-state')).toHaveText('Ready');
      await expect(item).toContainText('LOCAL_PROCESS');

      expect(service.calls.some(call => call.method === 'POST' && !call.command && call.id === 'orders-v3'))
        .toBe(true);
      expect(service.calls.some(call => call.command === 'start' && call.id === 'orders-v3')).toBe(true);
      // The id field is cleared on success, like the credential window clears its value.
      await expect(page.locator('#deployment-id-input')).toHaveValue('');
    });

  test('stops, restarts and undeploys a registered deployment from its own row buttons',
    async ({ page }) => {
      await withDeploymentService(page);
      await page.goto('/');
      await openDeployments(page);
      await page.locator('#deployment-id-input').fill('orders-v3');
      await page.locator('#deployment-register').click();
      const item = page.locator('#deployment-list .deployment-item');
      await expect(item.locator('.deployment-state')).toHaveText('Ready');

      await item.locator('[data-deployment-action="stop"]').click();
      await expect(item.locator('.deployment-state')).toHaveText('Stopped');

      await item.locator('[data-deployment-action="start"]').click();
      await expect(item.locator('.deployment-state')).toHaveText('Ready');

      await item.locator('[data-deployment-action="restart"]').click();
      await expect(item.locator('.deployment-state')).toHaveText('Ready');

      // Undeploy asks for confirmation before it touches the service.
      page.once('dialog', dialog => dialog.accept());
      await item.locator('[data-deployment-action="undeploy"]').click();
      await expect(page.locator('#deployment-list .deployment-item')).toHaveCount(0);
      await expect(page.locator('#deployment-empty')).toBeVisible();
    });

  test('undeploy does nothing when the confirmation is dismissed', async ({ page }) => {
    await withDeploymentService(page);
    await page.goto('/');
    await openDeployments(page);
    await page.locator('#deployment-id-input').fill('orders-v3');
    await page.locator('#deployment-register').click();
    const item = page.locator('#deployment-list .deployment-item');
    await expect(item.locator('.deployment-state')).toHaveText('Ready');

    page.once('dialog', dialog => dialog.dismiss());
    await item.locator('[data-deployment-action="undeploy"]').click();

    await expect(page.locator('#deployment-list .deployment-item')).toHaveCount(1);
  });

  test('stops polling on Escape, not only on the Close button', async ({ page }) => {
    // The dialog's own `cancel` handler was a no-op that never called
    // `preventDefault`, so Escape closed the dialog natively without ever reaching this module's
    // `close()` -- leaving the 2s poll interval running invisibly for the rest of the session. The
    // module listens for the dialog's own `close` event, which fires for every dismissal, Escape
    // included. Ten seconds after Escape is five poll periods' worth of opportunity to leak.
    const service = withDeploymentService(page);
    await page.goto('/');
    await openDeployments(page);

    const listCallCount = () => service.calls.filter(call => call.method === 'GET' && !call.id).length;
    // `open()` issues its own immediate refresh, so at least one listing call is already in.
    await expect.poll(listCallCount).toBeGreaterThan(0);

    await page.keyboard.press('Escape');
    await expect(page.locator('#deployments-dialog')).not.toHaveAttribute('open', '');

    const countAtClose = listCallCount();
    await page.waitForTimeout(10000);
    expect(listCallCount()).toBe(countAtClose);
  });

  test('Run and Test are unaffected by this window -- neither touches /v1/deployments', async ({ page }) => {
    const service = withDeploymentService(page);
    await page.route('**/v1/node-types', route => route.fulfill({
      status: 200, contentType: 'application/json', body: '[]',
    }));
    await page.route('**/v1/events**', route => route.fulfill({
      status: 200, contentType: 'text/event-stream', body: '',
    }));
    const executionCalls = [];
    await page.route('**/v1/executions**', route => {
      executionCalls.push({ method: route.request().method(), url: route.request().url() });
      return route.fulfill({
        status: 202, contentType: 'application/json',
        body: JSON.stringify({ executionId: 'one-shot', graphVersion: 'v1', executionPolicy: 'STANDARD' }),
      });
    });
    await page.goto('/');
    await expect(page.locator('#btn-run')).toBeEnabled();

    page.once('dialog', dialog => dialog.accept());
    await page.locator('#btn-run').click();
    await expect.poll(() => executionCalls.filter(call => call.method === 'POST').length).toBe(1);
    expect(executionCalls.find(call => call.method === 'POST').url).toContain('mode=run');
    expect(service.calls).toHaveLength(0);
  });
});

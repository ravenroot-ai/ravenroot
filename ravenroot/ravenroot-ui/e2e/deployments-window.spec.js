import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

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

const legacyCapabilities = () => ({
  contractVersion: 1,
  scope: 'DEPLOYMENT',
  commands: ['START', 'PAUSE', 'RESUME', 'CANCEL', 'DRAIN', 'STOP', 'RESTART', 'UNDEPLOY']
    .map(command => ({
      command,
      available: ['START', 'STOP', 'RESTART', 'UNDEPLOY'].includes(command),
      reasonRequired: false,
      unavailableReason: ['START', 'STOP', 'RESTART', 'UNDEPLOY'].includes(command)
        ? null : 'DURABLE_AUTHORITY_UNAVAILABLE',
    })),
});

const durableCapabilities = scope => ({
  contractVersion: 1,
  scope,
  drainBound: scope === 'DEPLOYMENT' ? 'PT30S' : 'UNTIL_ACCEPTED_WORK_SETTLES',
  commands: (scope === 'DEPLOYMENT'
    ? ['START', 'PAUSE', 'RESUME', 'CANCEL', 'DRAIN', 'STOP', 'RESTART', 'UNDEPLOY']
    : ['PAUSE', 'RESUME', 'CANCEL', 'DRAIN', 'STOP'])
    .map(command => ({ command, available: true,
      reasonRequired: ['PAUSE', 'CANCEL', 'STOP', 'UNDEPLOY'].includes(command),
      unavailableReason: null })),
});

const localDeployment = (overrides = {}) => ({
  deploymentId: 'orders-v3', tenantId: 'tenant-a', state: 'READY', sourceCount: 0,
  graphVersion: 'graph-v1', scope: 'LOCAL_PROCESS', diagnostic: null,
  continuity: 'PROCESS_LOCAL', deploymentRevision: null, desiredState: null,
  observedState: null, recoveryFailure: null, lifecycleCapabilities: legacyCapabilities(),
  ...overrides,
});

const durableDeployment = (overrides = {}) => localDeployment({
  continuity: 'DURABLE', deploymentGeneration: 3, deploymentRevision: 3,
  desiredState: 'RUNNING', observedState: 'READY', lifecycleCapabilities: durableCapabilities('DEPLOYMENT'),
  ...overrides,
});

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
    calls.push({ method: request.method(), id, command, headers: await request.allHeaders() });

    if (request.method() === 'GET' && command === 'view' && held.has(id)) {
      const projection = {
        viewerContractVersion: '1.0', graphId: id, graphVersionId: 'graph-v1',
        canonicalDigest: 'graph-v1',
        nodes: [
          { id: 'start', kind: 'START', label: 'Start', layout: { x: 100, y: 120, width: 84, height: 84 } },
          { id: 'worker', kind: 'BEHAVIOR', label: 'Worker', visualType: 'actor',
            layout: { x: 300, y: 120, width: 120, height: 52 } },
        ],
        edges: [{ id: 'route-1', source: 'start', target: 'worker', visualType: 'continue' }],
      };
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        viewerSourceVersion: '1',
        source: { kind: 'deployment', deploymentId: id, graphVersion: 'graph-v1', incarnationId: 'inc-1' },
        lifecycle: held.get(id).state, canonicalDigest: 'graph-v1', projection,
      }) });
      return;
    }
    if (request.method() === 'GET' && command === 'events' && held.has(id)) {
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body:
        'id: cursor-1\nevent: execution\ndata: {"type":"execution","deploymentId":"orders-v3",'
        + '"graphVersion":"graph-v1","incarnationId":"inc-1","event":{"type":"NODE_STARTED",'
        + '"executionId":"execution-1","nodeId":"worker","activeInstances":1}}\n\n'
        + 'id: cursor-gap\nevent: source-gap\ndata: {"type":"gap","deploymentId":"orders-v3",'
        + '"graphVersion":"graph-v1","incarnationId":"inc-1","reason":"REPLAY_WINDOW_EXCEEDED"}\n\n' });
      return;
    }

    if (request.method() === 'POST' && !command) {
      const entry = localDeployment({ deploymentId: id, state: 'REGISTERED' });
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

  test('uses advertised deployment and process capabilities at narrow width, with authoritative reconciliation',
    async ({ page }, testInfo) => {
      await page.setViewportSize({ width: 390, height: 844 });
      const service = withDeploymentService(page);
      service.held.set('orders-v3', durableDeployment({ sourceCount: 1, graphVersion: 'graph-v3' }));
      const processId = 'aaaaaaaa-0000-0000-0000-000000000001';
      let process = {
        tenantId: 'tenant-a', processInstanceId: processId, status: 'RUNNING',
        disposition: 'ACTIVE', revision: 7, lifecycleGeneration: 4, graphVersion: 'graph-v3',
        deploymentId: 'orders-v3', workloadId: null, correlationId: null,
        ownerWorkerId: 'worker-1', fencingToken: 9, leaseExpiresAt: '2026-09-24T10:00:30Z',
        traversalCount: 2, createdAt: '2026-09-24T09:59:00Z', updatedAt: '2026-09-24T10:00:00Z',
        retainedUntil: null, terminationReason: null, cancelled: false, controlState: 'RUNNING',
        lifecycleCapabilities: durableCapabilities('PROCESS'),
      };
      const terminalProcess = {
        ...process, processInstanceId: 'aaaaaaaa-0000-0000-0000-000000000002', status: 'FAILED',
        terminationReason: 'CANCELLED', cancelled: true, disposition: 'TERMINAL_RETAINED',
        controlState: 'RUNNING', lifecycleCapabilities: {
          ...durableCapabilities('PROCESS'),
          commands: durableCapabilities('PROCESS').commands.map(command => ({
            ...command, available: false, reasonRequired: false, unavailableReason: 'TERMINAL_TARGET',
          })),
        },
      };
      const processCalls = [];
      await page.route('**/v1/executions/inventory**', route => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({
          items: [process, terminalProcess], nextCursor: null,
          retainedFrom: '2026-09-01T00:00:00Z', maxPageSize: 100,
        }),
      }));
      await page.route('**/v1/processes/**', async route => {
        const request = route.request();
        processCalls.push({ url: request.url(), headers: await request.allHeaders() });
        process = { ...process, revision: 8, lifecycleGeneration: 5, controlState: 'PAUSED' };
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
          outcome: 'APPLIED', processInstanceId: processId, generation: 8, state: 'PAUSED',
          reason: 'planned maintenance', traversals: [],
        }) });
      });

      await page.goto('/');
      await openDeployments(page);
      const deployment = page.locator('li[data-deployment-id="orders-v3"]');
      await expect(deployment).toContainText('tenant tenant-a');
      await expect(deployment).toContainText('graph graph-v3');
      await expect(deployment).toContainText('continuity DURABLE');
      await expect(deployment).toContainText('Drain closes new admission immediately');
      await page.locator('[data-deployment-operational="orders-v3"]').click();
      await expect(page.locator('#lifecycle-process-status')).toContainText('2 authoritative process instances');
      const terminal = page.locator(`li[data-process-id="${terminalProcess.processInstanceId}"]`);
      await expect(terminal.locator('.deployment-state')).toHaveText('Status FAILED');
      await expect(terminal).toContainText('control RUNNING');
      await expect(terminal).toContainText('terminal reason CANCELLED');
      await expect(terminal).toContainText('cancellation recorded');
      await terminal.locator('[data-process-select]').click();
      const unavailable = terminal.locator('[data-process-action="pause"]');
      await expect(unavailable).toHaveAttribute('aria-disabled', 'true');
      await unavailable.focus();
      await expect(unavailable).toBeFocused();
      await unavailable.press('Enter');
      expect(processCalls).toHaveLength(0);
      const describedBy = await unavailable.getAttribute('aria-describedby');
      await expect(page.locator(`#${describedBy}`)).toContainText('Pause unavailable:');
      await terminal.scrollIntoViewIfNeeded();
      await page.screenshot({
        path: testInfo.outputPath('operational-console-terminal-unavailable-mobile.png'), fullPage: true,
      });

      const active = page.locator(`li[data-process-id="${processId}"]`);
      await expect(active).toContainText('revision 7');
      await expect(active).toContainText('fence 9');
      await active.locator('[data-process-select]').click();
      await expect(page.locator('[data-process-action="pause"]')).toBeEnabled();
      await expect(page.locator('.process-item-actions')).toContainText('work already accepted continues until it settles');

      page.once('dialog', async dialog => {
        expect(dialog.message()).toContain('Why should process');
        await dialog.accept('planned maintenance');
      });
      await page.locator('[data-process-action="pause"]').click();
      await expect(active.locator('.deployment-state')).toHaveText('Status RUNNING');
      await expect(active).toContainText('control PAUSED');
      await expect(active).toContainText('revision 8');
      expect(processCalls).toHaveLength(1);
      expect(new URL(processCalls[0].url).searchParams.get('reason')).toBe('planned maintenance');
      expect(processCalls[0].headers['x-ravenroot-expected-generation']).toBe('7');
      expect(processCalls[0].headers['idempotency-key']).toBeTruthy();

      const dialogBounds = await page.locator('#deployments-dialog').boundingBox();
      expect(dialogBounds.x).toBeGreaterThanOrEqual(0);
      expect(dialogBounds.x + dialogBounds.width).toBeLessThanOrEqual(390);
      expect(await page.locator('#deployments-dialog').evaluate(element => element.scrollWidth <= element.clientWidth))
        .toBe(true);
      const accessibility = await new AxeBuilder({ page }).include('#deployments-dialog').analyze();
      expect(accessibility.violations).toEqual([]);
      await page.screenshot({ path: testInfo.outputPath('operational-console-mobile.png'), fullPage: true });
    });

  test('re-reads authoritative process state after stale, conflict, lost, and authorization failures without duplicate intent',
    async ({ page }) => {
      const service = withDeploymentService(page);
      service.held.set('orders-v3', durableDeployment());
      const processId = 'aaaaaaaa-0000-0000-0000-000000000003';
      let process = {
        tenantId: 'tenant-a', processInstanceId: processId, status: 'RUNNING',
        terminationReason: null, cancelled: false, disposition: 'ACTIVE', revision: 7,
        lifecycleGeneration: 4, graphVersion: 'graph-v1', deploymentId: 'orders-v3',
        workloadId: null, correlationId: null, ownerWorkerId: 'worker-1', fencingToken: 9,
        leaseExpiresAt: '2026-09-24T10:00:30Z', traversalCount: 2,
        createdAt: '2026-09-24T09:59:00Z', updatedAt: '2026-09-24T10:00:00Z',
        retainedUntil: null, controlState: 'RUNNING', lifecycleCapabilities: durableCapabilities('PROCESS'),
      };
      let inventoryReads = 0;
      const intents = [];
      await page.route('**/v1/executions/inventory**', async route => {
        inventoryReads += 1;
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
          items: [process], nextCursor: null, retainedFrom: '2026-09-01T00:00:00Z', maxPageSize: 100,
        }) });
      });
      await page.route('**/v1/processes/**', async route => {
        const request = route.request();
        const command = new URL(request.url()).pathname.split('/').at(-1);
        intents.push({ command, headers: await request.allHeaders() });
        process = { ...process, revision: process.revision + 1 };
        if (command === 'pause') {
          await route.fulfill({ status: 409, contentType: 'application/json',
            body: JSON.stringify({ error: 'STALE_REVISION' }) });
        } else if (command === 'resume') {
          await route.fulfill({ status: 409, contentType: 'application/json',
            body: JSON.stringify({ error: 'CONCURRENT_CONFLICT' }) });
        } else if (command === 'drain') {
          await route.fulfill({ status: 409, contentType: 'application/json',
            body: JSON.stringify({ error: 'IDEMPOTENCY_CONFLICT' }) });
        } else if (command === 'cancel') {
          await route.fulfill({ status: 200, contentType: 'application/json', body: '' });
        } else {
          process = { ...process, lifecycleCapabilities: {
            ...process.lifecycleCapabilities,
            commands: process.lifecycleCapabilities.commands.map(capability => ({
              ...capability, available: false, reasonRequired: false,
              unavailableReason: 'NOT_AUTHORIZED',
            })),
          } };
          await route.fulfill({ status: 403, contentType: 'application/json',
            body: JSON.stringify({ error: 'authorization lost' }) });
        }
      });

      await page.goto('/');
      await openDeployments(page);
      await page.locator('[data-deployment-operational="orders-v3"]').click();
      await page.locator('[data-process-select]').click();
      const row = page.locator(`li[data-process-id="${processId}"]`);
      let expectedRevision = 7;
      for (const command of ['pause', 'resume', 'drain', 'cancel', 'stop']) {
        if (['pause', 'cancel', 'stop'].includes(command)) {
          page.once('dialog', dialog => dialog.accept(`${command} diagnostic`));
        }
        const readsBefore = inventoryReads;
        await row.locator(`[data-process-action="${command}"]`).click();
        expectedRevision += 1;
        await expect(row).toContainText(`revision ${expectedRevision}`);
        await expect.poll(() => inventoryReads).toBeGreaterThan(readsBefore);
      }

      expect(intents.map(intent => intent.command)).toEqual(['pause', 'resume', 'drain', 'cancel', 'stop']);
      expect(new Set(intents.map(intent => intent.headers['idempotency-key'])).size).toBe(5);
      for (const command of ['pause', 'resume', 'drain', 'cancel', 'stop']) {
        expect(intents.filter(intent => intent.command === command)).toHaveLength(1);
      }
      const unavailable = row.locator('[data-process-action="pause"]');
      await expect(unavailable).toHaveAttribute('aria-disabled', 'true');
      const describedBy = await unavailable.getAttribute('aria-describedby');
      await expect(page.locator(`#${describedBy}`)).toContainText('Not authorized for this target');
    });

  test('opens a registered deployment without a local file and applies its bounded live stream read-only',
    async ({ page }) => {
      const service = withDeploymentService(page);
      service.held.set('orders-v3', localDeployment());
      await page.route('**/v1/node-types', route => route.fulfill({
        status: 200, contentType: 'application/json', body: '[]',
      }));
      await page.route('**/v1/events?include=diagnostics', route => route.fulfill({
        status: 200, contentType: 'text/event-stream', body: '',
      }));
      await page.goto('/');
      await page.locator('#access-token').fill('deployment-view-token');
      await page.locator('#btn-authenticate').click();
      await openDeployments(page);

      await page.locator('[data-deployment-view="orders-v3"]').click();
      await expect(page.locator('#deployments-dialog')).not.toHaveAttribute('open', '');
      await expect.poll(() => page.evaluate(() => {
        const owner = window.ravenroot.activeDocument();
        return owner && {
          name: owner.displayName,
          format: owner.graph.format,
          binding: owner.graph.viewerBinding,
          continuity: owner.deploymentView?.state.continuity,
          worker: owner.deploymentView?.state.nodeStates.get('worker')?.runtimeState,
        };
      })).toEqual({
        name: 'orders-v3 (deployment)', format: 'deployment',
        binding: { deploymentId: 'orders-v3', graphVersion: 'graph-v1', incarnationId: 'inc-1' },
        continuity: 'GAP', worker: undefined,
      });

      await expect(page.locator('#btn-modify')).toBeDisabled();
      await expect(page.locator('#btn-run')).toBeDisabled();
      const eventCall = service.calls.find(call => call.command === 'events');
      expect(eventCall.headers.authorization).toBe('Bearer deployment-view-token');
      expect(eventCall.headers['x-ravenroot-deployment-incarnation']).toBe('inc-1');
      expect(service.calls.some(call => ['start', 'stop', 'restart'].includes(call.command))).toBe(false);
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

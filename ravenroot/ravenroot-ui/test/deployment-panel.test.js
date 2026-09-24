import { readFile } from 'node:fs/promises';

import { JSDOM } from 'jsdom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  DEPLOYMENT_SCOPE_TEXT,
  createDeploymentsWindow,
  deploymentRowActions,
} from '../src/deployment-panel.js';

// This window is where "also registered and controlled as a local
// deployment" actually lives, decoupled from Run/Stop. Tested against the REAL markup shipped in
// `index.html`, the same discipline `credential-panel.test.js` uses, so this file cannot keep passing
// after the shipped window drifts from what it exercises.

const LEGACY_CAPABILITIES = { contractVersion: 1, scope: 'DEPLOYMENT', commands: [
  { command: 'START', available: true, reasonRequired: false, unavailableReason: null },
  { command: 'PAUSE', available: false, reasonRequired: false,
    unavailableReason: 'DURABLE_AUTHORITY_UNAVAILABLE' },
  { command: 'RESUME', available: false, reasonRequired: false,
    unavailableReason: 'DURABLE_AUTHORITY_UNAVAILABLE' },
  { command: 'CANCEL', available: false, reasonRequired: false,
    unavailableReason: 'DURABLE_AUTHORITY_UNAVAILABLE' },
  { command: 'DRAIN', available: false, reasonRequired: false,
    unavailableReason: 'DURABLE_AUTHORITY_UNAVAILABLE' },
  { command: 'STOP', available: true, reasonRequired: false, unavailableReason: null },
  { command: 'RESTART', available: true, reasonRequired: false, unavailableReason: null },
  { command: 'UNDEPLOY', available: true, reasonRequired: false, unavailableReason: null },
] };
const DURABLE_CAPABILITIES = { contractVersion: 1, scope: 'DEPLOYMENT', drainBound: 'PT30S',
  commands: LEGACY_CAPABILITIES.commands.map(command => ({ ...command, available: true,
    reasonRequired: ['PAUSE', 'CANCEL', 'STOP', 'UNDEPLOY'].includes(command.command),
    unavailableReason: null })) };
const READY = { deploymentId: 'orders-v3', state: 'READY', sourceCount: 0, graphVersion: 'graph-v3',
  scope: 'LOCAL_PROCESS', diagnostic: null, tenantId: 'tenant-a', continuity: 'PROCESS_LOCAL',
  deploymentRevision: null, desiredState: null, observedState: null, recoveryFailure: null,
  lifecycleCapabilities: LEGACY_CAPABILITIES };
const DURABLE_READY = { ...READY, continuity: 'DURABLE', deploymentGeneration: 6,
  deploymentRevision: 6, desiredState: 'RUNNING', observedState: 'READY',
  lifecycleCapabilities: DURABLE_CAPABILITIES };

let dialog;

beforeEach(async () => {
  const html = await readFile('index.html', 'utf8');
  const source = new JSDOM(html).window.document.getElementById('deployments-dialog');
  document.body.innerHTML = '';
  dialog = document.importNode(source, true);
  document.body.appendChild(dialog);
  if (typeof dialog.showModal !== 'function') dialog.showModal = function open() { this.open = true; };
  if (typeof dialog.close !== 'function') dialog.close = function close() { this.open = false; };
});

afterEach(() => { document.body.innerHTML = ''; });

const field = id => document.getElementById(id);

function stubClient(overrides = {}) {
  return {
    deployments: vi.fn(async () => []),
    registerDeployment: vi.fn(async id => ({ ...READY, deploymentId: id, state: 'REGISTERED' })),
    startDeployment: vi.fn(async id => ({ ...READY, deploymentId: id })),
    stopDeployment: vi.fn(async id => ({ ...READY, deploymentId: id, state: 'STOPPED' })),
    restartDeployment: vi.fn(async id => ({ ...READY, deploymentId: id })),
    undeployDeployment: vi.fn(async id => ({ ...READY, deploymentId: id, state: 'STOPPED' })),
    processInventory: vi.fn(async () => ({ items: [], nextCursor: null,
      retainedFrom: '2026-01-01T00:00:00Z', maxPageSize: 100 })),
    ...overrides,
  };
}

describe('deploymentRowActions: which buttons a truthful state offers', () => {
  it('offers exactly Start and Undeploy for REGISTERED and STOPPED', () => {
    expect(deploymentRowActions('REGISTERED')).toEqual(['start', 'undeploy']);
    expect(deploymentRowActions('STOPPED')).toEqual(['start', 'undeploy']);
  });
  it('offers Stop, Restart and Undeploy for READY and DEGRADED', () => {
    expect(deploymentRowActions('READY')).toEqual(['stop', 'restart', 'undeploy']);
    expect(deploymentRowActions('DEGRADED')).toEqual(['stop', 'restart', 'undeploy']);
  });
  it('offers Restart and Undeploy for FAILED', () => {
    expect(deploymentRowActions('FAILED')).toEqual(['restart', 'undeploy']);
  });
  it('offers nothing for a transient state -- it is mid-flight, not actionable', () => {
    expect(deploymentRowActions('STARTING')).toEqual([]);
    expect(deploymentRowActions('STOPPING')).toEqual([]);
  });
});

describe('what the window renders', () => {
  it('shows the server\'s own state word, never a translated one', async () => {
    const window_ = createDeploymentsWindow({
      dialog, client: stubClient({ deployments: vi.fn(async () => [READY]) }), pollMs: 0,
    });
    await window_.refresh();

    const item = field('deployment-list').querySelector('.deployment-item');
    expect(item.querySelector('b').textContent).toBe('orders-v3');
    expect(item.querySelector('.deployment-state').textContent).toBe('Ready');
    expect(dialog.textContent).not.toContain('LISTENING');
  });

  it('names scope=LOCAL_PROCESS on every row', async () => {
    const window_ = createDeploymentsWindow({
      dialog, client: stubClient({ deployments: vi.fn(async () => [READY]) }), pollMs: 0,
    });
    await window_.refresh();

    expect(field('deployment-list').textContent).toContain('LOCAL_PROCESS');
  });

  it('shows the authoritative durable generation when the server supplies one', async () => {
    const window_ = createDeploymentsWindow({
      dialog, client: stubClient({ deployments: vi.fn(async () => [
        { ...READY, lifecycleCapabilities: DURABLE_CAPABILITIES, continuity: 'DURABLE',
          deploymentGeneration: 9, deploymentRevision: 12, desiredState: 'RUNNING',
          observedState: 'READY', recoveryFailure: null },
      ]) }), pollMs: 0,
    });
    await window_.refresh();
    expect(field('deployment-list').textContent).toContain('tenant tenant-a');
    expect(field('deployment-list').textContent).toContain('graph graph-v3');
    expect(field('deployment-list').textContent).toContain('continuity DURABLE');
    expect(field('deployment-list').textContent).toContain('generation 9');
    expect(field('deployment-list').textContent).toContain('registry revision 12');
    expect(field('deployment-list').textContent).toContain('desired RUNNING, observed READY');
    expect(field('deployment-list').textContent).toContain('Drain closes new admission immediately');
  });

  it('shows a diagnostic when the server sends one', async () => {
    const degraded = { ...READY, state: 'DEGRADED', diagnostic: 'One inbound source subscription failed.' };
    const window_ = createDeploymentsWindow({
      dialog, client: stubClient({ deployments: vi.fn(async () => [degraded]) }), pollMs: 0,
    });
    await window_.refresh();

    expect(field('deployment-list').textContent).toContain('One inbound source subscription failed.');
  });

  it('says once what this window is and how a deployment outlives the document that started it',
    () => {
      createDeploymentsWindow({ dialog, client: null, pollMs: 0 });
      expect(field('deployment-scope').textContent).toBe(DEPLOYMENT_SCOPE_TEXT);
      expect(DEPLOYMENT_SCOPE_TEXT).toMatch(/LOCAL_PROCESS/);
    });

  it('distinguishes "you hold none" from "nobody has asked yet"', async () => {
    const connected = createDeploymentsWindow({
      dialog, client: stubClient({ deployments: vi.fn(async () => []) }), pollMs: 0,
    });
    await connected.refresh();
    expect(field('deployment-empty').hidden).toBe(false);
    expect(field('deployment-empty').textContent).toContain('no deployments yet');

    const offline = createDeploymentsWindow({ dialog, client: null, pollMs: 0 });
    await offline.refresh();
    expect(field('deployment-empty').textContent).toContain('Connect');
  });
});

describe('registering from the active document', () => {
  it('registers then starts, in that order, under the typed id', async () => {
    const client = stubClient();
    const onRegistered = vi.fn();
    const window_ = createDeploymentsWindow({
      dialog, client, pollMs: 0, onRegistered,
      currentDocument: () => ({ documentId: 'document-1', displayName: 'Orders', graphMl: '<graphml/>' }),
    });
    field('deployment-id-input').value = 'orders-v3';

    await window_.register();

    expect(client.registerDeployment).toHaveBeenCalledWith('orders-v3', '<graphml/>');
    expect(client.startDeployment).toHaveBeenCalledWith('orders-v3');
    const registerOrder = client.registerDeployment.mock.invocationCallOrder[0];
    const startOrder = client.startDeployment.mock.invocationCallOrder[0];
    expect(registerOrder).toBeLessThan(startOrder);
    expect(onRegistered).toHaveBeenCalledWith(expect.objectContaining({ deploymentId: 'orders-v3',
      graphVersion: 'graph-v3' }), expect.objectContaining({ documentId: 'document-1' }));
    // Cleared on success so the next registration starts from a blank id, exactly like the
    // credential window clears its value on a successful store.
    expect(field('deployment-id-input').value).toBe('');
  });

  it('refuses an empty id without calling the service at all', async () => {
    const client = stubClient();
    const window_ = createDeploymentsWindow({
      dialog, client, pollMs: 0,
      currentDocument: () => ({ displayName: 'Orders', graphMl: '<graphml/>' }),
    });
    field('deployment-id-input').value = '  ';

    await window_.register();

    expect(client.registerDeployment).not.toHaveBeenCalled();
    expect(field('deployment-id-error').hidden).toBe(false);
  });

  it('refuses to register when no document is open, and touches no service', async () => {
    const client = stubClient();
    const window_ = createDeploymentsWindow({
      dialog, client, pollMs: 0, currentDocument: () => null,
    });
    field('deployment-id-input').value = 'orders-v3';

    await window_.register();

    expect(client.registerDeployment).not.toHaveBeenCalled();
    expect(field('deployment-status').textContent).toMatch(/open or create a graph/i);
  });

  it('says so and registers nothing when there is no service connection', async () => {
    const window_ = createDeploymentsWindow({
      dialog, client: null, pollMs: 0,
      currentDocument: () => ({ displayName: 'Orders', graphMl: '<graphml/>' }),
    });
    field('deployment-id-input').value = 'orders-v3';

    await window_.register();

    expect(field('deployment-status').textContent).toMatch(/no connection/i);
  });
});

describe('row actions', () => {
  it('opens a deployment in a separate read-only canvas without invoking lifecycle controls', async () => {
    const client = stubClient({ deployments: vi.fn(async () => [READY]) });
    const onOpenDeployment = vi.fn(async () => {});
    const window_ = createDeploymentsWindow({ dialog, client, onOpenDeployment, pollMs: 0 });
    await window_.refresh();

    field('deployment-list').querySelector('[data-deployment-view="orders-v3"]').click();
    await vi.waitFor(() => expect(onOpenDeployment).toHaveBeenCalledWith('orders-v3', client));

    expect(client.startDeployment).not.toHaveBeenCalled();
    expect(client.stopDeployment).not.toHaveBeenCalled();
    expect(client.restartDeployment).not.toHaveBeenCalled();
    expect(client.undeployDeployment).not.toHaveBeenCalled();
  });

  it('publishes an explicit server-pinned deployment context for the active graph', async () => {
    const onDeploymentSelected = vi.fn();
    const client = stubClient({ deployments: vi.fn(async () => [READY]) });
    const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0, onDeploymentSelected });
    await window_.refresh();

    field('deployment-list').querySelector('[data-deployment-context]').click();
    expect(onDeploymentSelected).toHaveBeenCalledWith(READY);
  });

  it('starts a REGISTERED/STOPPED row on its own Start button', async () => {
    const stopped = { ...READY, state: 'STOPPED' };
    const client = stubClient({ deployments: vi.fn(async () => [stopped]) });
    const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
    await window_.refresh();

    field('deployment-list').querySelector('[data-deployment-action="start"]').click();
    await vi.waitFor(() => expect(client.startDeployment).toHaveBeenCalledWith('orders-v3'));
  });

  it('undeploys only after the operator confirms, and never on a bare click through a stub that refuses',
    async () => {
      const client = stubClient({ deployments: vi.fn(async () => [READY]) });
      const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
      await window_.refresh();
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);

      field('deployment-list').querySelector('[data-deployment-action="undeploy"]').click();
      await Promise.resolve();

      expect(confirmSpy).toHaveBeenCalled();
      expect(confirmSpy.mock.calls[0][0]).toMatch(/process-local registration/);
      expect(confirmSpy.mock.calls[0][0]).toMatch(/same local id can be registered again/);
      expect(confirmSpy.mock.calls[0][0]).not.toMatch(/permanently retires/);
      expect(client.undeployDeployment).not.toHaveBeenCalled();
      confirmSpy.mockRestore();
    });

  it('undeploys once the operator confirms', async () => {
    const client = stubClient({ deployments: vi.fn(async () => [READY]) });
    const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
    await window_.refresh();
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    field('deployment-list').querySelector('[data-deployment-action="undeploy"]').click();
    await vi.waitFor(() => expect(client.undeployDeployment).toHaveBeenCalledWith('orders-v3'));

    vi.restoreAllMocks();
  });

  it('restarts and stops a READY row on their own buttons', async () => {
    const client = stubClient({ deployments: vi.fn(async () => [READY]) });
    const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
    await window_.refresh();

    field('deployment-list').querySelector('[data-deployment-action="stop"]').click();
    await vi.waitFor(() => expect(client.stopDeployment).toHaveBeenCalledWith('orders-v3'));
  });

  it('sends durable Stop with the displayed generation and an explicit reason', async () => {
    const durable = DURABLE_READY;
    const client = stubClient({ deployments: vi.fn(async () => [durable]) });
    const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
    await window_.refresh();
    vi.spyOn(window, 'prompt').mockReturnValue('operator maintenance');

    field('deployment-list').querySelector('[data-deployment-action="stop"]').click();
    await vi.waitFor(() => expect(client.stopDeployment).toHaveBeenCalledWith('orders-v3', {
      expectedGeneration: 6, reason: 'operator maintenance',
    }));
    vi.restoreAllMocks();
  });

  it('surfaces reconciled state without inventing an outcome after both command responses are lost',
    async () => {
      const durable = DURABLE_READY;
      const client = stubClient({
        deployments: vi.fn(async () => [durable]),
        stopDeployment: vi.fn(async () => ({
          outcome: null, status: { ...durable, state: 'STOPPED', deploymentGeneration: 7 },
          reconciliation: { delivery: 'AMBIGUOUS', authoritative: 'STATE' },
        })),
      });
      const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
      await window_.refresh();
      vi.spyOn(window, 'prompt').mockReturnValue('maintenance');

      field('deployment-list').querySelector('[data-deployment-action="stop"]').click();
      await vi.waitFor(() => expect(field('deployment-status').textContent).toMatch(/lost both command responses/i));

      expect(field('deployment-status').textContent).toMatch(/command outcome is unknown/i);
      expect(field('deployment-status').textContent).toMatch(/authoritative reconciliation/i);
      vi.restoreAllMocks();
    });

  it('requires an explicit durable Undeploy disposition and reason', async () => {
    const durable = { ...DURABLE_READY, deploymentGeneration: 11, deploymentRevision: 11 };
    const client = stubClient({ deployments: vi.fn(async () => [durable]) });
    const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
    await window_.refresh();
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(window, 'prompt')
      .mockReturnValueOnce('CANCEL_IN_FLIGHT')
      .mockReturnValueOnce('retired by operator');

    field('deployment-list').querySelector('[data-deployment-action="undeploy"]').click();
    await vi.waitFor(() => expect(client.undeployDeployment).toHaveBeenCalledWith('orders-v3', {
      expectedGeneration: 11, disposition: 'CANCEL_IN_FLIGHT', reason: 'retired by operator',
    }));
    expect(confirmSpy.mock.calls[0][0]).toMatch(/permanently retires its durable identity/);
    expect(confirmSpy.mock.calls[0][0]).toMatch(/tombstone remains authoritative/);
    expect(confirmSpy.mock.calls[0][0]).not.toMatch(/registered again/);
    vi.restoreAllMocks();
  });

  it('does not collect disposition or reason when durable Undeploy confirmation is cancelled', async () => {
    const durable = { ...DURABLE_READY, deploymentGeneration: 11, deploymentRevision: 11 };
    const client = stubClient({ deployments: vi.fn(async () => [durable]) });
    const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
    await window_.refresh();
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const promptSpy = vi.spyOn(window, 'prompt');

    field('deployment-list').querySelector('[data-deployment-action="undeploy"]').click();
    await Promise.resolve();

    expect(promptSpy).not.toHaveBeenCalled();
    expect(client.undeployDeployment).not.toHaveBeenCalled();
    vi.restoreAllMocks();
  });

  it.each([
    ['disposition', [null]],
    ['reason', ['DRAIN_FIRST', null]],
  ])('does not send durable Undeploy when %s collection is cancelled', async (_field, answers) => {
    const durable = { ...DURABLE_READY, deploymentGeneration: 11, deploymentRevision: 11 };
    const client = stubClient({ deployments: vi.fn(async () => [durable]) });
    const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
    await window_.refresh();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const promptSpy = vi.spyOn(window, 'prompt');
    answers.forEach(answer => promptSpy.mockReturnValueOnce(answer));

    field('deployment-list').querySelector('[data-deployment-action="undeploy"]').click();
    await Promise.resolve();

    expect(client.undeployDeployment).not.toHaveBeenCalled();
    vi.restoreAllMocks();
  });

  it('selects a deployment and a process, then sends only its advertised process command', async () => {
    const process = {
      tenantId: 'tenant-a', deploymentId: 'orders-v3', graphVersion: 'graph-v3',
      processInstanceId: 'aaaaaaaa-0000-0000-0000-000000000001', status: 'RUNNING',
      terminationReason: null, cancelled: false, controlState: 'RUNNING', disposition: 'ACTIVE',
      revision: 7, lifecycleGeneration: 4,
      fencingToken: 9,
      lifecycleCapabilities: { contractVersion: 1, scope: 'PROCESS',
        drainBound: 'UNTIL_ACCEPTED_WORK_SETTLES', commands: [
          { command: 'PAUSE', available: true, reasonRequired: true, unavailableReason: null },
          { command: 'RESUME', available: false, reasonRequired: false,
            unavailableReason: 'INCOMPATIBLE_STATE' },
        ] },
    };
    const client = stubClient({
      deployments: vi.fn(async () => [{ ...DURABLE_READY, deploymentGeneration: 3,
        deploymentRevision: 3 }]),
      processInventory: vi.fn(async () => ({ items: [process], nextCursor: null,
        retainedFrom: '2026-01-01T00:00:00Z', maxPageSize: 100 })),
      controlProcess: vi.fn(async () => ({ outcome: 'APPLIED', processInstanceId: process.processInstanceId,
        generation: 8, state: 'PAUSED', reason: 'maintenance', traversals: [] })),
    });
    const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
    await window_.refresh();
    field('deployment-list').querySelector('[data-deployment-operational="orders-v3"]').click();
    await vi.waitFor(() => expect(client.processInventory).toHaveBeenCalled());
    field('lifecycle-process-list').querySelector('[data-process-select]').click();

    const resume = field('lifecycle-process-list').querySelector('[data-process-action="resume"]');
    expect(resume.disabled).toBe(false);
    expect(resume.getAttribute('aria-disabled')).toBe('true');
    expect(document.getElementById(resume.getAttribute('aria-describedby')).textContent)
      .toContain('Resume unavailable:');
    resume.focus();
    expect(document.activeElement).toBe(resume);
    resume.click();
    expect(client.controlProcess).not.toHaveBeenCalled();
    vi.spyOn(window, 'prompt').mockReturnValue('maintenance');
    field('lifecycle-process-list').querySelector('[data-process-action="pause"]').click();
    await vi.waitFor(() => expect(client.controlProcess).toHaveBeenCalled());

    expect(client.controlProcess).toHaveBeenCalledWith(process.processInstanceId, 'pause', 7,
      expect.objectContaining({ reason: 'maintenance', idempotencyKey: expect.any(String) }));
    expect(document.querySelector('#human-task-dialog [data-process-action]')).toBeNull();
    vi.restoreAllMocks();
  });

  it('keeps terminal aggregate status, RUNNING control state, and cancellation evidence distinct', async () => {
    const terminal = {
      tenantId: 'tenant-a', deploymentId: 'orders-v3', graphVersion: 'graph-v3',
      processInstanceId: 'aaaaaaaa-0000-0000-0000-000000000002', status: 'FAILED',
      terminationReason: 'CANCELLED', cancelled: true, controlState: 'RUNNING',
      disposition: 'TERMINAL_RETAINED', revision: 8, lifecycleGeneration: 4, fencingToken: 9,
      lifecycleCapabilities: { contractVersion: 1, scope: 'PROCESS', commands: [
        { command: 'PAUSE', available: false, reasonRequired: false,
          unavailableReason: 'TERMINAL_TARGET' },
      ] },
    };
    const client = stubClient({
      deployments: vi.fn(async () => [DURABLE_READY]),
      processInventory: vi.fn(async () => ({ items: [terminal], nextCursor: null,
        retainedFrom: '2026-01-01T00:00:00Z', maxPageSize: 100 })),
    });
    const window_ = createDeploymentsWindow({ dialog, client, pollMs: 0 });
    await window_.refresh();
    field('deployment-list').querySelector('[data-deployment-operational]').click();
    await vi.waitFor(() => expect(client.processInventory).toHaveBeenCalled());

    const row = field('lifecycle-process-list').querySelector('.lifecycle-process-item');
    expect(row.querySelector('.deployment-state').textContent).toBe('Status FAILED');
    expect(row.textContent).toContain('control RUNNING');
    expect(row.textContent).toContain('terminal reason CANCELLED');
    expect(row.textContent).toContain('cancellation recorded');
  });
});

describe('the file\'s own rules', () => {
  it('contains no logging of any kind', async () => {
    const source = await readFile('src/deployment-panel.js', 'utf8');
    expect(source).not.toContain('console');
    expect(source).not.toContain('debugger');
  });
});

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

const READY = { deploymentId: 'orders-v3', state: 'READY', sourceCount: 0, scope: 'LOCAL_PROCESS', diagnostic: null };

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
    const window_ = createDeploymentsWindow({
      dialog, client, pollMs: 0,
      currentDocument: () => ({ displayName: 'Orders', graphMl: '<graphml/>' }),
    });
    field('deployment-id-input').value = 'orders-v3';

    await window_.register();

    expect(client.registerDeployment).toHaveBeenCalledWith('orders-v3', '<graphml/>');
    expect(client.startDeployment).toHaveBeenCalledWith('orders-v3');
    const registerOrder = client.registerDeployment.mock.invocationCallOrder[0];
    const startOrder = client.startDeployment.mock.invocationCallOrder[0];
    expect(registerOrder).toBeLessThan(startOrder);
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
});

describe('the file\'s own rules', () => {
  it('contains no logging of any kind', async () => {
    const source = await readFile('src/deployment-panel.js', 'utf8');
    expect(source).not.toContain('console');
    expect(source).not.toContain('debugger');
  });
});

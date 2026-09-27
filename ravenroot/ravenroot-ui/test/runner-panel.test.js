import { beforeEach, expect, it, vi } from 'vitest';
import { createRunnerWindow } from '../src/runner-panel.js';

let dialog;
beforeEach(() => {
  document.body.innerHTML = '<dialog></dialog>';
  dialog = document.querySelector('dialog');
  dialog.showModal = () => { dialog.open = true; };
  dialog.close = () => { dialog.open = false; };
});
const flush = () => new Promise(resolve => setTimeout(resolve, 0));
const click = text => [...dialog.querySelectorAll('button')].find(button => button.textContent === text).click();

it('shows native physical authority and bounded usage separately from Agent output', async () => {
  const id = '11111111-1111-4111-8111-111111111111';
  const kubernetes = { cluster: 'approved', namespace: 'agents', podName: 'rr-pod', podUid: id,
    claimName: 'rr-volume', claimUid: id, phase: 'RUNNING', reason: 'NONE', modelTurns: 3, toolCalls: 7, modelTokens: 100 };
  const panel = createRunnerWindow({ dialog });
  panel.setClient({ runnerWorkspace: vi.fn(async () => ({ revision: 1,
    workspaces: [{ nodeId: 'source', workspaceId: id, state: 'READY', driver: 'KUBERNETES', kubernetes }],
    jobs: [{ runnerJobId: id, definition: 'reviewer', command: 'review', state: 'CLAIMED', driver: 'KUBERNETES', kubernetes, result: { result: 'reviewed' } }] })) });
  dialog.querySelector('input').value = id; click('Inspect workspace'); await flush();
  for (const text of ['KUBERNETES', 'approved', 'agents', 'rr-pod', 'rr-volume', 'reviewed', '100']) expect(dialog.textContent).toContain(text);
  expect(dialog.textContent).not.toContain('kubeconfig');
});

it('shows direct Agent output safely and stops only the selected Workspace at the observed revision', async () => {
  const id = '11111111-1111-4111-8111-111111111111';
  const client = {
    runnerWorkspace: vi.fn(async () => ({ revision: 42, workspaces: [
      { nodeId: 'source-tree', workspaceId: id, state: 'READY', runtimeId: 'physical-a', profile: { runtimeLifecycle: 'PER_WORKSPACE' } },
      { nodeId: 'docs', workspaceId: id, state: 'READY', runtimeId: 'physical-b' },
    ], jobs: [{ command: 'review', state: 'COMPLETED', result: { result: '<img src=x onerror=alert(1)>' } }] })),
    stopRunnerWorkspace: vi.fn(async () => ({})),
  };
  const panel = createRunnerWindow({ dialog }); panel.setClient(client);
  dialog.querySelector('input').value = id; click('Inspect workspace'); await flush();
  expect(dialog.textContent).toContain('physical-a');
  expect(dialog.textContent).toContain('Agent result');
  expect(dialog.textContent).toContain('<img src=x');
  expect(dialog.querySelector('img')).toBeNull();
  click('Stop this Workspace'); await flush(); await flush();
  expect(client.stopRunnerWorkspace).toHaveBeenCalledExactlyOnceWith(id, 'source-tree', 42);
});

it.each([
  ['RESUME', 'Resume undispatched successors'],
  ['ACKNOWLEDGE', 'Acknowledge complete successors'],
  ['ABANDON', 'Abandon unfinished traversal'],
])('requires review and sends the observed revision for %s', async (resolution, label) => {
  const id = '11111111-1111-4111-8111-111111111111';
  const client = {
    runnerWorkspace: vi.fn(async () => ({ workspaceId: id, revision: 17, runnerId: 'remote', jobs: [{
      runnerJobId: id, command: 'implement', state: 'COMPLETED', continuationUncertain: true, artifacts: [],
    }] })),
    resolveRunnerContinuation: vi.fn(async () => ({ revision: 18, resolution })),
  };
  const panel = createRunnerWindow({ dialog }); panel.setClient(client);
  dialog.querySelector('input').value = id; click('Inspect workspace'); await flush();
  click(label); await flush();
  expect(client.resolveRunnerContinuation).not.toHaveBeenCalled();
  expect(dialog.textContent).toContain('Confirm graph-state review');
  dialog.querySelector('input[type=checkbox]').checked = true;
  click(label); await flush(); await flush();
  expect(client.resolveRunnerContinuation).toHaveBeenCalledExactlyOnceWith(id, id, 17, resolution);
  expect(client.runnerWorkspace).toHaveBeenCalledTimes(2);
});

it.each(['Revision conflict', 'Access denied'])('surfaces %s without retrying a resolution', async message => {
  const id = '11111111-1111-4111-8111-111111111111';
  const client = {
    runnerWorkspace: vi.fn(async () => ({ workspaceId: id, revision: 4, jobs: [{
      runnerJobId: id, command: 'implement', state: 'COMPLETED', continuationUncertain: true,
    }] })),
    resolveRunnerContinuation: vi.fn(async () => { throw new Error(message); }),
  };
  const panel = createRunnerWindow({ dialog }); panel.setClient(client);
  dialog.querySelector('input').value = id; click('Inspect workspace'); await flush();
  dialog.querySelector('input[type=checkbox]').checked = true;
  click('Resume undispatched successors'); await flush();
  expect(dialog.textContent).toContain(message);
  expect(client.resolveRunnerContinuation).toHaveBeenCalledTimes(1);
  expect(client.runnerWorkspace).toHaveBeenCalledTimes(1);
});

it('explains the disconnected state and never sends an anonymous mutation', async () => {
  const panel = createRunnerWindow({ dialog }); panel.open(); click('Save version / approval');
  expect(dialog.textContent).toContain('Connect to a service');
});

it('discards late responses and clears tenant-owned instructions on client replacement', async () => {
  let resolve;
  const client = { runnerCatalog: vi.fn(() => new Promise(done => { resolve = done; })) };
  const panel = createRunnerWindow({ dialog }); panel.setClient(client); panel.open();
  panel.setClient(null); resolve({ items: [{ kind: 'RUNNER', name: 'private', version: 1 }] }); await flush();
  expect(dialog.textContent).not.toContain('private');
  expect(dialog.querySelector('textarea').value).toBe('');
});

it('renders unknown effects and untrusted artifact previews as text, without a retry command', async () => {
  const id = '11111111-1111-4111-8111-111111111111';
  const client = {
    runnerWorkspace: vi.fn(async () => ({ workspaceId: id, runnerId: 'remote', jobs: [{
      runnerJobId: id, command: 'implement', state: 'UNKNOWN', definition: 'dev', definitionVersion: 1,
      fence: 2, artifacts: [{ artifactId: id, kind: 'LOG', sha256: 'a'.repeat(64), sizeBytes: 10 }],
    }] })),
    runnerArtifact: vi.fn(async () => ({ content: '<img src=x onerror=alert(1)>', truncated: false })),
  };
  const panel = createRunnerWindow({ dialog }); panel.setClient(client);
  dialog.querySelector('input').value = id; click('Inspect workspace'); await flush();
  expect(dialog.textContent).toContain('report-only');
  expect([...dialog.querySelectorAll('button')].some(button => /retry/i.test(button.textContent))).toBe(false);
  [...dialog.querySelectorAll('button')].find(button => button.textContent.startsWith('LOG')).click(); await flush();
  expect(dialog.querySelector('img')).toBeNull();
  expect(dialog.textContent).toContain('<img src=x');
});

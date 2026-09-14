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

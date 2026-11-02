import { it, expect, vi, afterEach } from 'vitest';
import { repositoryOperationDialog } from '../src/repository-operation-dialog.js';
afterEach(() => document.body.replaceChildren());
function open() { HTMLDialogElement.prototype.showModal = vi.fn(); HTMLDialogElement.prototype.close = vi.fn(); return repositoryOperationDialog(); }
it('announces work immediately and prevents dismissal until the result arrives', () => {
  const feedback = open(); const dialog = document.querySelector('dialog');
  expect(dialog.textContent).toContain('Preparing the release review');
  expect(dialog.querySelector('button').disabled).toBe(true);
  const event = new Event('cancel', { cancelable: true }); dialog.dispatchEvent(event); expect(event.defaultPrevented).toBe(true);
  feedback.failure('The source changed. Reopen the current draft.');
  expect(dialog.querySelector('[role=alert]').textContent).toContain('source changed');
  expect(dialog.querySelector('button').disabled).toBe(false);
  dialog.querySelector('button').click(); expect(dialog.isConnected).toBe(false);
});
it('shows the created or reused review link without implying publication', () => {
  const feedback = open(); feedback.success({ reused: true, url: 'https://github.com/example/graphs/pull/9' });
  expect(document.querySelector('a').hidden).toBe(false);
  expect(document.querySelector('a').href).toContain('/pull/9');
  expect(document.querySelector('[role=status]').textContent).toContain('existing release review');
});

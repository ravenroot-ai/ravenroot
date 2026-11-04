import { uiText } from './ui-text.js';

export function repositoryOperationDialog() {
  const origin = document.activeElement;
  const controller = new AbortController();
  const dialog = document.createElement('dialog'); dialog.className = 'repository-operation-dialog';
  dialog.setAttribute('aria-labelledby', 'repository-operation-title');
  const title = document.createElement('h2'); title.id = 'repository-operation-title';
  title.textContent = uiText('repository.review.title');
  const message = document.createElement('p'); message.setAttribute('role', 'status');
  message.textContent = uiText('repository.review.working');
  const link = document.createElement('a'); link.hidden = true; link.target = '_blank'; link.rel = 'noopener noreferrer';
  link.textContent = uiText('repository.review.open');
  const close = document.createElement('button'); close.type = 'button'; close.disabled = true;
  close.textContent = uiText('repository.review.close');
  const retry = document.createElement('button'); retry.type = 'button'; retry.hidden = true;
  retry.textContent = uiText('repository.review.retry');
  const dismiss = () => { controller.abort(); dialog.close(); dialog.remove(); origin?.focus(); };
  dialog.addEventListener('close', () => controller.abort(), { once: true });
  close.addEventListener('click', dismiss);
  dialog.addEventListener('keydown', event => event.stopPropagation());
  dialog.addEventListener('cancel', event => { event.preventDefault(); if (!close.disabled) dismiss(); });
  dialog.append(title, message, link, close, retry); document.body.append(dialog); dialog.showModal();
  return {
    signal: controller.signal,
    success(result) {
      message.textContent = uiText(result.reused ? 'repository.review.reused' : 'repository.review.created');
      const url = new URL(result.url);
      if (url.protocol === 'https:' || url.protocol === 'http:') { link.href = url.href; link.hidden = false; }
      close.disabled = false; close.focus();
    },
    isOpen() { return dialog.open && dialog.isConnected; },
    released() { message.setAttribute('role', 'status'); message.textContent = uiText('repository.review.released'); },
    published() { retry.hidden = true; message.setAttribute('role', 'status'); message.textContent = uiText('repository.review.published'); },
    pending() { message.textContent = uiText('repository.review.pending'); },
    reconcile(action) {
      message.textContent = uiText('repository.review.reconcile'); retry.hidden = false;
      retry.onclick = async () => {
        retry.disabled = true;
        try { await action(); } finally { retry.disabled = false; }
      };
    },
    refused() { retry.hidden = true; message.textContent = uiText('repository.review.refused'); message.setAttribute('role', 'alert'); },
    automationQueued() { retry.hidden = true; message.textContent = uiText('repository.review.queued'); },
    automationFailed(reason) { message.textContent = uiText('repository.review.triggerFailed', { reason }); message.setAttribute('role', 'alert'); },
    failure(reason) { message.textContent = reason; message.setAttribute('role', 'alert'); close.disabled = false; close.focus(); },
  };
}

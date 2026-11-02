import { uiText } from './ui-text.js';

export function repositoryOperationDialog() {
  const origin = document.activeElement;
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
  const dismiss = () => { dialog.close(); dialog.remove(); origin?.focus(); };
  close.addEventListener('click', dismiss);
  dialog.addEventListener('keydown', event => event.stopPropagation());
  dialog.addEventListener('cancel', event => { event.preventDefault(); if (!close.disabled) dismiss(); });
  dialog.append(title, message, link, close); document.body.append(dialog); dialog.showModal();
  return {
    success(result) {
      message.textContent = uiText(result.reused ? 'repository.review.reused' : 'repository.review.created');
      const url = new URL(result.url);
      if (url.protocol === 'https:' || url.protocol === 'http:') { link.href = url.href; link.hidden = false; }
      close.disabled = false; close.focus();
    },
    failure(reason) { message.textContent = reason; message.setAttribute('role', 'alert'); close.disabled = false; close.focus(); },
  };
}

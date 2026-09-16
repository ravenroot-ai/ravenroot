import { humanTaskActionDisplayLabel, humanTaskActionLabelKey, utf8Length,
  validateDecisionComment } from './human-task-attention.js';

const ACTION_DISPOSITIONS = Object.freeze({ RESOLVE: 'Resolve', DENY: 'Deny', CANCEL: 'Cancel' });

export function humanTaskActionName(action, label) {
  const disposition = ACTION_DISPOSITIONS[action];
  const displayLabel = humanTaskActionDisplayLabel(label);
  return !displayLabel || humanTaskActionLabelKey(disposition) === humanTaskActionLabelKey(label)
    ? disposition : `${disposition} — ${displayLabel}`;
}

function short(value) {
  const string = String(value || '');
  return string.length > 18 ? `${string.slice(0, 8)}…${string.slice(-6)}` : string;
}

function time(value) {
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date.toLocaleString() : String(value || '');
}

function element(doc, name, className = '', text = '') {
  const result = doc.createElement(name);
  if (className) result.className = className;
  if (text) result.textContent = text;
  return result;
}

export function renderHumanTaskInspector(host, state, nodeId, {
  onSelect = () => {}, onNext = () => {}, onPrevious = () => {}, onRefresh = () => {},
} = {}) {
  const doc = host.ownerDocument;
  const previous = host.querySelector('[data-human-task-inspector]');
  const active = doc.activeElement;
  // A reconnect can replace ready → loading → ready after dialog focus was restored.
  // Carry only focus already owned by this node's Inspector, never a deferred focus claim
  // that could steal focus from a dialog, another control, or a newly selected node.
  const restoreFocus = previous?.contains(active) && previous.dataset.humanTaskNodeId === nodeId
    && active.matches('[data-human-task-id], .human-task-status');
  const focusedId = active?.dataset.humanTaskId ?? active?.dataset.humanTaskFocusId;
  const focusedGeneration = active?.dataset.humanTaskGeneration ?? active?.dataset.humanTaskFocusGeneration;
  previous?.remove();
  const section = element(doc, 'section', 'human-task-inspector');
  section.dataset.humanTaskInspector = '';
  section.dataset.humanTaskNodeId = nodeId;
  section.setAttribute('aria-labelledby', 'human-task-inspector-title');
  const heading = element(doc, 'div', 'editor-section-title');
  const title = element(doc, 'h3', '', 'Human tasks');
  title.id = 'human-task-inspector-title';
  heading.append(title);
  section.append(heading);

  const status = element(doc, 'p', 'human-task-status');
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');
  if (state.kind === 'unavailable') status.textContent = state.message;
  else if (state.kind === 'loading') status.textContent = 'Loading actionable tasks from the service…';
  else if (state.kind === 'error') status.textContent = state.message;
  else if (state.kind === 'ready') {
    const pending = state.counts?.pending ?? state.items.length;
    const escalated = state.counts?.escalated ?? 0;
    status.textContent = pending
      ? `${pending} actionable task${pending === 1 ? '' : 's'} for this node`
        + `${escalated ? ` · ${escalated} escalated` : ''}.`
      : 'No actionable Human Tasks for this node.';
  }
  section.append(status);

  if (state.kind === 'error') {
    const reconnect = element(doc, 'button', 'btn', 'Reconnect');
    reconnect.type = 'button';
    reconnect.addEventListener('click', onRefresh);
    section.append(reconnect);
  }

  if (state.kind === 'ready' && state.items.length) {
    const list = element(doc, 'ul', 'human-task-list');
    list.setAttribute('aria-label', `Actionable Human Tasks for node ${nodeId}`);
    for (const item of state.items) {
      const row = element(doc, 'li', `human-task-row${item.status === 'ESCALATED' ? ' is-escalated' : ''}`);
      const button = element(doc, 'button', 'human-task-row-button');
      button.type = 'button';
      button.dataset.humanTaskId = item.taskId;
      button.dataset.humanTaskGeneration = String(item.generation);
      button.setAttribute('aria-label', `${item.status === 'ESCALATED' ? 'Escalated' : 'Pending'}: `
        + `${item.presentation.prompt}. Task ${item.taskId}, generation ${item.generation}.`);
      const head = element(doc, 'span', 'human-task-row-head');
      head.append(element(doc, 'strong', 'human-task-row-prompt', item.presentation.prompt),
        element(doc, 'span', 'human-task-state', item.status === 'ESCALATED' ? '▲ Escalated' : '● Pending'));
      const identity = element(doc, 'span', 'human-task-row-identity',
        `Task ${short(item.taskId)} · process ${short(item.processInstanceId)}`
        + ` · traversal ${short(item.traversalId)}`
        + `${item.deploymentId ? ` · deployment ${short(item.deploymentId)}` : ''}`
        + ` · generation ${item.generation}`);
      const dates = element(doc, 'span', 'human-task-row-time',
        `Created ${time(item.createdAt)} · expires ${time(item.expiresAt)}`);
      button.append(head, identity, dates);
      button.addEventListener('click', () => onSelect(item));
      row.append(button);
      list.append(row);
    }
    section.append(list);
  }

  if (state.kind === 'ready') {
    const controls = element(doc, 'div', 'human-task-pagination');
    const previous = element(doc, 'button', 'btn', 'Previous');
    previous.type = 'button'; previous.disabled = !state.hasPrevious;
    previous.addEventListener('click', onPrevious);
    const page = element(doc, 'span', '', `Page ${state.pageNumber || 1}`);
    const next = element(doc, 'button', 'btn', 'Next');
    next.type = 'button'; next.disabled = !state.nextCursor;
    next.addEventListener('click', onNext);
    const refresh = element(doc, 'button', 'btn', 'Refresh');
    refresh.type = 'button'; refresh.addEventListener('click', onRefresh);
    controls.append(previous, page, next, refresh);
    section.append(controls);
  }
  // Actionable work belongs at the top of the selected-node Inspector. The ordinary node details
  // remain below it, but a long property list must not bury a time-sensitive confirmation.
  host.prepend(section);
  host.scrollTop = 0;
  if (restoreFocus) {
    const rows = [...section.querySelectorAll('[data-human-task-id]')];
    const target = rows.find(row => row.dataset.humanTaskId === focusedId
      && row.dataset.humanTaskGeneration === focusedGeneration) || rows[0] || status;
    if (target === status) {
      status.tabIndex = -1;
      if (focusedId !== undefined) {
        status.dataset.humanTaskFocusId = focusedId;
        status.dataset.humanTaskFocusGeneration = focusedGeneration;
      }
    }
    target.focus({ preventScroll: true });
  }
  return section;
}

export function createHumanTaskDecisionDialog({ dialog, onSubmit = async () => ({}), onClose = () => {} } = {}) {
  if (!dialog) return { open() {}, close() {}, suspend() {}, selected: () => null };
  const prompt = dialog.querySelector('[data-human-task-prompt]');
  const identity = dialog.querySelector('[data-human-task-identity]');
  const review = dialog.querySelector('[data-human-task-review]');
  const reviewStatus = dialog.querySelector('[data-human-task-review-status]');
  const reviewText = dialog.querySelector('[data-human-task-review-text]');
  const reviewDigest = dialog.querySelector('[data-human-task-review-digest]');
  const commentField = dialog.querySelector('[data-human-task-comment-field]');
  const comment = dialog.querySelector('[data-human-task-comment]');
  const commentHint = dialog.querySelector('[data-human-task-comment-hint]');
  const error = dialog.querySelector('[data-human-task-error]');
  const actions = dialog.querySelector('[data-human-task-actions]');
  let task = null;
  let capability = null;
  let submitting = false;
  let generation = 0;
  let notifyAfterNativeClose = false;

  const taskKey = value => value && `${value.taskId}\u0000${value.generation}`;
  function advance() { generation += 1; }
  function isCurrent(token, expectedTask) {
    return generation === token && taskKey(task) === taskKey(expectedTask);
  }

  function say(message = '') {
    error.textContent = message;
    error.hidden = !message;
    comment.toggleAttribute('aria-invalid', Boolean(message));
  }

  function setBusy(value) {
    submitting = value;
    dialog.querySelectorAll('button').forEach(button => { button.disabled = value; });
    dialog.setAttribute('aria-busy', String(value));
  }

  function clearReview() {
    review.hidden = true;
    reviewStatus.textContent = '';
    reviewText.textContent = '';
    reviewDigest.textContent = '';
  }

  function identify(value) {
    prompt.textContent = value.presentation?.prompt || '';
    identity.textContent = `Task ${value.taskId} · process ${value.processInstanceId || 'pending'}`
      + `${value.traversalId ? ` · traversal ${value.traversalId}` : ''}`
      + `${value.deploymentId ? ` · deployment ${value.deploymentId}` : ''}`
      + ` · generation ${value.generation}`;
  }

  function close() {
    if (submitting) return;
    advance();
    task = null;
    capability = null;
    comment.value = '';
    clearReview();
    say();
    if (dialog.open && typeof dialog.close === 'function') {
      notifyAfterNativeClose = true;
      dialog.close();
    } else {
      dialog.removeAttribute('open');
      onClose();
    }
  }

  function suspend() {
    advance();
    submitting = false;
    dialog.removeAttribute('aria-busy');
    task = null;
    capability = null;
    comment.value = '';
    clearReview();
    say();
    notifyAfterNativeClose = false;
    if (dialog.open && typeof dialog.close === 'function') dialog.close();
    else dialog.removeAttribute('open');
  }

  async function decide(action) {
    if (!task || !capability || submitting) return;
    const check = validateDecisionComment(comment.value, task.presentation.commentRequirement,
      task.commentMaxUtf8Bytes);
    if (!check.ok) { say(check.error); comment.focus(); return; }
    const sourceTask = task;
    const token = generation;
    say();
    setBusy(true);
    try {
      await onSubmit({ task: sourceTask, action, comment: check.value,
        isCurrent: () => isCurrent(token, sourceTask) });
    } catch (failure) {
      if (isCurrent(token, sourceTask)) {
        say(failure?.message || 'The decision outcome is unknown. Refresh before trying another action.');
        setBusy(false);
      }
      return;
    }
    if (!isCurrent(token, sourceTask)) return;
    setBusy(false);
    close();
  }

  actions.addEventListener('click', event => {
    const button = event.target.closest('[data-human-task-action]');
    if (button) void decide(button.dataset.humanTaskAction);
  });
  dialog.querySelector('[data-human-task-close]').addEventListener('click', close);
  dialog.addEventListener('cancel', event => { event.preventDefault(); close(); });
  dialog.addEventListener('close', () => {
    if (!notifyAfterNativeClose) return;
    notifyAfterNativeClose = false;
    onClose();
  });
  dialog.addEventListener('keydown', event => event.stopPropagation());
  comment.addEventListener('input', () => {
    say();
    commentHint.textContent = `${utf8Length(comment.value)} / ${task?.commentMaxUtf8Bytes || 0} UTF-8 bytes`;
  });

  return {
    loading(nextTask, nextCapability, { show = false } = {}) {
      advance();
      submitting = false;
      task = nextTask;
      capability = nextCapability;
      identify(task);
      review.hidden = false;
      reviewStatus.textContent = 'Loading authorized review content…';
      reviewText.textContent = '';
      reviewDigest.textContent = '';
      commentField.hidden = true;
      actions.replaceChildren();
      say();
      dialog.setAttribute('aria-busy', 'true');
      if (show && !dialog.open) {
        dialog.showModal ? dialog.showModal() : dialog.setAttribute('open', '');
      }
    },
    unavailable(message) {
      if (!task) return;
      dialog.removeAttribute('aria-busy');
      review.hidden = false;
      reviewStatus.textContent = message;
      reviewText.textContent = '';
      reviewDigest.textContent = '';
      say(message);
      if (!dialog.open) {
        dialog.showModal ? dialog.showModal() : dialog.setAttribute('open', '');
      }
    },
    open(nextTask, nextCapability) {
      advance();
      setBusy(false);
      task = nextTask;
      capability = nextCapability;
      identify(task);
      clearReview();
      if (task.reviewPresentation) {
        review.hidden = false;
        reviewStatus.textContent = task.reviewPresentation.text.length
          ? 'Exact inert text/plain content pinned when this task was created.'
          : 'This task has an empty plain-text review value.';
        reviewText.textContent = task.reviewPresentation.text;
        reviewDigest.textContent = `Content ${task.reviewPresentation.contentDigest}`;
      }
      const mode = task.presentation.commentRequirement;
      commentField.hidden = mode === 'DISALLOWED';
      comment.required = mode === 'REQUIRED';
      comment.value = '';
      commentHint.textContent = mode === 'REQUIRED'
        ? `Required · 0 / ${task.commentMaxUtf8Bytes} UTF-8 bytes`
        : `Optional · 0 / ${task.commentMaxUtf8Bytes} UTF-8 bytes`;
      actions.replaceChildren(...task.availableActions.map(action => {
        const actionName = humanTaskActionName(action, task.presentation.labels[action]);
        const button = element(dialog.ownerDocument, 'button',
          `btn human-task-decision human-task-decision-${action.toLowerCase()}`,
          actionName);
        button.type = 'button';
        button.dataset.humanTaskAction = action;
        button.setAttribute('aria-label', actionName);
        return button;
      }));
      say();
      if (!dialog.open) {
        dialog.showModal ? dialog.showModal() : dialog.setAttribute('open', '');
      }
      (mode === 'REQUIRED' ? comment : actions.querySelector('button'))?.focus();
    },
    close, suspend,
    selected: () => task && { taskId: task.taskId, generation: task.generation },
  };
}

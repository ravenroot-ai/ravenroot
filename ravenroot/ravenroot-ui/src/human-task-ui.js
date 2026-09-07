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

function humanTaskRow(doc, item, onSelect) {
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
    `Task ${short(item.taskId)} · node ${short(item.nodeId)}`
    + ` · process ${short(item.processInstanceId)} · traversal ${short(item.traversalId)}`
    + `${item.deploymentId ? ` · deployment ${short(item.deploymentId)}` : ''}`
    + ` · generation ${item.generation}`);
  const dates = element(doc, 'span', 'human-task-row-time',
    `Created ${time(item.createdAt)} · expires ${time(item.expiresAt)}`);
  button.append(head, identity, dates);
  button.addEventListener('click', () => onSelect(item));
  row.append(button);
  return row;
}

export function renderHumanTaskInspector(host, state, nodeId, {
  onSelect = () => {}, onNext = () => {}, onPrevious = () => {}, onRefresh = () => {},
} = {}) {
  host.querySelector('[data-human-task-inspector]')?.remove();
  const doc = host.ownerDocument;
  const section = element(doc, 'section', 'human-task-inspector');
  section.dataset.humanTaskInspector = '';
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
      list.append(humanTaskRow(doc, item, onSelect));
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
  return section;
}

export function createHumanTaskDecisionDialog({ dialog, onSubmit = async () => ({}), onClose = () => {},
  onRelatedPage = async () => null, onRelatedSelect = () => false } = {}) {
  if (!dialog) return { open() {}, close() {}, suspend() {}, selected: () => null };
  const decisionView = dialog.querySelector('[data-human-task-decision-view]') || dialog.querySelector('form');
  const prompt = dialog.querySelector('[data-human-task-prompt]');
  const identity = dialog.querySelector('[data-human-task-identity]');
  const commentField = dialog.querySelector('[data-human-task-comment-field]');
  const comment = dialog.querySelector('[data-human-task-comment]');
  const commentHint = dialog.querySelector('[data-human-task-comment-hint]');
  const error = dialog.querySelector('[data-human-task-error]');
  const actions = dialog.querySelector('[data-human-task-actions]');
  const relatedOpen = dialog.querySelector('[data-human-task-related-open]');
  const relatedBack = dialog.querySelector('[data-human-task-related-back]');
  const relatedView = dialog.querySelector('[data-human-task-related-view]');
  const relatedTitle = dialog.querySelector('#human-task-related-title');
  const relatedStatus = dialog.querySelector('[data-human-task-related-status]');
  const relatedList = dialog.querySelector('[data-human-task-related-list]');
  const relatedPagination = dialog.querySelector('[data-human-task-related-pagination]');
  const relatedPrevious = dialog.querySelector('[data-human-task-related-previous]');
  const relatedNext = dialog.querySelector('[data-human-task-related-next]');
  const relatedPage = dialog.querySelector('[data-human-task-related-page]');
  const relatedRefresh = dialog.querySelector('[data-human-task-related-refresh]');
  const relatedCurrent = dialog.querySelector('[data-human-task-related-current]');
  let task = null;
  let capability = null;
  let lease = null;
  let submitting = false;
  let generation = 0;
  let relatedController = null;
  let decisionController = null;
  let relatedCursors = [null];
  let relatedPageIndex = 0;
  let relatedPageValue = null;
  let relatedCanReturn = true;
  let relatedWasOpened = false;

  const taskKey = value => value && `${value.taskId}\u0000${value.generation}`;
  function isCurrent(token, expectedTask = task) {
    return generation === token && (!expectedTask || taskKey(task) === taskKey(expectedTask));
  }

  function abortRelated() {
    relatedController?.abort();
    relatedController = null;
  }

  function advance() {
    generation += 1;
    abortRelated();
    decisionController?.abort();
    decisionController = null;
    return generation;
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

  function resetRelatedView() {
    relatedCursors = [null];
    relatedPageIndex = 0;
    relatedPageValue = null;
    relatedCanReturn = true;
    relatedWasOpened = false;
    relatedList?.replaceChildren();
    if (relatedStatus) relatedStatus.textContent = '';
    if (relatedPagination) relatedPagination.hidden = true;
    if (relatedPrevious) relatedPrevious.disabled = true;
    if (relatedNext) relatedNext.disabled = true;
    if (relatedPage) relatedPage.textContent = 'Page 1';
    if (relatedRefresh) relatedRefresh.disabled = false;
    if (relatedCurrent) relatedCurrent.hidden = false;
  }

  function close() {
    if (submitting) return;
    advance();
    task = null;
    capability = null;
    lease = null;
    resetRelatedView();
    comment.value = '';
    say();
    if (dialog.open && typeof dialog.close === 'function') dialog.close();
    else dialog.removeAttribute('open');
    onClose();
  }

  function suspend() {
    advance();
    submitting = false;
    dialog.removeAttribute('aria-busy');
    task = null;
    capability = null;
    lease = null;
    resetRelatedView();
    comment.value = '';
    say();
    if (dialog.open && typeof dialog.close === 'function') dialog.close();
    else dialog.removeAttribute('open');
  }

  function focusDecision() {
    const mode = task?.presentation.commentRequirement;
    (mode === 'REQUIRED' ? comment : actions.querySelector('button'))?.focus();
  }

  function showDecision(nextTask, { fromRelated = false, focus = true } = {}) {
    task = nextTask;
    decisionView.hidden = false;
    if (relatedView) relatedView.hidden = true;
    prompt.textContent = task.presentation.prompt;
    identity.textContent = `Task ${task.taskId} · process ${task.processInstanceId}`
      + ` · traversal ${task.traversalId}`
      + `${task.deploymentId ? ` · deployment ${task.deploymentId}` : ''}`
      + ` · generation ${task.generation}`;
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
        `btn human-task-decision human-task-decision-${action.toLowerCase()}`, actionName);
      button.type = 'button';
      button.dataset.humanTaskAction = action;
      button.setAttribute('aria-label', actionName);
      return button;
    }));
    if (relatedOpen) relatedOpen.hidden = !lease?.relatedContext || relatedPageValue != null;
    if (relatedBack) relatedBack.hidden = !lease?.relatedContext || !relatedWasOpened || !fromRelated;
    say();
    if (focus) focusDecision();
  }

  function renderRelatedPage(page) {
    relatedPageValue = page;
    const pending = page.counts.pending;
    const escalated = page.counts.escalated;
    relatedStatus.textContent = pending === 0
      ? 'No actionable related Human Tasks remain for this node.'
      : page.items.length === 0
        ? `${pending} actionable related Human Task${pending === 1 ? '' : 's'} remain for this node; none are on this page.`
        : `${pending} actionable related Human Task${pending === 1 ? '' : 's'} for this node`
          + `${escalated ? ` · ${escalated} escalated` : ''}.`;
    relatedList.replaceChildren(...page.items.map(item => humanTaskRow(dialog.ownerDocument, item, selected => {
      if (!lease || !onRelatedSelect({ task: selected, lease })) return;
      advance();
      relatedCanReturn = true;
      relatedCursors = [null];
      relatedPageIndex = 0;
      relatedPageValue = null;
      showDecision(selected, { fromRelated: true });
    })));
    relatedPagination.hidden = false;
    relatedPrevious.disabled = relatedPageIndex < 1;
    relatedNext.disabled = !page.nextCursor;
    relatedPage.textContent = `Page ${relatedPageIndex + 1}`;
    relatedRefresh.disabled = false;
  }

  async function showRelated({ reset = false, focus = true, current = relatedCanReturn } = {}) {
    if (!lease?.relatedContext || !task) return;
    relatedWasOpened = true;
    if (reset) { relatedCursors = [null]; relatedPageIndex = 0; }
    const sourceTask = task;
    const sourceLease = lease;
    const token = advance();
    relatedCanReturn = current;
    decisionView.hidden = true;
    relatedView.hidden = false;
    if (relatedCurrent) relatedCurrent.hidden = !current;
    relatedStatus.textContent = 'Loading related Human Tasks from the service…';
    relatedList.replaceChildren();
    relatedPagination.hidden = true;
    relatedController = new AbortController();
    const signal = relatedController.signal;
    if (focus) relatedTitle?.focus();
    try {
      const page = await onRelatedPage({ task: sourceTask, lease: sourceLease,
        cursor: relatedCursors[relatedPageIndex] || undefined, signal });
      if (!isCurrent(token, sourceTask) || signal.aborted) return;
      if (!page) {
        relatedStatus.textContent = 'Related tasks are unavailable for this task.';
        relatedCurrent?.focus();
        return;
      }
      renderRelatedPage(page);
      (relatedList.querySelector('button') || relatedStatus)?.focus?.();
    } catch (failure) {
      if (!isCurrent(token, sourceTask) || signal.aborted) return;
      if (failure?.status === 400 && relatedPageIndex > 0) {
        relatedCursors = [null];
        relatedPageIndex = 0;
        relatedPageValue = null;
        void showRelated({ focus: false });
        return;
      }
      relatedStatus.textContent = `Related Human Tasks could not be refreshed: ${failure?.message || failure}`;
      relatedPagination.hidden = false;
      relatedPrevious.disabled = relatedPageIndex < 1;
      relatedNext.disabled = true;
      relatedRefresh.disabled = false;
      relatedRefresh.focus();
    } finally {
      if (generation === token) relatedController = null;
    }
  }

  async function decide(action) {
    if (!task || !capability || submitting) return;
    const check = validateDecisionComment(comment.value, task.presentation.commentRequirement,
      task.commentMaxUtf8Bytes);
    if (!check.ok) { say(check.error); comment.focus(); return; }
    const sourceTask = task;
    const sourceLease = lease;
    const token = generation;
    decisionController = new AbortController();
    const signal = decisionController.signal;
    say();
    setBusy(true);
    try {
      await onSubmit({ task: sourceTask, action, comment: check.value, lease: sourceLease, signal,
        isCurrent: () => isCurrent(token, sourceTask) });
    } catch (failure) {
      if (isCurrent(token, sourceTask)) {
        decisionController = null;
        say(failure?.message || 'The decision outcome is unknown. Refresh before trying another action.');
        setBusy(false);
      }
      return;
    }
    if (!isCurrent(token, sourceTask)) return;
    decisionController = null;
    setBusy(false);
    if (!sourceLease?.relatedContext || !relatedWasOpened) { close(); return; }
    clearHumanTaskForm();
    relatedCanReturn = false;
    await showRelated({ reset: true, focus: true, current: false });
  }

  function clearHumanTaskForm() {
    comment.value = '';
    actions.replaceChildren();
    say();
  }

  actions.addEventListener('click', event => {
    const button = event.target.closest('[data-human-task-action]');
    if (button) void decide(button.dataset.humanTaskAction);
  });
  relatedOpen?.addEventListener('click', () => { void showRelated({ reset: true }); });
  relatedBack?.addEventListener('click', () => { void showRelated(); });
  relatedCurrent?.addEventListener('click', () => {
    if (task) { advance(); showDecision(task, { fromRelated: true }); }
  });
  relatedRefresh?.addEventListener('click', () => { void showRelated(); });
  relatedPrevious?.addEventListener('click', () => {
    if (relatedPageIndex < 1) return;
    relatedPageIndex -= 1;
    void showRelated();
  });
  relatedNext?.addEventListener('click', () => {
    if (!relatedPageValue?.nextCursor) return;
    relatedCursors[relatedPageIndex + 1] = relatedPageValue.nextCursor;
    relatedPageIndex += 1;
    void showRelated();
  });
  dialog.querySelector('[data-human-task-close]').addEventListener('click', close);
  dialog.addEventListener('cancel', event => { event.preventDefault(); close(); });
  dialog.addEventListener('keydown', event => event.stopPropagation());
  comment.addEventListener('input', () => {
    say();
    commentHint.textContent = `${utf8Length(comment.value)} / ${task?.commentMaxUtf8Bytes || 0} UTF-8 bytes`;
  });

  return {
    open(nextTask, nextCapability, { relatedLease = null } = {}) {
      advance();
      setBusy(false);
      capability = nextCapability;
      lease = relatedLease;
      resetRelatedView();
      showDecision(nextTask, { focus: false });
      dialog.showModal ? dialog.showModal() : dialog.setAttribute('open', '');
      focusDecision();
    },
    close, suspend,
    selected: () => task && { taskId: task.taskId, generation: task.generation },
  };
}

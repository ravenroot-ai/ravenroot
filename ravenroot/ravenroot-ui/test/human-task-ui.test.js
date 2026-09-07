import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';

import { createHumanTaskDecisionDialog, humanTaskActionName,
  renderHumanTaskInspector } from '../src/human-task-ui.js';

const capability = { commentMaxUtf8Bytes: 4096 };
const task = { taskId: 'task-secret-safe-id', generation: 7, status: 'ESCALATED',
  commentMaxUtf8Bytes: 8,
  promptMaxUtf8Bytes: 4096, actionLabelMaxUtf8Bytes: 64,
  graphVersion: 'graph-safe-id', deploymentId: 'deployment-safe-id', nodeId: 'review',
  processInstanceId: 'process-safe-id', traversalId: 'traversal-safe-id',
  createdAt: '2026-09-06T08:00:00Z',
  expiresAt: '2026-09-07T08:00:00Z', presentation: { prompt: '<b>Approve?</b>',
    commentRequirement: 'REQUIRED', labels: { RESOLVE: '<Confirm>', DENY: 'No', CANCEL: 'Cancel' } },
  availableActions: ['RESOLVE', 'DENY'] };

function dialogDocument() {
  return new JSDOM(`<body><button id="stable">Run</button><dialog id="d" aria-labelledby="t"><form>
    <button type="button" data-human-task-close>Close</button><div data-human-task-decision-view>
    <p data-human-task-prompt></p><p data-human-task-identity></p>
    <button type="button" data-human-task-related-open hidden>View related tasks</button>
    <button type="button" data-human-task-related-back hidden>Back to related tasks</button>
    <div data-human-task-comment-field><textarea data-human-task-comment></textarea>
    <small data-human-task-comment-hint></small></div><p data-human-task-error hidden></p>
    <div data-human-task-actions></div></div><section data-human-task-related-view hidden>
    <h3 id="human-task-related-title" tabindex="-1">Related Human tasks</h3>
    <p data-human-task-related-status></p><ul data-human-task-related-list></ul>
    <div data-human-task-related-pagination hidden><button type="button" data-human-task-related-previous>Previous</button>
    <span data-human-task-related-page></span><button type="button" data-human-task-related-next>Next</button>
    <button type="button" data-human-task-related-refresh>Refresh</button></div>
    <button type="button" data-human-task-related-current>Back to current task</button></section>
    </form></dialog></body>`)
    .window.document;
}

describe('Human Task inspector and decision dialog', () => {
  it('renders multiple rows as text and opens only the explicitly selected task', () => {
    const doc = new JSDOM('<body><div id="host"></div></body>').window.document;
    const selected = vi.fn();
    renderHumanTaskInspector(doc.getElementById('host'), { kind: 'ready', items: [task,
      { ...task, taskId: 'task-2', generation: 8, status: 'WAITING' }],
    counts: { pending: 2, escalated: 1 }, nextCursor: null, hasPrevious: false, pageNumber: 1 },
    'human-confirmation', { onSelect: selected });
    const buttons = doc.querySelectorAll('[data-human-task-id]');
    expect(buttons).toHaveLength(2);
    expect(doc.querySelector('b')).toBeNull();
    expect(doc.querySelector('.human-task-status').textContent).toContain('2 actionable tasks');
    buttons[1].click();
    expect(selected).toHaveBeenCalledWith(expect.objectContaining({ taskId: 'task-2', generation: 8 }));
  });

  it('contains focus, requires the policy-bounded comment, and submits exactly once', async () => {
    const doc = dialogDocument();
    const submitted = vi.fn(async () => ({}));
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'),
      onSubmit: submitted });
    controller.open(task, capability);
    expect(doc.querySelector('[data-human-task-prompt]').textContent).toBe('<b>Approve?</b>');
    expect(doc.querySelector('[data-human-task-actions]').innerHTML).not.toContain('<confirm>');
    expect(doc.querySelector('[data-human-task-action="RESOLVE"]').textContent).toBe('Resolve — <Confirm>');
    expect(doc.querySelector('[data-human-task-action="DENY"]').getAttribute('aria-label')).toBe('Deny — No');
    expect(doc.querySelector('[data-human-task-comment-hint]').textContent).toContain('/ 8 UTF-8 bytes');
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await Promise.resolve();
    expect(submitted).not.toHaveBeenCalled();
    expect(doc.querySelector('[data-human-task-error]').textContent).toContain('required');
    doc.querySelector('[data-human-task-comment]').value = '\u{1F642}\u{1F642}';
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(submitted).toHaveBeenCalledTimes(1);
    expect(submitted).toHaveBeenCalledWith(expect.objectContaining({ task, action: 'RESOLVE',
      comment: '\u{1F642}\u{1F642}' }));
  });

  it('keeps the disposition explicit for duplicate historical labels and unfamiliar code points', () => {
    expect(humanTaskActionName('RESOLVE', 'Proceed')).toBe('Resolve — Proceed');
    expect(humanTaskActionName('DENY', ' proceed ')).toBe('Deny — proceed');
    expect(humanTaskActionName('DENY', 'Deny')).toBe('Deny');
    expect(humanTaskActionName('RESOLVE', 'A')).toBe('Resolve — A');
    expect(humanTaskActionName('DENY', '\u{1ccd6}')).toBe('Deny — \u{1ccd6}');
    expect(humanTaskActionName('RESOLVE', '\u00a0')).toBe('Resolve');
    expect(humanTaskActionName('DENY', '\ufeff')).toBe('Deny — \ufeff');
  });

  it('suspends stale presentation during reauthentication without clearing the durable locator', () => {
    const doc = dialogDocument();
    const closed = vi.fn();
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'), onClose: closed });
    controller.open(task, capability);
    controller.suspend();
    expect(doc.getElementById('d').open).toBe(false);
    expect(controller.selected()).toBeNull();
    expect(closed).not.toHaveBeenCalled();
  });

  it('suspends immediately when authentication changes during an uncertain decision', async () => {
    const doc = dialogDocument();
    const closed = vi.fn();
    let rejectDecision;
    const pending = new Promise((_resolve, reject) => { rejectDecision = reject; });
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'), onClose: closed,
      onSubmit: () => pending });
    controller.open(task, capability);
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();

    controller.suspend();
    expect(doc.getElementById('d').open).toBe(false);
    expect(controller.selected()).toBeNull();
    rejectDecision(new Error('connection lost'));
    await pending.catch(() => {});
    await Promise.resolve();
    expect(doc.querySelector('[data-human-task-error]').hidden).toBe(true);
    expect(closed).not.toHaveBeenCalled();
  });

  it('opens a server-counted related page and persists only the explicitly selected fresh row', async () => {
    const doc = dialogDocument();
    const second = { ...task, taskId: 'task-2', generation: 8, status: 'WAITING' };
    const onRelatedPage = vi.fn(async () => ({ items: [task, second], nextCursor: 'next',
      counts: { pending: 2, escalated: 1 } }));
    const onRelatedSelect = vi.fn(() => true);
    const lease = { opaque: true, relatedContext: { graphVersion: 'graph-safe-id',
      deploymentId: 'deployment-safe-id', nodeId: 'review' } };
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'),
      onRelatedPage, onRelatedSelect });
    controller.open(task, capability, { relatedLease: lease });
    doc.querySelector('[data-human-task-related-open]').click();
    await vi.waitFor(() => expect(onRelatedPage).toHaveBeenCalledWith(expect.objectContaining({
      task, lease, cursor: undefined, signal: expect.any(AbortSignal),
    })));
    expect(doc.querySelector('[data-human-task-related-status]').textContent)
      .toContain('2 actionable related Human Tasks');
    expect(doc.querySelectorAll('[data-human-task-related-list] [data-human-task-id]')).toHaveLength(2);
    doc.querySelector('[data-human-task-related-list] [data-human-task-id="task-2"]').click();
    expect(onRelatedSelect).toHaveBeenCalledWith({ task: second, lease });
    expect(controller.selected()).toEqual({ taskId: 'task-2', generation: 8 });
  });

  it('distinguishes an empty page from an authoritative zero and keeps read failures recoverable', async () => {
    const doc = dialogDocument();
    const pages = [
      { items: [], nextCursor: null, counts: { pending: 2, escalated: 0 } },
      { items: [], nextCursor: null, counts: { pending: 0, escalated: 0 } },
    ];
    const onRelatedPage = vi.fn(async () => pages.shift());
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'), onRelatedPage });
    controller.open(task, capability, { relatedLease: { relatedContext: {} } });
    doc.querySelector('[data-human-task-related-open]').click();
    await vi.waitFor(() => expect(doc.querySelector('[data-human-task-related-status]').textContent)
      .toContain('none are on this page'));
    expect(doc.querySelector('[data-human-task-related-status]').textContent)
      .not.toContain('No actionable related');
    doc.querySelector('[data-human-task-related-refresh]').click();
    await vi.waitFor(() => expect(doc.querySelector('[data-human-task-related-status]').textContent)
      .toContain('No actionable related Human Tasks remain'));
  });

  it('makes late decision success and failure inert after a replacement task opens', async () => {
    const doc = dialogDocument();
    const settlements = [];
    const onSubmit = vi.fn(() => new Promise((resolve, reject) => settlements.push({ resolve, reject })));
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'), onSubmit });
    controller.open(task, capability);
    doc.querySelector('[data-human-task-comment]').value = 'ok';
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const replacement = { ...task, taskId: 'replacement', generation: 9 };
    controller.suspend();
    controller.open(replacement, capability);
    doc.querySelector('[data-human-task-comment]').value = 'new';
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
    settlements[0].resolve({ outcome: 'APPLIED' });
    await Promise.resolve();
    expect(controller.selected()).toEqual({ taskId: 'replacement', generation: 9 });
    expect(doc.getElementById('d').open).toBe(true);
    expect(doc.querySelector('[data-human-task-error]').hidden).toBe(true);
    expect(doc.querySelectorAll('button:disabled').length).toBeGreaterThan(0);
    settlements[1].reject(new Error('old failure'));
    await vi.waitFor(() => expect(doc.querySelector('[data-human-task-error]').textContent)
      .toContain('old failure'));
    expect(doc.querySelectorAll('button:disabled')).toHaveLength(0);
  });

  it('makes a late decision inert when the same task is reopened under a new dialog generation', async () => {
    const doc = dialogDocument();
    const settlements = [];
    const onSubmit = vi.fn(() => new Promise((resolve, reject) => settlements.push({ resolve, reject })));
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'), onSubmit });
    controller.open(task, capability);
    doc.querySelector('[data-human-task-comment]').value = 'ok';
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    controller.suspend();
    controller.open(task, capability);
    doc.querySelector('[data-human-task-comment]').value = 'new';
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
    settlements[0].reject(new Error('old failure'));
    await Promise.resolve();
    await Promise.resolve();
    expect(controller.selected()).toEqual({ taskId: task.taskId, generation: task.generation });
    expect(doc.getElementById('d').open).toBe(true);
    expect(doc.querySelector('[data-human-task-error]').hidden).toBe(true);
    expect(doc.querySelectorAll('button:disabled').length).toBeGreaterThan(0);
    settlements[1].resolve({ outcome: 'APPLIED' });
    await vi.waitFor(() => expect(doc.getElementById('d').open).toBe(false));
  });

  it('discards a rejected page cursor before returning to the authoritative first page', async () => {
    const doc = dialogDocument();
    const cursorFailure = Object.assign(new Error('cursor no longer matches'), { status: 400 });
    const onRelatedPage = vi.fn()
      .mockResolvedValueOnce({ items: [task], nextCursor: 'next', counts: { pending: 2, escalated: 1 } })
      .mockRejectedValueOnce(cursorFailure)
      .mockResolvedValueOnce({ items: [task], nextCursor: null, counts: { pending: 1, escalated: 1 } });
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'), onRelatedPage });
    controller.open(task, capability, { relatedLease: { relatedContext: {} } });
    doc.querySelector('[data-human-task-related-open]').click();
    await vi.waitFor(() => expect(doc.querySelector('[data-human-task-related-next]').disabled).toBe(false));
    doc.querySelector('[data-human-task-related-next]').click();
    await vi.waitFor(() => expect(onRelatedPage).toHaveBeenCalledTimes(3));
    expect(onRelatedPage.mock.calls.map(([request]) => request.cursor)).toEqual([undefined, 'next', undefined]);
    expect(doc.querySelector('[data-human-task-related-page]').textContent).toBe('Page 1');
    expect(doc.querySelector('[data-human-task-related-previous]').disabled).toBe(true);
  });

  it('ignores late related-page success and failure after a newer request or close', async () => {
    const doc = dialogDocument();
    const settlements = [];
    const onRelatedPage = vi.fn(() => new Promise((resolve, reject) => settlements.push({ resolve, reject })));
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'), onRelatedPage });
    controller.open(task, capability, { relatedLease: { relatedContext: {} } });
    doc.querySelector('[data-human-task-related-open]').click();
    await vi.waitFor(() => expect(onRelatedPage).toHaveBeenCalledTimes(1));
    // The refresh control is programmatically activated because its pagination group is hidden
    // while loading. This directly proves that generation fencing, rather than AbortSignal
    // cooperation by the request provider, chooses the result that may update the dialog.
    doc.querySelector('[data-human-task-related-refresh]').click();
    await vi.waitFor(() => expect(onRelatedPage).toHaveBeenCalledTimes(2));
    settlements[0].resolve({ items: [{ ...task, taskId: 'stale' }], nextCursor: null,
      counts: { pending: 1, escalated: 0 } });
    await Promise.resolve();
    expect(doc.querySelector('[data-human-task-related-list] [data-human-task-id="stale"]')).toBeNull();
    settlements[1].resolve({ items: [task], nextCursor: null, counts: { pending: 1, escalated: 1 } });
    await vi.waitFor(() => expect(doc.querySelector('[data-human-task-related-list] [data-human-task-id]'))
      .not.toBeNull());

    doc.querySelector('[data-human-task-related-refresh]').click();
    await vi.waitFor(() => expect(onRelatedPage).toHaveBeenCalledTimes(3));
    controller.close();
    settlements[2].reject(new Error('late list failure'));
    await Promise.resolve();
    await Promise.resolve();
    expect(doc.getElementById('d').open).toBe(false);
    expect(doc.querySelector('[data-human-task-related-status]').textContent)
      .not.toContain('late list failure');
  });

  it('erases rendered related rows and paging state when authority is suspended', async () => {
    const doc = dialogDocument();
    const onRelatedPage = vi.fn(async ({ cursor }) => ({ items: [task],
      nextCursor: cursor ? null : 'next', counts: { pending: 1, escalated: 1 } }));
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'), onRelatedPage });
    controller.open(task, capability, { relatedLease: { relatedContext: {} } });
    doc.querySelector('[data-human-task-related-open]').click();
    await vi.waitFor(() => expect(doc.querySelector('[data-human-task-related-list] [data-human-task-id]'))
      .not.toBeNull());
    expect(doc.querySelector('[data-human-task-related-status]').textContent).toContain('1 actionable');
    expect(doc.querySelector('[data-human-task-related-next]').disabled).toBe(false);

    controller.suspend();
    expect(doc.querySelector('[data-human-task-related-list]').children).toHaveLength(0);
    expect(doc.querySelector('[data-human-task-related-status]').textContent).toBe('');
    expect(doc.querySelector('[data-human-task-related-pagination]').hidden).toBe(true);
    expect(doc.querySelector('[data-human-task-related-page]').textContent).toBe('Page 1');
  });
});

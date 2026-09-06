import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';

import { createHumanTaskDecisionDialog, renderHumanTaskInspector } from '../src/human-task-ui.js';

const capability = { commentMaxUtf8Bytes: 4096 };
const task = { taskId: 'task-secret-safe-id', generation: 7, status: 'ESCALATED',
  commentMaxUtf8Bytes: 8,
  promptMaxUtf8Bytes: 4096, actionLabelMaxUtf8Bytes: 64,
  processInstanceId: 'process-safe-id', createdAt: '2026-09-06T08:00:00Z',
  expiresAt: '2026-09-07T08:00:00Z', presentation: { prompt: '<b>Approve?</b>',
    commentRequirement: 'REQUIRED', labels: { RESOLVE: '<Confirm>', DENY: 'No', CANCEL: 'Cancel' } },
  availableActions: ['RESOLVE', 'DENY'] };

function dialogDocument() {
  return new JSDOM(`<body><dialog id="d" aria-labelledby="t"><form><p data-human-task-prompt></p>
    <p data-human-task-identity></p><div data-human-task-comment-field><textarea data-human-task-comment></textarea>
    <small data-human-task-comment-hint></small></div><p data-human-task-error hidden></p>
    <div data-human-task-actions></div><button type="button" data-human-task-close>Close</button></form></dialog></body>`)
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
});

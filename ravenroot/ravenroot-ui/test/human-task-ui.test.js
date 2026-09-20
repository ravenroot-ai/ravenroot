import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';

import { createHumanTaskDecisionDialog, humanTaskActionName,
  renderHumanTaskInspector } from '../src/human-task-ui.js';

const capability = { commentMaxUtf8Bytes: 4096 };
const task = { taskId: 'task-secret-safe-id', generation: 7, status: 'ESCALATED',
  commentMaxUtf8Bytes: 8,
  promptMaxUtf8Bytes: 4096, actionLabelMaxUtf8Bytes: 64,
  processInstanceId: 'process-safe-id', createdAt: '2026-09-06T08:00:00Z',
  expiresAt: '2026-09-07T08:00:00Z', presentation: { prompt: '<b>Approve?</b>',
    commentRequirement: 'REQUIRED', labels: { RESOLVE: '<Confirm>', DENY: 'No', CANCEL: 'Cancel' } },
  availableActions: ['RESOLVE', 'DENY'], reviewPresentation: { version: 1,
    contentType: 'text/plain', text: '<script>alert(1)</script>\nsecond line',
    contentDigest: `sha256:${'a'.repeat(64)}`, maxUtf8Bytes: 128 } };

function dialogDocument() {
  return new JSDOM(`<body><dialog id="d" aria-labelledby="t"><form><p data-human-task-prompt></p>
    <p data-human-task-identity></p><section data-human-task-review><p data-human-task-review-status></p>
    <pre data-human-task-review-text></pre><p data-human-task-review-digest></p></section>
    <div data-human-task-comment-field><textarea data-human-task-comment></textarea>
    <small data-human-task-comment-hint></small></div><p data-human-task-error hidden></p>
    <div data-human-task-actions></div><button type="button" data-human-task-close>Close</button></form></dialog></body>`)
    .window.document;
}

describe('Human Task inspector and decision dialog', () => {
  it('keeps content hidden while detail loads and offers no action on failure', () => {
    const doc = dialogDocument();
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d') });
    controller.loading({ taskId: task.taskId, generation: task.generation }, capability);
    expect(doc.getElementById('d').open).toBe(false);
    expect(doc.querySelector('[data-human-task-review-text]').textContent).toBe('');
    expect(doc.querySelector('[data-human-task-actions]').children).toHaveLength(0);
    controller.unavailable('Authorized detail is unavailable.');
    expect(doc.getElementById('d').open).toBe(true);
    expect(doc.querySelector('[data-human-task-review-status]').textContent).toContain('unavailable');
    expect(doc.querySelector('[data-human-task-actions]').children).toHaveLength(0);
    doc.querySelector('[data-human-task-close]').click();
    controller.loading({ taskId: task.taskId, generation: task.generation }, capability, { show: true });
    expect(doc.getElementById('d').open).toBe(true);
    expect(doc.querySelector('[data-human-task-actions]').children).toHaveLength(0);
  });

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

  it('preserves the focused task across ready, loading and ready Inspector replacements', () => {
    const doc = new JSDOM('<body><div id="host"></div></body>').window.document;
    const host = doc.getElementById('host');
    const ready = { kind: 'ready', items: [task, { ...task, taskId: 'task-2', generation: 8 }],
      counts: { pending: 2, escalated: 0 } };
    renderHumanTaskInspector(host, ready, 'human-confirmation');
    host.querySelector('[data-human-task-id="task-2"]').focus();
    renderHumanTaskInspector(host, ready, 'human-confirmation');
    expect(doc.activeElement.dataset.humanTaskId).toBe('task-2');
    renderHumanTaskInspector(host, { kind: 'loading', items: [] }, 'human-confirmation');
    expect(doc.activeElement.classList.contains('human-task-status')).toBe(true);
    renderHumanTaskInspector(host, ready, 'human-confirmation');
    expect(doc.activeElement.dataset.humanTaskId).toBe('task-2');
    renderHumanTaskInspector(host, { kind: 'ready', items: [] }, 'human-confirmation');
    expect(doc.activeElement.classList.contains('human-task-status')).toBe(true);
  });

  it('does not reclaim focus after the user leaves the Inspector or its node changes', () => {
    const doc = new JSDOM('<body><button id="outside">Other control</button><div id="host"></div></body>').window.document;
    const host = doc.getElementById('host');
    const ready = { kind: 'ready', items: [task], counts: { pending: 1, escalated: 0 } };
    renderHumanTaskInspector(host, ready, 'human-confirmation');
    host.querySelector('[data-human-task-id]').focus();
    renderHumanTaskInspector(host, { kind: 'loading', items: [] }, 'human-confirmation');
    doc.getElementById('outside').focus();
    renderHumanTaskInspector(host, ready, 'human-confirmation');
    expect(doc.activeElement.id).toBe('outside');
    host.querySelector('[data-human-task-id]').focus();
    renderHumanTaskInspector(host, ready, 'another-node');
    expect(doc.activeElement.tagName).toBe('BODY');
  });

  it('contains focus, requires the policy-bounded comment, and submits exactly once', async () => {
    const doc = dialogDocument();
    const submitted = vi.fn(async () => ({}));
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'),
      onSubmit: submitted });
    controller.open(task, capability);
    expect(doc.querySelector('[data-human-task-prompt]').textContent).toBe('<b>Approve?</b>');
    expect(doc.querySelector('[data-human-task-review-text]').textContent)
      .toBe('<script>alert(1)</script>\nsecond line');
    expect(doc.querySelector('[data-human-task-review-text] script')).toBeNull();
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

  it('renders accessible multiline and bounded numeric form controls and emits typed values', async () => {
    const doc = dialogDocument();
    const submitted = vi.fn(async () => ({}));
    const formTask = { ...task, presentation: { ...task.presentation, commentRequirement: 'OPTIONAL' },
      interactionPresentation: { kind: 'FORM', version: 1, profileId: '', profileVersion: 1,
        formSchema: { version: 1, fields: [
          { name: 'notes', label: 'Notes', help: 'Explain', type: 'MULTILINE_TEXT', required: true,
            maxUtf8Bytes: 512, allowedValues: [], minimum: null, maximum: null },
          { name: 'score', label: 'Score', help: '', type: 'INTEGER', required: true,
            maxUtf8Bytes: 16, allowedValues: [], minimum: 1, maximum: 5 },
        ] } } };
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'),
      onSubmit: submitted });
    controller.open(formTask, capability);
    const notes = doc.querySelector('textarea[data-human-task-form-field="notes"]');
    const score = doc.querySelector('input[data-human-task-form-field="score"]');
    expect(notes.closest('label').textContent).toContain('Notes');
    expect(score.min).toBe('1');
    expect(score.max).toBe('5');
    notes.value = 'first line\nsecond line';
    score.value = '4';
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await vi.waitFor(() => expect(submitted).toHaveBeenCalled());
    expect(submitted.mock.calls[0][0].response.value).toEqual({
      notes: 'first line\nsecond line', score: 4,
    });
  });

  it('runs a registered presentation in an opaque-origin sandbox without exposing a bearer', async () => {
    const doc = dialogDocument();
    const custom = { ...task, presentation: { ...task.presentation, commentRequirement: 'OPTIONAL' },
      interactionPresentation: { kind: 'CUSTOM', version: 1, profileId: 'trusted-form',
        profileVersion: 2 }, availableActions: ['RESOLVE'] };
    const launch = { schemaVersion: 1, capability: 'signed-capability', capabilityId: 'cap-1',
      expiresAt: new Date(Date.now() + 60_000).toISOString(), launchUri: 'https://forms.example/task',
      origin: 'https://forms.example', kind: 'CUSTOM', taskId: custom.taskId,
      generation: custom.generation, actions: ['RESOLVE'], review: null,
      responseSchema: { contentType: 'application/vnd.ravenroot.payload+json', schema: 'test',
        schemaVersion: '1', kind: 'MAP', maxBytes: 4096 } };
    const complete = vi.fn(async () => ({ outcome: 'RESOLVED' }));
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'),
      onLaunch: async () => launch, onInteractionSubmit: complete });
    controller.open(custom, capability);
    doc.querySelector('.human-task-presentation-launch').click();
    await vi.waitFor(() => expect(doc.querySelector('.human-task-presentation-frame')).not.toBeNull());
    const frame = doc.querySelector('.human-task-presentation-frame');
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts allow-forms');
    expect(frame.src).toBe(launch.launchUri);
    const postMessage = vi.spyOn(frame.contentWindow, 'postMessage');
    frame.dispatchEvent(new doc.defaultView.Event('load'));
    expect(postMessage).toHaveBeenCalled();
    expect(postMessage.mock.calls[0][1]).toBe('*');
    expect(postMessage.mock.calls[0][0]).not.toHaveProperty('capability');
    expect(postMessage.mock.calls[0][0]).not.toHaveProperty('subject');
    doc.defaultView.dispatchEvent(new doc.defaultView.MessageEvent('message', {
      origin: 'https://attacker.example', source: frame.contentWindow,
      data: { protocol: 'ravenroot.human-task.presentation', version: 1, type: 'complete',
        taskId: custom.taskId, generation: custom.generation, capabilityId: launch.capabilityId,
        action: 'RESOLVE', comment: '', response: { contentType: launch.responseSchema.contentType,
          payloadBase64: 'e30=' } },
    }));
    await Promise.resolve();
    expect(complete).not.toHaveBeenCalled();
    doc.defaultView.dispatchEvent(new doc.defaultView.MessageEvent('message', {
      origin: 'null', source: frame.contentWindow,
      data: { protocol: 'ravenroot.human-task.presentation', version: 1, type: 'complete',
        taskId: custom.taskId, generation: custom.generation, capabilityId: launch.capabilityId,
        action: 'RESOLVE', comment: '', response: { contentType: launch.responseSchema.contentType,
          payloadBase64: 'e30=' } },
    }));
    await vi.waitFor(() => expect(complete).toHaveBeenCalledWith(custom, launch, 'RESOLVE', '',
      { contentType: launch.responseSchema.contentType, payloadBase64: 'e30=' }));
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

  it('notifies a user close only after the native close event has restored focus', async () => {
    const doc = dialogDocument();
    const dialog = doc.getElementById('d');
    const closed = vi.fn();
    dialog.close = vi.fn(() => {
      dialog.removeAttribute('open');
      queueMicrotask(() => dialog.dispatchEvent(new doc.defaultView.Event('close')));
    });
    const controller = createHumanTaskDecisionDialog({ dialog, onClose: closed });
    controller.open(task, capability);

    doc.querySelector('[data-human-task-close]').click();
    expect(closed).not.toHaveBeenCalled();
    await Promise.resolve();
    expect(closed).toHaveBeenCalledTimes(1);
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

  it('does not let an old success close or unlock a replacement decision already in flight', async () => {
    const doc = dialogDocument();
    const settlements = [];
    const submitted = vi.fn(() => new Promise((resolve, reject) => settlements.push({ resolve, reject })));
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'), onSubmit: submitted });
    controller.open(task, capability);
    doc.querySelector('[data-human-task-comment]').value = 'old';
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await vi.waitFor(() => expect(submitted).toHaveBeenCalledTimes(1));

    const replacement = { ...task, taskId: 'replacement-task', generation: 8 };
    controller.suspend();
    controller.open(replacement, capability);
    doc.querySelector('[data-human-task-comment]').value = 'new';
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await vi.waitFor(() => expect(submitted).toHaveBeenCalledTimes(2));
    settlements[0].resolve({ outcome: 'APPLIED' });
    await Promise.resolve();
    expect(controller.selected()).toEqual({ taskId: replacement.taskId, generation: replacement.generation });
    expect(doc.getElementById('d').open).toBe(true);
    expect(doc.querySelectorAll('button:disabled').length).toBeGreaterThan(0);
    settlements[1].reject(new Error('replacement failure'));
    await vi.waitFor(() => expect(doc.querySelector('[data-human-task-error]').textContent)
      .toContain('replacement failure'));
  });

  it('does not let an old error or finally effect change the same task reopened in a new generation', async () => {
    const doc = dialogDocument();
    const settlements = [];
    const submitted = vi.fn(() => new Promise((resolve, reject) => settlements.push({ resolve, reject })));
    const controller = createHumanTaskDecisionDialog({ dialog: doc.getElementById('d'), onSubmit: submitted });
    controller.open(task, capability);
    doc.querySelector('[data-human-task-comment]').value = 'old';
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await vi.waitFor(() => expect(submitted).toHaveBeenCalledTimes(1));

    controller.suspend();
    controller.open(task, capability);
    doc.querySelector('[data-human-task-comment]').value = 'new';
    doc.querySelector('[data-human-task-action="RESOLVE"]').click();
    await vi.waitFor(() => expect(submitted).toHaveBeenCalledTimes(2));
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
});

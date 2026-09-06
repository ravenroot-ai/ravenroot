import { describe, expect, it } from 'vitest';

import {
  humanTaskContext,
  nextHumanTaskBackoff,
  nodeAttention,
  utf8Length,
  validateDecisionComment,
  validateHumanTaskAttention,
  validateHumanTaskCapability,
} from '../src/human-task-attention.js';

const CAPABILITY = Object.freeze({ schemaVersion: 1, confirmationPresentationVersions: [1],
  confirmationPromptMaxUtf8Bytes: 4096, confirmationActionLabelMaxUtf8Bytes: 64,
  attentionPollMillis: 1000, attentionBackoffMaxMillis: 10000,
  attentionPageSize: 25, attentionPageSizeMax: 1000, commentMaxUtf8Bytes: 4096 });

function row(overrides = {}) {
  return { taskId: 'task-1', generation: 3, status: 'WAITING', graphVersion: 'graph-v1',
    deploymentId: 'deployment-1', processInstanceId: 'process-1', traversalId: 'traversal-1',
    nodeId: 'review', createdAt: '2026-09-06T08:00:00Z', expiresAt: '2026-09-07T08:00:00Z',
    escalateAt: '2026-09-06T12:00:00Z', presentation: { version: 1,
      prompt: 'Release this build?', commentRequirement: 'REQUIRED', actions: ['RESOLVE', 'DENY'],
      resolveLabel: 'Release', denyLabel: 'Send back', cancelLabel: 'Cancel' },
    availableActions: ['RESOLVE', 'DENY'], promptMaxUtf8Bytes: 8192,
    actionLabelMaxUtf8Bytes: 128, commentMaxUtf8Bytes: 8192, ...overrides };
}

describe('Human Task capability and attention projection', () => {
  it('requires a complete server-owned timing and bounds policy', () => {
    expect(validateHumanTaskCapability(CAPABILITY)).toMatchObject({ attentionPollMillis: 1000,
      attentionBackoffMaxMillis: 10000, attentionPageSize: 25, commentMaxUtf8Bytes: 4096 });
    for (const field of ['confirmationPromptMaxUtf8Bytes', 'confirmationActionLabelMaxUtf8Bytes',
      'attentionPollMillis', 'attentionBackoffMaxMillis', 'attentionPageSize',
      'attentionPageSizeMax', 'commentMaxUtf8Bytes']) {
      expect(() => validateHumanTaskCapability({ ...CAPABILITY, [field]: undefined })).toThrow();
    }
    expect(() => validateHumanTaskCapability({ ...CAPABILITY, attentionBackoffMaxMillis: 999 })).toThrow();
    expect(() => validateHumanTaskCapability({ ...CAPABILITY,
      confirmationPresentationVersions: [2] })).toThrow(/schema version 1/);
  });

  it('validates actionable pages, presentation versions, actions and aggregate counts', () => {
    const capability = validateHumanTaskCapability(CAPABILITY);
    const page = validateHumanTaskAttention({ schemaVersion: 1, items: [row(), row({
      taskId: 'task-2', generation: 1, status: 'ESCALATED', escalateAt: null,
    })], nextCursor: 'next', counts: { pending: 2, escalated: 1 }, nodeCounts: [] }, capability);
    expect(nodeAttention(page.items).get('review')).toEqual({ pending: 2, escalated: 1 });
    expect(page.items[0].commentMaxUtf8Bytes).toBe(8192);
    expect(page.items[0]).toMatchObject({ promptMaxUtf8Bytes: 8192, actionLabelMaxUtf8Bytes: 128 });
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row({
      commentMaxUtf8Bytes: 0 })], nextCursor: null, counts: { pending: 1, escalated: 0 }, nodeCounts: [] }, capability))
      .toThrow(/pinned comment maximum/);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row({ status: 'RESOLVED' })],
      nextCursor: null, counts: { pending: 0, escalated: 0 }, nodeCounts: [] }, capability)).toThrow(/actionable/);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row({ presentation: {
      ...row().presentation, version: 2 } })], nextCursor: null,
    counts: { pending: 1, escalated: 0 }, nodeCounts: [] }, capability)).toThrow(/not supported/);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row()], nextCursor: null,
      counts: { pending: 1, escalated: 2 }, nodeCounts: [] }, capability)).toThrow(/exceeds/);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row()], nextCursor: null,
      counts: { pending: 1, escalated: 0 }, nodeCounts: [] }, capability,
    { graphVersion: 'different' })).toThrow(/requested context/);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row(), row()],
      nextCursor: null, counts: { pending: 2, escalated: 0 }, nodeCounts: [] }, capability)).toThrow(/duplicate task/);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row()], nextCursor: null,
      counts: { pending: 1, escalated: 0 }, nodeCounts: [
        { nodeId: 'review', pending: 1, escalated: 0 },
        { nodeId: 'review', pending: 1, escalated: 0 },
      ] }, capability)).toThrow(/duplicate node/);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row(), row({ taskId: 'task-2' })],
      nextCursor: null, counts: { pending: 2, escalated: 0 }, nodeCounts: [] }, capability, { limit: 1 }))
      .toThrow(/requested page size/);
    const oldPinnedPrompt = 'p'.repeat(CAPABILITY.confirmationPromptMaxUtf8Bytes + 1);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row({ presentation: {
      ...row().presentation, prompt: oldPinnedPrompt, cancelLabel: '' } })], nextCursor: null,
    counts: { pending: 1, escalated: 0 }, nodeCounts: [] }, capability)).not.toThrow();
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row({
      promptMaxUtf8Bytes: 10, presentation: { ...row().presentation, prompt: 'longer than ten' } })],
    nextCursor: null, counts: { pending: 1, escalated: 0 }, nodeCounts: [] }, capability))
      .toThrow(/pinned maximum/);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row({
      actionLabelMaxUtf8Bytes: 3, presentation: { ...row().presentation, resolveLabel: 'Four' } })],
    nextCursor: null, counts: { pending: 1, escalated: 0 }, nodeCounts: [] }, capability))
      .toThrow(/action label exceeds its pinned maximum/);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [row({ presentation: {
      ...row().presentation, prompt: 'bad\ntext' } })], nextCursor: null,
    counts: { pending: 1, escalated: 0 }, nodeCounts: [] }, capability)).toThrow(/invalid display text/);
    expect(() => validateHumanTaskAttention({ schemaVersion: 1, items: [], nextCursor: null,
      counts: { pending: 1, escalated: 0 }, nodeCounts: [{ nodeId: 'review', pending: 0, escalated: 0 }] },
    capability, { graphVersion: 'graph-v1', deploymentId: 'deployment-1' }))
      .toThrow(/do not match aggregate/);
  });

  it('keeps attention context on authoritative runtime pins and out of graph content', () => {
    expect(humanTaskContext({ execution: { graphVersion: 'run-v7', processInstanceId: 'p7',
      executionId: 't7' }, humanTasks: { graphVersion: 'deployment-v7', deploymentId: 'd7' } })).toEqual({
      graphVersion: 'deployment-v7', deploymentId: 'd7' });
    expect(humanTaskContext({ execution: { graphVersion: 'v7', processInstanceId: 'p7',
      executionId: 't7' }, humanTasks: {} })).toEqual({
      graphVersion: 'v7', processInstanceId: 'p7' });
    expect(humanTaskContext({ execution: { graphVersion: null } })).toBeNull();
    expect(humanTaskContext({ execution: { graphVersion: 'v7' }, humanTasks: {
      deploymentId: 'd7', graphVersion: null } })).toBeNull();
  });

  it('measures comments as UTF-8 and never substitutes a browser limit', () => {
    expect(utf8Length('\u{1F642}')).toBe(4);
    expect(validateDecisionComment('', 'REQUIRED', 12)).toMatchObject({ ok: false });
    expect(validateDecisionComment('no', 'DISALLOWED', 12)).toMatchObject({ ok: false });
    expect(validateDecisionComment('\u{1F642}\u{1F642}', 'OPTIONAL', 7)).toMatchObject({ ok: false });
    expect(validateDecisionComment('\u{1F642}\u{1F642}', 'OPTIONAL', 8)).toEqual({ ok: true,
      value: '\u{1F642}\u{1F642}', bytes: 8 });
    expect(validateDecisionComment(' line 1\n\tline 2 ', 'OPTIONAL', 30)).toMatchObject({ ok: true,
      value: 'line 1\n\tline 2' });
    expect(validateDecisionComment('bad\rcontrol', 'OPTIONAL', 30)).toMatchObject({ ok: false });
    expect(validateDecisionComment('\ud800', 'OPTIONAL', 30)).toMatchObject({ ok: false });
  });

  it('uses the advertised interval and bounded exponential reconnect delay', () => {
    const capability = validateHumanTaskCapability(CAPABILITY);
    expect(nextHumanTaskBackoff(0, capability)).toBe(1000);
    expect(nextHumanTaskBackoff(1000, capability)).toBe(2000);
    expect(nextHumanTaskBackoff(8000, capability)).toBe(10000);
  });
});

import { describe, expect, it, vi } from 'vitest';

import { createHumanTaskController } from '../src/human-task-controller.js';

const capability = { schemaVersion: 1, confirmationPresentationVersions: [1],
  confirmationPromptMaxUtf8Bytes: 4096, confirmationActionLabelMaxUtf8Bytes: 64,
  attentionPollMillis: 1000, attentionBackoffMaxMillis: 8000,
  attentionPageSize: 2, attentionPageSizeMax: 10, commentMaxUtf8Bytes: 4096 };

const aggregate = { items: [], nextCursor: null, counts: { pending: 3, escalated: 1 },
  nodeCounts: [{ nodeId: 'human-confirmation', pending: 3, escalated: 1 }] };
const page = { items: [{ taskId: 'one' }, { taskId: 'two' }], nextCursor: 'cursor-2',
  counts: { pending: 3, escalated: 1 }, nodeCounts: [] };

describe('Human Task attention controller', () => {
  it('queries authoritative aggregate and exact selected-node page, then traverses opaque cursors', async () => {
    const client = { humanTaskAttention: vi.fn(async filters => filters.nodeId ? page : aggregate) };
    const changes = [];
    const controller = createHumanTaskController({ onChange: state => changes.push(state),
      setTimer: () => 1, clearTimer: () => {} });
    const documentRecord = { execution: { graphVersion: 'graph-v1', processInstanceId: 'process-v1' },
      humanTasks: {} };
    await controller.configure(client, capability, documentRecord);
    await controller.selectNode('human-confirmation');

    expect(client.humanTaskAttention).toHaveBeenLastCalledWith({ graphVersion: 'graph-v1',
      processInstanceId: 'process-v1', nodeId: 'human-confirmation', cursor: undefined },
    expect.objectContaining({ capability }));
    expect(controller.state().nodeCounts.get('human-confirmation')).toEqual({ pending: 3, escalated: 1 });
    expect(controller.state().page).toMatchObject({ kind: 'ready', pageNumber: 1,
      nextCursor: 'cursor-2', hasPrevious: false });

    await controller.nextPage();
    expect(client.humanTaskAttention).toHaveBeenLastCalledWith(expect.objectContaining({
      nodeId: 'human-confirmation', cursor: 'cursor-2' }), expect.anything());
    expect(controller.state().page).toMatchObject({ pageNumber: 2, hasPrevious: true });
    await controller.previousPage();
    expect(controller.state().page).toMatchObject({ pageNumber: 1, hasPrevious: false });
    expect(changes.some(state => state.kind === 'loading')).toBe(true);
  });

  it('does not query without a capability or exact graph context', async () => {
    const client = { humanTaskAttention: vi.fn() };
    const controller = createHumanTaskController({ setTimer: () => 1, clearTimer: () => {} });
    await controller.configure(client, null, { execution: {}, humanTasks: {} });
    expect(client.humanTaskAttention).not.toHaveBeenCalled();
    expect(controller.state()).toMatchObject({ kind: 'unavailable' });
  });

  it('retains the selected node while capability and execution context arrive asynchronously', async () => {
    const client = { humanTaskAttention: vi.fn(async filters => filters.nodeId ? page : aggregate) };
    const controller = createHumanTaskController({ setTimer: () => 1, clearTimer: () => {} });
    const documentRecord = { execution: {}, humanTasks: {} };
    await controller.configure(client, null, documentRecord);
    await controller.selectNode('human-confirmation');
    documentRecord.execution = { graphVersion: 'graph-v1', processInstanceId: 'process-v1' };
    await controller.configure(client, capability, documentRecord);
    expect(client.humanTaskAttention).toHaveBeenCalledWith(expect.objectContaining({
      nodeId: 'human-confirmation' }), expect.anything());
    expect(controller.state().page.items).toEqual(page.items);
  });

  it('publishes reconnect state and uses policy backoff without retrying an action', async () => {
    const timers = [];
    const client = { humanTaskAttention: vi.fn().mockRejectedValue(new Error('network down')) };
    const controller = createHumanTaskController({ setTimer: (callback, delay) => {
      timers.push({ callback, delay }); return timers.length;
    }, clearTimer: () => {} });
    await controller.configure(client, capability, { execution: { graphVersion: 'g',
      processInstanceId: 'p' }, humanTasks: {} });
    expect(controller.state()).toMatchObject({ kind: 'error' });
    expect(timers.at(-1).delay).toBe(1000);
    await controller.refresh({ automatic: true });
    expect(timers.at(-1).delay).toBe(2000);
  });

  it('returns to the first page when changed authority invalidates an opaque cursor', async () => {
    const calls = [];
    const client = { humanTaskAttention: vi.fn(async filters => {
      calls.push(filters);
      if (filters.cursor) throw Object.assign(new Error('cursor no longer belongs to authority'), { status: 400 });
      return filters.nodeId ? page : aggregate;
    }) };
    const controller = createHumanTaskController({ setTimer: () => 1, clearTimer: () => {} });
    await controller.configure(client, capability, { execution: { graphVersion: 'g',
      processInstanceId: 'p' }, humanTasks: {} });
    await controller.selectNode('human-confirmation');
    await controller.nextPage();
    expect(controller.state().page).toMatchObject({ kind: 'ready', pageNumber: 1,
      hasPrevious: false });
    expect(calls.at(-1)).toMatchObject({ nodeId: 'human-confirmation', cursor: undefined });
  });
});

import { describe, expect, it, vi } from 'vitest';
import {
  requestExecutionLifecycle,
  UNAVAILABLE_LIFECYCLE_REASONS,
  validateExecutionLifecycleResult,
} from '../src/graph-lifecycle.js';

function deferred() {
  let resolve;
  const promise = new Promise(resolve_ => { resolve = resolve_; });
  return { promise, resolve };
}

describe('execution lifecycle boundary', () => {
  it.each([
    ['pause', 'PAUSED'], ['resume', 'RESUMED'], ['cancel', 'CANCELLED'],
  ])('invokes %s and reconciles from an authoritative read', async (action, outcome) => {
    const client = {
      [`${action}Execution`]: vi.fn().mockResolvedValue({ outcome, traversalId: 'execution-a', note: 'ok' }),
      execution: vi.fn().mockResolvedValue({ status: 'RUNNING', paused: action === 'pause' }),
    };

    await expect(requestExecutionLifecycle(action, {
      client, executionId: 'execution-a', isCurrent: () => true,
    })).resolves.toMatchObject({
      status: 'reconciled', command: { outcome },
      authoritative: { status: 'RUNNING', paused: action === 'pause' },
    });
    expect(client[`${action}Execution`]).toHaveBeenCalledWith('execution-a', expect.any(Object));
    expect(client.execution).toHaveBeenCalledWith('execution-a', expect.any(Object));
  });

  it('uses authoritative state after an unreadable command response without simulating success', async () => {
    const client = {
      pauseExecution: vi.fn().mockRejectedValue(new Error('response lost')),
      execution: vi.fn().mockResolvedValue({ status: 'RUNNING', paused: true }),
    };

    await expect(requestExecutionLifecycle('pause', {
      client, executionId: 'execution-a', isCurrent: () => true,
    })).resolves.toMatchObject({
      status: 'reconciled-after-error', commandError: expect.objectContaining({ message: 'response lost' }),
      authoritative: { paused: true },
    });
  });

  it('reports unknown only when authoritative observation is unavailable', async () => {
    const client = {
      cancelExecution: vi.fn().mockResolvedValue({ outcome: 'CANCELLED', traversalId: 'execution-a', note: 'ok' }),
      execution: vi.fn().mockRejectedValue(new Error('disconnected')),
    };
    await expect(requestExecutionLifecycle('cancel', {
      client, executionId: 'execution-a', isCurrent: () => true,
    })).resolves.toMatchObject({ status: 'unknown', observationError: expect.any(Error) });
  });

  it('fences a superseded command before authoritative readback', async () => {
    const response = deferred();
    let current = true;
    const client = {
      pauseExecution: vi.fn(() => response.promise), execution: vi.fn(),
    };
    const request = requestExecutionLifecycle('pause', {
      client, executionId: 'execution-a', isCurrent: () => current,
    });
    current = false;
    response.resolve({ outcome: 'PAUSED', traversalId: 'execution-a', note: 'ok' });

    await expect(request).resolves.toEqual({ status: 'stale', action: 'pause' });
    expect(client.execution).not.toHaveBeenCalled();
  });

  it('rejects wrong identities and unknown or server-wide actions', async () => {
    expect(() => validateExecutionLifecycleResult(
      { outcome: 'PAUSED', traversalId: 'execution-b', note: 'ok' }, 'execution-a', 'pause',
    )).toThrow(/invalid/);
    await expect(requestExecutionLifecycle('shutdown', {})).rejects.toThrow(/Unknown/);
  });

  it('publishes honest reasons for unavailable deployment and service controls', () => {
    expect(UNAVAILABLE_LIFECYCLE_REASONS.deploymentStop).toMatch(/does not advertise.*Stop API/i);
    expect(UNAVAILABLE_LIFECYCLE_REASONS.shutdown).toMatch(/does not advertise.*Shutdown API/i);
  });
});

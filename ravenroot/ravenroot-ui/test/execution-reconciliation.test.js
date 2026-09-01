import { describe, expect, it, vi } from 'vitest';

import {
  acquireExecutionCommand,
  captureExecutionOutcomeToken,
  claimExecutionOutcomeFetch,
  claimExecutionOutcomeReport,
  completeExecutionOutcomeFetch,
  enforceExecutionOutcomeCapacity,
  executionCommandIsCurrent,
  executionOutcomeFetchSignal,
  isTerminalExecution,
  preflightBoundExecutionCommand,
  preflightExecutionCommand,
  reconcileExecution,
  releaseExecutionCommand,
  retireExecutionOutcomeClaim,
} from '../src/execution-reconciliation.js';

function executionBinding(overrides = {}) {
  return {
    executionId: 'execution-1', reconciliationState: 'unknown', reconciliationClient: null,
    generation: 1, commandFlight: null, reconciliationController: null,
    outcomeClaim: null, retiredOutcomeClaim: null, ...overrides,
  };
}

describe('execution completion reconciliation', () => {
  it('publishes unknown once, keeps reconciling at bounded backoff, and settles a later terminal GET once', async () => {
    const failure = new Error('runtime lookup unavailable');
    const lookup = vi.fn()
      .mockRejectedValueOnce(failure)
      .mockRejectedValueOnce(failure)
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce({ status: 'COMPLETED', executionId: 'execution-1' });
    const onUnknown = vi.fn();
    const onTerminal = vi.fn();
    const sleep = vi.fn(async () => {});

    const result = await reconcileExecution({
      lookup,
      isCurrent: () => true,
      onUnknown,
      onTerminal,
      sleep,
      failureThreshold: 2,
      pollDelayMs: 10,
      degradedPollDelayMs: 50,
    });

    expect(result).toMatchObject({ state: 'terminal', recoveredFromUnknown: true });
    expect(lookup).toHaveBeenCalledTimes(4);
    expect(onUnknown).toHaveBeenCalledOnce();
    expect(onUnknown).toHaveBeenCalledWith({ error: failure, failureCount: 2 });
    expect(onTerminal).toHaveBeenCalledOnce();
    expect(sleep.mock.calls.map(([delay]) => delay)).toEqual([10, 50, 50]);
  });

  it('recovers a transient lookup failure without publishing unknown or changing clean success', async () => {
    const lookup = vi.fn()
      .mockRejectedValueOnce(new Error('temporary failure'))
      .mockResolvedValueOnce({ status: 'FAILED', executionId: 'execution-2' });
    const onUnknown = vi.fn();
    const onTerminal = vi.fn();

    const result = await reconcileExecution({
      lookup,
      isCurrent: () => true,
      onUnknown,
      onTerminal,
      sleep: vi.fn(async () => {}),
      failureThreshold: 2,
    });

    expect(result).toMatchObject({ state: 'terminal', recoveredFromUnknown: false });
    expect(onUnknown).not.toHaveBeenCalled();
    expect(onTerminal).toHaveBeenCalledOnce();
  });

  it('does not invent completion from failures or a non-terminal server status', async () => {
    let current = true;
    const onTerminal = vi.fn();
    const lookup = vi.fn()
      .mockRejectedValueOnce(new Error('unavailable'))
      .mockResolvedValueOnce({ status: 'RUNNING' });
    const sleep = vi.fn(async () => {
      if (sleep.mock.calls.length === 2) current = false;
    });

    const result = await reconcileExecution({
      lookup,
      isCurrent: () => current,
      onTerminal,
      sleep,
      failureThreshold: 1,
    });

    expect(result).toEqual({ state: 'cancelled' });
    expect(onTerminal).not.toHaveBeenCalled();
    expect(isTerminalExecution({ status: 'RUNNING' })).toBe(false);
    expect(isTerminalExecution({ status: 'COMPLETED' })).toBe(true);
  });

  it('turns an unknown-state command into GET-only refusal until the prior execution is terminal', async () => {
    const submit = vi.fn();
    const unavailable = await preflightExecutionCommand({
      unknown: true,
      lookup: vi.fn().mockRejectedValue(new Error('offline')),
      onUnavailable: vi.fn(),
    });
    if (unavailable.allowed) submit();

    const active = await preflightExecutionCommand({
      unknown: true,
      lookup: vi.fn().mockResolvedValue({ status: 'RUNNING' }),
      onNonTerminal: vi.fn(),
    });
    if (active.allowed) submit();

    expect(unavailable.reason).toBe('unavailable');
    expect(active.reason).toBe('active');
    expect(submit).not.toHaveBeenCalled();
  });

  it('allows the requested command exactly after GET proves the prior execution terminal', async () => {
    const onTerminal = vi.fn();
    const preflight = await preflightExecutionCommand({
      unknown: true,
      lookup: vi.fn().mockResolvedValue({ status: 'COMPLETED' }),
      onTerminal,
    });

    expect(preflight).toMatchObject({ allowed: true, reason: 'terminal' });
    expect(onTerminal).toHaveBeenCalledOnce();
  });

  it('preflights only through the client that accepted the execution after the global client changes', async () => {
    const submissionClient = {
      execution: vi.fn().mockResolvedValue({ status: 'COMPLETED', executionId: 'execution-1' }),
    };
    let runtimeClient = submissionClient;
    const binding = {
      ...executionBinding(), reconciliationClient: runtimeClient,
    };
    const flight = acquireExecutionCommand(binding);
    runtimeClient = { execution: vi.fn().mockResolvedValue({ status: 'COMPLETED' }) };

    const preflight = await preflightBoundExecutionCommand({ flight });

    expect(preflight).toMatchObject({ allowed: true, reason: 'terminal' });
    expect(submissionClient.execution).toHaveBeenCalledExactlyOnceWith('execution-1', {
      signal: flight.controller.signal,
    });
    expect(runtimeClient.execution).not.toHaveBeenCalled();
  });

  it('fails closed when an unknown binding has lost its submission client', async () => {
    const unavailable = vi.fn();
    const binding = executionBinding();
    const preflight = await preflightBoundExecutionCommand({
      flight: acquireExecutionCommand(binding),
      onUnavailable: unavailable,
    });

    expect(preflight).toMatchObject({ allowed: false, reason: 'unavailable' });
    expect(unavailable).toHaveBeenCalledOnce();
  });

  it('acquires one synchronous command flight and shares one delayed terminal lookup', async () => {
    let resolveLookup;
    const lookupResult = new Promise(resolve => { resolveLookup = resolve; });
    const client = { execution: vi.fn(() => lookupResult) };
    const binding = executionBinding({ reconciliationClient: client });
    const flight = acquireExecutionCommand(binding);

    expect(acquireExecutionCommand(binding)).toBeNull();
    const first = preflightBoundExecutionCommand({ flight });
    const second = preflightBoundExecutionCommand({ flight });
    expect(first).toBe(second);
    expect(client.execution).toHaveBeenCalledExactlyOnceWith('execution-1', {
      signal: flight.controller.signal,
    });

    resolveLookup({ status: 'COMPLETED' });
    await expect(first).resolves.toMatchObject({ allowed: true, reason: 'terminal' });
    releaseExecutionCommand(flight);
    expect(flight.controller.signal.aborted).toBe(true);
    expect(acquireExecutionCommand(binding)).not.toBeNull();
  });

  it('invalidates the command guard when id, client, generation or binding ownership changes', () => {
    const binding = executionBinding({ reconciliationClient: { execution: vi.fn() } });
    const flight = acquireExecutionCommand(binding);
    expect(executionCommandIsCurrent(flight)).toBe(true);
    binding.generation += 1;
    expect(executionCommandIsCurrent(flight)).toBe(false);

    const replacement = executionBinding({ reconciliationClient: binding.reconciliationClient });
    const replacementFlight = acquireExecutionCommand(replacement);
    replacement.executionId = 'execution-2';
    expect(executionCommandIsCurrent(replacementFlight)).toBe(false);
  });

  it('claims outcome fetch and report once for only the current binding generation', () => {
    const firstClient = {};
    const secondClient = {};
    const binding = executionBinding({ reconciliationClient: firstClient });
    const token = captureExecutionOutcomeToken(binding, 'execution-1', firstClient, 1);

    expect(token).toBe(captureExecutionOutcomeToken(binding, 'execution-1', firstClient, 1));
    expect(captureExecutionOutcomeToken(binding, 'execution-1', secondClient, 1)).toBeNull();
    expect(captureExecutionOutcomeToken(binding, 'execution-1', firstClient, 2)).toBeNull();
    expect(claimExecutionOutcomeFetch(token)).toBe(true);
    expect(claimExecutionOutcomeFetch(token)).toBe(false);
    expect(claimExecutionOutcomeReport(token)).toBe(true);
    expect(claimExecutionOutcomeReport(token)).toBe(false);
  });

  it('keeps one bounded outcome slot across many sequential executions and clients', () => {
    const binding = executionBinding();

    for (let index = 0; index < 250; index += 1) {
      const client = { index };
      const executionId = `execution-${index}`;
      retireExecutionOutcomeClaim(binding, executionId, client);
      binding.generation += 1;
      binding.executionId = executionId;
      binding.reconciliationClient = client;
      const token = captureExecutionOutcomeToken(binding, executionId, client, binding.generation);

      expect(claimExecutionOutcomeReport(token)).toBe(true);
      expect(binding.outcomeClaim).toMatchObject({ token, reportClaimed: true });
      expect(Array.isArray(binding.outcomeClaim)).toBe(false);
      expect(binding.retiredOutcomeClaim).toBeNull();
    }
  });

  it('keeps one in-flight prior outcome through rebinding and releases it after settlement', () => {
    const client = {};
    const binding = executionBinding({ reconciliationClient: client });
    const oldToken = captureExecutionOutcomeToken(binding, 'execution-1', client, 1);

    expect(claimExecutionOutcomeFetch(oldToken)).toBe(true);
    expect(executionOutcomeFetchSignal(oldToken)).toBe(oldToken.controller.signal);
    retireExecutionOutcomeClaim(binding, 'execution-2', client);
    binding.generation += 1;
    binding.executionId = 'execution-2';

    expect(binding.outcomeClaim).toBeNull();
    expect(binding.retiredOutcomeClaim?.token).toBe(oldToken);
    expect(claimExecutionOutcomeReport(oldToken)).toBe(true);
    expect(completeExecutionOutcomeFetch(oldToken)).toBe(true);
    expect(binding.retiredOutcomeClaim).toBeNull();
  });

  it('aborts the oldest request before a third overlapping run and keeps the next outcome tracked', () => {
    const client = {};
    const binding = executionBinding({ reconciliationClient: client });
    const first = captureExecutionOutcomeToken(binding, 'execution-1', client, 1);
    claimExecutionOutcomeFetch(first);
    retireExecutionOutcomeClaim(binding, 'execution-2', client);
    binding.generation = 2;
    binding.executionId = 'execution-2';
    const second = captureExecutionOutcomeToken(binding, 'execution-2', client, 2);
    claimExecutionOutcomeFetch(second);

    expect(enforceExecutionOutcomeCapacity(binding)).toEqual({ executionId: 'execution-1', generation: 1 });
    expect(first.controller.signal.aborted).toBe(true);
    expect(claimExecutionOutcomeReport(first)).toBe(false);
    expect(binding.retiredOutcomeClaim).toBeNull();
    expect(binding.outcomeClaim?.token).toBe(second);

    retireExecutionOutcomeClaim(binding, 'execution-3', client);
    expect(binding.retiredOutcomeClaim?.token).toBe(second);
    expect(second.controller.signal.aborted).toBe(false);
  });

  it('aborts and releases a hanging retired lookup when its document closes', () => {
    const client = {};
    const binding = executionBinding({ reconciliationClient: client });
    const token = captureExecutionOutcomeToken(binding, 'execution-1', client, 1);
    claimExecutionOutcomeFetch(token);
    retireExecutionOutcomeClaim(binding, 'execution-2', client);

    retireExecutionOutcomeClaim(binding, null, null, { cancelInFlight: true });

    expect(token.controller.signal.aborted).toBe(true);
    expect(binding.outcomeClaim).toBeNull();
    expect(binding.retiredOutcomeClaim).toBeNull();
    expect(claimExecutionOutcomeReport(token)).toBe(false);
  });

  it('rejects a delayed old token when the identical execution id and client are rebound', async () => {
    const client = {};
    const binding = executionBinding({ reconciliationClient: client });
    let resolveOldOutcome;
    const oldOutcome = new Promise(resolve => { resolveOldOutcome = resolve; });
    const oldToken = captureExecutionOutcomeToken(binding, 'execution-1', client, 1);

    expect(claimExecutionOutcomeFetch(oldToken)).toBe(true);
    const delayedReport = oldOutcome.then(() => claimExecutionOutcomeReport(oldToken));
    retireExecutionOutcomeClaim(binding, 'execution-1', client);
    expect(oldToken.controller.signal.aborted).toBe(true);
    binding.generation += 1;
    const currentToken = captureExecutionOutcomeToken(binding, 'execution-1', client, 2);

    resolveOldOutcome({ status: 'COMPLETED' });
    await expect(delayedReport).resolves.toBe(false);
    expect(binding.outcomeClaim?.token).toBe(currentToken);
    expect(binding.outcomeClaim?.reportClaimed).toBe(false);
    expect(claimExecutionOutcomeReport(currentToken)).toBe(true);
  });
});

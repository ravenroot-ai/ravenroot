const EXECUTION_ACTIONS = Object.freeze({
  pause: Object.freeze({ method: 'pauseExecution', outcomes: new Set(['PAUSED', 'ALREADY_PAUSED', 'NOT_ACTIVE']) }),
  resume: Object.freeze({ method: 'resumeExecution', outcomes: new Set(['RESUMED', 'NOT_PAUSED', 'NOT_ACTIVE']) }),
  cancel: Object.freeze({ method: 'cancelExecution', outcomes: new Set(['CANCELLED', 'ALREADY_CANCELLED', 'ALREADY_COMPLETED']) }),
});

export const UNAVAILABLE_LIFECYCLE_REASONS = Object.freeze({
  deploymentStop: 'Unavailable: this runtime does not advertise the versioned deployment Stop API.',
  shutdown: 'Unavailable: this runtime does not advertise the authorized global Shutdown API.',
});

export function validateExecutionLifecycleResult(value, executionId, action) {
  const contract = EXECUTION_ACTIONS[action];
  if (!contract) throw new TypeError(`Unknown execution lifecycle action: ${action}`);
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || !contract.outcomes.has(value.outcome)
      || value.traversalId !== executionId
      || typeof value.note !== 'string') {
    throw new Error(`Execution ${action} response is invalid`);
  }
  return value;
}

/**
 * Invoke one authenticated, execution-scoped control and then read the authoritative execution.
 * The caller supplies its current-owner fence because document identity and binding generation live
 * in the workspace, not in this transport adapter. The fence is checked on both asynchronous
 * boundaries so a delayed response cannot trigger a read or projection for a superseded binding.
 */
export async function requestExecutionLifecycle(action, {
  client, executionId, signal, isCurrent = () => true,
} = {}) {
  const contract = EXECUTION_ACTIONS[action];
  if (!contract) throw new TypeError(`Unknown execution lifecycle action: ${action}`);
  if (!client || typeof client[contract.method] !== 'function' || typeof client.execution !== 'function') {
    throw new Error(`Execution ${action} is unavailable on this runtime connection`);
  }
  if (!executionId) throw new Error(`Execution ${action} requires an id`);
  if (!isCurrent()) return Object.freeze({ status: 'stale', action });

  let command = null;
  let commandError = null;
  try {
    command = validateExecutionLifecycleResult(
      await client[contract.method](executionId, { signal }), executionId, action,
    );
  } catch (error) {
    commandError = error;
  }
  if (!isCurrent()) return Object.freeze({ status: 'stale', action });

  try {
    const authoritative = await client.execution(executionId, { signal });
    if (!isCurrent()) return Object.freeze({ status: 'stale', action });
    return Object.freeze({
      status: commandError ? 'reconciled-after-error' : 'reconciled',
      action, command, commandError, authoritative,
    });
  } catch (observationError) {
    if (!isCurrent()) return Object.freeze({ status: 'stale', action });
    return Object.freeze({ status: 'unknown', action, command, commandError, observationError });
  }
}

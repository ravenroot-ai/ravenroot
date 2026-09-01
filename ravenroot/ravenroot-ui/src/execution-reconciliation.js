const TERMINAL_EXECUTION_STATUSES = new Set(['COMPLETED', 'FAILED']);
const preflightFlights = new WeakMap();

export const DEFAULT_RECONCILIATION_OPTIONS = Object.freeze({
  pollDelayMs: 250,
  failureThreshold: 8,
  degradedPollDelayMs: 5_000,
});

export function isTerminalExecution(outcome) {
  return TERMINAL_EXECUTION_STATUSES.has(outcome?.status);
}

export async function preflightExecutionCommand({
  unknown,
  lookup,
  onTerminal = () => {},
  onNonTerminal = () => {},
  onUnavailable = () => {},
} = {}) {
  if (!unknown) return { allowed: true, reason: 'not-unknown' };
  try {
    const outcome = await lookup();
    if (isTerminalExecution(outcome)) {
      onTerminal(outcome);
      return { allowed: true, reason: 'terminal', outcome };
    }
    onNonTerminal(outcome);
    return { allowed: false, reason: 'active', outcome };
  } catch (error) {
    onUnavailable(error);
    return { allowed: false, reason: 'unavailable', error };
  }
}

export function acquireExecutionCommand(binding) {
  if (!binding || binding.commandFlight) return null;
  const flight = Object.freeze({
    binding,
    executionId: binding.executionId,
    client: binding.reconciliationClient,
    generation: binding.generation,
    unknown: binding.reconciliationState === 'unknown',
    controller: new AbortController(),
  });
  binding.commandFlight = flight;
  return flight;
}

export function executionCommandIsCurrent(flight) {
  const binding = flight?.binding;
  return Boolean(binding && binding.commandFlight === flight
    && binding.executionId === flight.executionId
    && binding.reconciliationClient === flight.client
    && binding.generation === flight.generation);
}

export function releaseExecutionCommand(flight) {
  flight?.controller?.abort();
  if (flight?.binding?.commandFlight === flight) flight.binding.commandFlight = null;
}

export function preflightBoundExecutionCommand({ flight, ...callbacks } = {}) {
  const binding = flight?.binding;
  if (!binding) return Promise.resolve({ allowed: false, reason: 'stale-binding' });
  const existing = preflightFlights.get(binding);
  if (existing?.executionId === flight.executionId && existing.client === flight.client
      && existing.generation === flight.generation) return existing.promise;
  const promise = preflightExecutionCommand({
    ...callbacks,
    unknown: flight.unknown,
    lookup: async () => {
      if (!flight.client || typeof flight.client.execution !== 'function') {
        throw new Error('The runtime connection that accepted this execution is no longer available');
      }
      return flight.client.execution(flight.executionId, { signal: flight.controller.signal });
    },
  });
  const entry = { executionId: flight.executionId, client: flight.client, generation: flight.generation, promise };
  preflightFlights.set(binding, entry);
  const clear = () => {
    if (preflightFlights.get(binding) === entry) preflightFlights.delete(binding);
  };
  void promise.then(clear, clear);
  return promise;
}

function outcomeState(token) {
  const binding = token?.binding;
  if (binding?.outcomeClaim?.token === token) return binding.outcomeClaim;
  if (binding?.retiredOutcomeClaim?.token === token) return binding.retiredOutcomeClaim;
  return null;
}

export function captureExecutionOutcomeToken(binding, executionId, client, generation = binding?.generation) {
  if (!binding || binding.executionId !== executionId
      || binding.reconciliationClient !== client || binding.generation !== generation) return null;
  const existing = binding.outcomeClaim;
  if (existing?.token.generation === generation && existing.token.executionId === executionId
      && existing.token.client === client) return existing.token;
  const controller = new AbortController();
  const token = Object.freeze({ binding, generation, executionId, client, controller });
  binding.outcomeClaim = {
    token, fetchStarted: false, fetchPending: false, reportClaimed: false,
    completed: false,
  };
  return token;
}

function completeOutcomeState(claim) {
  if (!claim || claim.completed) return;
  claim.completed = true;
  claim.fetchPending = false;
}

function cancelOutcomeState(claim) {
  if (!claim) return;
  claim.token.controller.abort();
  completeOutcomeState(claim);
}

export function retireExecutionOutcomeClaim(binding, nextExecutionId = null, nextClient = null,
  { cancelInFlight = false } = {}) {
  if (!binding) return;
  let retired = cancelInFlight ? null : binding.retiredOutcomeClaim;
  const current = binding.outcomeClaim;
  if (cancelInFlight) {
    cancelOutcomeState(binding.retiredOutcomeClaim);
    cancelOutcomeState(current);
  } else if (current?.fetchPending && !current.reportClaimed) {
    if (retired && retired !== current) cancelOutcomeState(retired);
    retired = current;
  }
  // Reusing the same wire identity cannot make an old generation authoritative again. Its pending
  // callback becomes inert; the new generation will receive a distinct immutable token.
  if (retired?.token.executionId === nextExecutionId && retired.token.client === nextClient) {
    cancelOutcomeState(retired);
    retired = null;
  }
  binding.outcomeClaim = null;
  binding.retiredOutcomeClaim = retired;
}

export function claimExecutionOutcomeFetch(token) {
  const claim = outcomeState(token);
  if (!claim || claim.fetchStarted) return false;
  claim.fetchStarted = true;
  claim.fetchPending = true;
  return true;
}

export function executionOutcomeFetchSignal(token) {
  const claim = outcomeState(token);
  return claim?.fetchPending ? token.controller.signal : null;
}

export function enforceExecutionOutcomeCapacity(binding) {
  const current = binding?.outcomeClaim;
  const retired = binding?.retiredOutcomeClaim;
  if (!retired || !current?.fetchPending || current.reportClaimed) return null;
  cancelOutcomeState(retired);
  binding.retiredOutcomeClaim = null;
  return Object.freeze({
    executionId: retired.token.executionId,
    generation: retired.token.generation,
  });
}

export function claimExecutionOutcomeReport(token) {
  const claim = outcomeState(token);
  if (!claim || claim.reportClaimed) return false;
  claim.reportClaimed = true;
  return true;
}

export function completeExecutionOutcomeFetch(token) {
  const binding = token?.binding;
  const claim = outcomeState(token);
  if (!claim) return false;
  completeOutcomeState(claim);
  if (binding.retiredOutcomeClaim === claim) binding.retiredOutcomeClaim = null;
  return true;
}

/**
 * Reconciles one accepted execution without treating transport failure as completion.
 *
 * The fast polling phase is bounded by `failureThreshold`. Crossing that boundary publishes one
 * unknown-state transition and moves to a slower, bounded polling interval; it does not detach the
 * execution or manufacture a terminal result. The command layer may make Test/Run discoverable in
 * that state, but must preflight the same execution before sending another POST.
 */
export async function reconcileExecution({
  lookup,
  isCurrent,
  onUnknown = () => {},
  onKnown = () => {},
  onTerminal = () => {},
  sleep = delay => new Promise(resolve => setTimeout(resolve, delay)),
  pollDelayMs = DEFAULT_RECONCILIATION_OPTIONS.pollDelayMs,
  failureThreshold = DEFAULT_RECONCILIATION_OPTIONS.failureThreshold,
  degradedPollDelayMs = DEFAULT_RECONCILIATION_OPTIONS.degradedPollDelayMs,
} = {}) {
  if (typeof lookup !== 'function' || typeof isCurrent !== 'function') {
    throw new TypeError('Execution reconciliation requires lookup and isCurrent functions');
  }
  if (!Number.isInteger(failureThreshold) || failureThreshold < 1) {
    throw new RangeError('Execution reconciliation failureThreshold must be a positive integer');
  }

  let consecutiveFailures = 0;
  let unknownPublished = false;
  while (isCurrent()) {
    let outcome;
    try {
      outcome = await lookup();
      consecutiveFailures = 0;
      if (unknownPublished && !isTerminalExecution(outcome)) {
        unknownPublished = false;
        onKnown(outcome);
      }
    } catch (error) {
      consecutiveFailures += 1;
      if (!unknownPublished && consecutiveFailures >= failureThreshold) {
        unknownPublished = true;
        onUnknown({ error, failureCount: consecutiveFailures });
      }
    }

    if (!isCurrent()) return { state: 'cancelled' };
    if (isTerminalExecution(outcome)) {
      onTerminal({ outcome, recoveredFromUnknown: unknownPublished });
      return { state: 'terminal', outcome, recoveredFromUnknown: unknownPublished };
    }

    await sleep(unknownPublished ? degradedPollDelayMs : pollDelayMs);
  }
  return { state: 'cancelled' };
}

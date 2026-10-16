import { uiText } from './ui-text.js';

// The one-line summaries the Runtime activity panel prints when an execution reaches a terminal
// state, built from `/v1/executions/{id}/outcome` alone.
//
// ── WHY THE BYPASS LINE NAMES NO CAUSE ────────────────────────────────────────────────────
//
// A bypass can come from a `mode=test` submission or inbound `command=passthrough`, both properties
// of the RUN, or from a flag the author wrote on ONE node in the saved document. All three sources put
// node ids into the same `bypassedNodes` set. Describing that set as "bypassed by the pass-through
// run" would tell an operator whose run executed everything except one switched-off node that the
// entire execution was a rehearsal. That is a false result on the surface an operator reads FIRST
// when deciding whether a result can be trusted.
//
// `bypassedNodes` cannot be made to answer it. It is a `Set<String>` of node ids and carries no
// cause, and `ExecutionOutcome`'s javadoc now says so in as many words: two different decisions put a
// node in that set, membership alone implies nothing about the run as a whole, and a fully executing
// production run can populate it. So the honest choice was between rewording to something true of
// both causes, and deriving the cause from the events, which DO carry it as `publicReason`.
//
// **Rewording, deliberately, and not because deriving would have been hard.** The per-node
// classification already reaches this same panel from the field that genuinely holds it: every
// NODE_BYPASSED entry renders `authored` and `command.passthrough` as two different sentences
// (`execution-event-description.js`). Re-deriving that here would mean accumulating a per-execution
// side map of classifiers in the client and handing it to this function — a second, weaker authority
// over a fact one row above already states, that would be absent on any outcome this client did not
// stream (a reconciled run, a reload, durable replay, which never captured a classifier at all), and
// that would mis-attribute one run's cause to another's outcome if its scoping were ever wrong. Such
// misattribution would be even harder to detect than an over-broad summary. This line therefore
// reports what its own payload knows — which nodes did not run — and says out loud that the cause
// lives per node, rather than guessing it or silently dropping the question.
export function executionOutcomeMessages(outcome = {}) {
  const handledNodes = Array.isArray(outcome.handledFailureNodes) ? outcome.handledFailureNodes : [];
  const defaultedNodes = Array.isArray(outcome.defaultedNodes) ? outcome.defaultedNodes : [];
  const bypassedNodes = Array.isArray(outcome.bypassedNodes) ? outcome.bypassedNodes : [];
  const handledFailure = outcome.handledFailure === true || handledNodes.length > 0;
  const degraded = outcome.degraded === true || defaultedNodes.length > 0;
  const messages = [];

  if (handledFailure) {
    messages.push({ titleKey: 'executionOutcome.title', css: 'failed',
      detailKey: handledNodes.length ? 'executionOutcome.handled' : 'executionOutcome.handledUnavailable',
      detailParams: { status: outcome.status || 'COMPLETED', count: handledNodes.length,
        nodes: handledNodes.join(', ') } });
  } else if (degraded) {
    messages.push({ titleKey: 'executionOutcome.title', css: 'fallback',
      detailKey: defaultedNodes.length ? 'executionOutcome.degraded' : 'executionOutcome.degradedUnavailable',
      detailParams: { status: outcome.status || 'COMPLETED', count: defaultedNodes.length,
        nodes: defaultedNodes.join(', ') } });
  } else if (outcome.status === 'FAILED' && (outcome.cancelled === true || outcome.terminationReason === 'CANCELLED')) {
    // A cancelled execution is stored and reported as FAILED (see `ExecutionTerminationReason`'s own
    // documented rationale: forward/backward compatibility for a status persisted by name), so a bare
    // "FAILED" here is not a partial answer, it is the wrong one -- it tells an operator a deliberate
    // stop was an incident. `cancelled`/`terminationReason` are additive fields on the same outcome
    // JSON `/v1/executions/{id}` already returns; an older server that predates them never sets
    // either, so this branch is simply never reached and the FAILED branch below is unchanged.
    // Styled like the degraded branch above (`fallback`, the amber dot) rather than a new class: a
    // cancellation is deliberately neither the green of a clean COMPLETED nor the red this panel
    // reserves for an actual incident -- see TelemetryBridge's identical UNSET-not-ERROR span-status
    // reasoning for a cancelled traversal, made for the same reason on a different surface.
    messages.push({ titleKey: 'executionOutcome.title', detailKey: 'executionOutcome.cancelled', css: 'fallback' });
  } else if (outcome.status === 'FAILED') {
    messages.push({ titleKey: 'executionOutcome.title', detailKey: 'executionOutcome.failed', css: 'failed' });
  } else {
    messages.push({ titleKey: 'executionOutcome.title', css: 'completed',
      detailKey: 'executionOutcome.clean', detailParams: { status: outcome.status || 'COMPLETED' } });
  }
  if (bypassedNodes.length) {
    messages.push({ titleKey: 'executionOutcome.title', css: 'bypassed',
      detailKey: 'executionOutcome.bypassed',
      detailParams: { count: bypassedNodes.length, nodes: bypassedNodes.join(', ') } });
  }
  return messages.map(message => Object.defineProperties({
    title: uiText(message.titleKey), detail: uiText(message.detailKey, message.detailParams), css: message.css,
  }, {
    titleKey: { value: message.titleKey },
    detailKey: { value: message.detailKey },
    detailParams: { value: message.detailParams },
  }));
}

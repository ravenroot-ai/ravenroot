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
    const nodes = handledNodes.length ? `: ${handledNodes.join(', ')}` : '; node list unavailable';
    messages.push({ title: 'execution outcome', css: 'failed',
      detail: `${outcome.status || 'COMPLETED'} with ${handledNodes.length} handled node failure(s)${nodes}` });
  } else if (degraded) {
    const nodes = defaultedNodes.length ? `: ${defaultedNodes.join(', ')}` : '; node list unavailable';
    messages.push({ title: 'execution outcome', css: 'fallback',
      detail: `${outcome.status || 'COMPLETED'}, degraded: ${defaultedNodes.length} node(s) ran as an unresolved default${nodes}` });
  } else if (outcome.status === 'FAILED') {
    messages.push({ title: 'execution outcome', detail: 'FAILED', css: 'failed' });
  } else {
    messages.push({ title: 'execution outcome', css: 'completed',
      detail: `${outcome.status || 'COMPLETED'}, no handled failures and no defaulted nodes` });
  }
  if (bypassedNodes.length) {
    messages.push({ title: 'execution outcome', css: 'bypassed',
      detail: `${bypassedNodes.length} node(s) bypassed — behaviour not invoked, traversal continued`
        + ` past ${bypassedNodes.length === 1 ? 'it' : 'them'}: ${bypassedNodes.join(', ')}.`
        + ' Cause is reported per node, in the NODE_BYPASSED entries.' });
  }
  return messages;
}

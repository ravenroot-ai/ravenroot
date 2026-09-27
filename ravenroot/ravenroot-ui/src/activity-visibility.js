// Activity observation levels: how much of the runtime's event stream the Activity panel shows.
//
// A level is an OBSERVER preference and nothing else. It never enters GraphML, never alters the
// graph or the execution, and is never an execution-authorization input. It also never reaches the
// monitoring projection that paints nodes and edges: filtering happens on the one path that appends
// a row to `#activity-log` (`appendActivityEvent` in `src/app.js`), after the same event has already
// been offered to `observeNodeActivity`/`observeEdgeTraversal`. A quiet panel therefore cannot make
// the graph's runtime state inaccurate.
//
// The three levels are CUMULATIVE, least to most verbose:
//
//   Output  the intentional messages an author asked for -- the bounded value a trusted `log` node
//           emitted -- plus failures, the terminal execution state, and every UI-local message
//           (which no execution event can represent). The default.
//   Nodes   Output, plus node start and node completion.
//   Trace   the complete technical stream, including edges, joins, retries, bypass/default detail
//           and the execution identifiers.
//
// An entry at level L is visible when the selected mode ranks at least L, which is what makes the
// levels supersets rather than disjoint filters.
export const ACTIVITY_LEVEL_OUTPUT = 'output';
export const ACTIVITY_LEVEL_NODES = 'nodes';
export const ACTIVITY_LEVEL_TRACE = 'trace';

/** The three levels in presentation order, least verbose first. */
export const ACTIVITY_MODES = Object.freeze([
  ACTIVITY_LEVEL_OUTPUT, ACTIVITY_LEVEL_NODES, ACTIVITY_LEVEL_TRACE,
]);

/** A newly loaded UI starts quiet. Deliberately not persisted: see the issue's default-mode rule. */
export const DEFAULT_ACTIVITY_MODE = ACTIVITY_LEVEL_OUTPUT;

const LEVEL_RANK = new Map(ACTIVITY_MODES.map((mode, index) => [mode, index]));

const NODE_LIFECYCLE_TYPES = new Set(['NODE_STARTED', 'NODE_COMPLETED']);
const TERMINAL_TYPES = new Set(['EXECUTION_COMPLETED', 'EXECUTION_CANCELLED']);

/** Whether `value` names one of the three levels this build understands. */
export function isActivityMode(value) {
  return LEVEL_RANK.has(value);
}

// Idempotent, and total: a stale or unknown value reads as the default rather than as a mode name
// that would silently hide everything.
export function normalizeActivityMode(value) {
  return isActivityMode(value) ? value : DEFAULT_ACTIVITY_MODE;
}

/**
 * Whether `event` is an intentional `log` emission.
 *
 * The evidence is the trusted typed author projection and nothing else. `ExecutionEvent`'s compact
 * constructor nulls `authorOutput` unless the event is a `NODE_COMPLETED` whose node's CATALOG KEY is
 * `log` (the built-in `LogNodeBehaviorFactory` returns `NodeActionDiagnostic.log(...)`), and the SSE
 * live projection writes the `output` member only when that projection is present. So the member
 * exists because of catalog semantics -- never because of a node name, a graph attribute, a payload
 * shape or a visual style -- which is exactly the identification contract this panel must satisfy.
 *
 * The `NODE_COMPLETED` half is asserted here rather than left to the server. It changes nothing for a
 * truthful peer (the two conditions are set together), but a frame whose type disagrees with its
 * members must not be able to borrow the concise rendering and drop its own event type.
 */
export function isLogEmission(event) {
  return Boolean(event) && String(event.type) === 'NODE_COMPLETED' && Object.hasOwn(event, 'output');
}

/**
 * Whether `event` is a failure, named by the `FAILED` suffix every failure type carries
 * (`NODE_FAILED`, `JOIN_FAILED`, `EXECUTION_FAILED`).
 *
 * A suffix rather than a closed list, because the requirement is that no mode may hide a failure:
 * a type a newer peer introduces under the same convention stays visible instead of disappearing
 * behind a filter built before it existed.
 */
export function isFailureEvent(event) {
  return String(event?.type || '').includes('FAILED');
}

/**
 * The least verbose level at which `event` must appear.
 *
 * Order matters and is fixed: failure, then terminal state, then log emission -- so an event that is
 * both (a `log` node can never fail into its `output`, but the ordering keeps the rule independent
 * of that) is still shown. Everything unnamed falls to Trace, the only level that preserves the
 * current full behaviour, so a new event type is never silently dropped from the panel.
 */
export function activityEventLevel(event) {
  if (isFailureEvent(event)) return ACTIVITY_LEVEL_OUTPUT;
  if (TERMINAL_TYPES.has(String(event?.type || ''))) return ACTIVITY_LEVEL_OUTPUT;
  if (isLogEmission(event)) return ACTIVITY_LEVEL_OUTPUT;
  if (NODE_LIFECYCLE_TYPES.has(String(event?.type || ''))) return ACTIVITY_LEVEL_NODES;
  return ACTIVITY_LEVEL_TRACE;
}

/** Whether an event projected through `event` is shown while `mode` is selected. */
export function activityEventVisible(event, mode) {
  return LEVEL_RANK.get(activityEventLevel(event)) <= LEVEL_RANK.get(normalizeActivityMode(mode));
}

/**
 * Whether a log emission is rendered as concise workflow output rather than as the generic technical
 * node-completion row.
 *
 * Trace keeps the current full rendering, identifiers included, because that is what Trace is for.
 * Output and Nodes render the emitted value as the row itself.
 */
export function usesConciseLogRendering(event, mode) {
  return isLogEmission(event) && normalizeActivityMode(mode) !== ACTIVITY_LEVEL_TRACE;
}

/**
 * The concise row for one projected log emission: the emitted value is the row's primary text, and
 * the disclosure boundary survives as the flags beside it.
 *
 * The generic `NODE_COMPLETED` title, the instance counts and the process/traversal/invocation/
 * attempt identifiers are deliberately absent -- the point of Output mode is that the value can be
 * read without any of them. Redaction and truncation markers are preserved, not weakened: they ride
 * on `displayValue` itself (produced by `runtimeActivityOutput`) and are restated as flags here.
 */
export function logEmissionPresentation(output) {
  const flags = [output?.redacted ? 'redacted' : '', output?.truncated ? 'truncated' : '']
    .filter(Boolean).join(', ');
  return {
    title: typeof output?.displayValue === 'string' ? output.displayValue : '',
    detail: flags ? `(${flags})` : '',
  };
}

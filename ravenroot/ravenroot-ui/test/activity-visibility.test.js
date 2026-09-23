import { describe, expect, it } from 'vitest';

import {
  ACTIVITY_LEVEL_NODES, ACTIVITY_LEVEL_OUTPUT, ACTIVITY_LEVEL_TRACE, ACTIVITY_MODES,
  DEFAULT_ACTIVITY_MODE, activityEventLevel, activityEventVisible, isActivityMode,
  isFailureEvent, isLogEmission, logEmissionPresentation, normalizeActivityMode,
  usesConciseLogRendering,
} from '../src/activity-visibility.js';

// The observation levels decide what an author SEES in the Activity panel. They are a presentation
// preference and nothing more: nothing here touches GraphML, the graph, the execution, the
// monitoring projection or an authorization decision. These tests pin two things the panel's
// behaviour rests on — which level every runtime event type lands at, and what a concise log
// emission renders as — because both are contracts, not implementation detail.
//
// The matrix below is written out type by type rather than derived from the module, deliberately:
// it is an INVENTORY. A type added to the runtime's own enum has to be classified here on purpose,
// instead of agreeing with whatever the fall-through happens to produce.
const EVENT_LEVELS = {
  // Failures: visible in every mode, so a quiet filter can never make a failed execution look like
  // it is still running.
  NODE_FAILED: ACTIVITY_LEVEL_OUTPUT,
  JOIN_FAILED: ACTIVITY_LEVEL_OUTPUT,
  EXECUTION_FAILED: ACTIVITY_LEVEL_OUTPUT,
  // Terminal state, whether or not it is a failure.
  EXECUTION_COMPLETED: ACTIVITY_LEVEL_OUTPUT,
  EXECUTION_CANCELLED: ACTIVITY_LEVEL_OUTPUT,
  // Node lifecycle: Nodes and above.
  NODE_STARTED: ACTIVITY_LEVEL_NODES,
  NODE_COMPLETED: ACTIVITY_LEVEL_NODES,
  // Everything technical: Trace.
  EXECUTION_STARTED: ACTIVITY_LEVEL_TRACE,
  EXECUTION_PAUSED: ACTIVITY_LEVEL_TRACE,
  EXECUTION_RESUMED: ACTIVITY_LEVEL_TRACE,
  NODE_BYPASSED: ACTIVITY_LEVEL_TRACE,
  NODE_DEFAULTED: ACTIVITY_LEVEL_TRACE,
  NODE_RETRY_SCHEDULED: ACTIVITY_LEVEL_TRACE,
  EDGE_TRAVERSED: ACTIVITY_LEVEL_TRACE,
  JOIN_SATISFIED: ACTIVITY_LEVEL_TRACE,
  JOIN_ITERATION_BACKLOG: ACTIVITY_LEVEL_TRACE,
  JOIN_ARRIVAL_DISCARDED: ACTIVITY_LEVEL_TRACE,
};

describe('activity observation levels', () => {
  it('orders the levels least verbose first and defaults to Output', () => {
    expect(ACTIVITY_MODES).toEqual([ACTIVITY_LEVEL_OUTPUT, ACTIVITY_LEVEL_NODES, ACTIVITY_LEVEL_TRACE]);
    expect(DEFAULT_ACTIVITY_MODE).toBe(ACTIVITY_LEVEL_OUTPUT);
  });

  it('classifies every runtime event type at its ruled level', () => {
    for (const [type, level] of Object.entries(EVENT_LEVELS)) {
      expect(activityEventLevel({ type }), type).toBe(level);
    }
  });

  it('treats an unknown type as Trace rather than dropping it from the panel', () => {
    // Forward compatibility: a newer peer's event must stay visible somewhere. Trace is the only
    // level that preserves the current full behaviour, so nothing is silently lost.
    expect(activityEventLevel({ type: 'FUTURE_EVENT' })).toBe(ACTIVITY_LEVEL_TRACE);
    expect(activityEventLevel({})).toBe(ACTIVITY_LEVEL_TRACE);
    expect(activityEventLevel(null)).toBe(ACTIVITY_LEVEL_TRACE);
  });

  it('recognizes a failure by the FAILED suffix, not by a closed list', () => {
    // The rule is a convention, so a failure type this build has never seen is still shown.
    expect(isFailureEvent({ type: 'NODE_FAILED' })).toBe(true);
    expect(isFailureEvent({ type: 'JOIN_FAILED' })).toBe(true);
    expect(isFailureEvent({ type: 'EXECUTION_FAILED' })).toBe(true);
    expect(isFailureEvent({ type: 'WORKLOAD_FAILED' })).toBe(true);
    expect(activityEventLevel({ type: 'WORKLOAD_FAILED' })).toBe(ACTIVITY_LEVEL_OUTPUT);
    // `NODE_RETRY_SCHEDULED` is an attempt's settlement but not a FAILED event: retry detail belongs
    // to Trace, and reporting a retried-then-successful node as a failure would say the opposite of
    // what happened. `NODE_DEFAULTED`/`NODE_BYPASSED` are settlements too, not failures.
    expect(isFailureEvent({ type: 'NODE_RETRY_SCHEDULED' })).toBe(false);
    expect(isFailureEvent({ type: 'NODE_DEFAULTED' })).toBe(false);
  });

  it('reads the trusted typed author projection as the only log-emission signal', () => {
    // Presence of `output` is catalog semantics: the server writes it only for a NODE_COMPLETED of a
    // `log`-catalog node. Not a node name, not a graph attribute, not a payload shape.
    expect(isLogEmission({ type: 'NODE_COMPLETED', output: '3.141' })).toBe(true);
    expect(isLogEmission({ type: 'NODE_COMPLETED', output: '' })).toBe(true);
    expect(isLogEmission({ type: 'NODE_COMPLETED', output: null })).toBe(true);
    expect(isLogEmission({ type: 'NODE_COMPLETED' })).toBe(false);
    expect(isLogEmission({ type: 'NODE_STARTED', output: 'not a log emission' })).toBe(false);
    expect(isLogEmission(null)).toBe(false);
  });

  it('makes a log emission an Output-level row', () => {
    expect(activityEventLevel({ type: 'NODE_COMPLETED', output: '3.1' })).toBe(ACTIVITY_LEVEL_OUTPUT);
  });

  it('treats the levels as cumulative supersets, not disjoint filters', () => {
    const logEmission = { type: 'NODE_COMPLETED', output: '3.14' };
    const nodeStarted = { type: 'NODE_STARTED', nodeId: 'n1' };
    const edgeTraversed = { type: 'EDGE_TRAVERSED', nodeId: 'n1' };
    const nodeFailed = { type: 'NODE_FAILED', nodeId: 'n1' };
    const completed = { type: 'EXECUTION_COMPLETED' };

    // Output: intentional output, failures, terminal state, UI-local messages — and no lifecycle or
    // technical detail.
    expect(activityEventVisible(logEmission, ACTIVITY_LEVEL_OUTPUT)).toBe(true);
    expect(activityEventVisible(nodeFailed, ACTIVITY_LEVEL_OUTPUT)).toBe(true);
    expect(activityEventVisible(completed, ACTIVITY_LEVEL_OUTPUT)).toBe(true);
    expect(activityEventVisible(nodeStarted, ACTIVITY_LEVEL_OUTPUT)).toBe(false);
    expect(activityEventVisible(edgeTraversed, ACTIVITY_LEVEL_OUTPUT)).toBe(false);

    // Nodes: everything Output shows, plus node start/completion.
    expect(activityEventVisible(nodeStarted, ACTIVITY_LEVEL_NODES)).toBe(true);
    expect(activityEventVisible(logEmission, ACTIVITY_LEVEL_NODES)).toBe(true);
    expect(activityEventVisible(nodeFailed, ACTIVITY_LEVEL_NODES)).toBe(true);
    expect(activityEventVisible(completed, ACTIVITY_LEVEL_NODES)).toBe(true);
    expect(activityEventVisible(edgeTraversed, ACTIVITY_LEVEL_NODES)).toBe(false);

    // Trace: the complete technical stream.
    expect(activityEventVisible(edgeTraversed, ACTIVITY_LEVEL_TRACE)).toBe(true);
    expect(activityEventVisible(nodeStarted, ACTIVITY_LEVEL_TRACE)).toBe(true);
    expect(activityEventVisible(logEmission, ACTIVITY_LEVEL_TRACE)).toBe(true);
  });

  it('keeps failures and terminal state visible in every mode', () => {
    for (const mode of ACTIVITY_MODES) {
      for (const type of ['NODE_FAILED', 'JOIN_FAILED', 'EXECUTION_FAILED',
        'EXECUTION_COMPLETED', 'EXECUTION_CANCELLED']) {
        expect(activityEventVisible({ type, nodeId: 'n1' }, mode), `${type} in ${mode}`).toBe(true);
      }
    }
  });

  it('normalizes an unknown or stale mode to the default instead of hiding everything', () => {
    expect(isActivityMode(ACTIVITY_LEVEL_TRACE)).toBe(true);
    expect(isActivityMode('everything')).toBe(false);
    expect(isActivityMode(undefined)).toBe(false);
    expect(normalizeActivityMode('everything')).toBe(ACTIVITY_LEVEL_OUTPUT);
    expect(normalizeActivityMode(undefined)).toBe(ACTIVITY_LEVEL_OUTPUT);
    expect(normalizeActivityMode(ACTIVITY_LEVEL_NODES)).toBe(ACTIVITY_LEVEL_NODES);
  });
});

describe('concise log rendering', () => {
  const emission = (output, flags = {}) => ({ type: 'NODE_COMPLETED', nodeId: 'log-1', output, ...flags });

  it('renders a log emission concisely in Output and Nodes, and technically in Trace', () => {
    expect(usesConciseLogRendering(emission('3.141'), ACTIVITY_LEVEL_OUTPUT)).toBe(true);
    expect(usesConciseLogRendering(emission('3.141'), ACTIVITY_LEVEL_NODES)).toBe(true);
    // Trace preserves the current full Activity behaviour, identifiers included.
    expect(usesConciseLogRendering(emission('3.141'), ACTIVITY_LEVEL_TRACE)).toBe(false);
    // A non-log completion is never rendered as author output.
    expect(usesConciseLogRendering({ type: 'NODE_COMPLETED', nodeId: 'n1' }, ACTIVITY_LEVEL_OUTPUT)).toBe(false);
  });

  it('makes the emitted value the row itself, with no identifiers and no flags when clean', () => {
    const row = logEmissionPresentation({ displayValue: '3.141', redacted: false, truncated: false });
    expect(row).toEqual({ title: '3.141', detail: '' });
    // The rendering carries no node id, no process/traversal/invocation/attempt identity and no
    // instance counts: those are what the author should not have to read past to find the value.
    expect(Object.keys(row).sort()).toEqual(['detail', 'title']);
    expect(row.title).not.toContain('NODE_COMPLETED');
    expect(row.detail).not.toMatch(/instances=|inFlight=/);
  });

  it('preserves redaction and truncation markers beside the value', () => {
    // `displayValue` already carries the inline markers produced by `runtimeActivityOutput`; the
    // flags restate them, and neither is dropped by the concise rendering.
    const row = logEmissionPresentation({
      displayValue: 'password=[ravenroot:redacted:credential][ravenroot:truncated]',
      redacted: true, truncated: true,
    });
    expect(row.title).toContain('[ravenroot:redacted:credential]');
    expect(row.title).toContain('[ravenroot:truncated]');
    expect(row.detail).toBe('(redacted, truncated)');

    expect(logEmissionPresentation({ displayValue: 'x', redacted: true, truncated: false }).detail)
      .toBe('(redacted)');
    expect(logEmissionPresentation({ displayValue: 'x', redacted: false, truncated: true }).detail)
      .toBe('(truncated)');
  });

  it('keeps multi-line structured values intact for the wrapping contract', () => {
    // Real line feeds are preserved rather than collapsed; the panel's own CSS supplies `pre-wrap`
    // and `overflow-wrap: anywhere`, so this function must not touch whitespace.
    const value = '{\n  "pi": 3.141\n}';
    expect(logEmissionPresentation({ displayValue: value }).title).toBe(value);
  });

  it('degrades to an empty row rather than throwing on an absent projection', () => {
    expect(logEmissionPresentation(undefined)).toEqual({ title: '', detail: '' });
    expect(logEmissionPresentation(null)).toEqual({ title: '', detail: '' });
    expect(logEmissionPresentation({ displayValue: 42 })).toEqual({ title: '', detail: '' });
  });
});

import { describe, expect, it } from 'vitest';

import {
  ATTACHED,
  CONTEXT_CLASSES,
  CONTEXT_CLASS_IDS,
  CHIP_DETAIL_LIMIT,
  DEFAULT_CLASS_BUDGET_BYTES,
  NOT_PROVIDED,
  NO_DATA,
  SOURCE_ERROR,
  UNAVAILABLE,
  attachmentClaimViolations,
  composeContext,
  contextChipDescription,
} from '../src/assistant-context.js';

// Every class wired and returning something, so each test below can strip exactly one thing and
// attribute the difference to that.
const allSources = () => ({
  graph: () => ({ nodeCount: 2 }),
  catalog: () => [{ behavior: 'http.request' }],
  validation: () => ({ checks: [], findings: [] }),
  execution: () => ({ executionId: 'exec-1' }),
  logs: () => ['boot'],
  events: () => [{ type: 'NODE_STARTED' }],
});

const classNamed = (context, id) => context.classes.find(entry => entry.id === id);

describe('the named context classes', () => {
  it('covers the graph, catalog, validation, execution, logs and the event stream', () => {
    expect(CONTEXT_CLASS_IDS).toEqual(['graph', 'catalog', 'validation', 'execution', 'logs', 'events']);
  });

  it('is composed in a fixed order, so chips do not reshuffle between renders', () => {
    const first = composeContext(allSources()).classes.map(entry => entry.id);
    const second = composeContext({ graph: () => ({}) }).classes.map(entry => entry.id);
    expect(first).toEqual(CONTEXT_CLASS_IDS);
    expect(second).toEqual(CONTEXT_CLASS_IDS);
  });
});

// ── THE CLAIM THAT MUST BE ABLE TO FAIL ──────────────────────────────────────────────────────────
//
// This is the control the design record calls the falsifiable attachment claim, and these are the
// tests a panel that implied context it lacked has to fail. They assert the LABEL and the PAYLOAD
// together, in one assertion each, because checking them separately is what would let the two drift
// apart and still pass.
describe('attachment is a claim that can be falsified', () => {
  it('states the equivalence directly: attached exactly when the payload carries the class', () => {
    expect(attachmentClaimViolations(composeContext(allSources()))).toEqual([]);
    expect(attachmentClaimViolations(composeContext({}))).toEqual([]);
    expect(attachmentClaimViolations(composeContext({ graph: () => ({ a: 1 }) }))).toEqual([]);
  });

  it.each(CONTEXT_CLASSES.map(entry => entry.id))(
    'flips the label and the payload together when the %s source is stripped', id => {
      const before = composeContext(allSources());
      expect(classNamed(before, id).state).toBe(ATTACHED);
      expect(Object.hasOwn(before.payload, id)).toBe(true);

      const stripped = { ...allSources() };
      delete stripped[id];
      const after = composeContext(stripped);

      // BOTH halves, in the same test. A panel that kept the chip green while dropping the class
      // from the payload fails on the second expectation; one that dropped the chip while still
      // sending the data fails on the third.
      expect(classNamed(after, id).state).toBe(UNAVAILABLE);
      expect(Object.hasOwn(after.payload, id)).toBe(false);
      expect(attachmentClaimViolations(after)).toEqual([]);

      // And every OTHER class is untouched: stripping one source must not quietly cost another.
      for (const other of CONTEXT_CLASS_IDS.filter(entry => entry !== id)) {
        expect(classNamed(after, other).state).toBe(ATTACHED);
        expect(Object.hasOwn(after.payload, other)).toBe(true);
      }
    });

  it('restores both halves together when the source comes back', () => {
    const lost = composeContext({ ...allSources(), catalog: () => { throw new Error('offline'); } });
    expect(classNamed(lost, 'catalog').state).toBe(UNAVAILABLE);
    expect(Object.hasOwn(lost.payload, 'catalog')).toBe(false);

    const restored = composeContext(allSources());
    expect(classNamed(restored, 'catalog').state).toBe(ATTACHED);
    expect(Object.hasOwn(restored.payload, 'catalog')).toBe(true);
  });

  it('never carries a payload key for a class it has no chip for', () => {
    const context = composeContext(allSources());
    expect(Object.keys(context.payload).every(key => CONTEXT_CLASS_IDS.includes(key))).toBe(true);
  });
});

// ── WHY UNAVAILABLE IS NOT ONE REASON ────────────────────────────────────────────────────────────
describe('the reasons a class is unavailable stay distinguished', () => {
  it('separates a source that is not wired from one with nothing yet and one that failed', () => {
    const context = composeContext({
      catalog: () => null,
      graph: () => { throw new Error('parser died'); },
      // `validation`, `execution`, `logs`, `events` are not wired at all.
    });
    expect(classNamed(context, 'logs').reason).toBe(NOT_PROVIDED);
    expect(classNamed(context, 'catalog').reason).toBe(NO_DATA);
    expect(classNamed(context, 'graph').reason).toBe(SOURCE_ERROR);
  });

  it('degrades the panel only for a source that was LOST, never for one that is merely empty', () => {
    // Nothing wired, nothing to attach — an ordinary fresh session, not a fault.
    expect(composeContext({}).degraded).toBe(false);
    // Everything wired, nothing to show yet — still not a fault.
    expect(composeContext({
      graph: () => null, catalog: () => null, validation: () => null,
      execution: () => null, logs: () => null, events: () => null,
    }).degraded).toBe(false);
    // One wired source threw. THIS is the fault DEGRADED exists to report.
    expect(composeContext({ ...allSources(), events: () => { throw new Error('stream closed'); } })
      .degraded).toBe(true);
  });

  it('carries the failure text so the chip can say what broke rather than that something did', () => {
    const context = composeContext({ catalog: () => { throw new Error('HTTP 503'); } });
    expect(classNamed(context, 'catalog').detail).toContain('HTTP 503');
    expect(contextChipDescription(classNamed(context, 'catalog')))
      .toBe('Node catalog: unavailable, HTTP 503');
  });

  // A chip is a label in a wrapping row, not a log line. The entry keeps the full text; only the
  // rendered sentence is bounded, so nothing is lost — it is relocated.
  it('bounds and flattens the reason it renders, without truncating what it stores', () => {
    const sprawling = `line one\nline two ${'x'.repeat(200)}`;
    const context = composeContext({ catalog: () => { throw new Error(sprawling); } });
    const entry = classNamed(context, 'catalog');
    const sentence = contextChipDescription(entry);

    expect(entry.detail).toContain('x'.repeat(200));
    expect(sentence).not.toContain('\n');
    expect(sentence.length).toBeLessThanOrEqual('Node catalog: unavailable, '.length + CHIP_DETAIL_LIMIT);
    expect(sentence.endsWith('…')).toBe(true);
  });

  it('says only the state when there is no reason worth adding', () => {
    const context = composeContext({ graph: () => ({ a: 1 }) });
    expect(contextChipDescription(classNamed(context, 'graph'))).toBe('Open graph: attached');
  });

  it('reads a source that is not a function as not-wired rather than throwing', () => {
    const context = composeContext({ graph: 'the whole graph, honest' });
    expect(classNamed(context, 'graph').state).toBe(UNAVAILABLE);
    expect(classNamed(context, 'graph').reason).toBe(NOT_PROVIDED);
    expect(context.degraded).toBe(false);
  });
});

// ── TRUNCATION IS SHOWN, BECAUSE A SILENT DROP IS THE SAME DEFECT ────────────────────────────────
describe('a class larger than its budget', () => {
  const huge = () => ({ blob: 'x'.repeat(DEFAULT_CLASS_BUDGET_BYTES * 2) });

  it('still attaches, but says it was truncated in both the chip and the payload', () => {
    const context = composeContext({ ...allSources(), graph: huge });
    const entry = classNamed(context, 'graph');
    expect(entry.state).toBe(ATTACHED);
    expect(entry.truncated).toBe(true);
    expect(context.truncated).toBe(true);
    expect(context.payload.graph.truncated).toBe(true);
    expect(context.payload.graph.omittedBytes).toBeGreaterThan(0);
    expect(contextChipDescription(entry)).toMatch(/attached, truncated · \d+ bytes omitted/);
    // Truncation is a size outcome, not a lost source: it must not put the panel in DEGRADED.
    expect(context.degraded).toBe(false);
  });

  it('leaves a class within budget completely untouched', () => {
    const value = { nodeCount: 2 };
    const context = composeContext({ graph: () => value });
    expect(context.payload.graph).toBe(value);
    expect(classNamed(context, 'graph').truncated).toBe(false);
    expect(context.truncated).toBe(false);
  });

  it('honours a caller-supplied budget, so the rule is not a hidden constant', () => {
    const context = composeContext({ graph: () => ({ blob: 'x'.repeat(500) }) }, { budgetBytes: 64 });
    expect(classNamed(context, 'graph').truncated).toBe(true);
  });
});

describe('a value that cannot be serialized', () => {
  it('is reported rather than attached in some partial form', () => {
    const cyclic = {};
    cyclic.self = cyclic;
    expect(() => composeContext({ graph: () => cyclic })).toThrow(/not serializable/);
  });
});

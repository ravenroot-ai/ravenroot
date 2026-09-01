import { describe, expect, it } from 'vitest';
import {
  BLANK_OUTCOME, FALLBACK_OUTCOME, resolveOutcome, resolveOutcomes, unreachableOutcome,
} from '../src/node-outcomes.js';

// Mirrors what `/v1/catalog` ships for the two parameterized built-ins. Kept as literal
// fixtures rather than fetched, so a change to the server's JSON shape fails these loudly instead of
// being absorbed.
const celDecision = {
  behavior: 'cel-decision',
  properties: [
    { name: 'expression', defaultValue: '' },
    { name: 'trueOutcome', defaultValue: 'true' },
    { name: 'falseOutcome', defaultValue: 'false' },
  ],
  outcomes: [
    { name: '', fromProperty: 'trueOutcome', description: 'The expression evaluated to true.' },
    { name: '', fromProperty: 'falseOutcome', description: 'The expression evaluated to anything other than true.' },
  ],
};

const delay = {
  behavior: 'delay',
  properties: [{ name: 'durationMs', defaultValue: '1000' }],
  outcomes: [{ name: 'continue', fromProperty: '', description: 'The wait elapsed.' }],
};

describe('resolveOutcomes', () => {
  it('resolves a fixed outcome without consulting properties', () => {
    expect(resolveOutcomes(delay, {}).map(entry => entry.outcome)).toEqual(['continue']);
  });

  it('falls back to the declared default when the node configures nothing', () => {
    expect(resolveOutcomes(celDecision, {}).map(entry => entry.outcome)).toEqual(['true', 'false']);
  });

  // The case the design exists for: a static per-type set would answer true/false here, which is the
  // wrong answer for this node.
  it("resolves the author's own outcome names", () => {
    const properties = { trueOutcome: 'approved', falseOutcome: 'rejected' };
    expect(resolveOutcomes(celDecision, properties).map(entry => entry.outcome))
      .toEqual(['approved', 'rejected']);
  });

  it('resolves one custom name alongside one left at its default', () => {
    expect(resolveOutcomes(celDecision, { trueOutcome: 'approved' }).map(entry => entry.outcome))
      .toEqual(['approved', 'false']);
  });

  // Mirrors NodeProperties.string + NodeResult: a default substitutes for an ABSENT property only, and
  // a blank outcome is then coerced to 'continue'. Falling back to 'true' here would suggest an
  // outcome this node cannot produce.
  it('resolves a blank property to continue rather than to the declared default', () => {
    expect(resolveOutcomes(celDecision, { trueOutcome: '', falseOutcome: 'no' }).map(entry => entry.outcome))
      .toEqual([BLANK_OUTCOME, 'no']);
  });

  it('collapses two properties naming the same outcome into one', () => {
    expect(resolveOutcomes(celDecision, { trueOutcome: 'done', falseOutcome: 'done' })
      .map(entry => entry.outcome)).toEqual(['done']);
  });

  it('reports which property a parameterized outcome came from', () => {
    const [first] = resolveOutcomes(celDecision, { trueOutcome: 'approved' });
    expect(first).toMatchObject({ outcome: 'approved', parameterized: true, property: 'trueOutcome' });
  });

  it('reports a fixed outcome as not parameterized', () => {
    expect(resolveOutcomes(delay, {})[0]).toMatchObject({ parameterized: false, property: '' });
  });

  it('returns nothing for a catalog entry that predates the outcome declaration', () => {
    expect(resolveOutcomes({ behavior: 'legacy', properties: [] }, {})).toEqual([]);
  });

  it('returns nothing for a behavior the catalog does not contain', () => {
    expect(resolveOutcomes(null, {})).toEqual([]);
  });

  it('tolerates a node with no properties object at all', () => {
    expect(resolveOutcomes(celDecision, undefined).map(entry => entry.outcome)).toEqual(['true', 'false']);
  });

  it('stringifies a non-string property value, as NodeProperties.string does', () => {
    expect(resolveOutcomes(celDecision, { trueOutcome: 200 }).map(entry => entry.outcome))
      .toEqual(['200', 'false']);
  });

  /**
   * The editor holds no outcome list of its own. Adding an outcome to a behavior changes that
   * behavior's descriptor, and
   * it reaches the inspector through the catalog with no editor change at all — so this fixture is an
   * outcome no built-in declares, and it resolves purely because the descriptor said so.
   */
  it('surfaces an outcome a behavior newly declares, with no change to the editor', () => {
    const invented = {
      behavior: 'invented',
      properties: [{ name: 'timeoutOutcome', defaultValue: 'timed-out' }],
      outcomes: [
        { name: 'continue', fromProperty: '', description: 'Finished.' },
        { name: '', fromProperty: 'timeoutOutcome', description: 'Ran out of time.' },
      ],
    };
    expect(resolveOutcomes(invented, {}).map(entry => entry.outcome)).toEqual(['continue', 'timed-out']);
    expect(resolveOutcomes(invented, { timeoutOutcome: 'gave-up' }).map(entry => entry.outcome))
      .toEqual(['continue', 'gave-up']);
  });
});

describe('resolveOutcome', () => {
  it('prefers the configured value over the declared default', () => {
    expect(resolveOutcome({ fromProperty: 'p' }, 'mine', 'theirs')).toBe('mine');
  });

  it('uses the declared default only when the property is absent', () => {
    expect(resolveOutcome({ fromProperty: 'p' }, undefined, 'theirs')).toBe('theirs');
    expect(resolveOutcome({ fromProperty: 'p' }, null, 'theirs')).toBe('theirs');
  });

  it('coerces a whitespace-only value to continue, as NodeResult does', () => {
    expect(resolveOutcome({ fromProperty: 'p' }, '   ', 'theirs')).toBe(BLANK_OUTCOME);
  });

  // Not trimmed, because it is not trimmed at run time either and nextEdges matches with equals.
  it('preserves a padded value rather than trimming it', () => {
    expect(resolveOutcome({ fromProperty: 'p' }, ' ok ', 'theirs')).toBe(' ok ');
  });
});

// ── Unreachable outcome diagnostics ─────────────────────────────────────────────────────────
//
// `resolveOutcomes` says what a node CAN emit. This turns that into a verdict about one edge, and the
// interesting assertions below are the ones that stay SILENT: the design constraint is that a
// false positive is worse than no signal, so the exemptions carry more weight here than detection.

const httpRequest = {
  behavior: 'http-request',
  properties: [
    { name: 'url', defaultValue: '' },
    { name: 'successOutcome', defaultValue: 'continue' },
    { name: 'failureOutcome', defaultValue: 'failed' },
  ],
  outcomes: [
    { name: '', fromProperty: 'successOutcome', description: 'The response was a 2xx.' },
    { name: '', fromProperty: 'failureOutcome', description: 'The response was not a 2xx.' },
  ],
};

/** A behavior with no `outcomes` at all — seven of the nine extension modules today, and every node
 * package published outside this repository. */
const undeclared = { behavior: 'mail-send', properties: [], outcomes: [] };

describe('unreachableOutcome', () => {
  it('flags an outcome the source node cannot emit', () => {
    const resolved = resolveOutcomes(celDecision, { trueOutcome: 'approved', falseOutcome: 'rejected' });
    expect(unreachableOutcome(resolved, 'escalated')).toBe(true);
  });

  it('stays silent for an outcome the source node does emit', () => {
    const resolved = resolveOutcomes(celDecision, { trueOutcome: 'approved', falseOutcome: 'rejected' });
    expect(unreachableOutcome(resolved, 'approved')).toBe(false);
    expect(unreachableOutcome(resolved, 'rejected')).toBe(false);
  });

  // The two parameterized built-ins are judged under the AUTHOR'S names rather
  // than the declared defaults. Reading the defaults instead would make this fire on every correctly
  // wired cel-decision in every graph — a failure worse than shipping
  // nothing.
  it("judges cel-decision against the author's own outcome names", () => {
    const resolved = resolveOutcomes(celDecision, { trueOutcome: 'approved', falseOutcome: 'rejected' });
    // The DEFAULTS are the unreachable ones once the author has renamed, and that inversion is the point.
    expect(unreachableOutcome(resolved, 'true')).toBe(true);
    expect(unreachableOutcome(resolved, 'false')).toBe(true);
  });

  it("judges http-request against the author's own outcome names", () => {
    const resolved = resolveOutcomes(httpRequest, { successOutcome: 'ok', failureOutcome: 'broken' });
    expect(unreachableOutcome(resolved, 'ok')).toBe(false);
    expect(unreachableOutcome(resolved, 'broken')).toBe(false);
    expect(unreachableOutcome(resolved, 'failed')).toBe(true);
  });

  // ── The three exemptions ────────────────────────────────────────────────────────────────────────

  /**
   * `GraphRunner` retries `nextEdges` with 'continue' when the produced outcome matched nothing, so a
   * 'continue' edge is reachable from outcomes no node declares.
   */
  it("never flags an edge wired to 'continue', whatever the set says", () => {
    const resolved = resolveOutcomes(celDecision, { trueOutcome: 'approved', falseOutcome: 'rejected' });
    expect(resolved.map(entry => entry.outcome)).not.toContain(FALLBACK_OUTCOME);
    expect(unreachableOutcome(resolved, FALLBACK_OUTCOME)).toBe(false);
  });

  /**
   * `delay` declares 'continue' ALONE, the sharpest form of the case above: every other outcome
   * leaving it is genuinely unreachable and is caught, while 'continue' itself stays exempt.
   */
  it('catches a wrong outcome leaving a delay node while still exempting continue', () => {
    const resolved = resolveOutcomes(delay, {});
    expect(unreachableOutcome(resolved, 'timed-out')).toBe(true);
    expect(unreachableOutcome(resolved, FALLBACK_OUTCOME)).toBe(false);
  });

  /**
   * An empty set means 'this descriptor declares nothing', which is unknown — never 'emits nothing'.
   * Read the other way this would flag EVERY edge leaving every mail, telegram, kafka or amqp node.
   */
  it('never flags anything when the source declares no outcomes', () => {
    const resolved = resolveOutcomes(undeclared, {});
    expect(resolved).toEqual([]);
    expect(unreachableOutcome(resolved, 'delivered')).toBe(false);
    expect(unreachableOutcome(resolved, 'anything-at-all')).toBe(false);
  });

  /**
   * The residual third-party case the restricted `resolveOutcomes` javadoc now names: a behavior that
   * renders its own outcome property. No behavior in this repository does, but one outside it may, and
   * from here the token is the only visible trace. Silence is the correct answer to 'unknowable'.
   */
  it('never flags a node whose parameterized outcome still holds a template token', () => {
    const resolved = resolveOutcomes(celDecision, {
      trueOutcome: '{{payload.status}}', falseOutcome: 'rejected',
    });
    // Not even for the sibling outcome, because one unknowable name makes the SET inexact.
    expect(unreachableOutcome(resolved, 'anything')).toBe(false);
  });

  /**
   * The exemption above is scoped to PARAMETERIZED outcomes. A literal is fixed in the behavior's
   * source and cannot be a template, so braces in one are just an odd name and must not buy silence.
   */
  it('does not extend the template exemption to a literal outcome', () => {
    const braced = {
      behavior: 'odd',
      properties: [],
      outcomes: [{ name: '{{literal}}', fromProperty: '', description: 'Oddly named.' }],
    };
    expect(unreachableOutcome(resolveOutcomes(braced, {}), 'something-else')).toBe(true);
  });

  // ── Shapes the field can actually be in ─────────────────────────────────────────────────────────

  /**
   * `renderEdgeForm`'s submit handler saves `outcome.trim() || DEFAULT_EDGE_OUTCOME`. Judging the
   * untrimmed field would fire on 'approved ' mid-word and then clear itself on save — an error that
   * flaps is read as noise.
   */
  it('compares the value as the form will save it, trimmed', () => {
    const resolved = resolveOutcomes(celDecision, { trueOutcome: 'approved', falseOutcome: 'rejected' });
    expect(unreachableOutcome(resolved, '  approved  ')).toBe(false);
  });

  it('treats a blank field as the default outcome and stays silent', () => {
    const resolved = resolveOutcomes(celDecision, { trueOutcome: 'approved', falseOutcome: 'rejected' });
    expect(unreachableOutcome(resolved, '')).toBe(false);
    expect(unreachableOutcome(resolved, '   ')).toBe(false);
    expect(unreachableOutcome(resolved, null)).toBe(false);
    expect(unreachableOutcome(resolved, undefined)).toBe(false);
  });

  it('is silent rather than throwing when handed no set at all', () => {
    expect(unreachableOutcome(null, 'whatever')).toBe(false);
    expect(unreachableOutcome(undefined, 'whatever')).toBe(false);
  });

  // Case matters to `nextEdges`, which matches with equals, so it matters here.
  it('does not treat a case variant as a match', () => {
    const resolved = resolveOutcomes(celDecision, { trueOutcome: 'approved', falseOutcome: 'rejected' });
    expect(unreachableOutcome(resolved, 'Approved')).toBe(true);
  });
});

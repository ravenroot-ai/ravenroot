import { describe, expect, it } from 'vitest';
import { executionOutcomeMessages } from '../src/execution-outcome-description.js';

describe('execution outcome reconciliation in Runtime activity', () => {
  it('makes a handled failure visible even when the overall traversal completed', () => {
    expect(executionOutcomeMessages({ status: 'COMPLETED', handledFailure: true,
      handledFailureNodes: ['mail-1'] })).toContainEqual({
      title: 'execution outcome', css: 'failed',
      detail: 'COMPLETED with 1 handled node failure(s): mail-1',
    });
  });

  it('makes degraded/defaulted execution visible with the returned node ids', () => {
    expect(executionOutcomeMessages({ status: 'COMPLETED', degraded: true,
      defaultedNodes: ['future-1', 'future-2'] })).toContainEqual({
      title: 'execution outcome', css: 'fallback',
      detail: 'COMPLETED, degraded: 2 node(s) ran as an unresolved default: future-1, future-2',
    });
  });

  // `bypassedNodes` gained a second source — a node the graph's author switched off in the
  // document — while still being a bare `Set<String>` with no cause in it. The old sentence, "N
  // node(s) bypassed by the pass-through run", therefore announced that a REAL run had been a
  // rehearsal whenever it contained one switched-off node. These pin the sentence to what the
  // payload can actually support.
  describe('the bypass summary', () => {
    const bypassLine = outcome => executionOutcomeMessages(outcome)
      .find(message => message.css === 'bypassed').detail;

    it('never attributes a cause the outcome payload does not carry', () => {
      const detail = bypassLine({ status: 'COMPLETED', bypassedNodes: ['switched-off'] });
      // The exact regression: a genuine run that executed everything except one switched-off node.
      expect(detail).not.toContain('pass-through run');
      expect(detail).not.toContain('test');
      expect(detail).not.toContain('rehearsal');
      expect(detail).not.toContain('author');
    });

    it('states what did happen — no behaviour, traversal continued — and names the nodes', () => {
      const detail = bypassLine({ status: 'COMPLETED', bypassedNodes: ['a', 'b'] });
      expect(detail).toContain('2 node(s) bypassed');
      expect(detail).toContain('behaviour not invoked');
      expect(detail).toContain('traversal continued');
      expect(detail).toContain('a, b');
    });

    it('sends the reader to the surface that does hold the cause', () => {
      // Not decoration: without it the line reads as though nothing distinguishes the two causes,
      // when the per-node NODE_BYPASSED entries distinguish them from `publicReason`.
      expect(bypassLine({ status: 'COMPLETED', bypassedNodes: ['a'] }))
        .toContain('NODE_BYPASSED');
    });

    it('is the same sentence whichever cause produced the set, because the set cannot tell them apart', () => {
      // Two outcomes that are byte-identical on the wire and had different causes. If a future change
      // ever makes these differ, it is inventing a distinction from data that does not carry one.
      const authored = bypassLine({ status: 'COMPLETED', bypassedNodes: ['n1'] });
      const commanded = bypassLine({ status: 'COMPLETED', bypassedNodes: ['n1'] });
      expect(authored).toBe(commanded);
    });

    it('stays out of the way when nothing was bypassed', () => {
      expect(executionOutcomeMessages({ status: 'COMPLETED' })
        .some(message => message.css === 'bypassed')).toBe(false);
      expect(executionOutcomeMessages({ status: 'COMPLETED', bypassedNodes: [] })
        .some(message => message.css === 'bypassed')).toBe(false);
    });

    it('reports a bypass beside a degraded run rather than replacing either message', () => {
      // The two answer different questions — "the deployment lacks this behavior" and "this node did
      // not run" — and the contract is that they must not merge.
      const messages = executionOutcomeMessages({
        status: 'COMPLETED', degraded: true, defaultedNodes: ['missing'], bypassedNodes: ['off'],
      });
      expect(messages).toHaveLength(2);
      expect(messages[0].css).toBe('fallback');
      expect(messages[0].detail).toContain('missing');
      expect(messages[1].css).toBe('bypassed');
      expect(messages[1].detail).toContain('off');
    });
  });

  it('does not hide a true boolean when an older peer omits the node list', () => {
    expect(executionOutcomeMessages({ status: 'COMPLETED', handledFailure: true })[0].detail)
      .toContain('handled node failure');
    expect(executionOutcomeMessages({ status: 'COMPLETED', degraded: true })[0].detail)
      .toContain('degraded');
  });
});

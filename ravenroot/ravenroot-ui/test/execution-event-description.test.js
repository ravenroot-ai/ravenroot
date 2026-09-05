import { readFile } from 'node:fs/promises';

import { describe, expect, it } from 'vitest';

import {
  MAX_PUBLIC_DESCRIPTION_UTF8_BYTES,
  PUBLIC_DESCRIPTION_TRUNCATION_MARKER,
  UNKNOWN_EXECUTION_DESCRIPTION,
  normalizePublicDescription,
  publicExecutionDescription,
} from '../src/execution-event-description.js';

describe('public execution descriptions', () => {
  it('uses the structured field and preserves ordinary Unicode text', () => {
    expect(publicExecutionDescription('Task completed 日本語', 'NODE_COMPLETED'))
      .toBe('Task completed 日本語');
  });

  it('never falls back to legacy diagnostic detail', () => {
    const legacy = {
      type: 'NODE_FAILED',
      detail: 'password=hunter2 <img src=x onerror=alert(1)> /home/runner/secret',
    };
    expect(publicExecutionDescription(legacy.description, legacy.type))
      .toBe('Node failed. Protected diagnostics may contain more detail.');
  });

  it('renders unknown legacy event codes with safe useful copy', () => {
    expect(publicExecutionDescription(undefined, 'PASSWORD=hunter2\nUNKNOWN'))
      .toBe(UNKNOWN_EXECUTION_DESCRIPTION);
  });

  it('normalizes controls, lines and formatting characters to one space', () => {
    expect(normalizePublicDescription('\nHello\tworld\u0000\u2028日本語\u2066 safe\r\n'))
      .toBe('Hello world 日本語 safe');
  });

  it('caps encoded bytes without splitting a multi-byte code point', () => {
    const bounded = normalizePublicDescription('🙂'.repeat(200));
    expect(new TextEncoder().encode(bounded).byteLength).toBeLessThanOrEqual(
      MAX_PUBLIC_DESCRIPTION_UTF8_BYTES);
    expect(bounded.endsWith(PUBLIC_DESCRIPTION_TRUNCATION_MARKER)).toBe(true);
    expect(bounded).not.toContain('\ufffd');
  });

  it('turns controls-only content into the fixed fallback', () => {
    expect(normalizePublicDescription('\u0000\n\t\u2066')).toBe(UNKNOWN_EXECUTION_DESCRIPTION);
  });

  // Durable handler-lifecycle types. This table is the fallback for a peer that sent no
  // `description`; the server composes the same sentences from its own source-authored copy. Falling
  // through to UNKNOWN would render a handler event as generic activity in the one view an operator
  // uses to find out why a process has not moved — indistinguishable, there, from a node event.
  describe('durable handler lifecycle types', () => {
    const handlerTypes = [
      ['HANDLER_REGISTERED', 'waiting'],
      ['HANDLER_ESCALATED', 'escalated'],
      ['HANDLER_EXPIRED', 'ended without a trigger'],
      ['HANDLER_DENIED', 'denied'],
      ['HANDLER_RESOLVED', 're-entered'],
    ];

    it.each(handlerTypes)('%s renders authored copy naming what happened, not the unknown fallback', (type, phrase) => {
      const description = publicExecutionDescription(undefined, type);
      expect(description).not.toBe(UNKNOWN_EXECUTION_DESCRIPTION);
      expect(description.toLowerCase()).toContain('handler');
      expect(description).toContain(phrase);
    });

    it('still prefers a description the server composed over this local table', () => {
      expect(publicExecutionDescription('A handler was resolved by finance.', 'HANDLER_RESOLVED'))
        .toBe('A handler was resolved by finance.');
    });
  });

  // A cancelled execution is not a failed one, and this module is where that distinction survives or
  // dies for the activity log. The runtime publishes a terminal event type of its own precisely so
  // the two are never counted together; a mirror that had no entry for it rendered every cancellation
  // as generic activity, which loses the distinction at the last step before a human reads it.
  describe('cancellation as a terminal event of its own', () => {
    const CANCELLED_COPY = 'Execution was cancelled before it produced a result.';

    it('renders authored copy rather than the unknown fallback', () => {
      const description = publicExecutionDescription(undefined, 'EXECUTION_CANCELLED');
      expect(description).not.toBe(UNKNOWN_EXECUTION_DESCRIPTION);
      expect(description).toBe(CANCELLED_COPY);
    });

    it('says cancelled without calling it a failure or pointing at diagnostics', () => {
      const description = publicExecutionDescription(undefined, 'EXECUTION_CANCELLED');
      expect(description.toLowerCase()).toContain('cancelled');
      expect(description.toLowerCase()).not.toContain('failed');
      expect(description.toLowerCase()).not.toContain('diagnostics');
    });

    // The event really does arrive carrying a conforming classifier -- the deepest cause's class
    // name -- so this is the case a `describeWithReason` arm would have changed. The server has no
    // arm for this type and falls through to its type-only copy, so this side must too: an arm here
    // would put a Java type name into a sentence the server never composes.
    it('ignores a conforming classifier, exactly as the server does for this type', () => {
      expect(publicExecutionDescription(undefined, 'EXECUTION_CANCELLED', 'TraversalCancelledException'))
        .toBe(CANCELLED_COPY);
    });

    it('still prefers a description the server composed', () => {
      expect(publicExecutionDescription('Execution was cancelled by finance.', 'EXECUTION_CANCELLED'))
        .toBe('Execution was cancelled by finance.');
    });
  });

  /**
   * The structural guard, rather than another hardcoded list.
   *
   * This table is a positive allow-list, so a member added to the server's enum and not here does
   * not break anything visibly -- it silently degrades to generic activity copy in the one view an
   * operator uses to find out what a run did. That has now happened often enough to be a defect
   * class rather than an oversight, and a test that iterates a list written by hand cannot catch it,
   * because whoever forgets the table forgets the list in the same edit.
   *
   * So the vocabulary is read from the enum itself, the same way `generative-capability.test.js`
   * reads its Java constant. Reading the source rather than importing anything keeps this a
   * source-of-truth check with no build-order coupling.
   */
  it('has copy for every live event type the server declares, read from the enum itself', async () => {
    const java = await readFile(
      '../ravenroot-application-api/src/main/java/ai/ravenroot/api/application/ExecutionEventType.java',
      'utf8');
    const body = java.slice(java.indexOf('public enum ExecutionEventType'));
    const declared = [...new Set(
      body.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '')
        .match(/^\s{4}([A-Z][A-Z0-9_]+)\s*[,;]/gm)
        ?.map(entry => entry.trim().replace(/[,;]$/, '')) ?? [])];

    expect(declared).toContain('EXECUTION_CANCELLED');
    expect(declared.length).toBeGreaterThan(10);
    const missing = declared.filter(
      type => publicExecutionDescription(undefined, type) === UNKNOWN_EXECUTION_DESCRIPTION);
    expect(missing).toEqual([]);
  });
});

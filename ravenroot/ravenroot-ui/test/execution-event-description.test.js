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
});

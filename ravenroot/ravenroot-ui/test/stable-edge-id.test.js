import { describe, expect, it } from 'vitest';

import { createEdge, validateWorkflow } from '../src/graph-document.js';
import {
  STABLE_EDGE_ID_MAX_UTF8_BYTES,
  assertStableEdgeId,
  stableEdgeIdUtf8Bytes,
} from '../src/stable-edge-id.js';

describe('the shared stable edge identity bound', () => {
  const exact = `${'€'.repeat(2_730)}aa`;

  it('measures UTF-8 bytes and preserves the exact identity', () => {
    expect(stableEdgeIdUtf8Bytes(exact)).toBe(STABLE_EDGE_ID_MAX_UTF8_BYTES);
    expect(assertStableEdgeId(exact)).toBe(exact);
    expect(createEdge(exact, 'a', 'b').id).toBe(exact);
    expect(assertStableEdgeId(' edge ')).toBe(' edge ');
    expect(createEdge(' edge ', 'a', 'b').id).toBe(' edge ');
    // Java String#isBlank deliberately differs from ECMAScript trim() on both of these characters.
    expect(assertStableEdgeId('\u00a0')).toBe('\u00a0');
    expect(() => assertStableEdgeId('\u001c')).toThrow(/nonblank/);
  });

  it('rejects one byte over in construction and validation rather than truncating', () => {
    const over = `${exact}x`;
    expect(() => createEdge(over, 'a', 'b')).toThrow(/8193 UTF-8 bytes; maximum is 8192/);
    const violations = validateWorkflow({
      format: 'graphml', nodes: [
        { id: 'a', kind: 'START' }, { id: 'b', kind: 'END' },
      ], edges: [{ id: over, source: 'a', target: 'b', outcome: 'continue' }],
    });
    expect(violations).toContain('Edge id uses 8193 UTF-8 bytes; maximum is 8192');
  });
});

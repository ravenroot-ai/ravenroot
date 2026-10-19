import { isJavaBlank } from './adapter-binding.js';

/**
 * Mirrors `ai.ravenroot.api.application.StableEdgeId.MAX_UTF8_BYTES`.
 *
 * The public SSE frame ceiling is 65,536 bytes. Reserving 16,384 bytes for the complete fixed
 * EDGE_TRAVERSED envelope and allowing the JSON worst case of six ASCII bytes per input byte yields
 * (65,536 - 16,384) / 6 = 8,192. Identity is rejected, never truncated or normalized.
 */
export const STABLE_EDGE_ID_MAX_UTF8_BYTES = 8_192;

function isWellFormedUnicode(value) {
  if (typeof value.isWellFormed === 'function') return value.isWellFormed();
  return !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(^|[^\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value);
}

export function stableEdgeIdUtf8Bytes(value) {
  return new TextEncoder().encode(String(value)).byteLength;
}

export function stableEdgeIdFinding(value) {
  if (typeof value !== 'string' || isJavaBlank(value)) return { kind: 'nonblank', params: {} };
  if (!isWellFormedUnicode(value)) return { kind: 'unicode', params: {} };
  const bytes = stableEdgeIdUtf8Bytes(value);
  return bytes > STABLE_EDGE_ID_MAX_UTF8_BYTES
    ? { kind: 'maximumBytes', params: { bytes, maximum: STABLE_EDGE_ID_MAX_UTF8_BYTES } } : null;
}

export function stableEdgeIdViolation(value) {
  const finding = stableEdgeIdFinding(value);
  if (!finding) return null;
  if (finding.kind === 'nonblank') return 'must be a nonblank string';
  if (finding.kind === 'unicode') return 'must be well-formed Unicode';
  return `uses ${finding.params.bytes} UTF-8 bytes; maximum is ${finding.params.maximum}`;
}

export function assertStableEdgeId(value, context = 'Edge id') {
  const violation = stableEdgeIdViolation(value);
  if (violation) throw new Error(`${context} ${violation}`);
  return value;
}

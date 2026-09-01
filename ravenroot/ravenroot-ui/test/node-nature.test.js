import { describe, expect, it } from 'vitest';

import {
  DEFAULT_NATURE,
  DEFAULT_NATURE_PROPERTY,
  DEFAULT_MAX_CONCURRENCY_PROPERTY,
  effectiveMaxConcurrency,
  effectiveNature,
  natureLabel,
  natureRiskText,
} from '../src/node-nature.js';

// ADR 0024 §2, Inspector behavior: the client-side computation of `declared ?? descriptor
// .defaultNature` from the three fields `/v1/catalog` publishes (`defaultNature`, `allowedNatures`,
// `natureProperty` — see RavenrootServer#nodeTypeJson). There is deliberately no per-node inspection
// route, so this module IS the whole of "what nature does this node effectively have" on the client.

const WORKER_ONLY = { defaultNature: 'WORKER', allowedNatures: ['WORKER'], natureProperty: 'runtime.nature' };
const WORKER_OR_SOURCE = { defaultNature: 'WORKER', allowedNatures: ['WORKER', 'SOURCE'], natureProperty: 'runtime.nature' };
const FULL_VOCABULARY = {
  defaultNature: 'WORKER',
  allowedNatures: ['WORKER', 'TRAVERSAL', 'SOURCE', 'AUTHORITY', 'KEYED'],
  natureProperty: 'runtime.nature',
};

describe('effectiveNature — absence means the descriptor default, exactly like NodeRuntimeNatureProperty#effectiveNature', () => {
  it('a node with no descriptor at all (non-behavior kind, or uncatalogued behavior) is WORKER, never declared', () => {
    expect(effectiveNature(null, undefined)).toEqual({ value: 'WORKER', declared: false });
    expect(effectiveNature(undefined, 'AUTHORITY')).toEqual({ value: 'WORKER', declared: false });
  });

  it('a descriptor with no declared value resolves to its own default, marked inherited', () => {
    expect(effectiveNature(WORKER_OR_SOURCE, undefined)).toEqual({ value: 'WORKER', declared: false });
    expect(effectiveNature(WORKER_OR_SOURCE, null)).toEqual({ value: 'WORKER', declared: false });
    expect(effectiveNature(WORKER_OR_SOURCE, '')).toEqual({ value: 'WORKER', declared: false });
  });

  it('a value the descriptor allows is the effective value, marked declared — declared and inherited are distinguishable, not just two paths to the same shape', () => {
    const resolved = effectiveNature(WORKER_OR_SOURCE, 'SOURCE');
    expect(resolved.value).toBe('SOURCE');
    expect(resolved.declared).toBe(true);
    // Explicitly declaring the SAME value as the default is still a declaration, not silently folded
    // into "inherited" — the two are different statements even when the resulting value coincides
    // (NodeRuntimeNatureProperty's own javadoc draws exactly this line for 's visibleWhen too).
    expect(effectiveNature(WORKER_OR_SOURCE, 'WORKER')).toEqual({ value: 'WORKER', declared: true });
  });

  it('never offers — and never HONOURS — a value outside this descriptor\'s allowlist: this is the client-side half of "your job is not to offer the escalation"', () => {
    // WORKER_ONLY never declared AUTHORITY as allowed; a stray/legacy value must fall back to the
    // default rather than being trusted, exactly as a validated catalog could never actually have
    // served it (NodeRuntimeNatureValidator refuses this server-side before any actor exists).
    expect(effectiveNature(WORKER_ONLY, 'AUTHORITY')).toEqual({ value: 'WORKER', declared: false });
    expect(effectiveNature(WORKER_OR_SOURCE, 'AUTHORITY')).toEqual({ value: 'WORKER', declared: false });
    expect(effectiveNature(WORKER_OR_SOURCE, 'not-a-real-nature')).toEqual({ value: 'WORKER', declared: false });
  });

  it('an empty allowedNatures array (a descriptor that said nothing) still resolves through the default, never throws', () => {
    const bareDescriptor = { defaultNature: 'WORKER', allowedNatures: [], natureProperty: 'runtime.nature' };
    expect(effectiveNature(bareDescriptor, undefined)).toEqual({ value: 'WORKER', declared: false });
    expect(effectiveNature(bareDescriptor, 'SOURCE')).toEqual({ value: 'WORKER', declared: false });
  });
});

describe('effectiveMaxConcurrency — catalog default and trusted ceiling', () => {
  const descriptor = {
    defaultMaxConcurrency: 8,
    maxConcurrencyCeiling: 32,
    maxConcurrencyProperty: 'runtime.maxConcurrency',
  };

  it('distinguishes inherited from an explicit positive declaration', () => {
    expect(effectiveMaxConcurrency(descriptor, undefined))
      .toEqual({ value: 8, ceiling: 32, declared: false, valid: true });
    expect(effectiveMaxConcurrency(descriptor, '1'))
      .toEqual({ value: 1, ceiling: 32, declared: true, valid: true });
  });

  it('does not treat malformed or above-ceiling graph content as authorized', () => {
    expect(effectiveMaxConcurrency(descriptor, '0').valid).toBe(false);
    expect(effectiveMaxConcurrency(descriptor, '33').valid).toBe(false);
    expect(effectiveMaxConcurrency(descriptor, '1.5').valid).toBe(false);
    expect(effectiveMaxConcurrency({}, '1')).toBeNull();
  });
});

describe('natureRiskText — the deploy-refusal help text for AUTHORITY/KEYED', () => {
  it('is non-empty, plain language, and distinct for AUTHORITY and KEYED', () => {
    const authority = natureRiskText('AUTHORITY');
    const keyed = natureRiskText('KEYED');
    expect(authority).not.toBe('');
    expect(keyed).not.toBe('');
    expect(authority).not.toBe(keyed);
    // Plain rather than opaque-number soup: this must not read like empty citations to an author
    // who has no reason to know what those are.
    expect(authority).not.toMatch(/#\d/);
    expect(keyed).not.toMatch(/#\d/);
  });

  it('is empty for every nature deployable today — the warning is scoped to exactly the two refused values', () => {
    expect(natureRiskText('WORKER')).toBe('');
    expect(natureRiskText('TRAVERSAL')).toBe('');
    expect(natureRiskText('SOURCE')).toBe('');
    expect(natureRiskText('')).toBe('');
    expect(natureRiskText(undefined)).toBe('');
  });
});

describe('natureLabel and the published defaults', () => {
  it('is the identity function today — the seam a future label layer drops into without changing callers', () => {
    for (const identifier of ['WORKER', 'TRAVERSAL', 'SOURCE', 'AUTHORITY', 'KEYED']) {
      expect(natureLabel(identifier)).toBe(identifier);
    }
  });

  it('DEFAULT_NATURE and DEFAULT_NATURE_PROPERTY mirror the server constants', () => {
    expect(DEFAULT_NATURE).toBe('WORKER');
    expect(DEFAULT_NATURE_PROPERTY).toBe('runtime.nature');
    expect(DEFAULT_MAX_CONCURRENCY_PROPERTY).toBe('runtime.maxConcurrency');
  });
});

// Exercises the FULL_VOCABULARY fixture explicitly so a reader can see AUTHORITY/KEYED are legitimate
// EFFECTIVE values when a descriptor actually permits them (declarable today, per ADR 0024 — refused
// only at deploy, not at declaration time).
describe('a descriptor that permits the full vocabulary', () => {
  it('accepts TRAVERSAL, AUTHORITY and KEYED as declared, effective values', () => {
    expect(effectiveNature(FULL_VOCABULARY, 'TRAVERSAL')).toEqual({ value: 'TRAVERSAL', declared: true });
    expect(effectiveNature(FULL_VOCABULARY, 'AUTHORITY')).toEqual({ value: 'AUTHORITY', declared: true });
    expect(effectiveNature(FULL_VOCABULARY, 'KEYED')).toEqual({ value: 'KEYED', declared: true });
  });
});

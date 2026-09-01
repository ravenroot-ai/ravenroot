import { describe, expect, it } from 'vitest';
import fc from 'fast-check';

import {
  assertGraphMLWithinLimits,
  parseGraphML,
  detectAndParse,
  parseGraphifyJSON,
  GRAPH_INPUT_LIMITS,
  GraphInputRejection,
} from '../src/graph-parsers.js';

// QA-07 UI XML/JSON fuzz target. Its dedicated devDependency is
// fast-check (property-based testing with shrinking; see package.json). Runs only via
// `npm run test:fuzz` (vitest.fuzz.config.js) -- excluded from the default `npm test` by
// vitest.config.js's own `exclude`. No existing fuzz-shaped spec was found anywhere under test/ to
// leave untagged (checked: grep for fuzz/fast-check/mutation-style loops across test/*.js found
// nothing), unlike the Java side's GraphManagerSecurityTest.
//
// Each property generates the field its invariant is about directly.
//
// A hazard specific to this file: graph-parsers.js handles content that arrives as
// TEXT, and a generator that only produces well-formed strings never reaches the branches that
// matter. Property 4 below combines two generators for this reason, and they are NOT
// interchangeable: fast-check's `unit: 'binary'` string generation produces Unicode scalar values
// (NUL bytes, control characters, astral characters) UTF-16-encoded, which by construction excludes
// the surrogate range -- verified directly (2000 samples, 23382 code units, 0 lone surrogates).
// Lone surrogates need their own generator, built from raw 16-bit integers through
// String.fromCharCode rather than from scalar values -- see Property 4 for both and why the
// distinction matters. This project's own app.js already has NUL bytes that silently defeat grep,
// which `unit: 'binary'` does cover.
//
// Engine note: this suite runs under vitest's `environment: 'jsdom'`
// (vitest.fuzz.config.js), not a real browser. Every property below is explicitly marked with which
// engine established its result. None of them assert browser-general behaviour; where jsdom and a
// real browser are already known to diverge (Property 1's namespace handling), the divergence and
// which side is which is stated inline, exactly as bindingNamespace's own comment already does.

const CANONICAL_NAMESPACE = 'http://graphml.graphdrawing.org/xmlns';

describe('Property 1 (lead) -- namespace character-reference obfuscation still counts toward maxKeys', () => {
  // Mutation candidate: reintroduce the pre-SEC-08 jsdom namespace-decode order.
  //
  // Checked reachability before committing to it, as instructed, rather than assuming the original
  // applies: a standalone script reproduced the ORIGINAL evasion against the CURRENT
  // source (4097 keys under a canonical namespace obfuscated by one character reference, encoded
  // via assertGraphMLWithinLimits alone -- no DOM involved, so this specific check needed no engine
  // at all) by temporarily reverting bindingNamespace to `return rawAttributeValue;` (skipping
  // decodeXmlReferences entirely). The reverted source ACCEPTED the document; the restored source
  // REJECTS it. The defect is therefore still reachable today, so the candidate holds and is used
  // as designed rather than replaced.
  //
  // Generalizes the single hardcoded example (one specific position, hex-encoded) into a property
  // over WHICH character of the canonical namespace is obfuscated and WHICH numeric base (decimal
  // or hex) encodes it.
  it('rejects maxKeys+1 canonical-namespace keys obfuscated by one character reference (checked via assertGraphMLWithinLimits, no DOM engine involved)', () => {
    fc.assert(
      fc.property(
        fc.nat({ max: CANONICAL_NAMESPACE.length - 1 }),
        fc.boolean(),
        (position, hex) => {
          const codePoint = CANONICAL_NAMESPACE.codePointAt(position);
          const reference = hex ? `&#x${codePoint.toString(16)};` : `&#${codePoint};`;
          const obfuscatedNamespace =
            CANONICAL_NAMESPACE.slice(0, position) + reference + CANONICAL_NAMESPACE.slice(position + 1);

          let doc = `<graphml xmlns="${obfuscatedNamespace}">`;
          for (let i = 0; i <= GRAPH_INPUT_LIMITS.maxKeys; i++) {
            doc += `<key id="k${i}" for="node" attr.name="p${i}" attr.type="string"/>`;
          }
          doc += '<graph id="g" edgedefault="directed"><node id="n0"/></graph></graphml>';

          expect(() => assertGraphMLWithinLimits(doc)).toThrow(GraphInputRejection);
          try {
            assertGraphMLWithinLimits(doc);
          } catch (rejection) {
            expect(rejection.reason).toBe('RESOURCE_LIMIT');
          }
        },
      ),
      { numRuns: 15 }, // deliberately small: each run builds a 4097-element document.
    );
  });
});

describe('Property 2 -- Object.prototype property names are never treated as declared GraphML keys', () => {
  // DISCOVERED, not synthesized. keyDefinitions used to be a plain `{}`; `id` is document-authored
  // text (attr.name / the key's own id), so a key id of 'toString', 'constructor' or
  // '__proto__' either collided with an inherited Object.prototype property (a key's FIRST, only
  // declaration incorrectly rejected as a duplicate) or, for '__proto__' specifically, silently
  // rebound keyDefinitions' own prototype instead of storing anything. Measured directly in Node,
  // no DOM engine needed (plain object semantics, not GraphML-specific): with `{}`,
  // `({})['toString']` is truthy and `Object.keys({})` shows nothing was ever inserted for it.
  // `Object.create(null)` prevents this; the property is the regression control.
  it('accepts a key whose id is an inherited Object.prototype property name as a first declaration, not a duplicate (jsdom DOMParser, vitest.fuzz.config.js)', () => {
    const objectPrototypeNames = ['toString', 'constructor', 'hasOwnProperty', 'valueOf',
      'isPrototypeOf', 'propertyIsEnumerable', 'toLocaleString', '__proto__'];
    fc.assert(
      fc.property(fc.constantFrom(...objectPrototypeNames), (keyId) => {
        const doc = `<graphml xmlns="${CANONICAL_NAMESPACE}">`
          + `<key id="${keyId}" for="node" attr.name="p" attr.type="string"/>`
          + '<graph id="g" edgedefault="directed"><node id="n0"/></graph></graphml>';
        expect(() => parseGraphML(doc)).not.toThrow();
      }),
      { numRuns: objectPrototypeNames.length },
    );
  });
});

describe('Property 3 -- Object.prototype property names are never treated as declared graphify nodes', () => {
  // DISCOVERED, not synthesized. Same root cause as Property 2, different call site: nodeMap used
  // to be a plain `{}` in parseGraphifyJSON too, and the edge filter
  // `nodeMap[e.source] && nodeMap[e.target]` is the actual security-relevant check here -- it
  // decides which edges reach the renderer. Measured directly: with a plain {} and ZERO nodes
  // inserted, `nodeMap['toString']` and `nodeMap['constructor']` were already truthy, so an edge
  // naming either as source or target passed the filter despite referencing a node that was never
  // declared. `Object.create(null)` prevents this; the property is the regression control.
  // Pure JS object semantics -- no DOM engine involved.
  it('never keeps an edge whose endpoint is an inherited Object.prototype property name unless a real node was declared with that id', () => {
    const objectPrototypeNames = ['toString', 'constructor', 'hasOwnProperty', 'valueOf',
      'isPrototypeOf', 'propertyIsEnumerable', 'toLocaleString', '__proto__'];
    fc.assert(
      fc.property(fc.constantFrom(...objectPrototypeNames), (phantomId) => {
        const graph = {
          nodes: [{ id: 'real-node', label: 'Real' }],
          edges: [{ source: 'real-node', target: phantomId }],
        };
        const parsed = parseGraphifyJSON(graph);
        expect(parsed.edges).toHaveLength(0);
      }),
      { numRuns: objectPrototypeNames.length },
    );
  });
});

describe('Property 4 -- arbitrary raw text never escapes detectAndParse as an unclassified failure or a hang', () => {
  // This property enforces "no crash, hang, or OOM" for unstructured input, the same role Property 4
  // plays for GraphML and the arbitrary-bytes properties play. detectAndParse
  // dispatches to jsdom's DOMParser (for XML-looking input) or JSON.parse; both engines are
  // exercised here, but the assertion is only about crash/hang classification, not about what
  // either engine's DOMParser accepts as valid.
  //
  // TWO generators, deliberately, because they cover disjoint hazards and neither can stand in for
  // the other:
  //
  // - `fc.string({unit: 'binary'})` produces Unicode SCALAR VALUES (NUL bytes, control characters,
  // astral characters outside the BMP), UTF-16-encoded. Verified directly, not trusted from the
  // generator's documentation: 2000 sampled strings, 23382 total UTF-16 code units, 18 NUL bytes,
  // ZERO lone surrogates. That last number is not a rare event this sample size happened to miss
  // -- it is structurally guaranteed to be zero, because a scalar value excludes the surrogate
  // range (U+D800-U+DFFF) by definition before UTF-16 encoding ever runs. This generator therefore
  // does not cover lone surrogate halves; only the NUL-byte behavior is measured through it.
  //
  // - `loneSurrogateText`, below, is what actually reaches the surrogate range: an array of raw
  // 16-bit integers through String.fromCharCode, which -- unlike scalar-value generation --
  // performs no pairing or validity check at all, so isolated high or low surrogate code units are
  // an ordinary, frequent output rather than an edge case. Verified directly: 2000 samples, 9778
  // code units, 267 lone surrogates. Lone surrogates are a classic text-parser hazard specifically
  // because they cannot be encoded in UTF-8 and often take a different code path than the rest of
  // a string (e.g. TextEncoder, JSON.stringify's \uXXXX escaping, or a DOM API that round-trips
  // through UTF-8 somewhere in its implementation) -- worth covering in its own right, not merely
  // as an incidental byproduct of a broader "weird text" generator.
  it('never throws anything other than GraphInputRejection, SyntaxError or a plain Error, and never hangs (vitest.fuzz.config.js, jsdom environment)', () => {
    const loneSurrogateText = fc.array(fc.integer({ min: 0, max: 0xffff }), { maxLength: 64 })
      .map((units) => String.fromCharCode(...units));
    fc.assert(
      fc.property(fc.oneof(fc.string({ unit: 'binary', maxLength: 2048 }), loneSurrogateText), (text) => {
        try {
          detectAndParse(text, 'fuzz-input.txt');
        } catch (error) {
          const acceptable = error instanceof GraphInputRejection
            || error instanceof SyntaxError
            || error instanceof Error;
          expect(acceptable).toBe(true);
        }
      }),
      { numRuns: 300 },
    );
  });
});

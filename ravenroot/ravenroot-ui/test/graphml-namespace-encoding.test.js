import { describe, expect, it } from 'vitest';

import {
  assertGraphMLWithinLimits,
  GRAPH_INPUT_LIMITS,
} from '../src/graph-parsers.js';

const CANONICAL = 'http://graphml.graphdrawing.org/xmlns';

/**
 * The pre-DOM ceiling must count what the DOM will see.
 *
 * The lexical pre-pass stored namespace declarations as RAW SOURCE TEXT and compared them to the
 * canonical namespace with ===, while DOMParser decodes XML character references before resolving
 * namespaces. Two views of one document. A guard that inspects a different representation than its
 * consumer is not a weaker guard, it is a guard pointed at the wrong object: writing the canonical
 * namespace with a single character reference left the raw text unequal to the constant, so <key>
 * elements were never counted, the maxKeys ceiling never fired, and the document reached DOMParser
 * with every key live and in the canonical namespace.
 *
 * The tests below never hard-code which spellings are "equivalent". Each candidate is handed to
 * jsdom's DOMParser -- this is a vitest suite running under `environment: 'jsdom'`
 * (vitest.config.js), so that is the real consumer *of this file*, but it is NOT the consumer this
 * code ships to. The production consumer is a browser's DOMParser, and it disagrees with jsdom on
 * whitespace: jsdom trims a namespace URI before binding it, real Chromium refuses the whole
 * document as an invalid URI instead. Measured directly (re-measured against jsdom 29.1.1 after the
 * dependency bump that landed on `dev`): of the character-reference and whitespace candidates below,
 * every whitespace-only candidate (trailing/leading space, tab, newline, CR, U+00A0, U+2028, U+3000,
 * and whitespace written as a character reference) diverges between jsdom and Chromium; every
 * character-reference candidate that encodes to the canonical namespace agrees between the two. That
 * divergence is exactly why the "trims the namespace" cases below are commented and asserted as
 * jsdom-scoped rather than as DOM truth, and why e2e/graphml-namespace-encoding.spec.js pins the two
 * production-relevant facts directly in Chromium: the character-reference evasion is real there, and
 * the whitespace form is not reachable there because the browser refuses the document outright.
 *
 * Within jsdom, though, the resulting namespace is still EXTRACTED from the parsed DOM rather than
 * hard-coded, and the guard is required to agree with that verdict. A reject-list of spellings would
 * prove only that the list was copied correctly; deriving the expectation from the consumer is what
 * makes this a test of the property rather than of the fixture.
 */

/** What jsdom's DOMParser resolves this xmlns to. Extracted from the parsed document, never assumed. */
function domNamespaceOf(xmlnsLiteral) {
  const parsed = new DOMParser().parseFromString(
    `<graphml xmlns="${xmlnsLiteral}"><key/></graphml>`,
    'application/xml',
  );
  if (parsed.querySelector('parsererror')) return { error: true, namespace: null, canonicalKeys: 0 };
  return {
    error: false,
    namespace: parsed.documentElement.namespaceURI,
    canonicalKeys: parsed.getElementsByTagNameNS(CANONICAL, 'key').length,
  };
}

/** A document whose key count is one over the ceiling, bound to `xmlnsLiteral` as the default ns. */
function keyBomb(xmlnsLiteral, keys = GRAPH_INPUT_LIMITS.maxKeys + 1) {
  return `<graphml xmlns="${xmlnsLiteral}">${'<key/>'.repeat(keys)}</graphml>`;
}

function guardRejects(document) {
  try {
    assertGraphMLWithinLimits(document);
    return false;
  } catch (error) {
    expect(error.message).toContain('key count exceeds the configured limit');
    return true;
  }
}

// Spellings of the canonical namespace. Whether each one IS canonical (in jsdom, the DOM this suite
// runs under) is decided by jsdom at run time, not by this list -- the list only has to be varied
// enough to cover the encoding forms XML allows: decimal and hex character references, leading
// zeros, and hex digit case.
const CANDIDATES = [
  ['literal', CANONICAL],
  ['decimal char ref', 'http://graphml.graphdrawing.org&#47;xmlns'],
  ['decimal, leading zero', 'http://graphml.graphdrawing.org&#047;xmlns'],
  ['decimal, many leading zeros', 'http://graphml.graphdrawing.org&#00000047;xmlns'],
  ['hex char ref, lowercase', 'http://graphml.graphdrawing.org&#x2f;xmlns'],
  ['hex char ref, uppercase digit', 'http://graphml.graphdrawing.org&#x2F;xmlns'],
  ['hex, leading zero', 'http://graphml.graphdrawing.org&#x02f;xmlns'],
  ['every slash encoded', 'http&#58;&#47;&#47;graphml.graphdrawing.org&#47;xmlns'],
  ['mixed decimal and hex across many characters',
    '&#x68;&#116;&#x74;&#112;&#x3a;&#47;&#x2f;graphml&#46;graphdrawing&#x2e;org&#x2f;xmlns'],
  // Whitespace, which is a SECOND representation difference and not a character-reference one at
  // all. Namespace URIs are trimmed before they are bound in jsdom, so a single trailing space
  // produces the canonical namespace there while leaving the raw attribute text unequal to it.
  //
  // THIS IS A jsdom BEHAVIOUR, NOT A BROWSER ONE. Measured directly in real Chromium (see
  // e2e/graphml-namespace-encoding.spec.js): Chromium does not trim namespace URIs and refuses the
  // whole document as an invalid URI instead, so none of these whitespace spellings ever reach a
  // browser's DOMParser as canonical. It is real within jsdom -- this suite's own DOM -- which is
  // why `.trim()` stays in src/graph-parsers.js as defence-in-depth against a lax consumer, not
  // because it closes a live browser evasion. See bindingNamespace's JSDoc for the full account.
  ['trailing space', `${CANONICAL} `],
  ['trailing newline', `${CANONICAL}\n`],
  ['trailing tab', `${CANONICAL}\t`],
  ['leading space', ` ${CANONICAL}`],
  ['surrounded by whitespace', `  ${CANONICAL}\n\t`],
  ['trailing space written as a character reference', `${CANONICAL}&#32;`],
  ['trailing newline written as a character reference', `${CANONICAL}&#10;`],
  // Not equivalent, and the guard must not start rejecting these either. Over-rejection would be a
  // different defect with the same root cause: a guard that still disagrees with the DOM.
  ['unrelated namespace', 'urn:extension'],
  ['whitespace INSIDE the URI is not trimmed', 'http://graphml.graphdrawing.org/ xmlns'],
  ['double-encoded, decodes to literal text not a slash',
    'http://graphml.graphdrawing.org&#38;#47;xmlns'],
  ['ampersand-escaped', 'http://graphml.graphdrawing.org&amp;#47;xmlns'],
];

describe('SEC-08 pre-DOM key ceiling counts the namespace the DOM will resolve', () => {
  it('reproduces the 4,097-key evasion: the exact measured fixture', () => {
    const evasion = keyBomb('http://graphml.graphdrawing.org&#x2f;xmlns');

    // The fixture is the one that was measured: 24,652 bytes, 4,097 keys, one character reference.
    expect(new TextEncoder().encode(evasion).length).toBe(24_652);
    expect(GRAPH_INPUT_LIMITS.maxKeys + 1).toBe(4_097);

    // The evasion is real, and this half says so independently of the guard: the DOM resolves the
    // encoded spelling to the canonical namespace and every one of the 4,097 keys is live. Extracted
    // from the parsed document -- a count reconstructed from the source string would prove nothing.
    const parsed = new DOMParser().parseFromString(evasion, 'application/xml');
    expect(parsed.querySelector('parsererror')).toBeNull();
    expect(parsed.documentElement.namespaceURI).toBe(CANONICAL);
    expect(parsed.getElementsByTagNameNS(CANONICAL, 'key')).toHaveLength(4_097);

    // Therefore the guard must reject it. Comparing raw attribute text to the canonical constant
    // would count no keys and would never trigger the ceiling.
    expect(() => assertGraphMLWithinLimits(evasion))
      .toThrow('GraphML input rejected: key count exceeds the configured limit');
  });

  it.each(CANDIDATES)('agrees with the DOM on: %s', (_label, xmlnsLiteral) => {
    const dom = domNamespaceOf(xmlnsLiteral);
    expect(dom.error).toBe(false);

    const isCanonical = dom.namespace === CANONICAL;
    // Guards the test itself: for a spelling the DOM calls canonical, the keys must genuinely be in
    // the canonical namespace. Otherwise "the guard rejected it" could be true for a reason that has
    // nothing to do with the evasion this test exists to close.
    expect(dom.canonicalKeys).toBe(isCanonical ? 1 : 0);

    expect(guardRejects(keyBomb(xmlnsLiteral))).toBe(isCanonical);
  });

  it('covers at least one spelling of each encoding form, and both verdicts', () => {
    const verdicts = CANDIDATES.map(([, xmlns]) => domNamespaceOf(xmlns).namespace === CANONICAL);
    // A suite where every candidate landed on the same side would pass without discriminating.
    expect(verdicts.filter(Boolean).length).toBeGreaterThanOrEqual(8);
    expect(verdicts.filter(verdict => !verdict).length).toBeGreaterThanOrEqual(3);
  });

  it('counts keys bound through a prefix as well as through the default namespace', () => {
    const encoded = 'http://graphml.graphdrawing.org&#x2f;xmlns';
    const prefixed = `<graphml xmlns:g="${encoded}">${
      '<g:key/>'.repeat(GRAPH_INPUT_LIMITS.maxKeys + 1)
    }</graphml>`;

    const parsed = new DOMParser().parseFromString(prefixed, 'application/xml');
    expect(parsed.querySelector('parsererror')).toBeNull();
    expect(parsed.getElementsByTagNameNS(CANONICAL, 'key')).toHaveLength(4_097);

    expect(() => assertGraphMLWithinLimits(prefixed))
      .toThrow('GraphML input rejected: key count exceeds the configured limit');
  });

  it('decodes exactly once, because decoding twice is the same defect facing the other way', () => {
    // &#38;; is the character reference for '&' followed by the literal text ';'. The DOM
    // therefore sees "...org&#47;xmlns", which is NOT the canonical namespace. A decoder that looped
    // until stable would resolve it to a slash, call the document canonical and reject it -- once
    // again disagreeing with the DOM, only in the direction that rejects valid documents.
    const doubled = 'http://graphml.graphdrawing.org&#38;#47;xmlns';
    const dom = domNamespaceOf(doubled);

    expect(dom.namespace).toBe('http://graphml.graphdrawing.org&#47;xmlns');
    expect(dom.namespace).not.toBe(CANONICAL);
    expect(guardRejects(keyBomb(doubled))).toBe(false);
  });

  it('trims the namespace the way jsdom binds it, and only at the ends', () => {
    // This is a second vector rather than a variant of the first: no character reference is involved.
    // A namespace URI is trimmed before it is bound in
    // jsdom, so one trailing space is enough to make the raw attribute text unequal to the canonical
    // constant while jsdom binds the canonical namespace and every key stays live.
    //
    // JSDOM-SCOPED, NOT A BROWSER TRUTH. `new DOMParser()` here is jsdom's, because this suite runs
    // under `environment: 'jsdom'` -- the two assertions immediately below describe what THAT parser
    // does, not what ships to users. Real Chromium refuses this exact document outright as an
    // invalid URI (measured; see e2e/graphml-namespace-encoding.spec.js, the pin for this
    // divergence) rather than trimming and binding it, so the padded spelling is not a live evasion
    // in production. assertGraphMLWithinLimits still has to reject it here, though: its own
    // decode-then-trim logic (bindingNamespace in src/graph-parsers.js) is plain JS that runs
    // identically in every environment, and it must keep agreeing with jsdom's DOMParser -- the only
    // DOMParser this test suite can call -- even though jsdom is more lax than the browser this code
    // actually ships to.
    const padded = `<graphml xmlns="${CANONICAL} ">${
      '<key/>'.repeat(GRAPH_INPUT_LIMITS.maxKeys + 1)
    }</graphml>`;

    const parsed = new DOMParser().parseFromString(padded, 'application/xml');
    expect(parsed.documentElement.namespaceURI).toBe(CANONICAL); // jsdom, not Chromium.
    expect(parsed.getElementsByTagNameNS(CANONICAL, 'key')).toHaveLength(4_097); // jsdom, not Chromium.
    expect(() => assertGraphMLWithinLimits(padded))
      .toThrow('GraphML input rejected: key count exceeds the configured limit');

    // Only at the ends. Interior whitespace is preserved by the binding, so it stays a different
    // namespace and its keys must go on being ignored. This part is not whitespace-trim-specific and
    // holds in Chromium too (interior whitespace makes the URI invalid there as well, refused for
    // the same reason, not trimmed either way).
    const interior = `<graphml xmlns="http://graphml.graphdrawing.org/ xmlns">${
      '<key/>'.repeat(GRAPH_INPUT_LIMITS.maxKeys + 1)
    }</graphml>`;
    expect(new DOMParser().parseFromString(interior, 'application/xml')
      .documentElement.namespaceURI).not.toBe(CANONICAL);
    expect(() => assertGraphMLWithinLimits(interior)).not.toThrow();
  });

  it('still ignores keys in a genuinely unrelated namespace', () => {
    const inert = `<graphml xmlns="urn:extension">${
      '<key/>'.repeat(GRAPH_INPUT_LIMITS.maxKeys + 1)
    }</graphml>`;

    expect(() => assertGraphMLWithinLimits(inert)).not.toThrow();
  });
});

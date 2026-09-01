import { describe, expect, it } from 'vitest';

import { adapterIdOf } from '../src/adapter-binding.js';

// ── ADAPTER BLANKNESS MUST MATCH THE SERVER ──────────────────────────────────────────────────
//
// The editor and the server both decide "does this value name an adapter?" and used to disagree.
// The server (`NodePropertyDescriptor#adapterIdOf`) strips leading/trailing code points for which
// `java.lang.Character#isWhitespace` is true. The editor used `String(value).trim() === ''`, which is
// ECMAScript's WhiteSpace/LineTerminator set — a DIFFERENT set. `adapterIdOf` in `../src/adapter-
// binding.js` is a JavaScript port of the Java definition; these tests hold that port to the real JVM
// behaviour rather than to a hand-transcribed list.
//
// The 25 code points below are `java.lang.Character.isWhitespace`'s complete truth table, captured
// directly from the JVM: `java -cp <dir> WsEnum` sweeping every code point 0x0..0x10FFFF (surrogate
// halves excluded, they are not code points) and recording every one for which `isWhitespace` returns
// true. Transcribing this from memory or from a spec summary is exactly the kind of step that hides a
// transcription error, so it was generated, not typed from recollection.
const JAVA_WHITESPACE_CODEPOINTS = new Set([
  0x0009, 0x000a, 0x000b, 0x000c, 0x000d,
  0x001c, 0x001d, 0x001e, 0x001f,
  0x0020, 0x1680,
  0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2008, 0x2009, 0x200a,
  0x2028, 0x2029, 0x205f, 0x3000,
]);

// Direction 1: ECMAScript's `trim()` strips these, Java's `Character.isWhitespace` does not. All
// three space code points are Unicode `Zs`, but Java explicitly excludes non-breaking spaces from
// "whitespace"; U+FEFF is category `Cf` and ECMAScript includes it as WhiteSpace by fiat.
const ECMASCRIPT_ONLY = [0x00a0, 0x2007, 0x202f, 0xfeff];

// Direction 2: Java's `Character.isWhitespace` treats these as whitespace, ECMAScript's `trim()` does
// not strip them. These are the INFORMATION SEPARATOR control characters.
const JAVA_ONLY = [0x001c, 0x001d, 0x001e, 0x001f];

function isSurrogateHalf(codePoint) {
  return codePoint >= 0xd800 && codePoint <= 0xdfff;
}

// All literal code points used in assertions below are built with String.fromCodePoint rather than
// typed as literal characters in this source file, so a non-breaking space or a control character
// can never sit invisibly in the file itself.
const NBSP = String.fromCodePoint(0x00a0);
const TAB = String.fromCodePoint(0x0009);
const LF = String.fromCodePoint(0x000a);
const IDEOGRAPHIC_SPACE = String.fromCodePoint(0x3000);

describe('adapterIdOf mirrors NodePropertyDescriptor#adapterIdOf / Character.isWhitespace exactly', () => {
  it('null and undefined both normalise to the empty id, like the Java overload', () => {
    expect(adapterIdOf(null)).toBe('');
    expect(adapterIdOf(undefined)).toBe('');
  });

  it('classifies every code point in the BMP and astral planes exactly as Character.isWhitespace does', () => {
    // Self-verifying by construction, not by a separate positive control: this ACCUMULATES the set of
    // code points the port calls blank and compares it, element-by-element, against the JVM-captured
    // table. A sweep that silently iterates zero times (or is cut short, or skips a sub-range) produces
    // an `observedBlank` that is empty or short and fails the `toEqual` below on its own — there is no
    // way for a broken loop to make this assertion pass. Comparing only a `mismatches` diff would be
    // vacuous because an empty loop produces an empty diff that passes `toEqual([])`. Mutation-test
    // evidence empties the loop and shows the assertion below going red.
    //
    // `iterations` is a second, independent tripwire — it must equal the code-point count minus the
    // surrogate-half exclusions regardless of how many of those code points are classified blank.
    const observedBlank = [];
    let iterations = 0;
    for (let cp = 0; cp <= 0x10ffff; cp += 1) {
      if (isSurrogateHalf(cp)) continue;
      iterations += 1;
      const char = String.fromCodePoint(cp);
      if (adapterIdOf(char) === '') observedBlank.push(cp);
    }
    expect(observedBlank).toEqual([...JAVA_WHITESPACE_CODEPOINTS].sort((a, b) => a - b));
    expect(iterations).toBe(0x110000 - 0x800); // 0x110000 code points, 0x800 surrogate halves excluded.
  });

  it('sanity-checks the fixtures the sweep above is built from', () => {
    // NOT a proof that the sweep ran — that is established directly by the accumulate-and-compare
    // assertion above, which cannot pass on a broken loop (see its comment). This instead guards the
    // fixture data itself: if `JAVA_WHITESPACE_CODEPOINTS` were ever left empty or truncated while
    // editing this file, the size check below says so immediately, independently of the sweep.
    expect(adapterIdOf(' ')).toBe('');
    expect(JAVA_WHITESPACE_CODEPOINTS.size).toBe(25);
  });

  it('the ECMAScript/Java divergence is exactly these 8 code points, no more and no fewer', () => {
    const jsOnly = [];
    const javaOnly = [];
    for (let cp = 0; cp <= 0x10ffff; cp += 1) {
      if (isSurrogateHalf(cp)) continue;
      const char = String.fromCodePoint(cp);
      const jsTrimBlank = char.trim() === '';
      const javaBlank = JAVA_WHITESPACE_CODEPOINTS.has(cp);
      if (jsTrimBlank && !javaBlank) jsOnly.push(cp);
      if (!jsTrimBlank && javaBlank) javaOnly.push(cp);
    }
    expect(jsOnly).toEqual(ECMASCRIPT_ONLY);
    expect(javaOnly).toEqual(JAVA_ONLY);
  });

  it.each(ECMASCRIPT_ONLY.map(cp => ({ hex: cp.toString(16).toUpperCase(), cp })))(
    'U+$hex: adapterIdOf keeps it, because Character.isWhitespace does not call it whitespace',
    ({ cp }) => {
      const char = String.fromCodePoint(cp);
      expect(adapterIdOf(char)).toBe(char);
      expect(adapterIdOf(char)).not.toBe('');
    },
  );

  it.each(JAVA_ONLY.map(cp => ({ hex: cp.toString(16).toUpperCase(), cp })))(
    'U+$hex: adapterIdOf strips it, because Character.isWhitespace calls it whitespace',
    ({ cp }) => {
      const char = String.fromCodePoint(cp);
      expect(adapterIdOf(char)).toBe('');
    },
  );

  it('strips leading and trailing Java-whitespace but keeps interior content, like String#strip()', () => {
    expect(adapterIdOf('  openai  ')).toBe('openai');
    expect(adapterIdOf('openai')).toBe('openai');
    // NBSP is not Java-whitespace, so it is preserved at the edges rather than stripped.
    expect(adapterIdOf(`${NBSP}openai${NBSP}`)).toBe(`${NBSP}openai${NBSP}`);
  });

  it('a lone non-breaking space does not normalise to blank', () => {
    expect(adapterIdOf(NBSP)).toBe(NBSP);
    expect(adapterIdOf(NBSP)).not.toBe('');
  });

  it('a value made only of Java-whitespace normalises to the empty id regardless of length', () => {
    // TAB, LF, two ASCII spaces, IDEOGRAPHIC SPACE (U+3000) — all Java-whitespace.
    expect(adapterIdOf(`${TAB}${LF}  ${IDEOGRAPHIC_SPACE}`)).toBe('');
  });
});

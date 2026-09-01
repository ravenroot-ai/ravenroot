// Ports `ai.ravenroot.api.catalog.NodePropertyDescriptor#adapterIdOf` (ravenroot-application-api)
// to the editor. Read that method's javadoc first; this file exists only because the editor became a
// THIRD caller of the same "does this value name an adapter?" question that the Java side already
// treats as security-relevant and single-sourced (see also
// `ai.ravenroot.core.runtime.BehaviorPropertySchema#namesNoAdapter`).
//
// The editor used to spell this question as `String(value).trim() === ''`. ECMAScript's
// `trim()` strips Unicode "WhiteSpace"/"LineTerminator", which is NOT the character set Java's
// `Character.isWhitespace` (what `String#strip()` and `String#isBlank()` use) recognises. A property
// value that was only U+00A0 (NO-BREAK SPACE) was therefore "unconfigured" in the editor's amber hint
// and a named, refused adapter on the server — two answers to one question, exactly the shape CORE-07
// closed for the validator/factory pair. The editor must not reopen it with a different pair.
//
// The two character sets were enumerated exhaustively (every code point 0x0..0x10FFFF, surrogates
// excluded) and differ on exactly 8 code points:
// - ECMAScript trim() strips these, Java's Character.isWhitespace does NOT:
// U+00A0 NO-BREAK SPACE, U+2007 FIGURE SPACE, U+202F NARROW NO-BREAK SPACE (all three are
// Unicode `Zs`, but Java explicitly carves non-breaking spaces out of "whitespace"),
// U+FEFF ZERO WIDTH NO-BREAK SPACE / BYTE ORDER MARK (category `Cf`; ECMAScript treats it as
// WhiteSpace by fiat, Java does not).
// - Java's Character.isWhitespace treats these as whitespace, ECMAScript trim() does NOT:
// U+001C..U+001F (the INFORMATION SEPARATOR control characters).
// Every other whitespace-like code point (space, tab, CR/LF, U+1680, the rest of the `Zs` block,
// U+2028/U+2029, U+205F, U+3000, …) is classified the same way by both, which is exactly why the
// divergence went unnoticed: it agrees on every character a user would plausibly type.
//
// Do NOT reimplement this with `.trim()`, `.replace(/\s/g, '')`, a hand-picked Unicode range, or any
// other ad hoc spelling — any respelling that is not verified against the 8 code points above can
// silently reopen this gap. If the Java-side definition of `Character.isWhitespace` ever changes,
// this file's regex must be re-verified against it (walk every code point and diff the two sets).

// Single-character test, built algorithmically from Unicode General Category rather than a hardcoded
// list, so it tracks `Character.isWhitespace`'s own Zs/Zl/Zp-based definition rather than a snapshot
// of it. The three non-breaking exceptions are excluded exactly as Java excludes them; the four
// information-separator controls are included exactly as Java includes them.
const JAVA_WHITESPACE_CHAR = new RegExp(
  '^(?:[\\t\\n\\v\\f\\r\\x1C-\\x1F]|(?![\\u00A0\\u2007\\u202F])[\\p{Zs}\\p{Zl}\\p{Zp}])$',
  'u',
);

function isJavaWhitespaceChar(char) {
  return JAVA_WHITESPACE_CHAR.test(char);
}

// Mirrors `String#strip()`: removes leading and trailing runs of Java-whitespace code points only.
// Iterated by code point (`Array.from`), not UTF-16 code unit, so a surrogate pair at either edge is
// never split — moot for the whitespace set today (none of it is outside the BMP) but correct
// regardless of what sits next to it in the value.
function stripJavaWhitespace(value) {
  const chars = Array.from(value);
  let start = 0;
  let end = chars.length;
  while (start < end && isJavaWhitespaceChar(chars[start])) start += 1;
  while (end > start && isJavaWhitespaceChar(chars[end - 1])) end -= 1;
  return chars.slice(start, end).join('');
}

// Shared by other UI ports of Java String#isBlank. Classification only: callers retain the original
// value and must not use the stripped intermediary as an identity or persisted representation.
export function isJavaBlank(value) {
  return typeof value === 'string' && stripJavaWhitespace(value) === '';
}

// Mirrors `NodePropertyDescriptor#adapterIdOf` byte-for-byte in classification terms: null/undefined
// becomes '', everything else is stringified and stripped of Java-whitespace only. The caller decides
// "names no adapter" the same way the Java side does — by checking the result for emptiness — so
// there is exactly one predicate, ported rather than reinvented, on each side of the language
// boundary.
export function adapterIdOf(rawValue) {
  if (rawValue === null || rawValue === undefined) return '';
  return stripJavaWhitespace(String(rawValue));
}

// "has this property got a declared default?" is a second question this same Java-whitespace
// notion of blank answers -- distinct from "does this value name an adapter?" above, but built on the
// identical primitive, because the two ask the same shape of question: is this string blank once
// Java-whitespace is stripped? Independent answers disagree: `NodeTypeDescriptorValidator` uses
// `String#isBlank()` on the core side, while app.js and graph-editing.js's canvas insertion path
// (`createNodeForInsertion`) could use `=== ''` / `!== ''`. A defaultValue of pure whitespace is
// non-empty under `===`/`!==` but
// blank under `isBlank()`, so a closed-choice property with such a default would read "has a default"
// on the canvas-insertion path while the core validator and the property-editor render both call it
// "not declared" -- and on the canvas path there is no form in between to save it away as blank: the
// whitespace value lands directly in the document and the GraphML it serialises to.
//
// This export is the ONE answer every caller shares. `resolveDescriptorNodeType` follows the same
// rule for node-type resolution: an unimported copy in graph-editing.js would be free to diverge.
// Future callers must import this predicate rather than reimplement it inline.
export function catalogPropertyHasDeclaredDefault(property) {
  return adapterIdOf(property.defaultValue) !== '';
}

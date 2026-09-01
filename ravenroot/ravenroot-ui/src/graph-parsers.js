// The parser's fallback-kind guard used to be a second, hand-written copy of the canonical
// kind list (graph-document.js's NODE_KINDS) that nobody kept in sync -- ERROR was added to the
// canonical list and silently absent here, so an ERROR node degraded to PASSTHROUGH on every parse.
// Reading the one list instead of maintaining two removes the class of bug, not just this instance
// of it. graph-document.js has no imports of its own, so this does not create a cycle.
import { assertStableEdgeId } from './stable-edge-id.js';
import { authoredNodeType, classifyFailureRoutes, kindOwnsNodeType, kindToNodeType, NODE_KINDS }
  from './graph-document.js';

const GRAPHML_NS = 'http://graphml.graphdrawing.org/xmlns';

// Mirrors ravenroot-core's SecureGraphMlParser#INTERPRETED_NAMES (SEC-07-adjacent, not the
// same guard as the reserved `ravenroot.*` property namespace). A foreign-namespace element using
// one of these local names is not inert extension content: `graph`/`node`/`edge`/`key`/`data`
// spoof the vocabulary this parser interprets, and a consumer that resolved by local name alone
// could be misled into reading it as canonical. The core rejects such a document outright at
// ingest rather than trying to interpret it; the UI must refuse it for the same reason at the same
// point, or a file the server would never accept can still be opened, edited and silently
// mishandled here first.
const INTERPRETED_ELEMENT_NAMES = new Set(['graphml', 'graph', 'key', 'node', 'edge', 'data']);
const GRAPHML_SCALAR_TYPES = new Set(['boolean', 'int', 'long', 'float', 'double', 'string']);

// Handle given to an <edge> that the document left unnamed, which GraphML permits. Deliberately the
// same spelling as GraphMlDocument.SYNTHESIZED_EDGE_ID_PREFIX in ravenroot-core so one shared corpus
// fixture exercises the collision skip on both sides. It is an in-memory handle only and is never
// serialized - see serializeGraphML in graph-document.js.
export const SYNTHESIZED_EDGE_ID_PREFIX = 'ravenroot-synthesized-edge-';

// Keep these aligned with the server-side GraphML import defaults. They are
// deliberately generous for normal exported workflows while putting a hard
// ceiling in front of the browser's DOM parser and renderer.
export const GRAPH_INPUT_LIMITS = Object.freeze({
  maxBytes: 10 * 1024 * 1024,
  maxNodes: 10_000,
  maxEdges: 25_000,
  maxProperties: 100_000,
  maxDepth: 64,
  maxStringLength: 1024 * 1024,
  maxKeys: 4_096,
  maxElements: 250_000,
  maxAttributes: 500_000,
  maxNamespaceDeclarations: 10_000,
});

export const GRAPH_INPUT_REJECTION_REASONS = Object.freeze({
  DOCUMENT_TOO_LARGE: 'DOCUMENT_TOO_LARGE',
  RESOURCE_LIMIT: 'RESOURCE_LIMIT',
  UNSAFE_XML: 'UNSAFE_XML',
});

/** Stable rejection type used by loaders and UI error boundaries. */
export class GraphInputRejection extends Error {
  constructor(reason, message) {
    super(`GraphML input rejected: ${message}`);
    this.name = 'GraphInputRejection';
    this.reason = reason;
  }
}

function inputError(message, reason = GRAPH_INPUT_REJECTION_REASONS.RESOURCE_LIMIT) {
  return new GraphInputRejection(reason, message);
}

/** Reject content that cannot safely be buffered before format detection. */
export function assertInputWithinByteLimit(text) {
  const length = new TextEncoder().encode(String(text)).length;
  if (length > GRAPH_INPUT_LIMITS.maxBytes) {
    throw inputError(
      'document exceeds the 10 MiB byte limit',
      GRAPH_INPUT_REJECTION_REASONS.DOCUMENT_TOO_LARGE,
    );
  }
}

/** Guard the FileReader path before it can allocate the file text. */
export function assertLocalFileWithinByteLimit(file) {
  if (!file || !Number.isFinite(file.size)) {
    throw inputError('file size is unavailable');
  }
  if (file.size > GRAPH_INPUT_LIMITS.maxBytes) {
    throw inputError(
      'document exceeds the 10 MiB byte limit',
      GRAPH_INPUT_REJECTION_REASONS.DOCUMENT_TOO_LARGE,
    );
  }
}

async function readResponseTextWithinByteLimit(response) {
  const reader = response.body?.getReader();
  if (!reader) throw new Error('Unable to read response safely');
  const decoder = new TextDecoder();
  let text = '';
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > GRAPH_INPUT_LIMITS.maxBytes) {
        await reader.cancel();
        throw inputError(
          'document exceeds the 10 MiB byte limit',
          GRAPH_INPUT_REJECTION_REASONS.DOCUMENT_TOO_LARGE,
        );
      }
      text += decoder.decode(value, { stream: true });
    }
  } finally {
    reader.releaseLock();
  }
  return text + decoder.decode();
}

/** Guard URL content-length and streamed bodies before parsing or rendering. */
export async function fetchInputTextWithinByteLimit(url, fetchImpl = fetch) {
  const response = await fetchImpl(url);
  if (!response.ok) throw new Error('HTTP ' + response.status);
  const contentLength = Number(response.headers.get('content-length'));
  if (Number.isFinite(contentLength) && contentLength > GRAPH_INPUT_LIMITS.maxBytes) {
    throw inputError(
      'document exceeds the 10 MiB byte limit',
      GRAPH_INPUT_REJECTION_REASONS.DOCUMENT_TOO_LARGE,
    );
  }
  return readResponseTextWithinByteLimit(response);
}

/**
 * The actual browser file-loading orchestration. Dependencies are injected so
 * the pre-read boundary can be exercised without creating a UI graph.
 */
export function loadLocalGraphInput(file, handlers) {
  const { createReader, onStart, parseAndRender, onRejected, onError, onComplete } = handlers;
  try {
    assertLocalFileWithinByteLimit(file);
  } catch (error) {
    onRejected(error);
    return false;
  }
  onStart();
  const reader = createReader();
  reader.onload = event => {
    try {
      parseAndRender(event.target.result);
    } catch (error) {
      onError(error);
    } finally {
      onComplete();
    }
  };
  reader.onerror = () => {
    onError(new Error('Unable to read local graph file'));
    onComplete();
  };
  reader.readAsText(file);
  return true;
}

/** The actual browser URL-loading orchestration, with an injectable fetch. */
export async function loadUrlGraphInput(url, handlers) {
  const { fetchImpl, onStart, parseAndRender, onError, onComplete } = handlers;
  try {
    onStart();
    const text = await fetchInputTextWithinByteLimit(url, fetchImpl);
    await parseAndRender(text);
    return true;
  } catch (error) {
    onError(error);
    return false;
  } finally {
    onComplete();
  }
}

// (SEC-09 parity). Mirrors ravenroot-core's ReservedGraphProperties + the namespace check
// GraphManager#rejectReservedProperties applies at ingest: the whole `ravenroot.` prefix is
// Ravenroot's own operative property state, so graph content claiming it is refused rather than
// preserved. Matching is case-insensitive and whitespace-trimmed for the same reason core's is --
// GraphML attribute names are case-sensitive, so `RavenRoot.Format.Version` is a different string to
// a strict parser while being indistinguishable to a human reading the document or to any consumer
// that lower-cases before comparing.
const RESERVED_PROPERTY_PREFIX = 'ravenroot.';

function isReservedGraphProperty(key) {
  return typeof key === 'string' && key.trim().toLowerCase().startsWith(RESERVED_PROPERTY_PREFIX);
}

// Deliberately mirrors ONLY what GraphManager.rejectReservedProperties checks today: properties
// collected onto node and edge elements. `simpleProperties(graphElement)` is kept as
// `graph.graphProperties` for `hasDeclaredJoinSemantics` and related readers, but a key declared
// `for="graph"` never reaches this guard. A reserved key at graph scope is therefore accepted here
// just as it is accepted core-side (RESERVED_NOT_REFUSED, GraphMlProfileReportTest), exactly
// mirroring core's own guard,
// which walks imported vertices and edges and never the graph element either. Closing that gap only
// in the UI would invert the divergence by refusing documents the runtime still accepts; the UI and
// core must change together when the format-version marker can distinguish the stricter format.
//
// The message intentionally omits the reserved key's exact spelling and the element id, matching the
// core's public sentence (GraphMlRejection.Sentence.RESERVED_PROPERTY / FIX-03): the key is
// attacker-supplied content beyond its fixed prefix, so echoing it back would violate the same
// disclosure boundary FIX-03 enforces on the core side.
function rejectReservedProperties(elementKind, properties) {
  const reserved = Object.keys(properties).find(isReservedGraphProperty);
  if (reserved !== undefined) {
    throw new Error(
      `GraphML ${elementKind} declares a property in Ravenroot's reserved namespace; the '`
      + `${RESERVED_PROPERTY_PREFIX}' namespace is Ravenroot's own operative state and cannot be `
      + 'set by graph content. Properties outside it are preserved untouched and never interpreted',
    );
  }
}

function localName(qualifiedName) {
  const separator = qualifiedName.lastIndexOf(':');
  return separator === -1 ? qualifiedName : qualifiedName.slice(separator + 1);
}

const GRAPHML_NAMESPACE = 'http://graphml.graphdrawing.org/xmlns';

const XML_PREDEFINED_ENTITIES = new Map([
  ['amp', '&'],
  ['lt', '<'],
  ['gt', '>'],
  ['quot', '"'],
  ['apos', "'"],
]);

// Exactly what XML defines as a reference in an attribute value, and nothing more: decimal and
// hexadecimal character references plus the five predefined entities. The 'x' is lowercase in the
// XML production and DOMParser rejects '&#X2f;' as malformed, so accepting it here would make this
// pass disagree with the DOM in the opposite direction. Every other '&...;' sequence -- an unknown
// entity, an unterminated reference, an out-of-range code point -- is a parse error at DOMParser, so
// leaving it as literal text keeps the two views aligned on those too.
//
// Carries the 'g' flag because decodeXmlReferences drives it through String.replace, which resets
// lastIndex on every call and is therefore safe with a shared, module-scoped instance. It would NOT
// be safe passed to .test() or .exec(): those advance and read lastIndex on this same object, so
// interleaved calls (or a second call before the first's matches are exhausted) would silently skip
// or duplicate matches. Reach for a non-global copy, or drop the flag, before using it that way.
const XML_REFERENCE = /&(?:#(\d+)|#x([0-9a-fA-F]+)|(amp|lt|gt|quot|apos));/g;

/**
 * Decode XML references the way DOMParser will, in ONE left-to-right pass.
 *
 * The pre-DOM ceiling compares namespace declarations against GRAPHML_NAMESPACE, and
 * it used to compare the RAW SOURCE TEXT of the attribute while DOMParser compares the DECODED
 * value. Writing the canonical namespace with a single character reference therefore made the two
 * disagree: the raw text was unequal to the constant, no <key> was counted, the maxKeys ceiling
 * never fired, and the document reached DOMParser with every key live in the canonical namespace. A
 * guard that inspects a different representation than its consumer is pointed at the wrong object.
 * The comparison must use what the DOM will see, rather than raising a limit or enumerating alternate
 * spellings.
 *
 * One pass, deliberately. `String.replace` with a global pattern never rescans what it substituted,
 * and that is the required semantics: '&#38;;' is the reference for '&' followed by the literal
 * text ';', so the DOM sees '&#47;' and NOT a slash. A decoder that looped until stable would
 * call that document canonical and reject it -- disagreeing with the DOM again, this time by
 * refusing input the DOM accepts as unrelated.
 */
function decodeXmlReferences(value) {
  if (!value.includes('&')) return value;
  return value.replace(XML_REFERENCE, (whole, decimal, hex, named) => {
    if (named !== undefined) return XML_PREDEFINED_ENTITIES.get(named);
    const codePoint = Number.parseInt(decimal ?? hex, decimal === undefined ? 16 : 10);
    if (!Number.isInteger(codePoint) || codePoint > 0x10ffff) return whole;
    try {
      return String.fromCodePoint(codePoint);
    } catch {
      // Defensive only, and currently unreachable: the range check above already requires
      // 0 <= codePoint <= 0x10FFFF, and String.fromCodePoint does not throw anywhere in that
      // range -- notably NOT for a lone surrogate half. String.fromCodePoint(0xD800) returns the
      // surrogate character rather than refusing it, so this catch is not what stops surrogate
      // halves from reaching the DOM; nothing here needs to, since a lone surrogate in an XML
      // attribute value is a well-formedness error DOMParser rejects on its own. Kept as a guard
      // against a future change to the range check above; if it ever does throw, the literal text
      // is still the safe, DOM-aligned answer.
      return whole;
    }
  });
}

/**
 * The namespace URI a declaration will actually bind to: decoded, then trimmed.
 *
 * Decoding alone is not enough, and the second half is not a character-reference
 * problem at all: `String.prototype.trim` is applied to the decoded value before it is compared to
 * GRAPHML_NAMESPACE, so `xmlns="http://graphml.graphdrawing.org/xmlns "` -- one trailing space, no
 * encoding whatsoever -- is treated as canonical by this pass.
 *
 * MEASURED IN jsdom (the test suite's DOM, jsdom 29.1.1), NOT a live evasion in a real browser.
 * jsdom trims a namespace URI before binding it, so that spelling reaches jsdom's DOMParser as the
 * canonical namespace. Real Chromium does the opposite: it does not trim namespace URIs and refuses
 * the whole document outright, reporting `xmlns: '...xmlns ' is not a valid URI` with zero canonical
 * keys. Measured directly for trailing/leading space, tab, newline, CR, U+00A0, U+2028 and U+3000 --
 * see the e2e pin in e2e/graphml-namespace-encoding.spec.js and the jsdom-scoped cases in
 * test/graphml-namespace-encoding.test.js. So this padded spelling never reached DOMParser as
 * canonical in production; the browser's own parser already refuses it before this file's namespace
 * comparison would matter.
 *
 * The trim is therefore DEFENCE IN DEPTH: it protects against a DOM more lax about namespace URIs
 * than Chromium is -- jsdom today, and any future consumer -- rather than closing a live browser
 * evasion. It can only reclassify documents a real browser already refuses, so it over-rejects in
 * the safe direction and never under-rejects.
 *
 * Decode before trimming, because that is the order jsdom applies them: '&#32;' becomes a space and
 * is then trimmed away, so a value ending in '&#32;' is canonical under jsdom too. Trimming first
 * would miss it.
 */
function bindingNamespace(rawAttributeValue) {
  return decodeXmlReferences(rawAttributeValue).trim();
}

function elementNamespace(qualifiedName, declarations, namespaceScopes) {
  const separator = qualifiedName.indexOf(':');
  const prefix = separator === -1 ? '' : qualifiedName.slice(0, separator);
  if (declarations.has(prefix)) return declarations.get(prefix);
  for (let index = namespaceScopes.length - 1; index >= 0; index--) {
    if (namespaceScopes[index].has(prefix)) return namespaceScopes[index].get(prefix);
  }
  return null;
}

/**
 * Make a bounded lexical pass before DOMParser allocates an XML DOM. This is
 * not an XML parser: DOMParser remains the compatibility authority. It only
 * counts resource-bearing constructs and rejects DTD/entity declarations.
 */
export function assertGraphMLWithinLimits(xmlText) {
  assertInputWithinByteLimit(xmlText);

  const text = String(xmlText);
  const stack = [];
  const namespaceScopes = [];
  const textLengths = [];
  let nodes = 0;
  let edges = 0;
  let properties = 0;
  let keys = 0;
  let elements = 0;
  let attributes = 0;
  let namespaceDeclarations = 0;
  let index = 0;

  const countText = value => {
    const depth = stack.length;
    if (!depth) return;
    textLengths[depth - 1] += value.length;
    if (textLengths[depth - 1] > GRAPH_INPUT_LIMITS.maxStringLength) {
      throw inputError('text value exceeds the string length limit');
    }
  };
  const increment = (kind, current, maximum) => {
    if (current >= maximum) throw inputError(`${kind} count exceeds the configured limit`);
    return current + 1;
  };

  while (index < text.length) {
    const markup = text.indexOf('<', index);
    if (markup === -1) {
      countText(text.slice(index));
      break;
    }
    countText(text.slice(index, markup));

    if (text.startsWith('<!--', markup)) {
      const end = text.indexOf('-->', markup + 4);
      if (end === -1) break; // DOMParser supplies the stable malformed-XML error.
      countText(text.slice(markup + 4, end));
      index = end + 3;
      continue;
    }
    if (text.startsWith('<![CDATA[', markup)) {
      const end = text.indexOf(']]>', markup + 9);
      if (end === -1) break;
      countText(text.slice(markup + 9, end));
      index = end + 3;
      continue;
    }
    if (/^<!\s*(?:DOCTYPE|ENTITY)\b/i.test(text.slice(markup, markup + 32))) {
      throw inputError(
        'DTD and entity declarations are not allowed',
        GRAPH_INPUT_REJECTION_REASONS.UNSAFE_XML,
      );
    }
    if (text.startsWith('<?', markup)) {
      const end = text.indexOf('?>', markup + 2);
      if (end === -1) break;
      if (end - markup - 2 > GRAPH_INPUT_LIMITS.maxStringLength) {
        throw inputError('processing instruction exceeds the string length limit');
      }
      index = end + 2;
      continue;
    }
    if (text.startsWith('<!', markup)) {
      const end = text.indexOf('>', markup + 2);
      if (end === -1) break;
      index = end + 1;
      continue;
    }
    if (text.startsWith('</', markup)) {
      if (stack.length) {
        stack.pop();
        namespaceScopes.pop();
        textLengths.pop();
      }
      const end = text.indexOf('>', markup + 2);
      if (end === -1) break;
      index = end + 1;
      continue;
    }

    // Find the end of a start tag without being fooled by '>' in a quoted
    // attribute. Invalid quotes are left to DOMParser after this cheap pass.
    let end = markup + 1;
    let quote = null;
    for (; end < text.length; end++) {
      const character = text[end];
      if (quote) {
        if (character === quote) quote = null;
      } else if (character === '"' || character === "'") {
        quote = character;
      } else if (character === '>') {
        break;
      }
    }
    if (end === text.length) break;

    const body = text.slice(markup + 1, end);
    const selfClosing = /\/\s*$/.test(body);
    const nameMatch = /^\s*([^\s/>]+)/.exec(body);
    if (!nameMatch) {
      index = end + 1;
      continue;
    }
    const name = nameMatch[1];
    if (name.length > GRAPH_INPUT_LIMITS.maxStringLength) {
      throw inputError('element name exceeds the string length limit');
    }

    elements = increment('element', elements, GRAPH_INPUT_LIMITS.maxElements);

    // Tokenize attributes without relying on a quoted-value regex. Counting
    // every name-like token is intentionally conservative for malformed input:
    // DOMParser remains the syntax authority, but cannot be reached by using
    // invalid/unquoted attributes to evade the pre-DOM aggregate ceiling.
    const declarations = new Map();
    let attributeIndex = nameMatch[0].length;
    while (attributeIndex < body.length) {
      while (/\s/.test(body[attributeIndex])) attributeIndex++;
      if (attributeIndex >= body.length || body[attributeIndex] === '/') break;

      const nameStart = attributeIndex;
      while (attributeIndex < body.length && !/[\s=/>]/.test(body[attributeIndex])) {
        attributeIndex++;
      }
      const attributeName = body.slice(nameStart, attributeIndex);
      if (!attributeName) {
        attributeIndex++;
        continue;
      }
      attributes = increment('attribute', attributes, GRAPH_INPUT_LIMITS.maxAttributes);

      while (/\s/.test(body[attributeIndex])) attributeIndex++;
      let attributeValue = '';
      if (body[attributeIndex] === '=') {
        attributeIndex++;
        while (/\s/.test(body[attributeIndex])) attributeIndex++;
        const attributeQuote = body[attributeIndex];
        if (attributeQuote === '"' || attributeQuote === "'") {
          attributeIndex++;
          const valueStart = attributeIndex;
          while (attributeIndex < body.length && body[attributeIndex] !== attributeQuote) {
            attributeIndex++;
          }
          attributeValue = body.slice(valueStart, attributeIndex);
          if (attributeIndex < body.length) attributeIndex++;
        } else {
          const valueStart = attributeIndex;
          while (attributeIndex < body.length && !/[\s/>]/.test(body[attributeIndex])) {
            attributeIndex++;
          }
          attributeValue = body.slice(valueStart, attributeIndex);
        }
      }

      if (attributeName.length > GRAPH_INPUT_LIMITS.maxStringLength
          || attributeValue.length > GRAPH_INPUT_LIMITS.maxStringLength) {
        throw inputError('attribute value exceeds the string length limit');
      }
      if (attributeName === 'xmlns' || attributeName.startsWith('xmlns:')) {
        namespaceDeclarations = increment(
          'namespace declaration',
          namespaceDeclarations,
          GRAPH_INPUT_LIMITS.maxNamespaceDeclarations,
        );
        const prefix = attributeName === 'xmlns' ? '' : attributeName.slice('xmlns:'.length);
        // Normalised, because this value is about to be compared against GRAPHML_NAMESPACE and the
        // comparison has to be against the namespace the DOM will bind, not the bytes on disk.
        // The length checks above stay on the raw text on purpose: raw is never shorter than
        // decoded, so they can only ever be conservative. See bindingNamespace.
        declarations.set(prefix, bindingNamespace(attributeValue));
      }
    }

    stack.push(name);
    namespaceScopes.push(declarations);
    textLengths.push(0);
    if (stack.length > GRAPH_INPUT_LIMITS.maxDepth) {
      throw inputError('XML depth exceeds the configured limit');
    }
    switch (localName(name)) {
      case 'node': nodes = increment('node', nodes, GRAPH_INPUT_LIMITS.maxNodes); break;
      case 'edge': edges = increment('edge', edges, GRAPH_INPUT_LIMITS.maxEdges); break;
      case 'data': properties = increment('property', properties, GRAPH_INPUT_LIMITS.maxProperties); break;
      case 'key': {
        const namespace = elementNamespace(name, declarations, namespaceScopes.slice(0, -1));
        if (namespace === GRAPHML_NAMESPACE || (namespace === null && !name.includes(':'))) {
          keys = increment('key', keys, GRAPH_INPUT_LIMITS.maxKeys);
        }
        break;
      }
    }
    if (selfClosing) {
      stack.pop();
      namespaceScopes.pop();
      textLengths.pop();
    }
    index = end + 1;
  }
}

export function parseGraphML(xmlText) {
  assertGraphMLWithinLimits(xmlText);
  // Browser DOMParser implementations do not expose secure-processing controls.
  // Reject DTDs before parsing so entity declarations are never resolved.
  //
  // DEFENCE IN DEPTH ONLY. assertGraphMLWithinLimits above is the outer boundary and already
  // rejects DTD and entity declarations, so in practice this branch is unreachable and the
  // observable message is the scanner's ('GraphML input rejected: ...'), not the one thrown here.
  // Careful: if the scanner's DTD detection is ever narrowed, this guard silently becomes the
  // one that fires and flips the observable rejection message back. Keep the two in sync, and
  // update test/graphml-corpus.test.js and test/graph-parsers.test.js together if either changes.
  if (/<!DOCTYPE(?:\s|\[|>)/i.test(xmlText)) {
    throw new Error('Invalid or unsafe GraphML XML');
  }

  const parser = new DOMParser();
  const doc    = parser.parseFromString(xmlText, 'application/xml');

  if (doc.querySelector('parsererror')) throw new Error('XML parse error');
  const root = doc.documentElement;
  if (!root || root.localName !== 'graphml' || root.namespaceURI !== GRAPHML_NS) {
    throw new Error(`GraphML root must be {${GRAPHML_NS}}graphml`);
  }
  for (const element of root.getElementsByTagName('*')) {
    if (element.namespaceURI !== GRAPHML_NS && INTERPRETED_ELEMENT_NAMES.has(element.localName)) {
      throw new Error('GraphML extensions cannot redefine interpreted element names');
    }
  }
  const graphElements = Array.from(root.children)
    .filter(element => element.localName === 'graph' && element.namespaceURI === GRAPHML_NS);
  if (graphElements.length !== 1) {
    throw new Error(`GraphML must contain exactly one top-level graph; found ${graphElements.length}`);
  }
  const graphElement = graphElements[0];
  if (graphElement.getAttribute('edgedefault') !== 'directed') {
    throw new Error(`GraphML graph '${graphElement.id || '<unnamed>'}' must be directed`);
  }
  let graphSeen = false;
  for (const child of root.children) {
    if (child === graphElement) {
      graphSeen = true;
    } else if (graphSeen && child.localName === 'key' && child.namespaceURI === GRAPHML_NS) {
      throw new Error('GraphML key declarations must precede the graph');
    }
  }
  if (doc.getElementsByTagNameNS(GRAPHML_NS, 'graph').length !== 1) {
    throw new Error('Nested GraphML graphs are not supported');
  }
  ['hyperedge', 'port', 'locator'].forEach(localName => {
    if (doc.getElementsByTagNameNS(GRAPHML_NS, localName).length) {
      throw new Error(`GraphML element '${localName}' is not supported`);
    }
  });

  // Object.create(null), not {}: `id` is document-authored text an attacker controls, and a plain
  // object literal inherits Object.prototype, so an id of 'toString', 'constructor' or
  // '__proto__' would either collide with an inherited property (a first, non-duplicate <key
  // id="toString"> incorrectly rejected as a duplicate) or, for '__proto__' specifically, silently
  // rebind this object's own prototype instead of storing an entry at all. Found by fuzzing this
  // exact lookup (QA-07), not synthesized.
  const keyDefinitions = Object.create(null);
  const keyIdByScopeAndName = new Map();
  const effectiveNames = new Map();
  Array.from(root.children)
    .filter(element => element.localName === 'key' && element.namespaceURI === GRAPHML_NS)
    .forEach(keyEl => {
    const id = (keyEl.getAttribute('id') || '').trim();
    const attrName = (keyEl.getAttribute('attr.name') || keyEl.getAttribute('attrname') || '').trim();
    if (!id) throw new Error('GraphML key declaration is missing an id');
    if (keyDefinitions[id]) throw new Error(`Duplicate GraphML key id '${id}'`);
    const type = (keyEl.getAttribute('attr.type') || 'string').trim().toLowerCase();
    if (!GRAPHML_SCALAR_TYPES.has(type)) {
      throw new Error(`GraphML key '${id}' declares unsupported scalar type '${type}'`);
    }
    const scope = (keyEl.getAttribute('for') || 'all').trim();
    // GraphML permits keys for structural element kinds that Ravenroot does not render yet.
    // Their declarations are harmless metadata and commonly occur in exporter-generated files
    // even when no matching element exists (notably `for="port"`). Keep those declarations so
    // the source can round-trip, but do not expose them as node/edge properties below. Actual
    // unsupported structural elements are still rejected above.
    if (!['all', 'node', 'edge', 'graph', 'graphml', 'port', 'hyperedge', 'endpoint'].includes(scope)) {
      throw new Error(`GraphML key '${id}' has unsupported scope '${scope}'`);
    }
    const defaultElements = Array.from(keyEl.children)
      .filter(child => child.localName === 'default' && child.namespaceURI === GRAPHML_NS);
    if (defaultElements.length > 1) {
      throw new Error(`GraphML key '${id}' declares more than one default value`);
    }
    const defaultElement = defaultElements[0];
    const name = attrName || id;
    const definition = {
      id,
      name,
      type,
      scope,
      defaultValue: defaultElement && defaultElement.children.length === 0
        ? defaultElement.textContent
        : null,
    };
    if (definition.defaultValue !== null) {
      validateScalar(definition, definition.defaultValue, 'default');
    } else if (defaultElement && ['kind', 'behavior', 'outcome'].includes(name.toLowerCase())
        && scope !== 'graph') {
      throw new Error(`Complex default for executable GraphML property '${name}' is ambiguous`);
    }
    keyDefinitions[id] = definition;
    const targetScopes = scope === 'all' ? ['node', 'edge'] : [scope];
    targetScopes.forEach(targetScope => {
      if (!['node', 'edge'].includes(targetScope)) return;
      const lookup = `${targetScope}:${name.toLowerCase()}`;
      if (effectiveNames.has(lookup)) {
        throw new Error(`Ambiguous GraphML property name '${name}' for ${targetScope}`);
      }
      effectiveNames.set(lookup, id);
      keyIdByScopeAndName.set(lookup, id);
    });
  });

  for (const data of doc.getElementsByTagNameNS(GRAPHML_NS, 'data')) {
    const owner = data.parentElement;
    const keyId = data.getAttribute('key') || '';
    const definition = keyDefinitions[keyId];
    const isGraphmlResource = owner === root
      && definition && (definition.scope === 'graphml' || definition.scope === 'all');
    if (!isGraphmlResource && (!owner || owner.namespaceURI !== GRAPHML_NS
        || !['graph', 'node', 'edge'].includes(owner.localName))) {
      throw new Error(
        `GraphML data key '${keyId}' must belong directly to a graph, node or edge`,
      );
    }
  }

  // Helper: get first descendant by localName (namespace-safe)
  function byLN(parent, localName) {
    const r = parent.getElementsByTagNameNS('*', localName);
    return r.length ? r[0] : null;
  }

  // Helper: direct <data key="..."> child value
  function dataVal(el, keyId) {
    for (const c of el.children) {
      if (c.localName === 'data' && c.namespaceURI === GRAPHML_NS && c.getAttribute('key') === keyId)
        return c.textContent.trim();
    }
    const definition = keyDefinitions[keyId];
    if (definition && (definition.scope === 'all' || definition.scope === el.localName)
        && definition.defaultValue !== null) {
      return definition.defaultValue.trim();
    }
    return null;
  }

  function dataValByName(el, ...names) {
    for (const name of names.flat()) {
      if (!name) continue;
      const candidate = String(name);
      const scopedKey = keyIdByScopeAndName.get(`${el.localName}:${candidate.toLowerCase()}`);
      const keyId = scopedKey || candidate;
      const value = dataVal(el, keyId);
      if (value !== null && value !== '') return value;
    }
    return null;
  }

  function numericValByName(el, ...names) {
    const raw = dataValByName(el, ...names);
    if (raw === null || raw === '') return null;
    const match = String(raw).match(/-?\d+(?:[\.,]\d+)?/);
    if (!match) return null;
    const parsed = parseFloat(match[0].replace(',', '.'));
    return Number.isFinite(parsed) ? parsed : null;
  }

  function simpleProperties(el) {
    // Object.create(null): `name` is definition.name, itself the document-authored attr.name --
    // same reasoning as keyDefinitions/nodeMap above.
    const properties = Object.create(null);
    const propertyTypes = Object.create(null);
    const seen = new Set();
    for (const child of el.children) {
      if (child.localName !== 'data' || child.namespaceURI !== GRAPHML_NS) continue;
      const keyId = child.getAttribute('key') || '';
      const definition = keyDefinitions[keyId];
      if (!definition) throw new Error(`GraphML data references undeclared key '${keyId}'`);
      if (definition.scope !== 'all' && definition.scope !== el.localName) {
        throw new Error(`GraphML key '${keyId}' cannot be used on ${el.localName} '${el.id}'`);
      }
      if (seen.has(keyId)) throw new Error(`Duplicate GraphML data key '${keyId}' on ${el.localName} '${el.id}'`);
      seen.add(keyId);
      // Complex extension XML remains in sourceXml and is deliberately not flattened.
      if (Array.from(child.children).length) {
        if (['node', 'edge'].includes(el.localName)
            && ['kind', 'behavior', 'outcome'].includes(definition.name.toLowerCase())) {
          throw new Error(`Complex GraphML data key '${keyId}' collides with executable property '${definition.name}'`);
        }
        continue;
      }
      validateScalar(definition, child.textContent, `${el.localName} '${el.id}'`);
      const name = definition.name || keyId;
      if (!name) continue;
      // NOT `.trim()`: this is the generic property bag -- the one that now also
      // carries a `program` node's `source`, which is executable text whose sha256 is compared
      // against what the author actually wrote. `setData` (graph-document.js) writes
      // `String(value)` verbatim with no added padding, so a round trip through THIS app's own
      // save is exact on the write side already; trimming here was pure, one-directional loss on
      // the read side, silently eating a leading/trailing newline or first-line indentation on
      // every reopen. Measured: `"   indented();"` came back as `"indented();"`, and any starter
      // ending in `\n` (every one of them does) lost that newline on the very next reopen.
      // Save -> export -> reimport -> Create then produced a DIFFERENT sha256 from the one the
      // document names, with a 201 and a green pipeline -- the same failure mode as the asynchronous
      // starter overwrite, appearing one step later during the round trip.
      // Named canonical fields (`description`, `classname`, `outcome`, `behavior`, `kind`, ...)
      // are unaffected: they resolve through `dataVal`/`dataValByName` above, an independent DOM
      // read with its own `.trim()`, never through this map. This trim only ever touched the
      // generic UNKNOWN-property bag (`additionalProperties`, and now `source`/`language`), so
      // removing it does not touch anything with its own defined whitespace handling.
      properties[name] = child.textContent;
      propertyTypes[name] = definition.type || 'string';
    }
    return { properties, propertyTypes };
  }

  // Helper: extract direct text-node content (skipping child elements)
  function textOnly(el) {
    if (!el) return '';
    return Array.from(el.childNodes)
      .filter(n => n.nodeType === 3)   // TEXT_NODE
      .map(n => n.nodeValue)
      .join('').trim();
  }

  // ── Parse nodes ──────────────────────────────────────────────
  const nodes = [];
  // Object.create(null): node ids are document-authored text an attacker controls, and edge
  // endpoint validation below (`!nodeMap[source] || !nodeMap[target]`) must never treat an
  // inherited Object.prototype property (toString, constructor, hasOwnProperty, ...) as though a
  // node with that id had been declared. See keyDefinitions above for the same reasoning.
  const nodeMap = Object.create(null);

  const nodeElements = Array.from(graphElement.children)
    .filter(element => element.localName === 'node' && element.namespaceURI === GRAPHML_NS);
  nodeElements.forEach(nodeEl => {
    const id = nodeEl.getAttribute('id');
    if (!id) throw new Error('GraphML node is missing an id');
    if (nodeMap[id]) throw new Error(`Duplicate GraphML node id '${id}'`);

    const simple = simpleProperties(nodeEl);
    rejectReservedProperties('node', simple.properties);
    const canonicalKind = (dataValByName(nodeEl, 'kind') || '').trim().toUpperCase();
    const behavior = (dataValByName(nodeEl, 'behavior') || '').trim();

    const name_d4 = dataValByName(nodeEl, 'name', 'd4');
    const isStart  = canonicalKind === 'START' || dataValByName(nodeEl, 'start', 'd5') === 'true';
    const isEnd    = canonicalKind === 'END' || dataValByName(nodeEl, 'end', 'd6') === 'true';
    // No legacy boolean data key for this one -- ERROR is a new kind, so no document written
    // before this change could have encoded it any other way than the canonical `kind` value.
    const isError  = canonicalKind === 'ERROR';
    const isActor  = dataValByName(nodeEl, 'actor', 'd7') === 'true';
    const classname = (dataValByName(nodeEl, 'classname', 'class', 'd8') || '').trim();
    // Normalized through the canonical vocabulary instead of being upper-cased and compared
    // against a hand-written list below. An unrecognized string comes back as null, which is what
    // routes this node to the legacy heuristics rather than putting a node type nothing can draw on
    // the canvas.
    const classification = authoredNodeType(
      dataValByName(nodeEl, 'classification', 'nodeType', 'd23'));

    // Parse nodegraphics (key d11)
    const d11 = (function() {
      for (const c of nodeEl.children)
        if (c.localName === 'data' && c.getAttribute('key') === 'd11') return c;
      return null;
    })();

    let fillColor = '#FFCC00', shapeType = 'ellipse';
    let ox = 0, oy = 0, ow = 30, oh = 30;
    let positionIsCenter = false;
    let nodeLabelText = '';

    if (d11) {
      const sn   = byLN(d11, 'ShapeNode');
      if (sn) {
        const fill = byLN(sn, 'Fill');
        if (fill) fillColor = fill.getAttribute('color') || '#FFCC00';

        const shp = byLN(sn, 'Shape');
        if (shp) shapeType = shp.getAttribute('type') || 'ellipse';

        const geo = byLN(sn, 'Geometry');
        if (geo) {
          ox = parseFloat(geo.getAttribute('x'))      || 0;
          oy = parseFloat(geo.getAttribute('y'))      || 0;
          ow = parseFloat(geo.getAttribute('width'))  || 30;
          oh = parseFloat(geo.getAttribute('height')) || 30;
        }

        const nl = byLN(sn, 'NodeLabel');
        if (nl) nodeLabelText = textOnly(nl);
      }
    }

    const layoutX = numericValByName(nodeEl, 'layoutX');
    const layoutY = numericValByName(nodeEl, 'layoutY');
    const layoutWidth = numericValByName(nodeEl, 'layoutWidth');
    const layoutHeight = numericValByName(nodeEl, 'layoutHeight');
    if (layoutX !== null && layoutY !== null) {
      ox = layoutX;
      oy = layoutY;
      positionIsCenter = true;
    }
    if (layoutWidth !== null) ow = layoutWidth;
    if (layoutHeight !== null) oh = layoutHeight;

    const name = name_d4 || nodeLabelText || id;

    // Classify node type — classification (d23) is the primary source
    // The amber-diamond error style used to activate on the node's NAME ('Error') rather than
    // its kind, and only here in the parser -- so a freshly created ERROR node showed as a generic
    // actor until it was saved and reloaded. Classifying on `isError` (kind-based, like isStart and
    // isEnd) makes this path agree with direct node creation in graph-document.js's
    // kindToNodeType(), which now also keys off the kind. No node-name special case needed or kept:
    // it was a workaround for ERROR not being a real kind, and that reason is gone.
    //
    // `classification` must be consulted for BEHAVIOR and PASSTHROUGH nodes; placing the two kind
    // branches ahead of it short-circuits every time. Nothing looks wrong while every BEHAVIOR node
    // is an actor anyway; the AI node bundle ships one whose descriptor declares
    // `visualType: 'agent'`, which must survive reopening rather than fall back to Actor.
    //
    // The order below is the round-trip contract, read against what `app.js` writes:
    // - START/END/ERROR are fixed terminals whose visual type is not an author's choice. app.js
    // stamps them from the kind and ignores the Inspector's select, so `kindOwnsNodeType` wins
    // outright here and a contradicting classification is corrected rather than believed.
    // - otherwise the authored classification IS the author's choice, and it wins.
    // - the kind and shape heuristics are what answer for a document carrying no choice at all.
    // They are a FALLBACK, which is the only thing they should ever have been.
    //
    // `effectiveKind` is `canonicalKind` widened by the legacy boolean keys: a pre-canonical document
    // says START with `<data key="d5">true</data>` and carries no `kind` at all, and that document's
    // start node is just as much a fixed terminal as a modern one's.
    const effectiveKind = isStart ? 'START' : isEnd ? 'END' : isError ? 'ERROR' : canonicalKind;

    let nodeType = 'actor';
    if      (kindOwnsNodeType(effectiveKind))  nodeType = kindToNodeType(effectiveKind);
    else if (classification)                   nodeType = classification;
    else if (NODE_KINDS.includes(effectiveKind)) nodeType = kindToNodeType(effectiveKind);
    else if (shapeType === 'rectangle')        nodeType = 'system';

    const description = (dataValByName(nodeEl, 'description', 'd10') || '').trim();
    const instances = numericValByName(nodeEl, 'instances', 'instance', 'count', 'volume', 'size');

    let kind = canonicalKind;
    if (!NODE_KINDS.includes(kind)) {
      kind = isStart ? 'START' : isEnd ? 'END' : behavior ? 'BEHAVIOR' : 'PASSTHROUGH';
    }

    const node = { id, name, isStart, isEnd, isActor, classname,
                   fillColor, shapeType, ox, oy, ow, oh, nodeType, description, instances,
                   kind, behavior, properties: simple.properties, propertyTypes: simple.propertyTypes,
                   _positionIsCenter: positionIsCenter,
                   _legacyKind: canonicalKind === '' && (isStart || isEnd || isActor),
                   _sourceCanonical: {
                     name, kind, behavior, classname, classification: nodeType, description,
                     layoutX: ox, layoutY: oy, layoutWidth: ow, layoutHeight: oh,
                     start: kind === 'START', end: kind === 'END',
                   } };
    nodes.push(node);
    nodeMap[id] = node;
  });

  // ── Parse edges ──────────────────────────────────────────────
  const edges = [];

  const edgeIds = new Set();
  const edgeElements = Array.from(graphElement.children)
    .filter(element => element.localName === 'edge' && element.namespaceURI === GRAPHML_NS);
  // GraphML requires `id` on <node> but leaves it optional on <edge>: graphml-structure.xsd
  // declares `<xs:attribute name="id" type="xs:NMTOKEN" use="required">` inside node.type against
  // `<xs:attribute name="id" type="xs:NMTOKEN" >` inside edge.type, and the DTD spells the same
  // distinction as #REQUIRED against #IMPLIED. Rejecting an unnamed edge refused conforming
  // documents (FIX-01).
  //
  // The viewer still needs one id per edge: it is the Cytoscape element id, the editor's selection
  // handle and how serializeGraphML finds the element again. So unnamed edges get a synthesized
  // one, skipping any candidate an author already used so the two can never collide.
  // serializeGraphML matches those edges by document position and never writes the synthesized id
  // back, so an author who omitted edge ids still gets them omitted on save.
  const declaredEdgeIds = new Set(edgeElements
    .map(element => element.getAttribute('id') || '')
    .filter(declared => declared !== ''));
  let syntheticCandidate = 0;
  const synthesizeEdgeId = () => {
    let candidate;
    do {
      candidate = `${SYNTHESIZED_EDGE_ID_PREFIX}${syntheticCandidate++}`;
    } while (declaredEdgeIds.has(candidate));
    return candidate;
  };
  edgeElements.forEach((edgeEl, sourceEdgeIndex) => {
    const declaredId = edgeEl.getAttribute('id') || '';
    const syntheticId = declaredId === '';
    const id     = syntheticId ? synthesizeEdgeId() : declaredId;
    assertStableEdgeId(id, syntheticId ? 'Synthesized GraphML edge id' : 'GraphML edge id');
    const source = edgeEl.getAttribute('source');
    const target = edgeEl.getAttribute('target');
    if (edgeIds.has(id)) throw new Error(`Duplicate GraphML edge id '${id}'`);
    edgeIds.add(id);
    if ((edgeEl.getAttribute('directed') || '').toLowerCase() === 'false') {
      throw new Error(`GraphML edge '${id}' cannot override directedness`);
    }
    if (!nodeMap[source] || !nodeMap[target]) {
      throw new Error(`GraphML edge '${id}' references an undeclared endpoint: ${source} -> ${target}`);
    }

    const simple = simpleProperties(edgeEl);
    rejectReservedProperties('edge', simple.properties);
    const status   = parseInt(dataValByName(edgeEl, 'status', 'd13') || '0') || 0;
    const parallel = dataValByName(edgeEl, 'parallel', 'd17') === 'true';
    const edgeName = (dataValByName(edgeEl, 'name', 'edgeName', 'd15') || '').trim();

    // Parse edgegraphics (key d22)
    const d22 = (function() {
      for (const c of edgeEl.children)
        if (c.localName === 'data' && c.getAttribute('key') === 'd22') return c;
      return null;
    })();

    let color = '#8b949e', lineWidth = 1.5, label = '', dashed = false;

    if (d22) {
      const ls = byLN(d22, 'LineStyle');
      if (ls) {
        color     = ls.getAttribute('color') || '#8b949e';
        lineWidth = parseFloat(ls.getAttribute('width')) || 1.5;
        dashed    = ls.getAttribute('type') === 'dashed';
      }
      const el = byLN(d22, 'EdgeLabel');
      if (el) label = textOnly(el);
    }

    const outcome = (dataValByName(edgeEl, 'outcome') || label || 'continue').trim() || 'continue';
    const command = (dataValByName(edgeEl, 'command') || '').trim().toLowerCase();
    if (!label) label = outcome;

    // Classify edge type
    const lu = label.toUpperCase();
    let edgeType = 'default';
    if      (lu === 'FAILED'   || status === 1001)          edgeType = 'failed';
    else if (lu === 'COMPLETED'|| status === 1002)          edgeType = 'completed';
    else if (lu === 'CONTINUE' && parallel)                 edgeType = 'continueP';
    else if (lu === 'CONTINUE')                             edgeType = 'continue';
    else if (lu === 'VALIDATE' || status === 100)           edgeType = 'validate';
    else if (lu === 'PING'     || status === 10)            edgeType = 'ping';
    else if (lu.endsWith('_OUTCOME') || (status >= 200 && status < 300)) edgeType = 'outcome';
    else if (lu === 'UNDEFINED')                            edgeType = 'undefined';
    else if (lu.startsWith('CALLBACK_'))                    edgeType = 'callback';

    // The failure-route classification is NOT applied here. It depends on the TARGET
    // NODE'S KIND: an edge that declares no outcome into an `ERROR` node is a failure route,
    // which makes it a whole-graph question rather than a per-element one, and it has to be redone
    // whenever a kind or an endpoint changes rather than only at parse. `classifyFailureRoutes`
    // owns it, and runs over the finished graph below and again on every render.

    const edgeDescription = (dataValByName(edgeEl, 'description', 'd21') || '').trim();
    // Find the traffic field by attr.name (resolved by scope, not by absolute id).
    // 'traffic' takes precedence over 'weight' because some files use d14=1 as a placeholder.
    const trafficWeight = numericValByName(edgeEl,
      'traffic', 'trafficweight', 'throughput', 'load', 'bandwidth',
      'weights', 'weight', 'count');

    edges.push({ id, source, target, status, parallel, edgeName, outcome, command,
           color, lineWidth, label, dashed, edgeType, description: edgeDescription, trafficWeight,
           properties: simple.properties, propertyTypes: simple.propertyTypes,
           _legacyOutcome: !(dataValByName(edgeEl, 'outcome') || '').trim() && Boolean(label),
           _syntheticId: syntheticId, _sourceEdgeIndex: sourceEdgeIndex,
           _sourceCanonical: {
             outcome, command, name: edgeName, status, parallel, trafficWeight, description: edgeDescription,
           } });
  });

  function validateScalar(definition, lexicalValue, owner) {
    const value = lexicalValue.trim();
    let valid = true;
    if (definition.type === 'boolean') valid = /^(true|false)$/i.test(value);
    if (definition.type === 'int') valid = /^[+-]?\d+$/.test(value)
      && Number.isInteger(Number(value)) && Number(value) >= -2147483648 && Number(value) <= 2147483647;
    if (definition.type === 'long') {
      try {
        const parsed = BigInt(value);
        valid = parsed >= -9223372036854775808n && parsed <= 9223372036854775807n;
      } catch {
        valid = false;
      }
    }
    if (definition.type === 'float') {
      valid = value !== '' && Number.isFinite(Number(value))
        && Math.abs(Number(value)) <= 3.4028235e38;
    }
    if (definition.type === 'double') {
      valid = value !== '' && Number.isFinite(Number(value));
    }
    if (!valid) {
      throw new Error(`GraphML key '${definition.id}' on ${owner} expects ${definition.type} but found '${value}'`);
    }
  }

  // Graph-level `<data>` -- currently only `join.semantics` -- was written by
  // serializeGraphML/createWorkflowDocument but never read back by this function, so a document that
  // legitimately carries the marker (round-tripped through this editor, or hand-authored) opened as
  // though it did not: `graph.graphProperties` simply did not exist. Read with the same
  // `simpleProperties` every node and edge already uses, so a graph-level key follows the same
  // validation (declared, unambiguous, scalar-typed) as everything else in this file -- no
  // graph-scope-specific parsing invented here.
  const graphProperties = simpleProperties(graphElement).properties;

  // Classified here, where the nodes an edge's classification depends on finally exist.
  // Done at parse as well as at render so that a caller reading the parsed document directly -- the
  // tests, the execution payload, anything not going through the canvas -- sees the same edge types
  // the canvas will draw, rather than the pre-classification ones. `classifyFailureRoutes` mutates
  // and returns the same object it is given, so `graphProperties` rides through untouched --
  // it must, since this is exactly the field a distracted conflict resolution here would drop.
  return classifyFailureRoutes(
    { nodes, edges, nodeMap, format: 'graphml', sourceXml: xmlText, keyDefinitions, graphProperties });
}

// ═══════════════════════════════════════════════════════════════
// GRAPHIFY JSON PARSER + FORMAT AUTO-DETECTION
// ═══════════════════════════════════════════════════════════════

export const GFY_MAX_WARN = 4000;   // warn + offer sampling above this threshold
export const GFY_SAMPLE   = 3000;   // keep top-N hub nodes when sampling

/** Detect file format from content and dispatch to the right parser. */
export function detectAndParse(text, filename) {
  assertInputWithinByteLimit(text);
  const trimmed = text.trimStart();
  if (trimmed.startsWith('<')) return parseGraphML(text);
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    const obj = JSON.parse(trimmed);
    if (Array.isArray(obj.nodes) && (Array.isArray(obj.edges) || Array.isArray(obj.links)))
      return parseGraphifyJSON(obj);
    throw new Error('Unrecognized JSON: expected Graphify format (nodes + edges/links)');
  }
  throw new Error('Unrecognized format: expected XML/GraphML or Graphify JSON');
}

/**
 * Keep the top-N highest-degree hub nodes, discarding the rest.
 * Used to make oversized graphify graphs renderable in the browser.
 */
export function sampleLargeGraph(gd, maxNodes) {
  if (gd.nodes.length <= maxNodes) return gd;
  const degree = {};
  gd.edges.forEach(e => {
    degree[e.source] = (degree[e.source] || 0) + 1;
    degree[e.target] = (degree[e.target] || 0) + 1;
  });
  const sorted    = [...gd.nodes].sort((a, b) => (degree[b.id] || 0) - (degree[a.id] || 0));
  const kept      = sorted.slice(0, maxNodes);
  const keptIds   = new Set(kept.map(n => n.id));
  const nodeMap   = {};
  kept.forEach(n => { nodeMap[n.id] = n; });
  const keptEdges = gd.edges.filter(e => keptIds.has(e.source) && keptIds.has(e.target));
  return { ...gd, nodes: kept, edges: keptEdges, nodeMap,
           _sampled: true, _originalCount: gd.nodes.length };
}

/**
 * Parse a graphify graph.json file (single-repo OR merged/clustered) into
 * the viewer's internal {nodes, edges, nodeMap} format.
 *
 * Supported graphify variants:
 * - single-repo: { nodes, edges, hyperedges, input_tokens, output_tokens }
 * - merged/NetworkX: { nodes, links, directed, multigraph, graph, hyperedges }
 */
export function parseGraphifyJSON(raw) {
  const g        = (typeof raw === 'string') ? JSON.parse(raw) : raw;
  const rawNodes = Array.isArray(g.nodes) ? g.nodes : [];
  // single-repo graph stores edges as 'edges'; merged NetworkX graph uses 'links'
  const rawEdges = Array.isArray(g.edges) ? g.edges
                 : Array.isArray(g.links)  ? g.links : [];

  // ── Node type classification ──────────────────────────────────
  function classifyNodeType(n) {
    const lbl  = (n.label || n.id || '').toLowerCase();
    const kind = ((n.metadata && n.metadata.kind) || '').toLowerCase();
    // Explicit AST kinds from graphify extractor
    if (kind === 'file' || kind === 'bash_entrypoint') return 'system';
    if (kind === 'bash_function')                       return 'flow';
    if (kind === 'code')                                return 'agent';
    // Extension-based detection (catches Java classes, TS modules, etc.)
    if (/\.(java|ts|tsx|js|jsx|py|sh|xml|yaml|yml|graphql|gql|html|css|scss)$/i.test(lbl))
      return 'system';
    // Pattern-based heuristics on the symbol label
    if (/\(/.test(lbl))                                    return 'flow';     // method/function
    if (/controller/i.test(lbl))                           return 'consumer';
    if (/listener|receiver|subscriber/i.test(lbl))         return 'consumer';
    if (/serviceimpl|facade(?:impl)?|servicebean/i.test(lbl)) return 'agent';
    if (/\bservice\b/i.test(lbl) && !/interface/i.test(lbl)) return 'agent';
    if (/repositoryimpl|daoimpl/i.test(lbl))               return 'terminal';
    if (/\brepository\b|\bdao\b/i.test(lbl))               return 'terminal';
    if (/interface$|\bapi\b/i.test(lbl))                   return 'handler';
    if (/event\b|message\b|\bdto\b|request\b|response\b/i.test(lbl)) return 'flow';
    if (/config(?:uration)?|properties|settings/i.test(lbl)) return 'actor';
    if (/impl\b/i.test(lbl))                               return 'agent';
    return 'actor';
  }

  // ── graphify relation → viewer edge type ─────────────────────
  const RELATION_MAP = {
    calls:        'continue',
    imports:      'ping',
    imports_from: 'ping',
    re_exports:   'ping',
    defines:      'completed',
    inherits:     'callback',
    implements:   'callback',
    contains:     'outcome',
    method:       'validate',
    references:   'default',
  };

  // ── Build nodes ───────────────────────────────────────────────
  // Object.create(null): same reasoning as parseGraphML's nodeMap. The edge filter below
  // (`nodeMap[e.source] && nodeMap[e.target]`) must never treat an inherited Object.prototype
  // property as a declared node -- measured: with a plain {}, nodeMap["toString"] and
  // nodeMap["constructor"] are truthy with zero nodes ever inserted, so an edge naming either as
  // its source or target silently passed this filter and reached the renderer pointing at a node
  // that was never in `nodes`.
  const nodeMap = Object.create(null);
  const nodes   = rawNodes.map(n => {
    const meta = n.metadata || {};
    const obj  = {
      id:           n.id,
      name:         n.label || n.id,
      nodeType:     classifyNodeType(n),
      fillColor:    '#21262d',
      ox: 0, oy: 0, ow: 80, oh: 52,
      isStart: false, isEnd: false, isActor: false,
      classname:    '',
      shapeType:    'roundrectangle',
      description:  n.source_file
        ? n.source_file + (n.source_location ? '  ' + n.source_location : '')
        : '',
      instances:    null,
      kind:         '',
      behavior:     '',
      properties:   { ...meta },
      propertyTypes: Object.fromEntries(Object.keys(meta).map(key => [key, 'string'])),
      // ── graphify-specific extras (passed through to Cytoscape data) ──
      _graphify:    true,
      gfSourceFile: n.source_file    || '',
      gfSourceLoc:  n.source_location|| '',
      gfCommunity:  n.community_name || (n.community != null ? 'Community ' + n.community : ''),
      gfRepo:       n.repo           || '',
      gfLanguage:   meta.language    || '',
      gfKind:       meta.kind        || '',
    };
    nodeMap[n.id] = obj;
    return obj;
  });

  // ── Build edges ───────────────────────────────────────────────
  let edgeCounter = 0;
  const edges = rawEdges
    .filter(e => e.source && e.target && nodeMap[e.source] && nodeMap[e.target])
    .map(e => ({
      id:           'gfe' + (edgeCounter++),
      source:       e.source,
      target:       e.target,
      label:        e.relation || '',
      edgeType:     RELATION_MAP[e.relation] || 'default',
      color:        '#8b949e',
      lineWidth:    1.5,
      status:       0,
      parallel:     false,
      edgeName:     '',
      dashed:       false,
      trafficWeight: e.weight != null ? Number(e.weight) : null,
      description:  e.source_file
        ? e.source_file + (e.source_location ? '  ' + e.source_location : '')
        : '',
      gfRelation:   e.relation   || '',
      gfConfidence: e.confidence || '',
      outcome:      e.relation || 'continue',
      properties:   {},
      propertyTypes: {},
    }));

  return { nodes, edges, nodeMap, format: 'graphify' };
}

// ═══════════════════════════════════════════════════════════════
// BUILD CYTOSCAPE ELEMENTS
// ═══════════════════════════════════════════════════════════════

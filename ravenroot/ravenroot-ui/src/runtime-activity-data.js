export const MAX_RUNTIME_MESSAGE_UTF8_BYTES = 1024;
export const MAX_RUNTIME_OUTPUT_UTF8_BYTES = 16 * 1024;
export const MAX_RUNTIME_OUTPUT_DEPTH = 6;
export const MAX_RUNTIME_COLLECTION_SIZE = 32;
export const MAX_RUNTIME_VALUE_COUNT = 128;
export const MAX_RUNTIME_TEXT_UTF8_BYTES = 1024;
export const MAX_RUNTIME_KEY_UTF8_BYTES = 128;
export const RUNTIME_REDACTION_MARKER = '[ravenroot:redacted:credential]';
export const RUNTIME_TRUNCATION_MARKER = '[ravenroot:truncated]';

const SECRET_KEYS = new Set([
  'authorization', 'proxyauthorization', 'cookie', 'setcookie', 'password', 'passwd', 'secret',
  'token', 'accesstoken', 'refreshtoken', 'idtoken', 'apikey', 'clientsecret', 'privatekey',
  'credential', 'credentials',
]);

function normalize(value) {
  let result = '';
  for (const character of String(value).normalize('NFC')) {
    const codePoint = character.codePointAt(0);
    if ((character.length === 1 && codePoint >= 0xd800 && codePoint <= 0xdfff)
      || (codePoint <= 0x1f && codePoint !== 0x09 && codePoint !== 0x0a) || codePoint === 0x7f) {
      result += ' ';
    } else {
      result += character;
    }
  }
  return result;
}

function redact(value) {
  let changed = false;
  const replace = (input, pattern, replacement) => input.replace(pattern, (...args) => {
    changed = true;
    return typeof replacement === 'function' ? replacement(...args) : replacement;
  });
  let current = value;
  current = replace(current,
    /-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----/g,
    RUNTIME_REDACTION_MARKER);
  current = replace(current,
    /(\b(?:authorization|proxy-authorization)\s*[:=]\s*(?:bearer|basic)\s+)([^\s,;]+)/gi,
    (_all, prefix) => `${prefix}${RUNTIME_REDACTION_MARKER}`);
  current = replace(current, /(\bbearer\s+)([A-Za-z0-9._~+/-]{8,}=*)/gi,
    (_all, prefix) => `${prefix}${RUNTIME_REDACTION_MARKER}`);
  current = replace(current,
    /(["']?\b(?:[a-z0-9]+[-_.])*(?:api[-_]?key|access[-_]?token|refresh[-_]?token|id[-_]?token|password|passwd|client[-_]?secret|private[-_]?key|credential|secret|token)(?![-_.]?(?:ref|reference|count|type)\b)["']?\s*[:=]\s*)("(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|[^\s,;&"'}\]]+)/gi,
    (_all, prefix, assignedValue) => {
      const quote = assignedValue.length >= 2
        && (assignedValue[0] === '"' || assignedValue[0] === "'")
        && assignedValue.at(-1) === assignedValue[0] ? assignedValue[0] : '';
      return `${prefix}${quote}${RUNTIME_REDACTION_MARKER}${quote}`;
    });
  current = replace(current,
    /(\b(?:cookie|set-cookie)\s*[:=]\s*)([a-z0-9_.-]+=[^\s,;]+(?:;\s*[a-z0-9_.-]+=[^\s,;]+)*)/gi,
    (_all, prefix) => `${prefix}${RUNTIME_REDACTION_MARKER}`);
  current = replace(current, /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g,
    RUNTIME_REDACTION_MARKER);
  return { value: current, redacted: changed };
}

function bounded(value, maximum) {
  const encoder = new TextEncoder();
  if (encoder.encode(value).byteLength <= maximum) return { value, truncated: false };
  const budget = maximum - encoder.encode(RUNTIME_TRUNCATION_MARKER).byteLength;
  let result = '';
  let used = 0;
  for (const character of value) {
    const size = encoder.encode(character).byteLength;
    if (used + size > budget) break;
    result += character;
    used += size;
  }
  return { value: result + RUNTIME_TRUNCATION_MARKER, truncated: true };
}

function boundedWithRequiredMarker(value, maximum, requiredMarker) {
  const encoder = new TextEncoder();
  const budget = maximum - encoder.encode(requiredMarker).byteLength
    - encoder.encode(RUNTIME_TRUNCATION_MARKER).byteLength;
  let result = '';
  let used = 0;
  for (const character of value) {
    const size = encoder.encode(character).byteLength;
    if (used + size > budget) break;
    result += character;
    used += size;
  }
  return { value: result + requiredMarker + RUNTIME_TRUNCATION_MARKER, truncated: true };
}

function reboundWithinEncodedBound(value, maximum, requiredMarkers, markersRequiredAfterCut) {
  const encoder = new TextEncoder();
  const complete = JSON.stringify(`${value}${requiredMarkers.join('')}`);
  if (encoder.encode(complete).byteLength <= maximum) {
    return { value: complete, truncated: false };
  }

  const characters = [...value];
  const suffix = [...new Set(markersRequiredAfterCut)].join('');
  let retained = 0;
  let rejected = characters.length;
  while (retained < rejected) {
    const candidateLength = Math.ceil((retained + rejected) / 2);
    const candidate = JSON.stringify(`${characters.slice(0, candidateLength).join('')}${suffix}`);
    if (encoder.encode(candidate).byteLength <= maximum) retained = candidateLength;
    else rejected = candidateLength - 1;
  }
  return {
    value: JSON.stringify(`${characters.slice(0, retained).join('')}${suffix}`),
    truncated: retained < characters.length,
  };
}

function secretKey(key) {
  const normalized = String(key).toLowerCase().replace(/[^a-z0-9]/g, '');
  if (/(?:ref|reference|count|type)$/.test(normalized)) return false;
  return SECRET_KEYS.has(normalized) || [...SECRET_KEYS].some(secret => normalized.endsWith(secret));
}

export function runtimeActivityMessage(value, serverFlags = {}) {
  if (typeof value !== 'string' || !value.trim()) return { value: '', redacted: false, truncated: false };
  const redaction = redact(normalize(value));
  let bound = bounded(redaction.value, MAX_RUNTIME_MESSAGE_UTF8_BYTES);
  const redacted = Boolean(serverFlags.redacted) || redaction.redacted;
  const truncated = Boolean(serverFlags.truncated) || bound.truncated;
  if (redacted && truncated && !bound.value.includes(RUNTIME_REDACTION_MARKER)) {
    bound = boundedWithRequiredMarker(redaction.value, MAX_RUNTIME_MESSAGE_UTF8_BYTES, RUNTIME_REDACTION_MARKER);
  }
  return {
    value: bound.value,
    redacted,
    truncated: Boolean(serverFlags.truncated) || bound.truncated,
  };
}

export function runtimeActivityOutput(value, serverFlags = {}) {
  const state = { values: 0, redacted: false, truncated: false };
  const classified = classifyRuntimeOutput(value, serverFlags);
  state.redacted = classified.redacted;
  const projected = project(classified.value, 1, state);
  let encoded = JSON.stringify(projected);
  const oversized = new TextEncoder().encode(encoded).byteLength > MAX_RUNTIME_OUTPUT_UTF8_BYTES;
  const redacted = Boolean(serverFlags.redacted) || state.redacted;
  let truncated = Boolean(serverFlags.truncated) || state.truncated || oversized;
  const requiredMarkers = [];
  if (redacted && !encoded.includes(RUNTIME_REDACTION_MARKER)) {
    requiredMarkers.push(RUNTIME_REDACTION_MARKER);
  }
  if (truncated && !encoded.includes(RUNTIME_TRUNCATION_MARKER)) {
    requiredMarkers.push(RUNTIME_TRUNCATION_MARKER);
  }
  if (oversized || requiredMarkers.length > 0) {
    const markersRequiredAfterCut = [];
    if (redacted) markersRequiredAfterCut.push(RUNTIME_REDACTION_MARKER);
    markersRequiredAfterCut.push(RUNTIME_TRUNCATION_MARKER);
    const rebound = reboundWithinEncodedBound(
      encoded, MAX_RUNTIME_OUTPUT_UTF8_BYTES, requiredMarkers, markersRequiredAfterCut);
    encoded = rebound.value;
    truncated ||= rebound.truncated;
  }
  let displayValue = structuredDisplay(JSON.stringify(projected), classified.kind);
  if (redacted && !displayValue.includes(RUNTIME_REDACTION_MARKER)) {
    displayValue += RUNTIME_REDACTION_MARKER;
  }
  if (truncated && !displayValue.includes(RUNTIME_TRUNCATION_MARKER)) {
    displayValue += RUNTIME_TRUNCATION_MARKER;
  }
  const displayBound = boundRuntimeDisplay(displayValue, redacted);
  displayValue = displayBound.value;
  truncated ||= displayBound.truncated;
  return {
    value: encoded,
    displayValue,
    redacted,
    truncated,
  };
}

function boundRuntimeDisplay(value, redacted) {
  const bound = bounded(value, MAX_RUNTIME_OUTPUT_UTF8_BYTES);
  if (!bound.truncated || !redacted || bound.value.includes(RUNTIME_REDACTION_MARKER)) return bound;
  return boundedWithRequiredMarker(
    value, MAX_RUNTIME_OUTPUT_UTF8_BYTES, RUNTIME_REDACTION_MARKER);
}

// A string outcome is transport, not a declaration of its content type. Classify only a complete,
// bounded value, exactly once: an object/array JSON document becomes structured data before the
// existing projection applies key redaction and collection/depth limits; JSON scalars and nested
// JSON strings remain authored text. A server-declared truncation is never parsed into a document
// that would look complete in the UI.
function classifyRuntimeOutput(value, serverFlags) {
  if (typeof value !== 'string' || serverFlags.truncated
      || new TextEncoder().encode(value).byteLength > MAX_RUNTIME_OUTPUT_UTF8_BYTES) {
    return { value, kind: containerKind(value), redacted: false };
  }
  const trimmed = value.trim();
  if (!trimmed) return { value, kind: 'text', redacted: false };
  try {
    const parsed = JSON.parse(trimmed);
    const kind = containerKind(parsed);
    if (kind === 'json') return { value: parsed, kind, redacted: false };
  } catch {
    // JSON-looking but incomplete or malformed output remains literal text.
  }
  const xml = parseAndFormatXml(trimmed);
  return xml || { value, kind: 'text', redacted: false };
}

function containerKind(value) {
  return value !== null && typeof value === 'object' ? 'json' : 'text';
}

// Decode the final bounded representation once. This removes display-only JSON quotes/escaping
// from scalar text, while pretty-printing only actual object/array values. In particular, authored
// `\\n` remains two characters and a real line feed remains a line feed.
function structuredDisplay(encoded, preferredKind) {
  const decoded = JSON.parse(encoded);
  if (preferredKind === 'json' && decoded !== null && typeof decoded === 'object') {
    return unfoldJsonStringNewlines(JSON.stringify(decoded, null, 2));
  }
  return typeof decoded === 'string' ? decoded : JSON.stringify(decoded, null, 2);
}

function unfoldJsonStringNewlines(encoded) {
  let displayed = '';
  let inString = false;
  for (let index = 0; index < encoded.length; index += 1) {
    const character = encoded[index];
    if (character === '"') {
      inString = !inString;
      displayed += character;
      continue;
    }
    if (inString && character === '\\' && index + 1 < encoded.length) {
      const escaped = encoded[index + 1];
      displayed += escaped === 'n' ? '\n' : character + escaped;
      index += 1;
      continue;
    }
    displayed += character;
  }
  return displayed;
}

function parseAndFormatXml(value) {
  if (!value.startsWith('<') || /<!DOCTYPE\b/i.test(value)
      || typeof globalThis.DOMParser !== 'function'
      || typeof globalThis.XMLSerializer !== 'function') return null;
  const document_ = new DOMParser().parseFromString(value, 'application/xml');
  const root = document_.documentElement;
  if (!root || isParserError(document_)) return null;

  const state = { redacted: false };
  redactXmlNode(root, state);
  const declaration = value.match(/^\s*(<\?xml\s+[^?]*\?>)/i)?.[1] || '';
  const body = [...document_.childNodes]
    .map(node => node.nodeType === 1 ? formatXmlElement(node, 0, true) : serializeXmlNode(node))
    .filter(Boolean)
    .join('\n');
  return {
    value: declaration && !body.startsWith(declaration) ? `${declaration}\n${body}` : body,
    kind: 'xml',
    redacted: state.redacted,
  };
}

function isParserError(document_) {
  return [...document_.getElementsByTagName('*')].some(element => element.localName === 'parsererror'
    && (element.namespaceURI === 'http://www.mozilla.org/newlayout/xml/parsererror.xml'
      || element.namespaceURI === 'http://www.mozilla.org/newlayout/xml/parsererror.xml/'
      || element.namespaceURI === 'http://www.w3.org/1999/xhtml'));
}

function redactXmlNode(node, state) {
  if (node.nodeType === 1) {
    if (secretKey(node.localName || node.nodeName)) {
      node.replaceChildren(node.ownerDocument.createTextNode(RUNTIME_REDACTION_MARKER));
      state.redacted = true;
      return;
    }
    for (const attribute of [...node.attributes]) {
      if (secretKey(attribute.localName || attribute.name)) {
        attribute.value = RUNTIME_REDACTION_MARKER;
        state.redacted = true;
      } else {
        const redaction = redact(normalize(attribute.value));
        attribute.value = redaction.value;
        state.redacted ||= redaction.redacted;
      }
    }
  }
  if (node.nodeType === 3 || node.nodeType === 4 || node.nodeType === 7 || node.nodeType === 8) {
    const redaction = redact(normalize(node.data));
    node.data = redaction.value;
    state.redacted ||= redaction.redacted;
  }
  for (const child of [...node.childNodes]) redactXmlNode(child, state);
}

// Indentation is display-only and is introduced only where an element has no text/CDATA children.
// A mixed-content subtree is serialized inline, so `Hello <b>world</b>!`, comments and CDATA retain
// their exact reading order and significant character data.
function formatXmlElement(element, depth, allowIndent) {
  const opening = `<${element.nodeName}${[...element.attributes]
    .map(attribute => ` ${attribute.name}="${escapeXmlAttribute(attribute.value)}"`).join('')}`;
  const children = [...element.childNodes];
  if (children.length === 0) return `${opening}/>`;
  const closing = `</${element.nodeName}>`;
  const hasText = children.some(child => child.nodeType === 3 || child.nodeType === 4);
  if (!allowIndent || hasText) {
    return `${opening}>${children.map(child => child.nodeType === 1
      ? formatXmlElement(child, depth + 1, false) : serializeXmlNode(child)).join('')}${closing}`;
  }
  const indent = '  '.repeat(depth);
  const childIndent = '  '.repeat(depth + 1);
  return `${opening}>\n${children.map(child => `${childIndent}${child.nodeType === 1
    ? formatXmlElement(child, depth + 1, true) : serializeXmlNode(child)}`).join('\n')}\n${indent}${closing}`;
}

function serializeXmlNode(node) {
  return new XMLSerializer().serializeToString(node);
}

function escapeXmlAttribute(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('"', '&quot;')
    .replaceAll('\t', '&#x9;')
    .replaceAll('\n', '&#xA;')
    .replaceAll('\r', '&#xD;');
}

function marker(state, reason) {
  state.truncated = true;
  return `[ravenroot:truncated:${reason}]`;
}

function project(value, depth, state) {
  if (depth > MAX_RUNTIME_OUTPUT_DEPTH) return marker(state, 'depth');
  state.values += 1;
  if (state.values > MAX_RUNTIME_VALUE_COUNT) return marker(state, 'value-count');
  if (value === null || typeof value === 'boolean' || typeof value === 'number') return value;
  if (typeof value === 'string') {
    const redaction = redact(normalize(value));
    let bound = bounded(redaction.value, MAX_RUNTIME_TEXT_UTF8_BYTES);
    if (redaction.redacted && bound.truncated && !bound.value.includes(RUNTIME_REDACTION_MARKER)) {
      bound = boundedWithRequiredMarker(redaction.value, MAX_RUNTIME_TEXT_UTF8_BYTES, RUNTIME_REDACTION_MARKER);
    }
    state.redacted ||= redaction.redacted;
    state.truncated ||= bound.truncated;
    return bound.value;
  }
  if (Array.isArray(value)) {
    const retained = value.length > MAX_RUNTIME_COLLECTION_SIZE
      ? MAX_RUNTIME_COLLECTION_SIZE - 1 : value.length;
    const result = value.slice(0, retained).map(entry => project(entry, depth + 1, state));
    if (value.length > MAX_RUNTIME_COLLECTION_SIZE) result.push(marker(state, 'collection'));
    return result;
  }
  if (typeof value === 'object') {
    const keys = Object.keys(value).sort();
    const retained = keys.length > MAX_RUNTIME_COLLECTION_SIZE
      ? MAX_RUNTIME_COLLECTION_SIZE - 1 : keys.length;
    // JSON keys are data, including the three legacy prototype names. A normal object would route
    // `__proto__ = value` through Object.prototype's setter, silently dropping the own key and
    // giving hostile input control of this accumulator's prototype. Null-prototype storage keeps
    // every own key ordinary while preserving the same deterministic insertion/sort order.
    const result = Object.create(null);
    for (const key of keys.slice(0, retained)) {
      if (new TextEncoder().encode(key).byteLength > MAX_RUNTIME_KEY_UTF8_BYTES) {
        result[RUNTIME_TRUNCATION_MARKER] = marker(state, 'key-length');
        break;
      }
      if (secretKey(key)) {
        result[key] = RUNTIME_REDACTION_MARKER;
        state.redacted = true;
      } else {
        result[key] = project(value[key], depth + 1, state);
      }
    }
    if (keys.length > MAX_RUNTIME_COLLECTION_SIZE) result.$ravenrootTruncation = marker(state, 'collection');
    return result;
  }
  return marker(state, 'unsupported-type');
}

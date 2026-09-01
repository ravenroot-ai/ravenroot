import { adapterIdOf, catalogPropertyHasDeclaredDefault } from './adapter-binding.js';
import { isPropertyRequiredNow, isPropertyVisible } from './property-condition.js';

export const MULTI_PROPERTY_STATE = Object.freeze({
  SAME: 'same',
  MIXED: 'mixed',
  ABSENT: 'absent',
});

const SUPPORTED_TYPES = new Set([
  'STRING', 'TEXT', 'BOOLEAN', 'INTEGER', 'DECIMAL', 'URI', 'SECRET_REFERENCE', 'CEL_EXPRESSION',
]);

const URI_UNRESERVED = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';
const URI_SUB_DELIMS = "!$&'()*+,;=";
const URI_PCHAR = URI_UNRESERVED + URI_SUB_DELIMS + ':@';
const URI_PATH_CHARS = URI_PCHAR + '/';
// Java URI follows its RFC grammar here rather than WHATWG URL: square brackets are admitted in
// opaque scheme-specific parts, queries and fragments, but not in a hierarchical path.
const JAVA_URI_URIC = URI_PCHAR + '/?[]';
// java.net.URI accepts isolated UTF-16 surrogate code units in components. Preserve that legacy
// parser behavior here; separators and control characters remain illegal.
const URI_NON_ASCII_FORBIDDEN = /[\p{Z}\p{Cc}]/u;

export function sameSelectedIds(currentIds, requestedIds) {
  const current = new Set(currentIds || []);
  const requested = new Set(requestedIds || []);
  return current.size === requested.size && [...current].every(id => requested.has(id));
}

function normalizedCondition(condition) {
  if (!condition) return null;
  return {
    contract: String(condition.contract || ''),
    property: String(condition.property || ''),
    operator: String(condition.operator || ''),
    values: [...new Set((condition.values || []).map(String))].sort(),
  };
}

function normalizedAllowedValues(values) {
  return [...new Set((values || []).map(String))].sort();
}

// Descriptions and display names are presentation. Everything that changes what a value means,
// whether it may be absent, or when it may be edited is part of compatibility.
export function propertyCompatibilitySignature(property) {
  const hasDefault = catalogPropertyHasDeclaredDefault(property);
  return JSON.stringify({
    name: String(property?.name || ''),
    type: String(property?.type || '').toUpperCase(),
    required: Boolean(property?.required),
    adapterBinding: Boolean(property?.adapterBinding),
    allowedValues: normalizedAllowedValues(property?.allowedValues),
    visibleWhen: normalizedCondition(property?.visibleWhen),
    requiredWhen: normalizedCondition(property?.requiredWhen),
    defaultValue: hasDefault ? String(property.defaultValue) : null,
    hasDefault,
  });
}

function descriptorValues(descriptor, node) {
  return descriptorValuesFromProperties(descriptor, node.properties || {});
}

function descriptorValuesFromProperties(descriptor, properties) {
  return Object.fromEntries((descriptor.properties || []).map(property => [
    property.name,
    Object.hasOwn(properties, property.name)
      ? properties[property.name]
      : (catalogPropertyHasDeclaredDefault(property) ? property.defaultValue : ''),
  ]));
}

function isTrustedEditableProperty(property, descriptor, values) {
  const type = String(property?.type || '').toUpperCase();
  if (!property?.name || !SUPPORTED_TYPES.has(type)) return false;
  if (property.name === descriptor?.natureProperty) return false;
  // The same fail-closed boundary for `execution.bypass`, and the for
  // `runtime.maxConcurrency`. Each property's `validateShape` refuses any descriptor that declares
  // its key, so a catalog reaching here with either is already broken — but a batch editor is exactly
  // where a platform-owned key must not become an editable row, because one edit would write it
  // across every selected node at once, including nodes whose kind the runtime refuses it on.
  if (property.name === descriptor?.bypassProperty) return false;
  if (property.name === descriptor?.maxConcurrencyProperty) return false;
  // The current catalog has no independent mutability field: every published behavior property is
  // editable. These guards make a future explicit read-only descriptor fail closed.
  if (property.readOnly === true || property.mutable === false) return false;
  return isPropertyVisible(property, values);
}

export function commonMultiSelectionProperties(nodes, catalog) {
  if (!Array.isArray(nodes) || nodes.length < 2) return [];
  const byBehavior = new Map((catalog || []).map(descriptor => [descriptor.behavior, descriptor]));
  const contexts = nodes.map(node => {
    const descriptor = byBehavior.get(node.behavior);
    return descriptor ? { node, descriptor, values: descriptorValues(descriptor, node) } : null;
  });
  // Unknown/custom behavior has no trusted schema. Guessing from coincidentally equal GraphML keys
  // would turn unvalidated data into a batch-edit contract.
  if (contexts.some(context => context === null)) return [];

  const first = contexts[0];
  return (first.descriptor.properties || []).flatMap(property => {
    if (!isTrustedEditableProperty(property, first.descriptor, first.values)) return [];
    const signature = propertyCompatibilitySignature(property);
    const compatible = contexts.every(context => {
      const candidate = (context.descriptor.properties || [])
        .find(other => other.name === property.name);
      return candidate
        && propertyCompatibilitySignature(candidate) === signature
        && isTrustedEditableProperty(candidate, context.descriptor, context.values);
    });
    if (!compatible) return [];
    return [{
      ...property,
      type: String(property.type).toUpperCase(),
      allowedValues: normalizedAllowedValues(property.allowedValues),
      state: multiPropertyState(nodes, property),
    }];
  });
}

export function multiPropertyState(nodes, property) {
  const declarations = nodes.map(node => Object.hasOwn(node.properties || {}, property.name));
  if (declarations.every(declared => !declared)) {
    return { kind: MULTI_PROPERTY_STATE.ABSENT, value: '' };
  }
  const values = nodes.map((node, index) => declarations[index] ? String(node.properties[property.name]) : null);
  if (declarations.every(Boolean) && values.every(value => value === values[0])) {
    // A secret reference is sensitive configuration metadata. Its raw value never leaves the model
    // through the inspector projection, even when every selected node uses the same reference.
    return {
      kind: MULTI_PROPERTY_STATE.SAME,
      value: property.type === 'SECRET_REFERENCE' ? '' : values[0],
    };
  }
  return { kind: MULTI_PROPERTY_STATE.MIXED, value: '' };
}

export function propertyStateLabel(property) {
  const state = property.state?.kind;
  if (property.type === 'SECRET_REFERENCE') {
    if (state === MULTI_PROPERTY_STATE.SAME) return 'Configured on every selected node';
    if (state === MULTI_PROPERTY_STATE.MIXED) return 'Configuration differs across selected nodes';
    return 'Not configured on any selected node';
  }
  if (state === MULTI_PROPERTY_STATE.SAME) return 'Same value on every selected node';
  if (state === MULTI_PROPERTY_STATE.MIXED) return 'Mixed values';
  return 'Not declared on any selected node';
}

export function validateMultiPropertyValue(property, rawValue) {
  const value = String(rawValue ?? '');
  if (value === '') return '';
  if (property.allowedValues?.length && !property.allowedValues.includes(value)) {
    return `${property.displayName || property.name} must be one of its catalog choices`;
  }
  switch (property.type) {
    case 'BOOLEAN':
      return value === 'true' || value === 'false'
        ? '' : `${property.displayName || property.name} must be true or false`;
    case 'INTEGER':
      if (!/^[+-]?\d+$/.test(value)) return `${property.displayName || property.name} must be an integer`;
      try {
        const parsed = BigInt(value);
        return parsed >= -9223372036854775808n && parsed <= 9223372036854775807n
          ? '' : `${property.displayName || property.name} must fit a 64-bit integer`;
      } catch {
        return `${property.displayName || property.name} must be an integer`;
      }
    case 'DECIMAL':
      return /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)
        ? '' : `${property.displayName || property.name} must be a decimal number`;
    case 'URI': {
      return javaUriSyntaxIsAbsolute(value)
        ? '' : `${property.displayName || property.name} must be an absolute URI`;
    }
    case 'SECRET_REFERENCE':
      return /[\s\u0000-\u001f\u007f]/u.test(value)
        ? `${property.displayName || property.name} must be a secret reference without whitespace`
        : '';
    default:
      return '';
  }
}

// A structural port of the Java 21 `new URI(value)` syntax decision used by
// `BehaviorPropertySchema`, followed by `URI#isAbsolute`. This deliberately is not `new URL`: WHATWG
// both repairs invalid URI text (raw brackets/whitespace/escapes) and rejects valid generic URI
// forms such as an IPv6 scope identifier. The parser only judges syntax; it never decodes,
// normalizes, resolves or rewrites the raw value that GraphML stores. In particular, Java's IPv6
// scope syntax is raw `%` followed by ASCII letters, digits, `_` or `.`, not RFC 6874 decoding.
export function javaUriSyntaxIsAbsolute(value) {
  if (adapterIdOf(value) !== value) return false; // Java value.equals(value.strip())
  const match = /^([A-Za-z][A-Za-z0-9+.-]*):(.*)$/su.exec(value);
  if (!match) return false;
  const remainder = match[2];
  const fragmentAt = remainder.indexOf('#');
  const schemeSpecific = fragmentAt < 0 ? remainder : remainder.slice(0, fragmentAt);
  const fragment = fragmentAt < 0 ? null : remainder.slice(fragmentAt + 1);
  // Java rejects `scheme:` and `scheme:#fragment`: an absolute URI still needs a non-empty raw
  // scheme-specific part. An empty fragment after a non-empty part is valid.
  if (schemeSpecific === '' || (fragment !== null && !uriComponentIsValid(fragment, JAVA_URI_URIC))) {
    return false;
  }

  if (!schemeSpecific.startsWith('/')) {
    return uriComponentIsValid(schemeSpecific, JAVA_URI_URIC);
  }

  const queryAt = schemeSpecific.indexOf('?');
  const hierarchy = queryAt < 0 ? schemeSpecific : schemeSpecific.slice(0, queryAt);
  const query = queryAt < 0 ? null : schemeSpecific.slice(queryAt + 1);
  if (query !== null && !uriComponentIsValid(query, JAVA_URI_URIC)) return false;

  if (!hierarchy.startsWith('//')) return uriComponentIsValid(hierarchy, URI_PATH_CHARS);
  const authorityAndPath = hierarchy.slice(2);
  const pathAt = authorityAndPath.indexOf('/');
  const authority = pathAt < 0 ? authorityAndPath : authorityAndPath.slice(0, pathAt);
  const path = pathAt < 0 ? '' : authorityAndPath.slice(pathAt);
  // `scheme:///path` is the usual empty-authority form. Java also accepts an empty path after `//`
  // when a query or fragment delimiter follows; only bare `scheme://` reports "Expected authority".
  if (authority === '' && path === '' && query === null && fragment === null) return false;
  return javaUriAuthorityIsValid(authority) && uriComponentIsValid(path, URI_PATH_CHARS);
}

function uriComponentIsValid(value, asciiAllowed) {
  for (let index = 0; index < value.length;) {
    if (value[index] === '%') {
      if (!/^[0-9a-f]{2}$/iu.test(value.slice(index + 1, index + 3))) return false;
      index += 3;
      continue;
    }
    const codePoint = value.codePointAt(index);
    const character = String.fromCodePoint(codePoint);
    if (codePoint <= 0x7f) {
      if (!asciiAllowed.includes(character)) return false;
    } else if (URI_NON_ASCII_FORBIDDEN.test(character)) {
      return false;
    }
    index += character.length;
  }
  return true;
}

function javaUriAuthorityIsValid(authority) {
  if (authority === '') return true;
  const hostStart = authority.lastIndexOf('@') + 1;
  const userInfo = authority.slice(0, hostStart ? hostStart - 1 : 0);
  const hostAndPort = authority.slice(hostStart);
  if (hostStart && !uriComponentIsValid(userInfo, URI_PCHAR)) return false;

  if (!hostAndPort.startsWith('[')) {
    return !hostAndPort.includes(']') && !hostAndPort.includes('[')
      && uriComponentIsValid(hostAndPort, URI_PCHAR);
  }
  const close = hostAndPort.indexOf(']');
  if (close < 0 || !ipv6LiteralIsValid(hostAndPort.slice(1, close))) return false;
  const port = hostAndPort.slice(close + 1);
  return port === '' || /^:\d*$/u.test(port);
}

function ipv6LiteralIsValid(literal) {
  let address = literal;
  const zoneAt = literal.indexOf('%');
  if (zoneAt >= 0) {
    address = literal.slice(0, zoneAt);
    const scope = literal.slice(zoneAt + 1);
    if (!/^[A-Za-z0-9_.]+$/u.test(scope)) return false;
  }
  const compressionAt = address.indexOf('::');
  if (compressionAt !== address.lastIndexOf('::')) return false;
  const compressed = compressionAt >= 0;
  const left = (compressed ? address.slice(0, compressionAt) : address)
    .split(':').filter(Boolean);
  const right = (compressed ? address.slice(compressionAt + 2) : '')
    .split(':').filter(Boolean);
  const groups = [...left, ...right];
  if (!groups.length) return compressed;
  let count = groups.length;
  for (const [index, group] of groups.entries()) {
    if (group.includes('.')) {
      if (index !== groups.length - 1 || !ipv4AddressIsValid(group)) return false;
      count += 1; // an IPv4 tail occupies two h16 groups rather than one
    } else if (!/^[0-9a-f]{1,4}$/iu.test(group)) {
      return false;
    }
  }
  return compressed ? count < 8 : count === 8;
}

function ipv4AddressIsValid(value) {
  const parts = value.split('.');
  return parts.length === 4 && parts.every(part => /^\d{1,3}$/u.test(part) && Number(part) <= 255);
}

function graphMlType(type) {
  if (type === 'BOOLEAN') return 'boolean';
  if (type === 'INTEGER') return 'long';
  if (type === 'DECIMAL') return 'double';
  return 'string';
}

// Produces the dedicated command's map patches without ever whole-replacing a node map. Callers
// pass only controls the user actually touched; an untouched mixed/absent field is not an edit.
export function planMultiPropertyUpdate(nodes, commonProperties, changes, catalog = []) {
  const commonByName = new Map(commonProperties.map(property => [property.name, property]));
  const touched = (changes || []).filter(change => change?.touched);
  if (!touched.length) return { entries: [], errors: [] };

  const errors = [];
  for (const change of touched) {
    const property = commonByName.get(change.name);
    if (!property) {
      errors.push(`Property ${change.name || '(unnamed)'} is not common to every selected node`);
      continue;
    }
    const validation = validateMultiPropertyValue(property, change.value);
    if (validation) errors.push(validation);
  }
  if (errors.length) return { entries: [], errors };

  const descriptorByBehavior = new Map((catalog || [])
    .map(descriptor => [descriptor.behavior, descriptor]));
  const entries = nodes.map(node => {
    const descriptor = descriptorByBehavior.get(node.behavior);
    if (!descriptor) {
      errors.push(`No trusted catalog schema is available for ${node.name || node.id}`);
      return null;
    }
    const nextProperties = { ...(node.properties || {}) };
    for (const change of touched) {
      const declared = (descriptor.properties || []).find(property => property.name === change.name);
      const common = commonByName.get(change.name);
      if (!declared || propertyCompatibilitySignature(declared) !== propertyCompatibilitySignature(common)) {
        errors.push(`Property ${change.name} is no longer compatible for ${node.name || node.id}`);
        continue;
      }
      if (change.value === '') delete nextProperties[change.name];
      else nextProperties[change.name] = String(change.value);
    }

    // Conditions are evaluated against the values the descriptor makes effective after the entire
    // touched patch, including catalog defaults for absent sibling properties. Requiredness is a
    // property of that resulting node, not only of a field that happened to be cleared.
    const effectiveValues = descriptorValuesFromProperties(descriptor, nextProperties);
    const unconfigured = (descriptor.properties || []).some(property => property.adapterBinding
      && adapterIdOf(nextProperties[property.name]) === '');
    for (const declared of descriptor.properties || []) {
      const visible = isPropertyVisible(declared, effectiveValues);
      const raw = Object.hasOwn(nextProperties, declared.name)
        ? String(nextProperties[declared.name] ?? '') : '';
      const blank = adapterIdOf(raw) === '';
      if (!visible && touched.some(change => change.name === declared.name)) {
        errors.push(`${declared.displayName || declared.name} is not visible for ${node.name || node.id}`);
      }
      if (blank) {
        if (isPropertyRequiredNow(declared, effectiveValues) && !unconfigured) {
          errors.push(`${declared.displayName || declared.name} is required for ${node.name || node.id}`);
        }
        continue;
      }
      // Hidden values remain part of GraphML and the JVM validates them too. Visibility decides
      // presentation, never whether a declared value may bypass type/enum validation.
      const validation = validateMultiPropertyValue({
        ...declared,
        type: String(declared.type || '').toUpperCase(),
        allowedValues: normalizedAllowedValues(declared.allowedValues),
      }, raw);
      if (validation) errors.push(`${validation} for ${node.name || node.id}`);
    }

    if (errors.length) return null;

    const properties = { set: {}, unset: [] };
    const propertyTypes = { set: {}, unset: [] };
    for (const change of touched) {
      const property = commonByName.get(change.name);
      if (change.value === '') {
        properties.unset.push(change.name);
        propertyTypes.unset.push(change.name);
      } else {
        properties.set[change.name] = String(change.value);
        propertyTypes.set[change.name] = graphMlType(property.type);
      }
    }
    return { id: node.id, properties, propertyTypes };
  });

  return errors.length ? { entries: [], errors } : { entries, errors: [] };
}

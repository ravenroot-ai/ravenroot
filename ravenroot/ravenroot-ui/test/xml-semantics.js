// Shared definition of "the same GraphML document" for tests.
//
// Not byte equality: attribute order and insignificant whitespace between elements carry no XML
// meaning, and neither the DOM nor XMLSerializer promises to preserve them. What must not change is
// the expanded-name tree, the attribute set with values, comments, and the exact text inside
// <data>/<default>, where whitespace IS the payload.
//
// Extracted verbatim from graphml-corpus.test.js so the command-model round-trip suite
// (UI-01) asserts invariance against the same definition the GraphML corpus already uses,
// rather than inventing a weaker one.

export function xmlSemantics(xml) {
  const document = new DOMParser().parseFromString(xml, 'application/xml');
  return nodeSemantics(document.documentElement);
}

export function nodeSemantics(node, preserveWhitespace = false) {
  if (!node) return '';
  if (node.nodeType === Node.TEXT_NODE || node.nodeType === Node.CDATA_SECTION_NODE) {
    return preserveWhitespace || node.nodeValue.trim() ? `#text(${node.nodeValue})` : '';
  }
  if (node.nodeType === Node.COMMENT_NODE) return `#comment(${node.nodeValue})`;
  if (node.nodeType !== Node.ELEMENT_NODE) return '';

  const expandedName = `{${node.namespaceURI || ''}}${node.localName}`;
  const attributes = Array.from(node.attributes)
    .filter(attribute => attribute.namespaceURI !== 'http://www.w3.org/2000/xmlns/')
    .map(attribute => `[{${attribute.namespaceURI || ''}}${attribute.localName}=${attribute.value}]`)
    .sort()
    .join('');
  const insideData = preserveWhitespace || (node.namespaceURI
    === 'http://graphml.graphdrawing.org/xmlns' && ['data', 'default'].includes(node.localName));
  const children = Array.from(node.childNodes)
    .map(child => nodeSemantics(child, insideData))
    .join('');
  return `<${expandedName}${attributes}>${children}</${expandedName}>`;
}

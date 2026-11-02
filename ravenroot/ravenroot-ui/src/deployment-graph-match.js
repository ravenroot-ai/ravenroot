// Presentation changes do not change which executable nodes a pinned deployment owns.
// Everything outside this explicit allowlist remains part of the comparison, including
// unknown extensions and authored behavior properties.
const PRESENTATION = new Map([
  ['graph', new Set(['ravenroot.renderMode', 'ravenroot.layoutMode', 'ravenroot.designArrangement', 'ravenroot.ui.visualGroups'])],
  ['node', new Set(['layoutX', 'layoutY', 'layoutWidth', 'layoutHeight'])],
]);
export function deploymentGraphSignature(xml) {
  const document = new DOMParser().parseFromString(xml, 'application/xml');
  if (document.querySelector('parsererror')) throw new TypeError('Invalid deployment GraphML');
  const ignored = new Set();
  for (const key of [...document.documentElement.children]) {
    if (key.localName !== 'key' || key.namespaceURI !== 'http://graphml.graphdrawing.org/xmlns') continue;
    if (PRESENTATION.get(key.getAttribute('for'))?.has(key.getAttribute('attr.name'))) {
      ignored.add(key.getAttribute('id')); key.remove();
    }
  }
  for (const data of [...document.getElementsByTagNameNS('http://graphml.graphdrawing.org/xmlns', 'data')]) {
    if (ignored.has(data.getAttribute('key'))) data.remove();
  }
  const canonical = node => {
    if (node.nodeType === 3) return node.parentNode?.localName === 'data' ? node.textContent : node.textContent.trim() || null;
    if (node.nodeType !== 1) return null;
    if (node.namespaceURI === 'http://www.yworks.com/xml/graphml' && node.localName === 'Geometry') return null;
    return [node.namespaceURI, node.localName,
      [...node.attributes].map(attr => [attr.name, attr.value]).sort(([a], [b]) => a.localeCompare(b)),
      [...node.childNodes].map(canonical).filter(value => value !== null)];
  };
  return JSON.stringify(canonical(document.documentElement));
}

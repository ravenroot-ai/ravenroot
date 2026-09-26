/** Packaged, deterministic semantic symbols shared by the catalog and read-only renderers. */
export const CATALOG_NODE_SYMBOLS = Object.freeze({
  start: '▶', end: '■', error: '⚠', terminal: '⊙', consumer: '⧒', handler: '↩',
  agent: '⬡', flow: '⚙', actor: '◎', system: '▤', behavior: '⚙', passthrough: '↩',
  workspace: '▣', trace: '▤', 'human-task': '👤',
});

export function catalogNodeSymbol(nodeType) {
  const normalized = String(nodeType || 'node').trim().toLowerCase();
  if (CATALOG_NODE_SYMBOLS[normalized]) return CATALOG_NODE_SYMBOLS[normalized];
  const initial = [...normalized].find(character => /[a-z0-9]/u.test(character));
  return (initial || '?').toUpperCase();
}

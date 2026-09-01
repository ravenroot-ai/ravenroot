// A catalog descriptor and the canvas node it becomes must agree on ONE thing: which visual node
// type it is. `resolveDescriptorNodeType` is that single formula, extracted so every place that
// turns a descriptor into a node — the catalog palette, the "Add catalog node" form (`app.js`), and
// the canvas drag/drop insertion path (`graph-editing.js`) — reads it the same way instead of
// computing it independently.
//
// Independent placement and catalog-icon calculations had drifted: placement read
// `descriptor.visualType` first and fell back to `agentic` only when it was absent; the catalog's
// icon picker read `type.agentic` first and never consulted `visualType` at all. A descriptor that
// declares an explicit `visualType: 'agent'` without also setting `agentic` — LLM prompt — was
// therefore placed on the canvas as a full agent-type node (same brain artwork as Agent) while its
// catalog entry, upstream of that placement, showed a plain glyph instead of the brain preview. Two
// readings of the same descriptor, two different visual identities for what is, once placed, the
// identical node type.
//
// A third, independent copy of the same formula survived in `graph-editing.js`'s drag-and-drop
// insertion path until — textually identical at the time, so nothing was broken, but the
// invariant this comment claims did not actually hold until that copy was replaced with this import.
export function resolveDescriptorNodeType(descriptor) {
  return descriptor?.visualType || (descriptor?.agentic ? 'agent' : 'actor');
}

// The catalog palette's preview icon for a descriptor, kept in lockstep with what the canvas will
// actually draw once the node exists. `agent` is the one node type with its own bespoke artwork
// (`AGENT_BRAIN_SVG` in app.js) rather than a generic glyph-on-colour tile, so its catalog preview
// is the same emoji stand-in regardless of which `nodeIcons` entry the resolved type would
// otherwise map to. Every other resolved type keeps the plain per-type glyph.
export function catalogNodeIcon(descriptor, nodeIcons) {
  const nodeType = resolveDescriptorNodeType(descriptor);
  return nodeType === 'agent' ? '🧠' : (nodeIcons[nodeType] || '•');
}

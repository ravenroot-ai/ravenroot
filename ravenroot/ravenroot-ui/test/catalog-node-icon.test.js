import { describe, expect, it } from 'vitest';

import { catalogNodeIcon, resolveDescriptorNodeType } from '../src/catalog-node-icon.js';

// The generic per-type glyph map, standing in for `NODE_ICONS` in app.js. `agent` deliberately maps
// to a plain glyph here (as it does in app.js): the brain preview for agent-typed descriptors is a
// SEPARATE special case in `catalogNodeIcon`, not something this map is asked to carry.
const NODE_ICONS = { agent: '⬡ ', flow: '⚙ ', actor: '◉ ' };

describe('resolveDescriptorNodeType', () => {
  it('uses an explicit visualType over the agentic flag', () => {
    expect(resolveDescriptorNodeType({ visualType: 'agent', agentic: false })).toBe('agent');
  });

  it('falls back to agent when agentic is set and visualType is absent', () => {
    expect(resolveDescriptorNodeType({ agentic: true })).toBe('agent');
  });

  it('falls back to actor when neither visualType nor agentic is set', () => {
    expect(resolveDescriptorNodeType({})).toBe('actor');
  });
});

describe('catalogNodeIcon', () => {
  // The catalog palette showed 🧠 for an agentic descriptor but a plain hexagon for one that
  // declared `visualType: 'agent'` with `agentic: false`, even though both are placed on the canvas
  // as the exact same agent-type node with the exact same brain artwork. Reading `type.agentic`
  // without honoring an explicit `visualType` makes the palette and placement disagree. Both use the
  // same resolved node type.
  //
  // The descriptors use bundle-published behavior names because agent nodes are supplied by bundles,
  // not the core catalog. The assertion does not depend on those names: `behavior` is not read by
  // `catalogNodeIcon` and appears only to make the fixtures resemble real catalog entries.
  it('gives both agent-typed descriptors the brain icon, however they got that type', () => {
    const agentic = { behavior: 'acme-agent', visualType: 'agent', agentic: true };
    const visuallyAgent = { behavior: 'acme-prompt', visualType: 'agent', agentic: false };
    expect(catalogNodeIcon(agentic, NODE_ICONS)).toBe('🧠');
    expect(catalogNodeIcon(visuallyAgent, NODE_ICONS)).toBe('🧠');
  });

  it('falls back to the generic per-type glyph for non-agent types', () => {
    expect(catalogNodeIcon({ visualType: 'flow' }, NODE_ICONS)).toBe('⚙ ');
    expect(catalogNodeIcon({ visualType: 'actor' }, NODE_ICONS)).toBe('◉ ');
  });

  it('falls back to the bullet placeholder for an unmapped type', () => {
    expect(catalogNodeIcon({ visualType: 'unknown-future-type' }, NODE_ICONS)).toBe('•');
  });
});

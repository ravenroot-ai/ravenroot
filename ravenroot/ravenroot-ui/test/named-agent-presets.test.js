import { describe, it, expect } from 'vitest';
import { namedAgentPresets } from '../src/named-agent-presets.js';
import { catalogNodeIcon, resolveDescriptorNodeType } from '../src/catalog-node-icon.js';

describe('approved named Agent presets', () => {
  const catalog = [{ behavior: 'agent', properties: [{ name: 'agentDefinition', defaultValue: '' },
    { name: 'agentVersion', defaultValue: '1' }, { name: 'workspaceRef', defaultValue: '' }] }];
  it('keeps execution behavior agent and seeds exact definition/version without inventing a Workspace', () => {
    const [preset] = namedAgentPresets(catalog, [{ kind: 'AGENT_DEFINITION', approved: true, name: 'reviewer', version: 3 }]);
    expect(preset.behavior).toBe('agent'); expect(preset.presetId).toBe('agent:reviewer:3');
    expect(preset.properties.map(p => p.defaultValue)).toEqual(['reviewer', '3', '']);
    expect(catalog[0].properties[0].defaultValue).toBe('');
  });
  it('never advertises draft, retired, malformed or non-Agent resources', () => {
    expect(namedAgentPresets(catalog, [{ kind: 'AGENT_DEFINITION', approved: false, name: 'draft', version: 1 },
      { kind: 'RUNNER', approved: true, name: 'worker', version: 1 },
      { kind: 'AGENT_DEFINITION', approved: true, name: '<script>', version: 1 }])).toEqual([]);
  });
  it('gives Workspace a distinct non-colour identity', () => {
    expect(resolveDescriptorNodeType({ behavior: 'workspace' })).toBe('workspace');
    expect(catalogNodeIcon({ behavior: 'workspace' }, { workspace: '▣' })).toBe('▣');
  });
});

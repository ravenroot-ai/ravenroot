/** Tenant-authorized catalog references only; instructions, credentials and grants stay server-owned. */
export function namedAgentPresets(catalog, resources) {
  const agent = catalog.find(type => type.behavior === 'agent');
  if (!agent || !Array.isArray(resources)) return [];
  return resources.filter(resource => resource.kind === 'AGENT_DEFINITION' && resource.approved === true
    && /^[a-z][a-z0-9._-]{0,63}$/.test(resource.name) && Number.isSafeInteger(resource.version) && resource.version > 0)
    .map(resource => ({ ...agent, presetId: `agent:${resource.name}:${resource.version}`,
      displayName: `${resource.name} · v${resource.version}`, category: 'Named Agents',
      description: `Approved Agent ${resource.name}, version ${resource.version}. Optionally select its Workspace in this graph.`,
      properties: agent.properties.map(property => property.name === 'agentDefinition'
        ? { ...property, defaultValue: resource.name } : property.name === 'agentVersion'
          ? { ...property, defaultValue: String(resource.version) } : { ...property }),
    }));
}

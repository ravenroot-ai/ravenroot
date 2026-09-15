const CANONICAL_INDEX = /^[1-9][0-9]*$/;

function graphMlType(type) {
  if (type === 'BOOLEAN') return 'boolean';
  if (type === 'INTEGER') return 'long';
  if (type === 'DECIMAL') return 'double';
  return 'string';
}

export function splitAdditionalPropertyGroups(descriptor, properties = {}, propertyTypes = {}) {
  const definitions = descriptor?.additionalProperties || [];
  const byName = new Map(definitions.map(group => [group.name, group]));
  const grouped = new Map(definitions.map(group => [group.name, new Map()]));
  const claimed = new Set();
  for (const [name, value] of Object.entries(properties)) {
    const first = name.indexOf('.');
    const second = first < 0 ? -1 : name.indexOf('.', first + 1);
    if (first < 1 || second < 0) continue;
    const groupName = name.slice(0, first);
    const indexText = name.slice(first + 1, second);
    const fieldName = name.slice(second + 1);
    const definition = byName.get(groupName);
    if (!definition || !CANONICAL_INDEX.test(indexText)
        || !(definition.fields || []).some(field => field.name === fieldName)) continue;
    const index = Number(indexText);
    if (!Number.isSafeInteger(index)) continue;
    const items = grouped.get(groupName);
    if (!items.has(index)) items.set(index, {});
    items.get(index)[fieldName] = { value: String(value ?? ''), type: propertyTypes[name] || 'string' };
    claimed.add(name);
  }
  return {
    claimed,
    groups: definitions.map(definition => ({
      definition,
      items: [...grouped.get(definition.name).entries()]
        .sort(([left], [right]) => left - right)
        .map(([sourceIndex, fields]) => ({ sourceIndex, fields })),
    })),
  };
}

export function serializeAdditionalPropertyGroups(groups) {
  const properties = {};
  const propertyTypes = {};
  for (const group of groups) {
    group.items.forEach((item, offset) => {
      for (const field of group.definition.fields || []) {
        const entry = item.fields[field.name];
        const value = String(entry?.value ?? '');
        if (!value && !field.required) continue;
        const name = `${group.definition.name}.${offset + 1}.${field.name}`;
        properties[name] = value;
        propertyTypes[name] = graphMlType(field.type || 'STRING');
      }
    });
  }
  return { properties, propertyTypes };
}

export function additionalPropertyGroupsValid(groups) {
  return groups.every(group => group.items.every(item =>
    (group.definition.fields || []).every(field => !field.required
      || String(item.fields[field.name]?.value ?? '').trim() !== '')));
}

export function nextAdditionalPropertyGroupItem(group) {
  return {
    sourceIndex: group.items.length + 1,
    fields: Object.fromEntries((group.definition.fields || []).map(field => [field.name, {
      value: field.defaultValue || '', type: graphMlType(field.type || 'STRING'),
    }])),
  };
}

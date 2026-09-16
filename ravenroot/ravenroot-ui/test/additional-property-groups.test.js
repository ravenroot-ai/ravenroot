import { describe, expect, it } from 'vitest';

import {
  additionalPropertyGroupsValid,
  nextAdditionalPropertyGroupItem,
  serializeAdditionalPropertyGroups,
  splitAdditionalPropertyGroups,
} from '../src/additional-property-groups.js';

const descriptor = { additionalProperties: [{
  name: 'skill', displayName: 'Skill', indexStart: 1, contiguous: true,
  fields: [
    { name: 'name', type: 'STRING', required: true },
    { name: 'description', type: 'STRING', required: false },
    { name: 'instructions', type: 'TEXT', required: true },
  ],
}] };

describe('dynamic additional property groups', () => {
  it('sorts canonical numeric identities and preserves unrelated free-form keys', () => {
    const properties = {
      'skill.10.name': 'ten', 'skill.10.instructions': 'i10',
      'skill.2.name': 'two', 'skill.2.instructions': 'i2',
      'skill.02.name': 'noncanonical', 'skill.1.unknown': 'free', custom: 'free',
    };
    const parsed = splitAdditionalPropertyGroups(descriptor, properties);
    expect(parsed.groups[0].items.map(item => item.sourceIndex)).toEqual([2, 10]);
    expect([...parsed.claimed]).not.toContain('skill.02.name');
    expect([...parsed.claimed]).not.toContain('skill.1.unknown');
    expect([...parsed.claimed]).not.toContain('custom');
  });

  it('atomically compacts holes and serializes fields deterministically beyond eight items', () => {
    const properties = {};
    for (const index of [12, 1, 9, 3, 6, 2, 11, 5, 10, 4, 8, 7]) {
      properties[`skill.${index}.name`] = `name-${index}`;
      properties[`skill.${index}.instructions`] = `instructions-${index}`;
    }
    const group = splitAdditionalPropertyGroups(descriptor, properties).groups[0];
    group.items.splice(1, 1);
    group.items.push(nextAdditionalPropertyGroupItem(group));
    group.items.at(-1).fields.name.value = 'new';
    group.items.at(-1).fields.instructions.value = 'new instructions';
    const first = serializeAdditionalPropertyGroups([group]);
    const second = serializeAdditionalPropertyGroups(
      splitAdditionalPropertyGroups(descriptor, first.properties, first.propertyTypes).groups);
    expect(first).toEqual(second);
    expect(Object.keys(first.properties)).toContain('skill.12.instructions');
    expect(Object.keys(first.properties)).not.toContain('skill.13.name');
  });

  it('rejects a partial required item but permits an omitted optional field', () => {
    const group = splitAdditionalPropertyGroups(descriptor, {
      'skill.1.name': 'one', 'skill.1.description': 'optional',
    }).groups[0];
    expect(additionalPropertyGroupsValid([group])).toBe(false);
    group.items[0].fields.instructions = { value: 'complete', type: 'text' };
    delete group.items[0].fields.description;
    expect(additionalPropertyGroupsValid([group])).toBe(true);
  });
});

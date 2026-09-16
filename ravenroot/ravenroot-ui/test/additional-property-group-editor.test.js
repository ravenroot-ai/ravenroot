import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import {
  additionalPropertyGroupsValid,
  nextAdditionalPropertyGroupItem,
  serializeAdditionalPropertyGroups,
  splitAdditionalPropertyGroups,
} from '../src/additional-property-groups.js';

const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');

function extractFunctionSource(source, name) {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `${name} must remain a live app.js function`).toBeGreaterThan(-1);
  let index = source.indexOf('{', start);
  let depth = 0;
  for (; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}') {
      depth -= 1;
      if (depth === 0) { index += 1; break; }
    }
  }
  return source.slice(start, index);
}

function loadEditor() {
  const source = readFileSync(APP_SOURCE_PATH, 'utf8');
  const names = [
    'escapeHtml',
    'escapeAttribute',
    'additionalPropertyGroupsHtml',
    'additionalPropertyGroupItemHtml',
    'additionalPropertyGroupFieldControlHtml',
    'readAdditionalPropertyGroupEditor',
    'removeAdditionalPropertyGroupItem',
    'appendAdditionalPropertyGroupItem',
  ];
  // eslint-disable-next-line no-new-func
  const factory = new Function(
    'additionalPropertyGroupsValid',
    'nextAdditionalPropertyGroupItem',
    'serializeAdditionalPropertyGroups',
    'secretReferenceOptionsHtml',
    `${names.map(name => extractFunctionSource(source, name)).join('\n')}
    return {
      additionalPropertyGroupsHtml,
      readAdditionalPropertyGroupEditor,
      removeAdditionalPropertyGroupItem,
      appendAdditionalPropertyGroupItem,
    };`,
  );
  return factory(
    additionalPropertyGroupsValid,
    nextAdditionalPropertyGroupItem,
    serializeAdditionalPropertyGroups,
    value => `<option value="${String(value)}" selected>${String(value)}</option>`,
  );
}

const skillsDescriptor = { additionalProperties: [{
  name: 'skills',
  displayName: 'Skill',
  description: 'Complete model-loadable skills.',
  fields: [
    { name: 'name', displayName: 'Name', type: 'STRING', required: true },
    { name: 'description', displayName: 'Description', type: 'STRING', required: true },
    { name: 'instructions', displayName: 'Instructions', type: 'TEXT', required: true },
  ],
}] };

function render(editor, descriptor, properties, propertyTypes = {}) {
  const parsed = splitAdditionalPropertyGroups(descriptor, properties, propertyTypes);
  document.body.innerHTML = `<form>${editor.additionalPropertyGroupsHtml(parsed.groups)}</form>`;
  return document.querySelector('form');
}

describe('the live dynamic additional-property editor', () => {
  it('edits twelve complete skills atomically and round-trips multiline instructions canonically', () => {
    const editor = loadEditor();
    const properties = {};
    for (const index of [12, 3, 9, 1, 11, 2, 10, 4, 8, 5, 7, 6]) {
      properties[`skills.${index}.name`] = `skill-${index}`;
      properties[`skills.${index}.description`] = `description-${index}`;
      properties[`skills.${index}.instructions`] = index === 1
        ? 'first line\nsecond line\nthird line'
        : `instructions-${index}`;
    }

    const form = render(editor, skillsDescriptor, properties);
    const items = () => [...form.querySelectorAll('.additional-property-group-item')];
    expect(items()).toHaveLength(12);
    const instructions = form.querySelector('[data-additional-field="instructions"]');
    expect(instructions.tagName).toBe('TEXTAREA');
    expect(instructions.value).toBe('first line\nsecond line\nthird line');

    expect(editor.removeAdditionalPropertyGroupItem(
      items()[1].querySelector('[data-remove-additional-group]'))).toBe(true);
    expect(items()).toHaveLength(11);
    const section = form.querySelector('[data-additional-group="skills"]');
    expect(editor.appendAdditionalPropertyGroupItem(
      section, skillsDescriptor.additionalProperties[0])).toBe(true);
    expect(items()).toHaveLength(12);
    expect(() => editor.readAdditionalPropertyGroupEditor(form, skillsDescriptor))
      .toThrow('additional property group is incomplete');

    const added = items().at(-1);
    added.querySelector('[data-additional-field="name"]').value = 'new-skill';
    added.querySelector('[data-additional-field="description"]').value = 'new description';
    added.querySelector('[data-additional-field="instructions"]').value = 'new first line\nnew second line';
    const saved = editor.readAdditionalPropertyGroupEditor(form, skillsDescriptor);
    expect(saved.properties['skills.1.instructions']).toBe('first line\nsecond line\nthird line');
    expect(saved.properties['skills.2.name']).toBe('skill-3');
    expect(saved.properties['skills.12.name']).toBe('new-skill');
    expect(saved.properties).not.toHaveProperty('skills.13.name');
    expect(Object.keys(saved.properties)).toHaveLength(36);

    const reloaded = render(editor, skillsDescriptor, saved.properties, saved.propertyTypes);
    expect(editor.readAdditionalPropertyGroupEditor(reloaded, skillsDescriptor)).toEqual(saved);
    expect(reloaded.querySelectorAll('.additional-property-group-item')).toHaveLength(12);
    expect(reloaded.querySelector('[data-additional-field="instructions"]').value)
      .toBe('first line\nsecond line\nthird line');
  });

  it('uses catalog-consistent controls for every supported descriptor type', () => {
    const editor = loadEditor();
    const descriptor = { additionalProperties: [{
      name: 'typed', displayName: 'Typed', fields: [
        { name: 'string', type: 'STRING', required: true },
        { name: 'choice', type: 'STRING', required: true, allowedValues: ['one', 'two'] },
        { name: 'text', type: 'TEXT', required: true, maximumUtf8Bytes: 200 },
        { name: 'expression', type: 'CEL_EXPRESSION', required: true },
        { name: 'boolean', type: 'BOOLEAN', required: true },
        { name: 'integer', type: 'INTEGER', required: true, minimumValue: '1', maximumValue: '9' },
        { name: 'decimal', type: 'DECIMAL', required: true },
        { name: 'uri', type: 'URI', required: true },
        { name: 'secret', type: 'SECRET_REFERENCE', required: true },
      ],
    }] };
    const properties = Object.fromEntries(descriptor.additionalProperties[0].fields
      .map(field => [`typed.1.${field.name}`, field.name === 'boolean' ? 'true' : 'value']));
    properties['typed.1.choice'] = 'two';
    properties['typed.1.integer'] = '4';
    properties['typed.1.decimal'] = '1.5';
    const form = render(editor, descriptor, properties);
    const field = name => form.querySelector(`[data-additional-field="${name}"]`);

    expect(field('string').matches('input[type="text"]')).toBe(true);
    expect(field('choice').tagName).toBe('SELECT');
    expect(field('text').tagName).toBe('TEXTAREA');
    expect(field('text').dataset.maximumUtf8Bytes).toBe('200');
    expect(field('expression').tagName).toBe('TEXTAREA');
    expect(field('boolean').tagName).toBe('SELECT');
    expect(field('integer').matches('input[type="number"]')).toBe(true);
    expect(field('integer').min).toBe('1');
    expect(field('integer').max).toBe('9');
    expect(field('decimal').step).toBe('any');
    expect(field('uri').matches('input[type="text"]')).toBe(true);
    expect(field('secret').tagName).toBe('SELECT');
    expect([...form.querySelectorAll('[data-additional-field]')]
      .every(control => control.required)).toBe(true);
  });
});

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';
import { adapterIdOf, catalogPropertyHasDeclaredDefault } from '../src/adapter-binding.js';
import { isPropertyRequiredNow, isPropertyVisible } from '../src/property-condition.js';

const APP = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js'), 'utf8');

function functionSource(name) {
  const start = APP.indexOf(`function ${name}(`);
  expect(start).toBeGreaterThan(-1);
  let index = APP.indexOf('{', start);
  let depth = 0;
  for (; index < APP.length; index += 1) {
    if (APP[index] === '{') depth += 1;
    else if (APP[index] === '}' && --depth === 0) return APP.slice(start, index + 1);
  }
  throw new Error(`unterminated ${name}`);
}

function renderer() {
  const source = ['escapeHtml', 'escapeAttribute', 'contextualHelpButtonHtml',
    'catalogPropertyFieldsHtml'].map(functionSource).join('\n');
  // Exercise the production renderer while supplying its imported generic predicates.
  // eslint-disable-next-line no-new-func
  return new Function('adapterIdOf', 'catalogPropertyHasDeclaredDefault', 'isPropertyVisible',
    'isPropertyRequiredNow', `${source}; return catalogPropertyFieldsHtml;`)(
    adapterIdOf, catalogPropertyHasDeclaredDefault, isPropertyVisible, isPropertyRequiredNow);
}

const property = (name, displayName, type, required, defaultValue = '', allowedValues = []) => ({
  name, displayName, type, required, description: `${displayName} help`, defaultValue,
  allowedValues, adapterBinding: false, visibleWhen: null, requiredWhen: null,
});

describe('human-task catalog controls', () => {
  it('renders bounded task metadata and state selectors as labelled native keyboard controls', () => {
    const descriptor = {
      behavior: 'human-task', displayName: 'Human task',
      properties: [
        property('title', 'Title', 'STRING', true),
        property('description', 'Description', 'TEXT', false),
        property('responseKind', 'Response kind', 'STRING', false, 'MAP', ['SCALAR', 'LIST', 'MAP']),
        property('expiresAfterSeconds', 'Expire after (seconds)', 'INTEGER', false, '604800'),
      ],
    };
    const host = document.createElement('form');
    host.innerHTML = renderer()(descriptor, {}, { documentId: 'doc-1', nodeId: 'task-1' });

    const title = host.querySelector('[data-catalog-property="title"]');
    const description = host.querySelector('[data-catalog-property="description"]');
    const kind = host.querySelector('[data-catalog-property="responseKind"]');
    const expiry = host.querySelector('[data-catalog-property="expiresAfterSeconds"]');
    expect(title.tagName).toBe('INPUT');
    expect(title.required).toBe(true);
    expect(description.tagName).toBe('TEXTAREA');
    expect(kind.tagName).toBe('SELECT');
    expect([...kind.options].map(option => option.value)).toEqual(['SCALAR', 'LIST', 'MAP']);
    expect(expiry.type).toBe('number');
    const controls = [title, description, kind, expiry];
    expect(new Set(controls.map(control => control.id)).size).toBe(controls.length);
    for (const control of controls) {
      expect(control.id).not.toBe('');
      expect(control.getAttribute('aria-label')).toBeNull();
      expect(control.labels).toHaveLength(1);
      expect(control.labels[0].htmlFor).toBe(control.id);
    }
    expect(title.labels[0].textContent.trim()).toBe('Title *');
    expect(title.labels[0].querySelector('[aria-hidden="true"]')).not.toBeNull();
    expect(description.labels[0].textContent.trim()).toBe('Description');
    expect(kind.labels[0].textContent.trim()).toBe('Response kind');
    expect(expiry.labels[0].textContent.trim()).toBe('Expire after (seconds)');
  });
});

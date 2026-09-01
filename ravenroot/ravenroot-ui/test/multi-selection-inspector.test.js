import { describe, expect, it } from 'vitest';

import {
  commonMultiSelectionProperties,
  javaUriSyntaxIsAbsolute,
  MULTI_PROPERTY_STATE,
  planMultiPropertyUpdate,
  propertyCompatibilitySignature,
  propertyStateLabel,
  sameSelectedIds,
  validateMultiPropertyValue,
} from '../src/multi-selection-inspector.js';

const CONDITION = {
  contract: 'ravenroot.property-condition/1', property: 'mode', operator: 'EQUALS', values: ['ON'],
};

function descriptor(behavior, properties, extras = {}) {
  return { behavior, displayName: behavior, properties, ...extras };
}

function property(name, type = 'STRING', extras = {}) {
  return { name, displayName: name, type, required: false, adapterBinding: false, ...extras };
}

function node(id, behavior, properties = {}, propertyTypes = {}) {
  return { id, name: id, behavior, properties, propertyTypes };
}

describe('multi-selection inspector projection', () => {
  it('recognizes an unchanged selected-id set without depending on renderer order', () => {
    expect(sameSelectedIds(['one', 'two'], ['two', 'one'])).toBe(true);
    expect(sameSelectedIds(['one'], ['one', 'two'])).toBe(false);
    expect(sameSelectedIds(['one', 'one'], ['one'])).toBe(true);
  });

  it('intersects by semantic schema while preserving the first descriptor presentation order', () => {
    const first = descriptor('first', [
      property('mode', 'STRING', { allowedValues: ['B', 'A'] }),
      property('endpoint', 'URI', { required: true }),
      property('onlyFirst'),
    ]);
    const second = descriptor('second', [
      property('endpoint', 'URI', { required: true }),
      property('mode', 'STRING', { allowedValues: ['A', 'B', 'A'] }),
      property('onlySecond'),
    ]);

    const common = commonMultiSelectionProperties([
      node('one', 'first', { mode: 'A', endpoint: 'https://one.example' }),
      node('two', 'second', { mode: 'A', endpoint: 'https://two.example' }),
    ], [first, second]);

    expect(common.map(candidate => candidate.name)).toEqual(['mode', 'endpoint']);
    expect(common[0].allowedValues).toEqual(['A', 'B']);
    expect(common[0].state).toEqual({ kind: MULTI_PROPERTY_STATE.SAME, value: 'A' });
    expect(common[1].state).toEqual({ kind: MULTI_PROPERTY_STATE.MIXED, value: '' });
  });

  it('rejects differences in type, requiredness, binding, enum, conditions and default semantics', () => {
    const variants = [
      [property('p', 'STRING'), property('p', 'URI')],
      [property('p', 'STRING'), property('p', 'STRING', { required: true })],
      [property('p', 'STRING'), property('p', 'STRING', { adapterBinding: true })],
      [property('p', 'STRING', { allowedValues: ['A'] }), property('p', 'STRING', { allowedValues: ['B'] })],
      [property('p', 'STRING', { visibleWhen: CONDITION }), property('p', 'STRING')],
      [property('p', 'STRING', { requiredWhen: CONDITION }), property('p', 'STRING')],
      [property('p', 'STRING', { defaultValue: 'x' }), property('p', 'STRING')],
    ];
    for (const pair of variants) {
      const catalog = [descriptor('a', [pair[0]]), descriptor('b', [pair[1]])];
      expect(commonMultiSelectionProperties([node('a', 'a'), node('b', 'b')], catalog)).toEqual([]);
    }
  });

  it('normalizes enum and condition value sets but distinguishes a declared default from absence', () => {
    expect(propertyCompatibilitySignature(property('p', 'STRING', {
      allowedValues: ['b', 'a'], visibleWhen: { ...CONDITION, values: ['ON', 'OFF'] },
    }))).toBe(propertyCompatibilitySignature(property('p', 'string', {
      allowedValues: ['a', 'b', 'a'], visibleWhen: { ...CONDITION, values: ['OFF', 'ON'] },
    })));
    expect(propertyCompatibilitySignature(property('p', 'STRING', { defaultValue: 'value' })))
      .not.toBe(propertyCompatibilitySignature(property('p', 'STRING')));
  });

  it('requires a trusted descriptor and current visibility on every selected node', () => {
    const conditional = property('detail', 'STRING', { visibleWhen: CONDITION });
    const catalog = [descriptor('known', [property('mode'), conditional])];
    expect(commonMultiSelectionProperties([
      node('one', 'known', { mode: 'ON' }), node('two', 'known', { mode: 'OFF' }),
    ], catalog).map(candidate => candidate.name)).toEqual(['mode']);
    expect(commonMultiSelectionProperties([
      node('one', 'known'), node('two', 'custom'),
    ], catalog)).toEqual([]);
  });

  it('excludes runtime-owned and explicitly read-only properties', () => {
    const catalog = [descriptor('known', [
      property('runtime.nature'), property('locked', 'STRING', { mutable: false }), property('safe'),
    ], { natureProperty: 'runtime.nature' })];
    expect(commonMultiSelectionProperties([
      node('one', 'known'), node('two', 'known'),
    ], catalog).map(candidate => candidate.name)).toEqual(['safe']);
  });

  // `NodeBypassProperty.validateShape` refuses any descriptor that declares `execution.bypass`,
  // so a catalog reaching here with it is already broken -- which is exactly why the guard is here
  // and not assumed from the server. A batch editor is the worst place for a platform-owned key to
  // become an editable row: one edit writes it across every selected node at once, including nodes
  // whose kind the runtime refuses it on, and there is no per-node kind check in this path.
  it('excludes the platform-owned bypass property even from a catalog that wrongly declares it', () => {
    const catalog = [descriptor('known', [
      property('execution.bypass'), property('safe'),
    ], { natureProperty: 'runtime.nature', bypassProperty: 'execution.bypass' })];
    expect(commonMultiSelectionProperties([
      node('one', 'known'), node('two', 'known'),
    ], catalog).map(candidate => candidate.name)).toEqual(['safe']);
  });

  it('projects same, mixed and absent states without returning a secret-reference canary', () => {
    const canary = 'vault://SHOULD_NEVER_REACH_THE_DOM';
    const catalog = [descriptor('known', [
      property('same'), property('mixed'), property('missing'), property('secret', 'SECRET_REFERENCE'),
    ])];
    const common = commonMultiSelectionProperties([
      node('one', 'known', { same: 'x', mixed: 'a', secret: canary }),
      node('two', 'known', { same: 'x', mixed: 'b', secret: canary }),
    ], catalog);
    expect(common.find(candidate => candidate.name === 'same').state)
      .toEqual({ kind: MULTI_PROPERTY_STATE.SAME, value: 'x' });
    expect(common.find(candidate => candidate.name === 'mixed').state.kind).toBe(MULTI_PROPERTY_STATE.MIXED);
    expect(common.find(candidate => candidate.name === 'missing').state.kind).toBe(MULTI_PROPERTY_STATE.ABSENT);
    const secret = common.find(candidate => candidate.name === 'secret');
    expect(secret.state).toEqual({ kind: MULTI_PROPERTY_STATE.SAME, value: '' });
    expect(JSON.stringify(secret)).not.toContain(canary);
    expect(propertyStateLabel(secret)).toBe('Configured on every selected node');
  });
});

describe('multi-selection patch planning', () => {
  it('plans only touched common keys, removes an explicit clear, and preserves other maps', () => {
    const properties = [
      { ...property('owner'), state: { kind: 'mixed', value: '' }, allowedValues: [] },
      { ...property('obsolete'), state: { kind: 'same', value: 'yes' }, allowedValues: [] },
      { ...property('untouched'), state: { kind: 'mixed', value: '' }, allowedValues: [] },
    ];
    const nodes = [
      node('one', 'known', { owner: 'a', obsolete: 'yes', private: 'keep-1' },
        { owner: 'string', obsolete: 'string', private: 'string' }),
      node('two', 'known', { owner: 'b', obsolete: 'yes', private: 'keep-2' },
        { owner: 'string', obsolete: 'string', private: 'string' }),
    ];

    const plan = planMultiPropertyUpdate(nodes, properties, [
      { name: 'owner', value: 'team', touched: true },
      { name: 'obsolete', value: '', touched: true },
      { name: 'untouched', value: 'ignored', touched: false },
    ], [descriptor('known', properties)]);

    expect(plan.errors).toEqual([]);
    expect(plan.entries).toHaveLength(2);
    expect(plan.entries[0]).toEqual({
      id: 'one',
      properties: { set: { owner: 'team' }, unset: ['obsolete'] },
      propertyTypes: { set: { owner: 'string' }, unset: ['obsolete'] },
    });
    expect(nodes[0].properties).toEqual({ owner: 'a', obsolete: 'yes', private: 'keep-1' });
  });

  it('validates enum, format and conservative secret-reference syntax before planning', () => {
    expect(validateMultiPropertyValue(property('choice', 'STRING', { allowedValues: ['A'] }), 'B'))
      .toMatch(/catalog choices/);
    expect(validateMultiPropertyValue(property('count', 'INTEGER'), '1.2')).toMatch(/integer/);
    expect(validateMultiPropertyValue(property('count', 'INTEGER'), '9223372036854775808')).toMatch(/64-bit/);
    expect(validateMultiPropertyValue(property('ratio', 'DECIMAL'), '1.2e3')).toBe('');
    expect(validateMultiPropertyValue(property('url', 'URI'), '/relative')).toMatch(/absolute URI/);
    expect(validateMultiPropertyValue(property('url', 'URI'), ' https://example.test/path '))
      .toMatch(/absolute URI/);
    expect(validateMultiPropertyValue(property('url', 'URI'), 'https://example.test/%ZZ'))
      .toMatch(/absolute URI/);
    expect(validateMultiPropertyValue(property('url', 'URI'), 'https://example.test/raw space'))
      .toMatch(/absolute URI/);
    expect(validateMultiPropertyValue(property('secret', 'SECRET_REFERENCE'), 'vault://bad reference'))
      .toMatch(/without whitespace/);
    expect(validateMultiPropertyValue(property('secret', 'SECRET_REFERENCE'), 'vault://valid/ref')).toBe('');
  });

  it('matches the Java 21 URI syntax matrix for absolute generic and hierarchical forms', () => {
    for (const accepted of [
      'https://example.test/path?x=1#result',
      'urn:isbn:0451450523',
      'mailto:user@example.com',
      'custom:a/b?c=d#f',
      'http://example.com/a%5Bb',
      'http://[2001:db8::1]/',
      'http://[fe80::1%eth0]/',
      'http://[fe80::1%1]/',
      'http://[fe80::1%_]/',
      'http://[fe80::1%.]/',
      'http://[fe80::1%a_b.c]/',
      'http://[fe80::1%25]/',
      'http://[fe80::1%25eth0]/',
      'http://[fe80::1%25en_0]/',
      'http://[fe80::1%25en.0]/',
      'http://[::ffff:192.0.2.1]/',
      'foo:///path',
      'foo://?',
      'foo://#',
      'foo://?#',
      'foo://?q',
      'foo://#f',
      'foo://?#f',
      'foo:?query',
      'foo:opaque[metadata]',
      'foo:é',
      `foo:/\uD800`,
      `foo:/\uDC00`,
      `foo:\uD800`,
      `foo:/?\uD800`,
      `foo:/#\uD800`,
      `foo://\uD800/`,
    ]) expect(javaUriSyntaxIsAbsolute(accepted), accepted).toBe(true);

    for (const rejected of [
      '/relative',
      '1scheme:value',
      'scheme:',
      'scheme:#fragment',
      'http://example.com/a[b',
      'http://example.com/a]b',
      'http://[not-ip]/',
      'http://[fe80::1%]/',
      'http://[fe80::1%25en-0]/',
      'http://[fe80::1%25en~0]/',
      'http://[fe80::1%25en%2D0]/',
      'http://[fe80::1%25en%250]/',
      'http://[fe80::1%é]/',
      'http://[fe80::1%a$b]/',
      'http://[fe80::1%a+b]/',
      'http://[fe80::1%a@b]/',
      'foo://',
      ' https://example.test/path ',
      'https://example.test/%ZZ',
      'https://example.test/raw space',
      'foo:bar\\baz',
      'foo:bar|baz',
    ]) expect(javaUriSyntaxIsAbsolute(rejected), rejected).toBe(false);
  });

  it('refuses clearing a currently required property but permits an empty adapter binding', () => {
    const required = { ...property('required', 'STRING', { required: true }), state: { kind: 'same', value: 'x' } };
    const adapter = { ...property('adapter', 'STRING', { required: true, adapterBinding: true }), state: { kind: 'same', value: 'x' } };
    expect(planMultiPropertyUpdate([node('one', 'known', { required: 'x' })], [required], [
      { name: 'required', value: '', touched: true },
    ], [descriptor('known', [required])]).errors).toEqual(['required is required for one']);
    expect(planMultiPropertyUpdate([node('one', 'known', { adapter: 'x' })], [adapter], [
      { name: 'adapter', value: '', touched: true },
    ], [descriptor('known', [adapter])]).errors).toEqual([]);
  });

  it('keeps the JVM node-wide requiredness exemption while an adapter binding is unconfigured', () => {
    const adapter = property('adapter', 'STRING', { required: true, adapterBinding: true });
    const required = property('required', 'STRING', { required: true });
    const optional = property('optional');
    const catalog = [descriptor('known', [adapter, required, optional])];
    const nodes = [node('one', 'known'), node('two', 'known')];
    const common = commonMultiSelectionProperties(nodes, catalog);

    const plan = planMultiPropertyUpdate(nodes, common, [
      { name: 'optional', value: 'changed', touched: true },
    ], catalog);

    expect(plan.errors).toEqual([]);
    expect(plan.entries).toHaveLength(2);
  });

  it('validates newly visible required properties against the complete resulting descriptor', () => {
    const mode = property('mode', 'STRING', { allowedValues: ['OFF', 'ON'] });
    const callback = property('callback', 'URI', {
      visibleWhen: CONDITION, requiredWhen: CONDITION,
    });
    const catalog = [descriptor('known', [mode, callback])];
    const nodes = [node('one', 'known', { mode: 'OFF' }), node('two', 'known', { mode: 'OFF' })];
    const before = structuredClone(nodes);
    const common = commonMultiSelectionProperties(nodes, catalog);

    expect(common.map(candidate => candidate.name)).toEqual(['mode']);
    const plan = planMultiPropertyUpdate(nodes, common, [
      { name: 'mode', value: 'ON', touched: true },
    ], catalog);

    expect(plan.entries).toEqual([]);
    expect(plan.errors).toEqual(['callback is required for one', 'callback is required for two']);
    expect(nodes).toEqual(before);
  });

  it('resolves an absent sibling default before deciding conditional requiredness', () => {
    const mode = property('mode', 'STRING', { allowedValues: ['OFF', 'ON'], defaultValue: 'ON' });
    const detail = property('detail', 'STRING', {
      visibleWhen: CONDITION, requiredWhen: CONDITION,
    });
    const catalog = [descriptor('known', [mode, detail])];
    const nodes = [node('one', 'known'), node('two', 'known')];
    const common = commonMultiSelectionProperties(nodes, catalog);

    expect(common.map(candidate => candidate.name)).toEqual(['mode', 'detail']);
    const plan = planMultiPropertyUpdate(nodes, common, [
      { name: 'detail', value: '', touched: true },
    ], catalog);

    expect(plan.entries).toEqual([]);
    expect(plan.errors).toEqual(['detail is required for one', 'detail is required for two']);
  });

  it('rejects URI padding and malformed escapes without changing input node maps', () => {
    const endpoint = property('endpoint', 'URI');
    const catalog = [descriptor('known', [endpoint])];
    for (const invalid of [' https://example.test/path ', 'https://example.test/%ZZ']) {
      const nodes = [
        node('one', 'known', { endpoint: 'https://one.example' }, { endpoint: 'string' }),
        node('two', 'known', { endpoint: 'https://two.example' }, { endpoint: 'string' }),
      ];
      const before = structuredClone(nodes);
      const common = commonMultiSelectionProperties(nodes, catalog);

      const plan = planMultiPropertyUpdate(nodes, common, [
        { name: 'endpoint', value: invalid, touched: true },
      ], catalog);

      expect(plan.entries).toEqual([]);
      expect(plan.errors).toEqual(['endpoint must be an absolute URI']);
      expect(nodes).toEqual(before);
    }
  });

  it('does not flag a Java-valid raw IPv6 scope URI during an unrelated property batch', () => {
    const description = property('description', 'TEXT');
    const endpoint = property('endpoint', 'URI');
    const catalog = [descriptor('known', [description, endpoint])];
    const nodes = [
      node('one', 'known', { description: 'old', endpoint: 'http://[fe80::1%eth0]/' }),
      node('two', 'known', { description: 'old', endpoint: 'http://[fe80::1%eth0]/' }),
    ];
    const common = commonMultiSelectionProperties(nodes, catalog);

    const plan = planMultiPropertyUpdate(nodes, common, [
      { name: 'description', value: 'new', touched: true },
    ], catalog);

    expect(plan.errors).toEqual([]);
    expect(plan.entries).toHaveLength(2);
    expect(plan.entries[0].properties).toEqual({ set: { description: 'new' }, unset: [] });
  });
});

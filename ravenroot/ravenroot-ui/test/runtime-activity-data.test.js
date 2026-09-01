import { describe, expect, it } from 'vitest';
import {
  MAX_RUNTIME_MESSAGE_UTF8_BYTES,
  MAX_RUNTIME_OUTPUT_UTF8_BYTES,
  RUNTIME_REDACTION_MARKER,
  RUNTIME_TRUNCATION_MARKER,
  runtimeActivityMessage,
  runtimeActivityOutput,
} from '../src/runtime-activity-data.js';

describe('runtime activity diagnostics defense in depth', () => {
  it('retains useful failure text while redacting and bounding before rendering', () => {
    const secret = 'hunter2-client-sentinel';
    const projected = runtimeActivityMessage(
      `${'x'.repeat(MAX_RUNTIME_MESSAGE_UTF8_BYTES - 40)} password=${secret}; retry disabled ${'🙂'.repeat(20)}`,
    );

    expect(projected.value).not.toContain(secret);
    expect(projected.value).toContain(RUNTIME_REDACTION_MARKER);
    expect(projected.value).toContain(RUNTIME_TRUNCATION_MARKER);
    expect(projected.redacted).toBe(true);
    expect(projected.truncated).toBe(true);
    expect(new TextEncoder().encode(projected.value).byteLength).toBeLessThanOrEqual(MAX_RUNTIME_MESSAGE_UTF8_BYTES);
    expect(projected.value).not.toContain('\ufffd');
    expect(runtimeActivityMessage('bad \ud800 diagnostic').value).not.toContain('\ufffd');
  });

  it('does not infer operation flags from authored marker text', () => {
    const projected = runtimeActivityMessage(`literal ${RUNTIME_REDACTION_MARKER} ${RUNTIME_TRUNCATION_MARKER}`);
    expect(projected.redacted).toBe(false);
    expect(projected.truncated).toBe(false);
  });

  it('redacts complete single and double quoted assignments containing spaces', () => {
    for (const fixture of [
      '{"password":"alpha beta gamma"}',
      "password='alpha beta gamma' retry disabled",
      'client_secret = "alpha beta gamma"; retry disabled',
    ]) {
      const projected = runtimeActivityMessage(fixture);
      expect(projected.value).not.toContain('alpha');
      expect(projected.value).not.toContain('beta gamma');
      expect(projected.value).toContain(RUNTIME_REDACTION_MARKER);
      expect(projected.redacted).toBe(true);
    }

    const control = runtimeActivityMessage(
      'credentialRef=\'mail production\' tokenization="alpha beta gamma"',
    );
    expect(control.value).toContain('mail production');
    expect(control.value).toContain('alpha beta gamma');
    expect(control.redacted).toBe(false);
  });

  it('re-applies limits and targeted redaction to hostile or older peers', () => {
    const secret = 'peer-secret-sentinel';
    const output = runtimeActivityOutput({
      password: secret,
      credentialRef: 'mail-prod',
      markup: '<img src=x onerror=alert(1)>',
      nested: { text: `token=${secret}` },
      long: '🙂'.repeat(2000),
    });

    expect(output.value).not.toContain(secret);
    expect(output.value).toContain(RUNTIME_REDACTION_MARKER);
    expect(output.value).toContain('mail-prod');
    expect(output.displayValue).toContain(RUNTIME_REDACTION_MARKER);
    expect(output.displayValue).not.toContain(secret);
    expect(output.redacted).toBe(true);
    expect(output.truncated).toBe(true);
  });

  it('unfolds structured real newlines exactly once without changing literal backslash-n data', () => {
    const output = runtimeActivityOutput({
      actual: 'first line\nsecond line',
      literal: String.raw`first line\nsecond line`,
    });

    expect(output.value).toBe('{"actual":"first line\\nsecond line","literal":"first line\\\\nsecond line"}');
    expect(output.displayValue).toBe(`{
  "actual": "first line\\nsecond line",
  "literal": "first line\\\\nsecond line"
}`.replace('first line\\nsecond line', 'first line\nsecond line'));
    expect(JSON.parse(output.value).literal).toBe(String.raw`first line\nsecond line`);
    expect(output.displayValue.match(/first line\nsecond line/g)).toHaveLength(1);
  });

  it('keeps free text literal while decoding object and array documents exactly once', () => {
    const plain = runtimeActivityOutput(String.raw`line one\nline two "quoted" \\path`);
    const object = runtimeActivityOutput('{"name":"Ada","nested":"{\\"still\\":\\"text\\"}"}');
    const array = runtimeActivityOutput('[{"id":1},"two"]');
    const jsonScalar = runtimeActivityOutput('"scalar"');
    const doubleEncoded = runtimeActivityOutput(JSON.stringify(JSON.stringify({ id: 1 })));

    expect(plain.displayValue).toBe(String.raw`line one\nline two "quoted" \\path`);
    expect(object.displayValue).toBe(`{
  "name": "Ada",
  "nested": "{\\"still\\":\\"text\\"}"
}`);
    expect(array.displayValue).toBe(`[
  {
    "id": 1
  },
  "two"
]`);
    expect(jsonScalar.displayValue).toBe('"scalar"');
    expect(doubleEncoded.displayValue).toBe(JSON.stringify(JSON.stringify({ id: 1 })));
  });

  it('distinguishes a real free-text line feed from an authored backslash-n', () => {
    const realLineFeed = runtimeActivityOutput('first line\nsecond line');
    const literalBackslash = runtimeActivityOutput(String.raw`first line\nsecond line`);

    expect(realLineFeed.displayValue).toBe('first line\nsecond line');
    expect(literalBackslash.displayValue).toBe(String.raw`first line\nsecond line`);
    expect(realLineFeed.displayValue).not.toBe(literalBackslash.displayValue);
  });

  it('retains poison JSON keys as own data without prototype mutation', () => {
    const output = runtimeActivityOutput(
      '{"__proto__":{"visible":"must remain"},"constructor":"ctor",'
      + '"prototype":{"safe":true},"safe":1}',
    );
    const decoded = JSON.parse(output.value);

    expect(Object.keys(decoded)).toEqual(['__proto__', 'constructor', 'prototype', 'safe']);
    expect(Object.hasOwn(decoded, '__proto__')).toBe(true);
    expect(decoded.__proto__).toEqual({ visible: 'must remain' });
    expect(decoded.constructor).toBe('ctor');
    expect(decoded.prototype).toEqual({ safe: true });
    expect(output.displayValue).toContain('"visible": "must remain"');
    expect(output.displayValue).toContain('"constructor": "ctor"');
    expect(Object.prototype.visible).toBeUndefined();
    expect(({}).visible).toBeUndefined();
  });

  it('does not structure malformed, incomplete, oversized or server-truncated documents', () => {
    const malformedJson = '{"root":{"child":1}';
    const malformedXml = '<root><child></root>';
    const truncatedJson = '{"looks":"complete"}';
    const doctype = '<!DOCTYPE root [<!ENTITY x "expanded">]><root>&x;</root>';

    expect(runtimeActivityOutput(malformedJson).displayValue).toBe(malformedJson);
    expect(runtimeActivityOutput(malformedXml).displayValue).toBe(malformedXml);
    const declared = runtimeActivityOutput(truncatedJson, { truncated: true });
    expect(declared.displayValue).toContain(truncatedJson);
    expect(declared.displayValue).toContain(RUNTIME_TRUNCATION_MARKER);
    expect(declared.displayValue).not.toContain('\n  "looks"');
    expect(runtimeActivityOutput(doctype).displayValue).toBe(doctype);
    expect(runtimeActivityOutput(`{"long":"${'x'.repeat(MAX_RUNTIME_OUTPUT_UTF8_BYTES)}"}`).displayValue)
      .toContain(RUNTIME_TRUNCATION_MARKER);
    expect(runtimeActivityOutput(`<root>${'x'.repeat(MAX_RUNTIME_OUTPUT_UTF8_BYTES)}</root>`).displayValue)
      .toContain(RUNTIME_TRUNCATION_MARKER);
  });

  it('formats complete XML without changing mixed content, CDATA, comments or namespaces', () => {
    const xml = '<?xml version="1.0" encoding="UTF-8"?>'
      + '<root xmlns="urn:test" xmlns:x="urn:x"><x:item id="1">value</x:item>'
      + '<branch><leaf/></branch><!--note--></root>';
    const mixed = '<p>Hello <b>world</b> &amp; <![CDATA[<literal>]]><!--note--></p>';

    const formatted = runtimeActivityOutput(xml).displayValue;
    expect(formatted).toContain('<?xml version="1.0" encoding="UTF-8"?>\n');
    expect(formatted).toContain('<root xmlns="urn:test" xmlns:x="urn:x">\n');
    expect(formatted).toContain('  <x:item id="1">value</x:item>');
    expect(formatted).toContain('  <branch>\n    <leaf/>\n  </branch>');
    expect(formatted).toContain('  <!--note-->\n</root>');
    expect(runtimeActivityOutput(mixed).displayValue).toBe(mixed);
  });

  it('redacts secret-bearing JSON and XML structures before readable display', () => {
    const secret = 'structured-secret-sentinel';
    const json = runtimeActivityOutput(JSON.stringify({
      password: secret,
      nested: { authorization: `Bearer ${secret}`, safe: 'visible' },
    }));
    const xml = runtimeActivityOutput(
      `<root password="${secret}"><token>${secret}</token><safe>Bearer ${secret}</safe></root>`,
    );
    const text = runtimeActivityOutput(`password=${secret} retry disabled`);

    for (const output of [json, xml, text]) {
      expect(output.displayValue).not.toContain(secret);
      expect(output.displayValue).toContain(RUNTIME_REDACTION_MARKER);
      expect(output.redacted).toBe(true);
      expect(new TextEncoder().encode(output.displayValue).byteLength)
        .toBeLessThanOrEqual(MAX_RUNTIME_OUTPUT_UTF8_BYTES);
    }
    expect(json.displayValue).toContain('"safe": "visible"');
    expect(xml.displayValue).toContain(`<token>${RUNTIME_REDACTION_MARKER}</token>`);
  });

  it('applies depth, collection and value-count limits after decoding JSON text', () => {
    let deep = { leaf: 'visible' };
    for (let index = 0; index < 8; index += 1) deep = { child: deep };
    const collection = Array.from({ length: 40 }, (_, index) => ({ index, values: [index, index + 1] }));
    const valueCount = Array.from({ length: 31 }, (_, index) => [index, index + 1, index + 2, index + 3]);

    const deepOutput = runtimeActivityOutput(JSON.stringify(deep));
    const collectionOutput = runtimeActivityOutput(JSON.stringify(collection));
    const valueCountOutput = runtimeActivityOutput(JSON.stringify(valueCount));
    expect(deepOutput.displayValue).toContain('[ravenroot:truncated:depth]');
    expect(deepOutput.truncated).toBe(true);
    expect(collectionOutput.displayValue).toContain('[ravenroot:truncated:collection]');
    expect(collectionOutput.truncated).toBe(true);
    expect(valueCountOutput.displayValue).toContain('[ravenroot:truncated:value-count]');
    expect(valueCountOutput.truncated).toBe(true);
  });

  it('bounds pretty expansion even when the compact JSON input fits below 16 KiB', () => {
    const compact = JSON.stringify(Object.fromEntries(Array.from({ length: 32 }, (_, index) =>
      [`k${String(index).padStart(2, '0')}`, 'x'.repeat(500)])));
    expect(new TextEncoder().encode(compact).byteLength).toBeLessThan(MAX_RUNTIME_OUTPUT_UTF8_BYTES);
    expect(new TextEncoder().encode(JSON.stringify(JSON.parse(compact), null, 2)).byteLength)
      .toBeGreaterThan(MAX_RUNTIME_OUTPUT_UTF8_BYTES);

    const output = runtimeActivityOutput(compact);
    expect(new TextEncoder().encode(output.displayValue).byteLength)
      .toBeLessThanOrEqual(MAX_RUNTIME_OUTPUT_UTF8_BYTES);
    expect(output.displayValue).toContain(RUNTIME_TRUNCATION_MARKER);
    expect(output.truncated).toBe(true);
  });

  it('normalizes control and surrogate data without replacement characters in every display mode', () => {
    const plain = runtimeActivityOutput('plain\u0000bad\ud800text');
    const json = runtimeActivityOutput('{"text":"bad\\u0000surrogate\\ud800"}');

    for (const output of [plain, json]) {
      expect(output.displayValue).not.toContain('\u0000');
      expect(output.displayValue).not.toContain('\ud800');
      expect(output.displayValue).not.toContain('\ufffd');
    }
  });

  it('keeps a long unbroken output token intact for the wrapping presentation layer', () => {
    const token = 'x'.repeat(900);
    const output = runtimeActivityOutput(token);

    expect(output.displayValue).toContain(token);
    expect(output.displayValue).not.toContain(RUNTIME_TRUNCATION_MARKER);
    expect(output.truncated).toBe(false);
  });

  it('bounds the final encoded output while preserving a declared prefix', () => {
    const large = Object.fromEntries(Array.from({ length: 32 }, (_, index) =>
      [`field-${index}`, `value-${index}-${'x'.repeat(2000)}`]));
    const output = runtimeActivityOutput(large);

    expect(output.truncated).toBe(true);
    expect(output.value).toContain('field-0');
    expect(output.value).toContain(RUNTIME_TRUNCATION_MARKER);
    expect(output.displayValue).toContain(RUNTIME_TRUNCATION_MARKER);
    expect(new TextEncoder().encode(output.value).byteLength).toBeLessThanOrEqual(16 * 1024);
  });

  it('keeps late redaction and truncation markers together through whole-output rebounding', () => {
    const entries = Array.from({ length: 31 }, (_, index) =>
      [`a${String(index).padStart(2, '0')}`, 'x'.repeat(1000)]);
    entries.push(['zz_password', 'late-sensitive-sentinel']);

    const output = runtimeActivityOutput(Object.fromEntries(entries));

    expect(output.value).not.toContain('late-sensitive-sentinel');
    expect(output.redacted).toBe(true);
    expect(output.truncated).toBe(true);
    expect(output.value).toContain(RUNTIME_REDACTION_MARKER);
    expect(output.value).toContain(RUNTIME_TRUNCATION_MARKER);
    expect(new TextEncoder().encode(output.value).byteLength)
      .toBeLessThanOrEqual(MAX_RUNTIME_OUTPUT_UTF8_BYTES);
  });

  it('restores server-declared markers after hostile or legacy client rebounding', () => {
    const legacy = `${'x'.repeat(2000)}${RUNTIME_REDACTION_MARKER}${RUNTIME_TRUNCATION_MARKER}`;

    const output = runtimeActivityOutput(legacy, { redacted: true, truncated: true });

    expect(output.redacted).toBe(true);
    expect(output.truncated).toBe(true);
    expect(output.value).toContain(RUNTIME_REDACTION_MARKER);
    expect(output.value).toContain(RUNTIME_TRUNCATION_MARKER);
    expect(new TextEncoder().encode(output.value).byteLength)
      .toBeLessThanOrEqual(MAX_RUNTIME_OUTPUT_UTF8_BYTES);
  });

  it('retains all hostile structured content when escaped marker restoration fits the output bound', () => {
    const hostile = Object.fromEntries(Array.from({ length: 10 }, (_, index) =>
      [`field-${index}`, `value-${index}-${'x'.repeat(900)}`]));
    const projected = JSON.stringify(hostile);
    expect(new TextEncoder().encode(projected).byteLength).toBe(9211);

    const output = runtimeActivityOutput(hostile, { redacted: true, truncated: false });

    expect(output.value).toBe(JSON.stringify(`${projected}${RUNTIME_REDACTION_MARKER}`));
    expect(output.value).toContain('field-9');
    expect(output.redacted).toBe(true);
    expect(output.truncated).toBe(false);
    expect(output.value).toContain(RUNTIME_REDACTION_MARKER);
    expect(output.value).not.toContain(RUNTIME_TRUNCATION_MARKER);
    expect(new TextEncoder().encode(output.value).byteLength)
      .toBeLessThanOrEqual(MAX_RUNTIME_OUTPUT_UTF8_BYTES);
  });

  it('declares truncation when escaped marker restoration really cuts hostile structured content', () => {
    const hostile = Object.fromEntries(Array.from({ length: 10 }, (_, index) =>
      [`field-${index}`, '"'.repeat(600)]));
    expect(new TextEncoder().encode(JSON.stringify(hostile)).byteLength)
      .toBeLessThan(MAX_RUNTIME_OUTPUT_UTF8_BYTES);

    const output = runtimeActivityOutput(hostile, { redacted: true, truncated: false });

    expect(output.redacted).toBe(true);
    expect(output.truncated).toBe(true);
    expect(output.value).toContain(RUNTIME_REDACTION_MARKER);
    expect(output.value).toContain(RUNTIME_TRUNCATION_MARKER);
    expect(new TextEncoder().encode(output.value).byteLength)
      .toBeLessThanOrEqual(MAX_RUNTIME_OUTPUT_UTF8_BYTES);
  });
});

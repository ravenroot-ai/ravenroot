import { describe, expect, it } from 'vitest';

import { findPresentationSinkViolations } from '../scripts/check-ui-i18n.mjs';

describe('UI localization source audit fixtures', () => {
  it('finds inline text hidden in a template conditional', () => {
    expect(findPresentationSinkViolations(`
      const enabled = true;
      node.textContent = \`\${enabled ? 'Inline message' : 'Other message'}\`;
    `)).toEqual([3]);
  });

  it('follows an identifier into a presentation sink', () => {
    expect(findPresentationSinkViolations(`
      const message = 'Indirect inline message';
      node.textContent = message;
    `)).toEqual([2]);
  });

  it('finds product copy in a dynamic presentation record', () => {
    expect(findPresentationSinkViolations(`
      renderDialog({ title: 'Inline dialog title', message: 'Inline dialog message' });
    `)).toEqual([2]);
  });

  it('accepts localization calls through the supported aliases', () => {
    expect(findPresentationSinkViolations(`
      node.textContent = context.text('fixture.message');
      announce(t('fixture.notice'));
    `)).toEqual([]);
  });

  it('rejects an unescaped catalog value in an HTML template', () => {
    expect(findPresentationSinkViolations(`
      node.innerHTML = \`<span>\${uiText('fixture.message')}</span>\`;
    `)).toEqual([2]);
  });

  it('accepts a context-escaped catalog value in an HTML template', () => {
    expect(findPresentationSinkViolations(`
      node.innerHTML = \`<span>\${htmlText('fixture.message')}</span>\`;
    `)).toEqual([]);
  });

  it('finds product text returned for a caller in another module', () => {
    expect(findPresentationSinkViolations(`
      export function state() { return { kind: 'empty', message: 'Indirect empty state' }; }
    `)).toEqual([2]);
  });

  it('finds product labels in exported presentation metadata', () => {
    expect(findPresentationSinkViolations(`
      export const palette = [{ type: 'machine-value', label: 'Visible palette label' }];
    `)).toEqual([2]);
  });
});

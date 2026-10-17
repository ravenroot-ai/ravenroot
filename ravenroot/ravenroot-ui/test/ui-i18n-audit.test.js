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

  it('resolves the nearest scoped binding when a common presentation name is reused', () => {
    expect(findPresentationSinkViolations(`
      function unrelated() { const label = 'Unrelated authored value'; return label; }
      function paint(name) {
        const label = \`Overview — \${name || 'document'}\`;
        node.textContent = label;
      }
    `)).toEqual([4]);
  });

  it('finds inline product text passed indirectly as a localized message parameter', () => {
    expect(findPresentationSinkViolations(`
      const rendererName = mode === 'monitoring' ? 'Monitoring' : 'Design';
      node.title = uiText('fixture.renderer', { rendererName });
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
  it.each([
    `const say = text => { status.textContent = text; }; say('Inline status');`,
    `function first(value) { second(value); } function second(copy) { target.innerText = copy; } first('Indirect copy');`,
    `function status() { return 'Returned copy'; } const value = status(); node.textContent = value;`,
    `function paint(node, render) { node.textContent = render(); } paint(node, () => 'Deferred copy');`,
    `function card({ content }) { target.textContent = content; } card({ content: 'Option copy' });`,
    `function markup() { return '<span>Opaque HTML copy</span>'; } node.innerHTML = markup();`,
    `const fragment = '<b>Opaque HTML copy</b>'; node.insertAdjacentHTML('beforeend', fragment);`,
    `const values = ['First copy', 'Second copy']; node.textContent = values.join(' ');`,
    `let result = ''; result = 'Assigned copy'; node.textContent = result;`,
    `notify?.('Notification copy');`,
    `function fallback(value) { notify?.(value); } fallback('Fallback copy');`,
    `function showFormError(value) { error.textContent = value; } showFormError('Validation copy');`,
    `window.prompt('Prompt copy');`,
    `function markup(value) { return '<b>' + value + '</b>'; } node.innerHTML = markup(uiText('fixture.message'));`,
  ])('rejects structurally derived presentation flow: %s', source => {
    expect(findPresentationSinkViolations(source)).toEqual([1]);
  });

  it('accepts deferred localized text and authored values through opaque wrappers', () => {
    expect(findPresentationSinkViolations(`
      function paint(node, render) { node.textContent = render(); }
      paint(node, () => uiText('fixture.message', { author: model.name }));
      function markup() { return '<b>' + escapeHtml(uiText('fixture.message')) + '</b>'; }
      node.innerHTML = markup();
    `)).toEqual([]);
  });

});

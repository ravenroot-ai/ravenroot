import { describe, expect, it } from 'vitest';

import {
  findCrossModulePresentationSinkViolations,
  findPresentationSinkViolations,
  findRetainedMessageViolations,
} from '../scripts/check-ui-i18n.mjs';

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
    `try { throw new Error('Local failure'); } catch (error) { node.textContent = error.message; }`,
    `function fail() { throw new Error('Local failure'); } try { fail(); } catch (error) { node.textContent = uiErrorReason(error); }`,
    `async function request(action) { try { await action(); } catch (error) { node.textContent = uiErrorReason(error); } } request(() => { throw new Error('Local failure'); });`,
    `try { throw productError('fixture.message', 'Diagnostic failure'); } catch (error) { node.textContent = error.message; }`,
    `function invoke(paint) { paint('Connect service'); } invoke(text => { status.textContent = text; });`,
    `function invoke(paint) { const alias = paint; alias('Connect service'); } invoke(text => { status.textContent = text; });`,
    `function invoke(paint) { paint('Connect service'); } function forward(callback) { invoke(callback); } forward(text => { status.textContent = text; });`,
    `function first(value) { second(value); } function second(copy) { target.innerText = copy; } first('Indirect copy');`,
    `function status() { return 'Returned copy'; } const value = status(); node.textContent = value;`,
    `function paint(node, render) { node.textContent = render(); } paint(node, () => 'Deferred copy');`,
    `function card({ content }) { target.textContent = content; } card({ content: 'Option copy' });`,
    `function markup() { return '<span>Opaque HTML copy</span>'; } node.innerHTML = markup();`,
    `const fragment = '<b>Opaque HTML copy</b>'; node.insertAdjacentHTML('beforeend', fragment);`,
    `const values = ['First copy', 'Second copy']; node.textContent = values.join(' ');`,
    `let result = ''; result = 'Assigned copy'; node.textContent = result;`,
    `let result = ''; const paint = () => { node.textContent = result; }; function update() { result = 'Later assigned copy'; }`,
    `let message = ''; const state = () => ({ message }); function set(copy) { message = copy; } set('Later indirect copy');`,
    `function warning(message) { return message + ' Repair the document.'; } export function state() { return { warning: warning('Invalid document') }; }`,
    `selection.text(() => 'Chart copy');`,
    `selection.attr('aria-label', value => 'Graph control');`,
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
      function report(error) { node.textContent = uiErrorReason(error); }
      try { throw productError('fixture.message', 'Diagnostic failure'); } catch (error) { report(error); }
      try { throw productError('fixture.message', 'Diagnostic failure'); } catch (error) { node.textContent = uiErrorReason(error); }
      function invoke(paint) { paint(uiText('fixture.message')); }
      invoke(text => { status.textContent = text; });
      function paint(node, render) { node.textContent = render(); }
      paint(node, () => uiText('fixture.message', { author: model.name }));
      function markup() { return '<b>' + escapeHtml(uiText('fixture.message')) + '</b>'; }
      node.innerHTML = markup();
    `)).toEqual([]);
  });

  it('finds a destructured literal iterable value that reaches a presentation sink', () => {
    expect(findPresentationSinkViolations(`
      for (const [label, action] of [['Save selected node', 'save'], ['Rename', 'rename']]) {
        button.textContent = label;
        button.dataset.action = action;
      }
    `)).toEqual([2]);
  });

  it('finds an object-destructured iterable value while preserving its machine field', () => {
    expect(findPresentationSinkViolations(`
      for (const { copy, id } of [{ copy: 'Change setting', id: 'machine-id' }]) {
        button.textContent = copy;
        button.dataset.id = id;
      }
    `)).toEqual([2]);
  });

  it('finds presentation text returned by map callbacks before join reaches HTML', () => {
    expect(findPresentationSinkViolations(`
      host.innerHTML = documents.map(document_ =>
        \`<button aria-label="Close \${escapeAttribute(document_.name)}">×</button>\`).join('');
    `)).toEqual([3]);
  });

  it('finds presentation text returned through an opaque HTML helper', () => {
    expect(findPresentationSinkViolations(`
      function choice() { return '<option>Not declared</option>'; }
      host.insertAdjacentHTML('beforeend', choice());
    `)).toEqual([2]);
  });

  it('finds returned reason records consumed as presentation', () => {
    expect(findPresentationSinkViolations(`
      function validate() { return { ok: false, reason: 'Choose a source node' }; }
      feedback.textContent = validate().reason;
    `)).toEqual([2]);
  });

  it('finds copy pushed into a same-module returned validation array', () => {
    const source = `function validate(graph) { const errors=[]; if (!graph.nodes.length) errors.push('A start is required'); return errors; } root.innerHTML = validate(graph).map(message=>\`<li>\${escapeHtml(message)}</li>\`).join('');`;
    expect(findPresentationSinkViolations(source)).toEqual([1]);
    expect(findCrossModulePresentationSinkViolations({ 'src/probe.js': source }))
      .toEqual(['src/probe.js:1']);
  });

  it('finds presentation arrays returned across a real module import boundary', () => {
    expect(findCrossModulePresentationSinkViolations({
      'producer.js': `
        export function validate() {
          const violations = [];
          violations.push('Every node needs an ID');
          return violations;
        }
      `,
      'consumer.js': `
        import { validate } from './producer.js';
        target.innerHTML = validate().map(item => \`<li>\${escapeHtml(item)}</li>\`).join('');
      `,
    })).toEqual(['producer.js:4']);
  });

  it.each([
    `target.textContent = validate();`,
    `target.textContent = uiText('fixture.error', { reason: validate() });`,
  ])('finds a directly presented imported string return: %s', consumer => {
    expect(findCrossModulePresentationSinkViolations({
      'producer.js': `export function validate() { return 'A valid edge is required'; }`,
      'consumer.js': `import { validate } from './producer.js'; ${consumer}`,
    })).toEqual(['producer.js:1']);
  });

});


describe('retained activity localization audit', () => {
  it.each([
    `addActivityMessage(uiMessage('title'), uiText('detail'));`,
    `uiMessage('outer', { nested: uiText('inner') });`,
    `const label = uiText('state'); uiMessage('outer', { label });`,
    `const label = flag ? uiText('one') : uiText('other'); addActivityMessage('authored', label);`,
    `const reason = uiErrorReason(error); addActivityMessage(uiMessage('title'), reason);`,
    `const params = { reason: uiErrorReason(error) }; uiMessage('outer', params);`,
  ])('rejects eager retained translation: %s', source => {
    expect(findRetainedMessageViolations(source)).toEqual([1]);
  });
  it('accepts explicit nested semantics, deferred rendering and opaque authored text', () => {
    expect(findRetainedMessageViolations(`
      const authored = response.url;
      const nested = uiMessage('inner', { count: 5 });
      addActivityMessage(uiMessage('title'), uiMessage('outer', { nested, authored }));
      addActivityMessage(uiMessage('title'), uiComputed(() => uiText('outer', { authored })));
      addActivityMessage(uiMessage('title'), uiErrorMessage(error));
      addActivityMessage(user.title, user.content);
    `)).toEqual([]);
  });
  it('keeps unrelated immediate rendering legal', () => {
    expect(findRetainedMessageViolations(`node.textContent = uiText('instant');`)).toEqual([]);
  });
});

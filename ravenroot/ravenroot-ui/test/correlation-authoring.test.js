import { expect, it, vi } from 'vitest';
import { mountCorrelationPreview, MAX_CORRELATION_SAMPLE_BYTES } from '../src/correlation-authoring.js';
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
function fixture(preview, options = {}) {
  document.body.innerHTML = '<form><input data-catalog-property="correlation.mode" value="REQUIRE_EXISTING"><section></section></form>';
  const form = document.querySelector('form'), container = form.querySelector('section');
  const control = mountCorrelationPreview({ form, container, properties: () => ({ 'correlation.mode': 'REQUIRE_EXISTING', 'correlation.selector': '/id', auditReason: 'not a selector' }), preview, ...options });
  const sample = container.querySelector('textarea'), run = container.querySelector('button'); sample.value = '{"id":"operation"}';
  return { form, container, control, sample, run };
}
it('uses current explicit selectors and sample and distinguishes admission authority', async () => {
  const preview = vi.fn(async () => ({ valid: true, requiresExistingHistory: true, applicationContext: { applicationCorrelationId: 'operation', roundId: '0' } }));
  const f = fixture(preview); f.run.click(); await flush();
  expect(preview.mock.calls[0].slice(0, 2)).toEqual([{ 'correlation.mode': 'REQUIRE_EXISTING', 'correlation.selector': '/id' }, { id: 'operation' }]);
  expect(f.container.textContent).toContain('additionally requires an existing open history');
  expect(f.container.querySelector('pre').textContent).toContain('operation');
  f.control.destroy();
});
it('refuses malformed and oversized multibyte JSON before any service request', async () => {
  const preview = vi.fn(); const f = fixture(preview);
  for (const value of ['invalid', JSON.stringify('é'.repeat(MAX_CORRELATION_SAMPLE_BYTES / 2))]) {
    f.sample.value = value; f.run.click(); await flush(); expect(f.container.textContent).toContain('Enter valid JSON');
  }
  expect(preview).not.toHaveBeenCalled(); f.control.destroy();
});
it('invalidates a sample result on mapping changes and fences transport ignoring abort', async () => {
  let resolve; const f = fixture(() => new Promise(done => { resolve = done; }));
  f.run.click(); f.form.querySelector('input').dispatchEvent(new Event('input', { bubbles: true }));
  resolve({ valid: true, applicationContext: { applicationCorrelationId: 'old' } }); await flush();
  expect(f.container.querySelector('pre').textContent).toBe(''); f.control.destroy();
});
it('caps unsettled transports and aborts on removed origin form without late publication', async () => {
  const pending = [], preview = vi.fn((_properties, _sample, options) => new Promise(resolve => pending.push({ resolve, signal: options.signal })));
  const f = fixture(preview);
  for (let i = 0; i < 8; i++) { f.sample.dispatchEvent(new Event('input', { bubbles: true })); f.run.click(); }
  expect(preview).toHaveBeenCalledTimes(4);
  f.form.remove(); await flush(); expect(pending.every(item => item.signal.aborted)).toBe(true);
  pending.forEach(item => item.resolve({ valid: true, applicationContext: { applicationCorrelationId: 'late' } })); await flush();
  expect(f.container.querySelector('pre').textContent).toBe('');
});
it('shows a declared failure without copying the sample or claiming a new history', async () => {
  const f = fixture(async () => ({ valid: false, reason: 'INVALID_ID', selector: '/id' }));
  f.run.click(); await flush(); expect(f.container.textContent).toContain('INVALID_ID'); expect(f.container.querySelector('pre').textContent).toBe(''); f.control.destroy();
});

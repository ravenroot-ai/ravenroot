import { uiText } from './ui-text.js';

/** Finite editor budget; the server separately enforces the operator's extraction limits. */
export const MAX_CORRELATION_SAMPLE_BYTES = 65_536;

/** Caller-supplied sample only: no capture lookup, history admission or implicit ID mutation. */
export function mountCorrelationPreview({ container, form, properties, preview, isCurrent = () => true, t = uiText }) {
  const document = container.ownerDocument;
  let generation = 0, controller = null, destroyed = false, pending = 0;
  const create = (tag, key, args) => { const node = document.createElement(tag); if (key) node.textContent = t(key, args); return node; };
  const heading = create('h3', 'correlation.preview.title'), description = create('p', 'correlation.preview.description');
  heading.className = 'editor-section-title'; description.className = 'editor-hint';
  const label = create('label', 'correlation.preview.sample'), sample = create('textarea');
  label.className = 'editor-field full';
  sample.maxLength = MAX_CORRELATION_SAMPLE_BYTES; sample.rows = 5; sample.dataset.correlationSample = ''; label.append(sample);
  const bounds = create('p', 'correlation.preview.bound', { maximum: MAX_CORRELATION_SAMPLE_BYTES });
  bounds.className = 'editor-hint';
  const run = create('button', 'correlation.preview.run'); run.type = 'button'; run.className = 'btn';
  const status = create('p'), result = create('pre'); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
  result.dataset.correlationPreviewResult = ''; container.replaceChildren(heading, description, label, bounds, run, status, result);
  const configured = () => [...form.querySelectorAll('[data-catalog-property]')].some(field => field.dataset.catalogProperty === 'correlation.mode');
  const current = token => !destroyed && token === generation && container.isConnected && isCurrent();
  function invalidate() { generation++; controller?.abort(); controller = null; result.textContent = ''; status.textContent = ''; run.disabled = false; container.hidden = !configured(); }
  function changed(event) { if (event.target === sample || event.target.closest('[data-catalog-property]')) invalidate(); }
  form.addEventListener('input', changed); form.addEventListener('change', changed);
  run.addEventListener('click', async () => {
    invalidate(); const token = generation;
    if (!current(token) || container.hidden) return;
    if (pending >= 4) { status.textContent = t('correlation.preview.failed'); return; }
    let input;
    try {
      if (new TextEncoder().encode(sample.value).length > MAX_CORRELATION_SAMPLE_BYTES) throw new RangeError();
      input = JSON.parse(sample.value);
    } catch { status.textContent = t('correlation.preview.invalidSample'); return; }
    controller = new AbortController(); pending++; run.disabled = true; status.textContent = t('correlation.preview.loading');
    try {
      const mapping = Object.fromEntries(Object.entries(properties()).filter(([name]) => name.startsWith('correlation.')));
      const value = await preview(mapping, input, { signal: controller.signal });
      if (!current(token)) return;
      if (typeof value?.valid !== 'boolean') throw new TypeError('Invalid correlation preview response');
      if (!value.valid) { status.textContent = t('correlation.preview.refused', { reason: value.reason, selector: value.selector || t('history.unknown') }); return; }
      status.textContent = t(value.requiresExistingHistory ? 'correlation.preview.requiresExisting' : value.generatedIdentity ? 'correlation.preview.generated' : 'correlation.preview.selected');
      const fields = ['applicationCorrelationId', 'contributionId', 'contributionKind', 'roundId', 'publicReference'];
      result.textContent = JSON.stringify(Object.fromEntries(fields.map(name => [name, value.applicationContext?.[name] ?? null])), null, 2);
    } catch (error) { if (current(token) && error?.name !== 'AbortError') status.textContent = t('correlation.preview.failed'); }
    finally { pending--; if (current(token)) { run.disabled = false; controller = null; } }
  });
  function destroy() { if (destroyed) return; destroyed = true; controller?.abort(); observer.disconnect(); form.removeEventListener('input', changed); form.removeEventListener('change', changed); }
  const observer = new MutationObserver(() => { if (!container.isConnected) destroy(); }); observer.observe(document.body, { childList: true, subtree: true });
  invalidate(); return { reconcile: invalidate, destroy };
}

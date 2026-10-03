import { validateScene } from './frontend-plugin-api.js';

export function createFrontendPluginSandbox(source, options = {}) {
  const target = options.target || document.body;
  const timeout = Number.isFinite(options.timeout) ? options.timeout : 3000;
  const iframe = document.createElement('iframe');
  iframe.hidden = true;
  iframe.setAttribute('sandbox', 'allow-scripts');
  iframe.setAttribute('aria-hidden', 'true');
  iframe.src = options.sandboxUrl || `${import.meta.env.BASE_URL}frontend-plugin-sandbox.html`;
  let sequence = 0;
  let destroyed = false;
  const pending = new Map();
  let readyResolve;
  let readyReject;
  let readySettled = false;
  const ready = new Promise((resolve, reject) => { readyResolve = resolve; readyReject = reject; });
  ready.catch(() => {});
  const settleReady = (kind, value) => {
    if (readySettled) return;
    readySettled = true;
    clearTimeout(bootTimer);
    if (kind === 'resolve') readyResolve(value);
    else readyReject(value);
  };
  const fail = error => {
    if (destroyed) return;
    destroyed = true;
    settleReady('reject', error);
    removeEventListener('message', onMessage);
    for (const item of pending.values()) { clearTimeout(item.timer); item.reject(error); }
    pending.clear(); iframe.remove();
  };
  const onMessage = event => {
    if (event.source !== iframe.contentWindow || event.data?.channel !== 'ravenroot-plugin-v1') return;
    if (event.data.type === 'sandbox-ready') {
      iframe.contentWindow.postMessage({ channel: 'ravenroot-plugin-v1', type: 'load', source }, '*');
      return;
    }
    if (event.data.type === 'ready') { settleReady('resolve'); return; }
    if (event.data.type === 'boot-error') { fail(new Error(event.data.error)); return; }
    const item = pending.get(event.data.id);
    if (!item) return;
    pending.delete(event.data.id);
    clearTimeout(item.timer);
    if (event.data.type === 'error') item.reject(new Error(event.data.error));
    else item.resolve(event.data.result);
  };
  addEventListener('message', onMessage);
  const bootTimer = setTimeout(() => fail(new Error(`Frontend plugin sandbox did not start within ${timeout} ms`)), timeout);
  target.append(iframe);
  const invoke = async (method, input, { signal } = {}) => {
    if (destroyed) throw new Error('Frontend plugin sandbox was destroyed');
    await ready;
    if (signal?.aborted) throw new DOMException('Plugin operation was cancelled', 'AbortError');
    const id = ++sequence;
    return new Promise((resolve, reject) => {
      const retire = error => {
        const item = pending.get(id);
        if (!item) return;
        pending.delete(id); clearTimeout(item.timer); reject(error);
      };
      const timer = setTimeout(() => {
        const error = new Error(`Frontend plugin ${method} exceeded ${timeout} ms`);
        retire(error); fail(error);
      }, timeout);
      pending.set(id, { resolve, reject, timer });
      signal?.addEventListener('abort', () => retire(new DOMException('Plugin operation was cancelled', 'AbortError')), { once: true });
      iframe.contentWindow.postMessage({ channel: 'ravenroot-plugin-v1', type: 'invoke', id, method, input }, '*');
    });
  };
  return Object.freeze({
    layout: (input, options_) => invoke('layout', input, options_),
    render: async (input, options_) => validateScene(await invoke('render', input, options_)),
    destroy() {
      fail(new Error('Frontend plugin sandbox was destroyed'));
    },
    element: iframe,
  });
}

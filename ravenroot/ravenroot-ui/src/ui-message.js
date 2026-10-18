// Retained presentation is explicit. Neither catalog text nor an arbitrary object can become a
// render recipe. The private registries never enter authored records, JSON, or wire schemas.
const messages = new WeakMap();
const presentations = new WeakMap();

export function isUiMessage(value) {
  return value !== null && typeof value === 'object' && messages.has(value);
}

export function uiMessage(key, params = {}) {
  const values = Object.freeze(Object.fromEntries(Object.entries(params).map(([name, value]) => [name,
    isUiMessage(value) || value == null || typeof value !== 'object' ? value : String(value),
  ])));
  const message = Object.freeze({ key, params: values });
  messages.set(message, { key, params: values });
  return message;
}

// Callers capture event data before creating the recipe; only presentation may run again.
export function uiComputed(render) {
  const message = Object.freeze({ render });
  messages.set(message, { render });
  return message;
}

export function renderUiMessage(value, text) {
  const message = isUiMessage(value) ? messages.get(value) : null;
  if (!message) return String(value ?? '');
  if (message.render) return String(message.render() ?? '');
  const params = Object.fromEntries(Object.entries(message.params).map(([name, item]) => [name,
    isUiMessage(item) ? renderUiMessage(item, text) : item,
  ]));
  return text(message.key, params);
}

// Compatibility results keep their existing string fields. Their explicit presentation handle is
// private and can be retained without running validation again against a later document.
export function withUiMessage(value, message) {
  if (isUiMessage(message)) presentations.set(value, message);
  return value;
}

export function uiMessageOf(value, fallback = '') {
  return value && typeof value === 'object' ? presentations.get(value) ?? fallback : fallback;
}

export function uiErrorMessage(error) {
  const retained = uiMessageOf(error, null);
  if (retained) return retained;
  if (error?.uiMessageKey) {
    // A mutable factory/getter is sampled once, at the event boundary, never during repaint.
    const params = error.uiMessageParams;
    return uiMessage(error.uiMessageKey, typeof params === 'function' ? params() : params || {});
  }
  return String(error?.message || error || '');
}

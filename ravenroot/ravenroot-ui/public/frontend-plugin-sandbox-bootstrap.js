let plugin = null;

function send(message) {
  parent.postMessage({ channel: 'ravenroot-plugin-v1', ...message }, '*');
}

addEventListener('message', async event => {
  const message = event.data;
  if (event.source !== parent || message?.channel !== 'ravenroot-plugin-v1') return;
  if (message.type === 'load') {
    let url;
    try {
      url = URL.createObjectURL(new Blob([message.source], { type: 'text/javascript' }));
      const loaded = await import(url);
      plugin = loaded.default;
      if (!plugin || typeof plugin !== 'object') throw new Error('Plugin entry must export a default provider object');
      send({ type: 'ready' });
    } catch (error) {
      send({ type: 'boot-error', error: String(error?.message || error) });
    } finally {
      if (url) URL.revokeObjectURL(url);
    }
    return;
  }
  if (message.type !== 'invoke') return;
  try {
    const method = plugin?.[message.method];
    if (typeof method !== 'function') throw new Error(`Provider method '${message.method}' is unavailable`);
    const result = await method(structuredClone(message.input), Object.freeze({ signalId: message.id }));
    send({ type: 'result', id: message.id, result });
  } catch (error) {
    send({ type: 'error', id: message.id, error: String(error?.message || error) });
  }
});

send({ type: 'sandbox-ready' });

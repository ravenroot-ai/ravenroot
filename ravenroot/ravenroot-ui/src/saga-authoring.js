/** Trusted catalog eligibility mirrors server admission; generic side effects grant no protocol. */
export function sagaParticipant(descriptor) {
  const capabilities = new Set(descriptor?.capabilities || []);
  if (capabilities.has('saga-pure') && !capabilities.has('side-effect')) return 'pure';
  const supported = {
    'jdbc.insert': ['jdbc-receipt-v1', 'ravenroot.jdbc-receipt.v1'],
    'amqp.publish': ['amqp-inbox-v1', 'ravenroot.amqp-inbox.v1'],
    'http-request': ['http-idempotency-v1', 'ravenroot.http-idempotency.v1'],
  }[descriptor?.behavior];
  return supported && capabilities.has(`saga-adapter:${supported[1]}`) ? supported[0] : null;
}

/** Only the confirmed historical Log signature is repaired. Partial/authored/unknown declarations survive. */
export function migrateLegacySagaDefaults(node) {
  const properties = node.properties || {};
  const keys = Object.keys(properties).filter(key => key.startsWith('saga.')).sort();
  if (node.behavior !== 'log' || keys.join(',') !== 'saga.businessCompletionRequired,saga.irreversible,saga.role'
      || properties['saga.role'] !== 'forward'
      || ![false, 'false'].includes(properties['saga.irreversible'])
      || ![false, 'false'].includes(properties['saga.businessCompletionRequired'])) return node;
  const migrated = { ...node, properties: { ...properties }, propertyTypes: { ...node.propertyTypes } };
  for (const key of keys) { delete migrated.properties[key]; delete migrated.propertyTypes[key]; }
  return migrated;
}

/** Scopes exist only through explicit participant declarations. No empty scope opts in a graph. */
export function sagaScopes(graph) {
  const scopes = new Map();
  for (const node of graph?.nodes || []) {
    const scope = String(node.properties?.['saga.scope'] || '').trim();
    if (!scope) continue;
    if (!scopes.has(scope)) scopes.set(scope, []);
    scopes.get(scope).push(node);
  }
  return scopes;
}

/** Stable inspector opt-in boundary, shared by submit, autosave, and draft comparison. */
export function readSagaCatalogProperties(form, read) {
  const result = read();
  const enabled = form.querySelector('[data-saga-enabled]');
  if (enabled && !enabled.checked) {
    for (const key of Object.keys(result.properties)) {
      if (key.startsWith('saga.')) { delete result.properties[key]; delete result.propertyTypes[key]; }
    }
  }
  return result;
}

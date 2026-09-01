import { DEFAULT_NATURE_PROPERTY, effectiveNature } from './node-nature.js';

/**
 * Counts effective SOURCE nodes using the same trusted catalog fields as the Inspector.
 * The server repeats this inspection and is authoritative; this projection only selects the editor
 * route and never grants a runtime nature.
 */
export function effectiveSourceCount(graph, catalog) {
  const byBehavior = new Map((catalog || []).map(descriptor => [descriptor.behavior, descriptor]));
  return (graph?.nodes || []).filter(node => {
    if (node.kind !== 'BEHAVIOR') return false;
    const descriptor = byBehavior.get(node.behavior);
    if (!descriptor) return false;
    const property = descriptor.natureProperty || DEFAULT_NATURE_PROPERTY;
    return effectiveNature(descriptor, node.properties?.[property]).value === 'SOURCE';
  }).length;
}

export const ACTIVE_SOURCE_SESSION_STATES = new Set([
  'STARTING', 'LISTENING', 'DEGRADED', 'STOPPING', 'UNKNOWN',
]);

export function sourceSessionIsActive(session) {
  return Boolean(session?.sessionId && ACTIVE_SOURCE_SESSION_STATES.has(session.state));
}

/** Captures the exact browser binding an asynchronous source-session operation belongs to. */
export function captureSourceSessionToken(session) {
  return Object.freeze({
    session,
    client: session?.client,
    sessionId: session?.sessionId,
    generation: session?.generation,
  });
}

/** Older completions must not mutate a replacement binding, even when the server id is reused. */
export function sourceSessionTokenIsCurrent(token) {
  return Boolean(token?.session
    && token.session.client === token.client
    && token.session.sessionId === token.sessionId
    && token.session.generation === token.generation);
}

/** A transient UI claim is not honest once its request outcome becomes ambiguous. */
export function recoverSourceSessionState(state) {
  return state === 'STARTING' || state === 'STOPPING' ? 'UNKNOWN' : (state || 'UNKNOWN');
}

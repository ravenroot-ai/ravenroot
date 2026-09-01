/**
 * Owns the lifetime of one independently-rendered view per document.
 *
 * This registry is deliberately side-effect free.  A caller may associate a
 * token with a Cytoscape, D3, WebGL, or future renderer handle, but this
 * module never calls that handle and has no DOM dependency.  Instead, the
 * action returned from a transition tells the caller which handle it may
 * suspend, resume, or destroy.  A generation is global so a closed document
 * id cannot make an old asynchronous callback current after it is reopened.
 */
export function createRendererSessions() {
  const sessions = new Map();
  let nextGeneration = 0;

  const sameToken = (left, right) => left?.generation === right?.generation
    && left?.documentId === right?.documentId && left?.renderer === right?.renderer;

  const actionFor = (token, state, reason = null) => token == null ? null : Object.freeze({
    documentId: token.documentId,
    renderer: token.renderer,
    kind: token.kind,
    generation: token.generation,
    state,
    reason,
  });

  function register({ documentId, renderer, kind = 'renderer' } = {}) {
    if (documentId == null) throw new TypeError('A renderer session requires a documentId.');
    if (renderer == null) throw new TypeError('A renderer session requires a renderer handle.');

    const id = String(documentId);
    const prior = sessions.get(id);
    if (prior?.live && prior.token.renderer === renderer && prior.token.kind === kind) {
      return { token: prior.token, registered: false, retired: null };
    }

    const retired = prior?.live ? actionFor(prior.token, 'destroyed', 'replaced') : null;
    const token = Object.freeze({ documentId: id, renderer, kind, generation: ++nextGeneration });
    sessions.set(id, { token, live: true, suspended: false });
    return { token, registered: true, retired };
  }

  function sessionFor(token) {
    const session = sessions.get(token?.documentId);
    return session && sameToken(session.token, token) ? session : null;
  }

  function isCurrent(token) {
    return Boolean(sessionFor(token));
  }

  function isLive(token) {
    const session = sessionFor(token);
    return Boolean(session?.live && !session.suspended);
  }

  function isSuspended(token) {
    const session = sessionFor(token);
    return Boolean(session?.live && session.suspended);
  }

  function suspend(token) {
    const session = sessionFor(token);
    if (!session || !session.live || session.suspended) return { changed: false, action: null };
    session.suspended = true;
    return { changed: true, action: actionFor(token, 'suspended') };
  }

  function resume(token) {
    const session = sessionFor(token);
    if (!session || !session.live || !session.suspended) return { changed: false, action: null };
    session.suspended = false;
    return { changed: true, action: actionFor(token, 'live') };
  }

  function destroy(token, reason = 'destroyed') {
    const session = sessionFor(token);
    if (!session || !session.live) return { changed: false, action: null };
    sessions.delete(token.documentId);
    return { changed: true, action: actionFor(token, 'destroyed', reason) };
  }

  function invalidate(documentId) {
    const session = sessions.get(String(documentId));
    if (!session?.live) return { changed: false, action: null };
    return destroy(session.token, 'invalidated');
  }

  return Object.freeze({
    register,
    isCurrent,
    isLive,
    isSuspended,
    suspend,
    resume,
    destroy,
    invalidate,
  });
}

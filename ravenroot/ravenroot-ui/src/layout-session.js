/**
 * Keeps asynchronous layout callbacks tied to the Cytoscape instance that
 * started them.  This is deliberately state-only: callers own starting and
 * stopping native layouts, while this registry tells them whether an action
 * is still allowed to affect a document.
 *
 * A token is an immutable snapshot.  A token stops being current as soon as
 * its document receives another request, is replaced with another `cy`, or is
 * invalidated during close.  Generations are registry-wide rather than
 * per-document so a closed document id can never accidentally revive an old
 * callback after it is reopened.
 */
export function createLayoutSessions() {
  const sessions = new Map();
  let nextGeneration = 0;

  const sameToken = (left, right) => left?.generation === right?.generation
    && left?.documentId === right?.documentId && left?.cy === right?.cy;

  const cancellation = (token, reason) => token == null ? null : Object.freeze({
    documentId: token.documentId,
    cy: token.cy,
    generation: token.generation,
    mode: token.mode,
    nativeCancel: token.nativeCancel,
    reason,
  });

  function retire(session, reason) {
    const cancelled = [];
    // A queued ELK request has not reached the native renderer yet, but its
    // cancellation metadata is still returned for a caller that allocated
    // request-specific resources before asking us to serialize it.
    for (const token of [session?.current, session?.elkQueued, session?.elkRunning]) {
      const item = cancellation(token, reason);
      if (item && !cancelled.some(previous => previous.generation === item.generation)) cancelled.push(item);
    }
    return cancelled;
  }

  function replace(documentId, cy) {
    const prior = sessions.get(documentId);
    if (prior?.cy === cy && prior.live) return { replaced: false, cancelled: [] };
    const cancelled = retire(prior, 'replaced');
    sessions.set(documentId, { cy, live: true, current: null, elkRunning: null, elkQueued: null });
    return { replaced: Boolean(prior), cancelled };
  }

  function request({ documentId, cy, mode, kind = mode === 'elk' ? 'elk' : 'native', nativeCancel = null } = {}) {
    if (documentId == null) throw new TypeError('A layout session requires a documentId.');
    if (cy == null) throw new TypeError('A layout session requires a cy instance.');
    if (!mode) throw new TypeError('A layout session requires a mode.');

    const replacement = replace(documentId, cy);
    const session = sessions.get(documentId);
    const cancelled = [...replacement.cancelled];
    const currentCancellation = sameToken(session.current, session.elkRunning)
      ? null
      : cancellation(session.current, 'superseded');
    if (currentCancellation) cancelled.push(currentCancellation);
    // The running ELK must be allowed to settle so it can release the
    // serialization slot. Any waiting request, whatever its native layout
    // kind, is replaced by the newest request for this document.
    if (session.elkQueued) {
      const queuedCancellation = cancellation(session.elkQueued, 'superseded');
      if (queuedCancellation && !cancelled.some(item => item.generation === queuedCancellation.generation)) {
        cancelled.push(queuedCancellation);
      }
      session.elkQueued = null;
    }

    const token = Object.freeze({
      documentId,
      cy,
      mode,
      kind,
      generation: ++nextGeneration,
      nativeCancel,
    });
    if (session.elkRunning) {
      session.current = token;
      session.elkQueued = token;
      return { token, start: false, cancelled };
    }
    session.current = token;
    if (kind !== 'elk') return { token, start: true, cancelled };
    session.elkRunning = token;
    return { token, start: true, cancelled };
  }

  function isLive(token) {
    const session = sessions.get(token?.documentId);
    return Boolean(session?.live && sameToken(session.current, token));
  }

  function isCurrent(token) {
    return isLive(token) && token.mode === sessions.get(token.documentId).current.mode
      && token.cy === sessions.get(token.documentId).cy;
  }

  function complete(token) {
    const session = sessions.get(token?.documentId);
    if (!session || !sameToken(session.elkRunning, token)) return { start: null };
    session.elkRunning = null;
    const queued = session.elkQueued;
    session.elkQueued = null;
    // The queue is a serialization mechanism, not a second ownership
    // channel: only the request that still owns the document is released.
    if (!queued || !isCurrent(queued)) return { start: null };
    if (queued.kind === 'elk') session.elkRunning = queued;
    return { start: queued };
  }

  function invalidate(documentId) {
    const session = sessions.get(documentId);
    if (!session) return { cancelled: [] };
    const cancelled = retire(session, 'invalidated');
    sessions.delete(documentId);
    return { cancelled };
  }

  return Object.freeze({ request, isLive, isCurrent, complete, invalidate, replace });
}

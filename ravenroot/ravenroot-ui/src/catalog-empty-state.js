import { uiMessage, uiErrorMessage, renderUiMessage, withUiMessage } from './ui-message.js';
// Why the node palette is empty, in the user's words.
//
// An empty palette has several causes and they are not interchangeable: a service that answered
// "you must authenticate" is a very different situation from a service that did not answer at all.
// Saying "catalog unavailable" for both is what made a working, fully registered node catalog look
// like a removed feature. The client never assumes which case it is in — it reports what the
// service actually answered.
//
// `error` is the failure raised by the catalog request, or null when there was none.
// `catalog` is the array the service returned, or null when no request has completed.
// `pending` is true while a request is in flight and no answer has arrived yet.

import { uiText } from './ui-text.js';

function state(kind, key, params, t) {
  const message = uiMessage(key, params);
  return withUiMessage({ kind, message: renderUiMessage(message, t) }, message);
}

export function catalogEmptyState(error, catalog, pending = false, t = uiText) {
  if (error) return failureState(error, t);
  if (catalog === null || catalog === undefined) {
    // "Connect to the service" is an instruction. Once the page connects on its own it is an
    // instruction the user can neither act on nor decline, so while the request is in flight the
    // palette says what is happening instead of asking for something that is already under way.
    if (pending) {
      return state('connecting', 'catalog.empty.connecting', {}, t);
    }
    return state('disconnected', 'catalog.empty.disconnected', {}, t);
  }
  if (!catalog.length) {
    return state('empty', 'catalog.empty.available', {}, t);
  }
  return { kind: 'available', message: '' };
}

function failureState(error, t) {
  const status = Number(error?.status) || 0;
  if (status === 401) {
    return state('authentication-required', 'catalog.empty.authenticationRequired', {}, t);
  }
  if (status === 403) {
    return state('access-revoked', 'catalog.empty.accessRevoked', {}, t);
  }
  // Do not add "The service did not answer" for a
  // status-less error (`error.status` null/undefined -- the fetch promise itself rejected) and drop
  // it once `error.status` became a number (a response WAS received: a 404, a 500, anything not
  // 401/403). The status-less case is NOT "the service did not answer": a rejected fetch is a
  // response the browser could not read, and the established example is a response the service
  // actually sent -- `BrowserOriginPolicy.reject` answers 403 with a JSON body but never calls
  // `applyCorsResponse`, so the browser withholds it from the page and reports a rejected promise
  // indistinguishable from a network failure. Attributing that silence to the service is the same
  // false attribution of an unknowable cause. Neither case may say what the
  // service did or did not do -- only `reason(error)`, which is already the client's own observation
  // (and, for the status-less case, already enumerates the possibilities via `runtime-client.js`'s
  // hint) -- so there is nothing left to add here, and both cases share one sentence.
  return state('unreachable', 'catalog.empty.unreachable', {
    reason: error?.uiMessageKey ? uiErrorMessage(error)
      : String(error?.message || '').trim() || uiMessage('catalog.empty.requestFailed'),
  }, t);
}
